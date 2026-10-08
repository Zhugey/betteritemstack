package com.zhugey.betteritemstack.mixin;

import com.zhugey.betteritemstack.Config;
import net.minecraft.client.item.TooltipContext;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/**
 * <p>对 {@link ItemStack} 的 Mixin：改写全局最大堆叠数量，并在工具提示里追加堆叠数量。</p>
 *
 * <p><b>本分支（1.20 – 1.20.4）与 1.20.5+ 分支的差异</b>——这一代是更早的一套 API：</p>
 * <ul>
 *   <li><b>不需要重写 CODEC。</b>本代原版 {@code ItemStack.CODEC} 的 count 字段是
 *       {@code Codec.INT}（字段名 {@code Count}，<b>不限范围</b>）；
 *       1.20.5 起才改成 {@code rangedInt(1, 99)}，那才需要用 {@code <clinit>} 注入改写。
 *       因此这里没有 {@code @Shadow CODEC}、也没有 {@code bis$allowLargeCounts}。</li>
 *   <li><b>但数量在「落盘」与「网络」两条路上都会被截断</b>，必须另外处理：
 *       <ul>
 *         <li>落盘 —— 原版 {@code writeNbt} 写成 {@code putByte("Count", (byte)count)}，
 *             本类在 TAIL 处补一个 {@code putInt} 覆盖它，读回时在 {@code fromNbt} 的 RETURN
 *             处用 {@code getInt} 还原（{@code NbtCompound.contains(key, NUMBER_TYPE)}
 *             对 ByteTag 也返回 true，所以旧存档照常读得出来）；</li>
 *         <li>网络 —— 原版 {@code PacketByteBuf#writeItemStack} 用 {@code writeByte} 传输数量，
 *             由 {@code PacketByteBufMixin} 改为 VarInt。</li>
 *       </ul>
 *       这两处若漏掉任何一条，表现都是"数量超过 127 的物品在同步/存档后数量错乱甚至整格消失"。</li>
 *   <li><b>没有物品组件。</b>"可损坏物品"必须用 {@code getItem().isDamageable()}（纯物品级），
 *       不能用 {@link ItemStack#isDamageable()} —— 后者在本代的实现是
 *       {@code !isEmpty() && getItem().getMaxDamage() > 0 && !nbt.getBoolean("Unbreakable")}，
 *       会把附了 Unbreakable 的工具算作不可损坏以外的情形，详见下方注释。</li>
 *   <li>{@code ItemStack#getTooltip} 的签名是 {@code (PlayerEntity, TooltipContext)}，
 *       没有 1.20.5 才加入的 {@code Item.TooltipContext} 形参；
 *       这里的 {@code net.minecraft.client.item.TooltipContext} 正是 1.20.5 起改名的
 *       {@code TooltipType}（同一个 intermediary 类 {@code class_1836}）。</li>
 * </ul>
 */
@Mixin(ItemStack.class)
public abstract class ItemStackMixin {

    /** 堆叠数量达到该值时才在工具提示中显示数量。 */
    private static final int BIS_TOOLTIP_THRESHOLD = 1000;

    /** 原版序列化物品时数量所用的 NBT 键名。 */
    private static final String COUNT_KEY = "Count";

    /**
     * <p>重写 {@link ItemStack#getMaxCount()} 方法，返回全局最大堆叠数量。</p>
     * <p>非堆叠物品列表中的物品或可损坏物品将保持原始最大堆叠数量。</p>
     *
     * <p>注意：这是本 Mod 唯一一处"全局放大"物品上限的地方，也是问题的根源——
     * 原版有若干逻辑（尤其漏斗的容量判定）直接依赖该方法，因此凡是需要"物品原本能堆多少"
     * 的地方都必须改用 {@link com.zhugey.betteritemstack.VanillaMax}。</p>
     *
     * @param cir Mixin 注入所需的 CallbackInfoReturnable，用于设置返回值
     */
    @Inject(method = "getMaxCount", at = @At("HEAD"), cancellable = true)
    private void overrideMaxCount(CallbackInfoReturnable<Integer> cir) {
        ItemStack stack = (ItemStack) (Object) this;

        // 带耐久度的物品（工具、护甲）原版上限即为 1，必须保持原版值。
        //
        // 判定必须用 getItem().isDamageable()（等价于 maxDamage > 0，只看物品类型），
        // 而**不能**用 ItemStack#isDamageable()：后者在本代还额外要求"NBT 里没有 Unbreakable"。
        // 若用后者，一个附了 Unbreakable 的工具会被当成普通物品放大到 GLOBAL_MAX，
        // 进而导致 Item#isEnchantable（判断式 getMaxCount() == 1）失败——该装备将无法在附魔台附魔。
        // 1.20.5+ 分支用 contains(MAX_DAMAGE) 组件表达同样的语义，本代没有组件，故回到物品级判定。
        if (stack.getItem().isDamageable()) {
            return;
        }

        // 黑名单物品保持原版上限。仅在黑名单非空时才做 ID 查询：
        // getMaxCount() 是热路径，避免每次调用都分配一个 Identifier。
        List<String> blacklist = Config.get().nonStackableItems;
        if (!blacklist.isEmpty() && blacklist.contains(Registries.ITEM.getId(stack.getItem()).toString())) {
            return;
        }

        cir.setReturnValue(Config.GLOBAL_MAX);
    }

