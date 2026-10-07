package com.zhugey.betteritemstack.mixin;

import com.zhugey.betteritemstack.ContainerPolicy;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.slot.Slot;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * <p>针对 {@link Slot} 的 Mixin，用于让 GUI 侧的"每格容量"同样按容器类型区分。
 *
 * <p>原版：
 * <pre>
 * public int getMaxItemCount()                { return this.inventory.getMaxCountPerStack(); }
 * public int getMaxItemCount(ItemStack stack) { return Math.min(this.getMaxItemCount(), stack.getMaxCount()); }
 * </pre>
 *
 * <p>问题在于第二行的 {@code stack.getMaxCount()} 已被全局放大：如果不处理，所有容器的槽位
 * 都会变成超大容量——包括漏斗的 GUI（玩家手动往漏斗格塞 64 个以上就会破坏红石计时器），
 * 也包括附魔台输入槽这类"只接受 1 件"的槽位。
 *
 * <p><b>本 Mixin 的关键约束：必须保留 {@code this.getMaxItemCount()} 这一项。</b>
 * 不少原版与模组槽位通过覆写无参重载来声明"本槽位天生只能放 N 件"，最典型的是附魔台输入槽：
 * <pre>
 * // EnchantmentScreenHandler
 * this.addSlot(new Slot(this.inventory, 0, 15, 47) {
 *     &#64;Override public int getMaxItemCount() { return 1; }
 * });
 * </pre>
 * 附魔台与附魔灌注台的判定都要求槽内"恰好 1 件"（{@code Item.isEnchantable} 检查
 * {@code getMaxCount() == 1}、{@code BookItem.isEnchantable} 检查 {@code getCount() == 1}）。
 * 一旦绕过这个 1，整摞物品就能被 shift-click 塞进去，判定随即失败——表现为
 * <b>附魔台不显示附魔、附魔灌注台无法使用</b>。
 *
 * <p>因此这里只替换被放大的那一项，其余原版语义原样复刻：
 * <pre>
 * min(this.getMaxItemCount(), ContainerPolicy.itemCapFor(this.inventory, stack))
 * </pre>
 * 结果：附魔台输入槽仍为 1；箱子槽为 {@code global_max}；漏斗槽为原版上限（64 / 16 / 1）。
 *
 * <p>另外，若某个槽位子类<b>覆写了带参重载</b>，则本注入不会执行（方法被覆写后不再走父类实现），
 * 其自定义上限天然得到保留。
 *
 * <p>返回值还会夹一层下界（不小于槽内当前数量）。这是为了应对 {@code global_max} 在游戏内被
 * 调低之后、槽内仍留着超过新上限的堆叠的情形——原版 {@code Slot#insertStack} 会据此算出负数增量，
 * 而 {@code ItemStack#split(负数)} / {@code increment(负数)} 会让数量朝反方向变化。正常情形下
 * （当前数量不超过上限）该夹取不产生任何影响。
 *
 * <p>无参重载不拦截：非提升容器需要保留原版的 99，提升容器已由 {@code InventoryMixin} 处理。
 */
@Mixin(Slot.class)
public abstract class SlotMixin {

    @Shadow
    @Final
    public Inventory inventory;

    /**
     * @param stack 目标堆叠
     * @param cir   回调对象，用于设置返回值
     */
    @Inject(method = "getMaxItemCount(Lnet/minecraft/item/ItemStack;)I", at = @At("HEAD"), cancellable = true)
    private void bis$maxItemCount(ItemStack stack, CallbackInfoReturnable<Integer> cir) {
        Slot self = (Slot) (Object) this;
        // 保留槽位自身声明的上限（附魔台 = 1），只替换被放大的物品上限。
        int cap = Math.min(self.getMaxItemCount(), ContainerPolicy.itemCapFor(this.inventory, stack));

        // 夹下界：global_max 可在游戏内用 /bis set 调低，若槽内已有超过新上限的堆叠，
        // 原版 Slot#insertStack 会算出 min(count, cap - 当前数量) 为负数，
        // 随后 stack.split(负数) / increment(负数) 会让数量朝反方向变化（可被用于复制物品）。
        // 返回值不小于槽内当前数量即可保证增量恒为非负。正常情形下（当前数量 <= cap）无任何影响。
        cir.setReturnValue(Math.max(cap, self.getStack().getCount()));
    }
}
