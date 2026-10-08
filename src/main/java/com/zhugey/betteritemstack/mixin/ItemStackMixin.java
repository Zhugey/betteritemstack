package com.zhugey.betteritemstack.mixin;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.zhugey.betteritemstack.Config;
import net.minecraft.component.ComponentChanges;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
// 1.20.5 里这个类在 client 包下；1.21 起被移到了 net.minecraft.item.tooltip。
// 注意它是同一个 intermediary 类（class_1836），只是 Yarn 包名变了 ——
// 所以"符号核查通过"并不等于"源码能编译"，跨版本移植时以编译器为准。
import net.minecraft.client.item.TooltipType;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.dynamic.Codecs;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/**
 * <p>对 {@link ItemStack} 的 Mixin，用于修改默认最大堆叠数量、序列化 Codec，以及追加堆叠数量提示。</p>
 * <p>可以为大多数物品应用全局最大堆叠数量，非堆叠物品或可损坏物品保持原始数量。</p>
 * <p>同时重写 ItemStack 内部的 CODEC，以支持堆叠数量超过 64 的情况。</p>
 */
@Mixin(ItemStack.class)
public abstract class ItemStackMixin {

    /** 堆叠数量达到该值时才在工具提示中显示数量。 */
    private static final int BIS_TOOLTIP_THRESHOLD = 1000;

    @Mutable
    @Shadow
    @Final
    public static Codec<ItemStack> CODEC;

    /**
     * <p>重写 {@code ItemStack} 静态初始化块里的 CODEC，把 {@code count} 字段的取值上限
     * 从原版的 99 放开到 {@link Integer#MAX_VALUE}，使大堆叠物品能正常序列化与反序列化。</p>
     *
     * <p><b>为什么不 {@code @Shadow} 原版的 {@code ITEM_CODEC}</b>：1.21.1 的
     * {@code ItemStack#ITEM_CODEC}（intermediary 名为 {@code field_47312}）在 <b>1.21.2 起被移除</b>，
     * 其定义被内联进 CODEC。若继续 {@code @Shadow} 它，Mixin 在 1.21.2+ 上应用时会抛
     * {@code InvalidMixinException: @Shadow field field_47312 was not located ... No refMap loaded}，
     * 客户端在 Bootstrap 阶段直接崩溃。</p>
     *
     * <p>这里改为直接调用 {@link Registries#ITEM}.{@code getEntryCodec()}——1.21.1 的原版
     * ITEM_CODEC 本身就是用它构建的（原版只在其上多加了一层"不得为 minecraft:air"的 validate），
     * 而该 API 在 1.21.1 – 1.21.4 全部存在，所以同一份代码可跨这四个版本运行。</p>
     *
     * @param ci Mixin 注入所需的 CallbackInfo
     */
    @Inject(method = "<clinit>", at = @At("TAIL"))
    private static void bis$allowLargeCounts(CallbackInfo ci) {
        CODEC = Codec.lazyInitialized(
                () -> RecordCodecBuilder.create(
                        instance -> instance.group(
                                        Registries.ITEM.getEntryCodec().fieldOf("id").forGetter(ItemStack::getRegistryEntry),
                                        Codecs.rangedInt(1, Integer.MAX_VALUE).fieldOf("count").orElse(1).forGetter(ItemStack::getCount),
                                        ComponentChanges.CODEC.optionalFieldOf("components", ComponentChanges.EMPTY).forGetter(ItemStack::getComponentChanges)
                                )
                                .apply(instance, ItemStack::new)
                )
        );
    }

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
        // 判定用 contains(MAX_DAMAGE) 而非 isDamageable()：后者还额外要求
        // 「未附加 UNBREAKABLE、且携带 DAMAGE 组件」。若用 isDamageable()，则一个带
        // Unbreakable 的工具会被当成普通物品放大到 GLOBAL_MAX，进而导致
        // Item#isEnchantable（判断式 getMaxCount() == 1）失败——该装备将无法在附魔台附魔。
        // 原版校验保证 MAX_DAMAGE 与 MAX_STACK_SIZE > 1 不会同时存在，故此处更保守也更正确。
        if (stack.contains(DataComponentTypes.MAX_DAMAGE)) {
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
     * @param type 提示类型
     * @param cir  回调对象，用于取回并修改已构建好的提示行列表
     */
    @Inject(method = "getTooltip", at = @At("RETURN"))
    private void bis$addStackCountTooltip(Item.TooltipContext context, PlayerEntity player, TooltipType type,
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