    /**
     * <p>把写进 NBT 的数量由字节改为 int，供存档（区块、玩家数据）与物品实体使用。</p>
     *
     * <p>原版 {@code writeNbt} 的实现是 {@code nbt.putByte("Count", (byte) this.count)}，
     * 超过 127 的数量会被截断。这里在方法 TAIL 处补一个 {@code putInt} <b>覆盖</b>掉刚写下的
     * ByteTag，因此键名不变、也不多出任何字段。</p>
     *
     * <p>对 <b>≤ 127</b> 的数量，写出的值与原版逐位相同（只是 NBT 类型由 ByteTag 变成 IntTag），
     * 而 {@code NbtCompound#getByte} 对 IntTag 会走 {@code byteValue()}，读到的数字仍然正确——
     * 也就是说即使有人卸掉本 Mod 去读这份存档，小数量也不会损坏。</p>
     *
     * @param nbt 正在构造的 NBT（原方法的形参）
     * @param cir 回调对象（本注入不回写返回值，仅修改形参对象）
     */
    @Inject(method = "writeNbt", at = @At("TAIL"))
    private void bis$writeCountAsInt(NbtCompound nbt, CallbackInfoReturnable<NbtCompound> cir) {
        ItemStack self = (ItemStack) (Object) this;
        nbt.putInt(COUNT_KEY, self.getCount());
    }

    /**
     * <p>读回 {@link #bis$writeCountAsInt} 写下的 int 数量。</p>
     *
     * <p>原版 {@code fromNbt} 内部 {@code new ItemStack(NbtCompound)} 时读的是
     * {@code getByte("Count")}，IntTag 走 {@code byteValue()} 会被截断，所以这里在 RETURN 处
     * 用 {@code getInt} 重新取一次并还原。</p>
     *
     * <p>该 API 对旧存档同样成立：{@code NbtCompound#contains(String, NUMBER_TYPE)} 对
     * ByteTag / ShortTag / IntTag 等一律返回 true，{@code getInt} 则统一走
     * {@code AbstractNbtNumber#intValue()}，所以 <b>旧数据读出来的值与构造函数读到的一致</b>，
     * 本注入对旧存档是个恒等操作（用 {@code count != stack.getCount()} 判断，不做多余写入）。</p>
     *
     * @param nbt 源 NBT
     * @param cir 回调对象，取出已构建好的堆叠并就地修正数量
     */
    @Inject(method = "fromNbt", at = @At("RETURN"))
    private static void bis$readCountAsInt(NbtCompound nbt, CallbackInfoReturnable<ItemStack> cir) {
        ItemStack stack = cir.getReturnValue();
        // fromNbt 的早返回分支给出的是 EMPTY（解析失败时），此时无数量可言。
        if (stack == null || stack.isEmpty()) {
            return;
        }

        // 键缺失或非数值时 getInt 返回 0，用 > 0 一并挡掉。
        int count = nbt.getInt(COUNT_KEY);
        if (count > 0 && count != stack.getCount()) {
            stack.setCount(count);
        }
    }

    /**
     * <p>在物品的工具提示中追加一行堆叠数量（数量达到 {@link #BIS_TOOLTIP_THRESHOLD} 时）。</p>
     *
     * <p><b>为什么注入 {@link ItemStack#getTooltip} 而不是 {@code Item#appendTooltip}</b>：
     * 原版有若干物品覆写了 {@code Item#appendTooltip} 且<b>没有调用 {@code super.appendTooltip(...)}</b>，
     * 例如烟花（{@code FireworkRocketItem}）、药水箭（{@code TippedArrowItem}），以及旗帜、盾牌、弩、
     * 收纳袋、药水、滞留药水、成书、已探索地图、鱼桶、烟花之星、唱片碎片、盔甲纹饰。
     * 注入在 {@code Item#appendTooltip} 上的代码对这些物品<b>完全不会执行</b>，
     * 表现为"这些物品堆叠后看不到数量提示"。</p>
     *
     * <p>{@code ItemStack#getTooltip} 是所有物品提示的唯一汇总入口，且不存在绕过它的旁路调用
     * （弩调用 {@code Items.FIREWORK_ROCKET.appendTooltip} 之类的是在同一入口内部发生的），
     * 因此改注入这里可以覆盖全部物品。</p>
     *
     * @param player  玩家（本代签名如此，未用于逻辑）
     * @param context 提示上下文（1.20.5 起改名 TooltipType）
     * @param cir     回调对象，用于取回并修改已构建好的提示行列表
     */
    @Inject(method = "getTooltip", at = @At("RETURN"))
    private void bis$addStackCountTooltip(PlayerEntity player, TooltipContext context,
                                          CallbackInfoReturnable<List<Text>> cir) {
        ItemStack stack = (ItemStack) (Object) this;
        if (stack.getCount() < BIS_TOOLTIP_THRESHOLD) {
            return;
        }

        List<Text> tooltip = cir.getReturnValue();
        // 原版在 HIDE_TOOLTIP 分支直接返回 List.of()（不可变空列表），正常分支则至少含物品名一项。
        // 因此用 isEmpty() 既跳过"整条提示被隐藏"的情形，也避免对不可变列表调用 add。
        if (tooltip == null || tooltip.isEmpty()) {
            return;
        }

        // 插在物品名之后（索引 1），与旧实现的显示位置保持一致。
        // 文案走语言文件；颜色用 Formatting 而非在翻译值里嵌 § 代码，便于各语言复用。
        tooltip.add(Math.min(1, tooltip.size()),
                Text.translatable("tooltip.betteritemstack.stackcount", stack.getCount())
                        .formatted(Formatting.GREEN));
    }
}
