package com.zhugey.betteritemstack.mixin;

import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * <p>修正创造模式取物时服务端对数量的<b>硬编码 64</b>校验。</p>
 *
 * <h2>根因</h2>
 *
 * <p>本代（1.20 – 1.20.4）原版 {@code ServerPlayNetworkHandler#onCreativeInventoryAction} 里有一段
 * 防作弊校验，把「客户端声称的数量」与字面量 64 比较：</p>
 *
 * <pre>
 * boolean valid = itemStack.isEmpty()
 *         || itemStack.getDamage() &gt;= 0
 *         &amp;&amp; itemStack.getCount() &lt;= 64;   // ← 硬编码 64
 * </pre>
 *
 * <p>数量超过 64 的堆叠会被<b>静默丢弃</b>（既不 {@code setStack} 也不回包），于是出现了用户实测的
 * 现象：创造模式点一下拿到 9999 个（客户端把本 Mod 放大的 {@code getMaxCount()} 当上限，乐观显示），
 * 但服务端根本没接收；一打开箱子触发整包同步（{@code InventoryS2CPacket}），客户端背包被服务端的
 * 真实状态覆盖，那些物品就「消失」了。它<b>与网络/存档的字节截断无关</b>——那是 {@code PacketByteBufMixin}
 * 和 {@code ItemStackMixin} 各自负责的另一条路径。</p>
 *
 * <p>1.20.5 起原版才把这段改成 {@code getCount() <= getMaxCount()}。本 Mod 的目标正是让上限可配置，
 * 因此要把这条硬编码校验恢复成「数量不超过该物品真实上限」的语义。</p>
 *
 * <h2>改法</h2>
 *
 * <p>用 {@code @Redirect} 把校验里唯一的那次 {@code ItemStack#getCount()} 调用替换成「是否超限」的
 * 哨兵值：超限返回 {@code 65}（&gt; 64，触发原版的拒绝分支），合法返回 {@code 1}（≤ 64，通过）。
 * 于是原版的 {@code count > 64} 被等价地改写为 {@code count > maxCount}，其中 {@code maxCount} 已被
 * {@code ItemStackMixin} 放大为 {@code Config.GLOBAL_MAX}（对可堆叠物品）。</p>
 *
 * <p>之所以不直接 {@code @ModifyConstant} 把 64 改大：{@code bipush} 只能承载 -128 ~ 127 的常量，
 * 而 {@code global_max} 可被玩家在游戏内任意调高，改成一个固定值必然在某个上限下重新失效；
 * 也改写不了「改成调用 {@code getMaxCount()}」这种把常量换成方法调用的操作。哨兵值方案一次到位，
 * 且保留了原版的防作弊意图（数量超过真实上限仍会被拒）。</p>
 */
@Mixin(ServerPlayNetworkHandler.class)
public abstract class ServerPlayNetworkHandlerMixin {

    /**
     * 把创造模式取物校验里的数量读取替换为「是否超限」的哨兵值。
     *
     * @param stack 被校验的堆叠（原版该调用的接收者）
     * @return 65 表示超限（会触发原版 {@code > 64} 的拒绝分支），1 表示合法
     */
    @Redirect(method = "onCreativeInventoryAction",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/item/ItemStack;getCount()I"))
    private int bis$relaxCreativeCount(ItemStack stack) {
        int count = stack.getCount();
        int max = stack.getMaxCount();
        return count > max ? 65 : 1;
    }
}
