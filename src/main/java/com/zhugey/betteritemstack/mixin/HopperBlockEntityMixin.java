package com.zhugey.betteritemstack.mixin;

import com.zhugey.betteritemstack.ContainerPolicy;
import net.minecraft.block.entity.HopperBlockEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.Direction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * <p>针对 {@link HopperBlockEntity} 的 Mixin，负责把漏斗的容量判定接回<b>容器感知</b>的逻辑。
 *
 * <p>为什么必须单独处理漏斗：漏斗的容量判定<b>完全不经过</b>
 * {@code Inventory#getMaxCountPerStack()}，而是直接使用 {@code ItemStack#getMaxCount()}，
 * 而后者已被 {@code ItemStackMixin} 全局放大到 {@link com.zhugey.betteritemstack.Config#GLOBAL_MAX}。
 * 若不处理，漏斗永远认为自己"没装满"，会不停抽取与合并，以 16 / 64 计数的红石计时器、
 * 计数器随之失效。
 *
 * <p>处理原则（两件事分开）：
 * <table border="1">
 *   <caption>判定点与上限来源</caption>
 *   <tr><th>方法</th><th>判定对象</th><th>上限来源</th></tr>
 *   <tr><td>{@code isFull()}</td><td>漏斗自身</td><td>{@code itemCapFor(this)}：漏斗默认不提升 → 原版 64 / 16 / 1</td></tr>
 *   <tr><td>{@code method_17769(Inventory, int)}</td><td>目标容器</td><td>{@code itemCapFor(目标)}：箱子等提升容器 → {@code global_max}</td></tr>
 *   <tr><td>{@code transfer(…, int, Direction)}</td><td>目标容器</td><td>{@code itemCapFor(目标)}，并夹下界保证增量非负</td></tr>
 *   <tr><td>{@code canMergeItems(ItemStack, ItemStack)}</td><td>—</td><td>只判"是否同种物品"；计数上限交由 {@code transfer} 负责</td></tr>
 * </table>
 *
 * <p>由此得到的行为：
 * <ul>
 *   <li>漏斗自己仍然只能装到原版上限——红石计时器 / 计数器不受影响；</li>
 *   <li>漏斗向箱子等<b>提升容器</b>推送时，可以把每一格填到 {@code global_max}，而不是 64。</li>
 * </ul>
 *
 * <p>漏斗矿车（{@code HopperMinecartEntity}）的搬运逻辑完全委托给本类的
 * {@code extract} / {@code transfer}，因此同样被覆盖。
 *
 * <p>4 参数的重载 {@code transfer(Inventory, Inventory, ItemStack, Direction)} 只负责遍历格子，
 * 自身不直接读取上限，故不在重定向范围内。
 *
 * <p><b>本分支（1.20 – 1.20.4）与 1.20.5+ 的差异</b>：1.20.5 起，"目标容器是否已满"的判定被
 * 写在 {@code isInventoryFull(Inventory, Direction)} 方法<b>本体</b>里；而本代它是
 * {@code getAvailableSlots(...).allMatch(...)}，那段判定被编译进了合成 lambda
 * {@code method_17769(Inventory, int)}（{@code stack.getCount() >= stack.getMaxCount()}）。
 * <b>因此 {@code @Redirect} 必须指向那个 lambda，而不能写 {@code isInventoryFull}</b> ——
 * 写在后者会因"目标方法内找不到该调用"而注入失败。其余三处（{@code isFull}、{@code transfer}、
 * {@code canMergeItems}）的结构与 1.20.5+ 一致。
 */
@Mixin(HopperBlockEntity.class)
public abstract class HopperBlockEntityMixin {

    private static final String GET_MAX_COUNT = "Lnet/minecraft/item/ItemStack;getMaxCount()I";

    /** 1.20 – 1.20.4：{@code isInventoryFull} 的判定 lambda（Yarn 未给可读名，故仍是中介名）。 */
    private static final String INVENTORY_FULL_LAMBDA =
            "method_17769(Lnet/minecraft/inventory/Inventory;I)Z";

    private static final String TRANSFER_WITH_SLOT =
            "transfer(Lnet/minecraft/inventory/Inventory;Lnet/minecraft/inventory/Inventory;"
                    + "Lnet/minecraft/item/ItemStack;ILnet/minecraft/util/math/Direction;)"
                    + "Lnet/minecraft/item/ItemStack;";

    /**
     * 目标容器"是否已满"判定中的上限：由目标容器决定。
     *
     * <p>注入点是 {@code method_17769(Inventory inventory, int slot)}，其实现为
     * {@code ItemStack s = inventory.getStack(slot); return s.getCount() >= s.getMaxCount();}。
     * 若沿用原版值，箱子每格一到 64 就会被判定为"已满"，从而停止推送。
     *
     * @param stack     原调用接收者（目标容器该格中的堆叠）
     * @param inventory 目标容器（目标方法的第 1 个参数）
     * @param slot      目标格索引（目标方法的第 2 个参数）
     * @return 该物品在目标容器中的上限
     */
    @Redirect(method = INVENTORY_FULL_LAMBDA, at = @At(value = "INVOKE", target = GET_MAX_COUNT))
    private static int bis$inventoryFullCap(ItemStack stack, Inventory inventory, int slot) {
        return ContainerPolicy.itemCapFor(inventory, stack);
    }

    /**
     * {@code transfer(Inventory from, Inventory to, ItemStack stack, int slot, Direction side)}
     * 中的上限：由目标容器 {@code to} 决定，这是真正决定"一次能搬多少"的闸门。
     *
     * <p>该返回值在原方法中只参与一次减法：{@code i = 返回值 - 目标格当前数量}，
     * 随后 {@code j = min(来料数量, i)}，再对两堆分别做 {@code decrement(j)} / {@code increment(j)}。
     * 因此这里必须保证 {@code i >= 0}：一旦目标格的数量已经超过上限（例如玩家调低了
     * {@code global_max}），负数增量会让目标格的数量<b>倒退</b>。夹一层下界即可消除该风险。
     *
     * @param stack     原调用接收者（来料堆叠）
     * @param from      来源容器（追加的目标方法参数）
     * @param to        目标容器（追加的目标方法参数）
     * @param incoming  目标方法的第 3 个参数，即来料堆叠
     * @param slot      目标格索引（追加的目标方法参数）
     * @param side      方向（追加的目标方法参数）
     * @return 参与"剩余可容纳量"计算的上限，保证结果非负
     */
    @Redirect(method = TRANSFER_WITH_SLOT, at = @At(value = "INVOKE", target = GET_MAX_COUNT))
    private static int bis$transferCap(ItemStack stack, Inventory from, Inventory to, ItemStack incoming,
                                      int slot, Direction side) {
        int cap = ContainerPolicy.itemCapFor(to, incoming);
        int current = to.getStack(slot).getCount();
        return Math.max(cap, current);
    }

    /**
     * {@code canMergeItems(ItemStack first, ItemStack second)}：原版实现为
     * {@code first.getCount() < first.getMaxCount() && ItemStack.canCombine(first, second)}。
     *
     * <p>前半段的计数检查在这里既无意义也有害：{@code first} 是目标格里的堆叠，而"能装多少"
     * 必须由目标容器决定，这个方法拿不到目标容器。若沿用原版值，一个已经堆到 5000 的提升容器
     * 格子会因为 {@code 5000 > 64} 而被判定为"不可合并"，导致漏斗无法把箱子补满到
     * {@code global_max} —— 与本 Mod 的目标冲突。
     *
     * <p>因此这里只保留真正有意义的"是否同种物品"判定。<b>计数上限并没有被放松</b>：
     * 权威闸门是 {@code transfer} 里的 {@code bis$transferCap}（已夹下界保证安全），
     * 以及 {@code to.setStack} 路径上的容量收窄。
     *
     * <p>{@code ItemStack#canCombine} 在本代（1.20 – 1.20.4）的定义是
     * {@code areItemsEqual && areNbtEqual}，即"物品 + NBT 相同"，没有 1.20.5+ 才有的物品组件概念。
     *
     * @param first  目标格中的堆叠
     * @param second 来料堆叠
     * @param cir    回调对象，用于设置返回值
     */
    @Inject(method = "canMergeItems", at = @At("HEAD"), cancellable = true)
    private static void bis$canMerge(ItemStack first, ItemStack second, CallbackInfoReturnable<Boolean> cir) {
        cir.setReturnValue(ItemStack.canCombine(first, second));
    }

    /**
     * {@code isFull()} 中的上限：判定对象是<b>漏斗自身</b>，因此必须用漏斗自己的分类
     * （默认不提升 → 原版上限），这样漏斗装满 64 / 16 / 1 后就会停止抽取。
     *
     * @param stack 原调用接收者
     * @return 该物品在漏斗中的上限
     */
    @Redirect(method = "isFull", at = @At(value = "INVOKE", target = GET_MAX_COUNT))
    private int bis$isFullCap(ItemStack stack) {
        return ContainerPolicy.itemCapFor((HopperBlockEntity) (Object) this, stack);
    }
}
