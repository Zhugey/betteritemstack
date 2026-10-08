package com.zhugey.betteritemstack.mixin;

import com.zhugey.betteritemstack.Config;
import com.zhugey.betteritemstack.ContainerPolicy;
import net.minecraft.inventory.Inventory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * <p>针对 {@link Inventory} 的 Mixin，用于把"每格容量"按容器类型区分对待。
 *
 * <p>本代（1.20 – 1.20.4）的 {@code Inventory} <b>只有</b> {@code getMaxCountPerStack()}，
 * 还没有 1.20.5 才加入的 {@code getMaxCount(ItemStack)}。所以这里只拦截前者：
 * <ul>
 *   <li><b>提升容器</b>（默认：玩家背包、箱子、木桶、潜影盒等，见配置 {@code containers}）：
 *       返回 {@link Config#GLOBAL_MAX}；</li>
 *   <li><b>非提升容器</b>（默认：漏斗、漏斗矿车）：不拦截，保持原版默认 99。</li>
 * </ul>
 *
 * <p>注意：这里刻意<b>不</b>拦截非提升分支。原版默认值 99 本身没有意义——有效的格子容量
 * 总会被"物品层上限"收窄（1.20.5+ 是 {@code getMaxCount(ItemStack)}，本代则是插入逻辑里
 * 直接读的 {@code ItemStack#getMaxCount()}）。一旦在这里改成 64，反而会让 16 上限的物品
 * （鸡蛋、雪球等）被错误地允许堆到 64。
 *
 * <p>物品层的上限由被放大的 {@code ItemStack#getMaxCount()} 提供；漏斗那条路径不收窄，
 * 因为漏斗的容量判定<b>完全不经过本接口</b>，而是由 {@code HopperBlockEntityMixin} 分别在
 * {@code isFull} / {@code method_17769}（目标容器是否已满）/ {@code transfer} / {@code canMergeItems}
 * 四处接回容器感知逻辑。
 *
 * <p><b>与 1.20.5+ 分支的差异</b>：那边还需要拦截 {@code getMaxCount(ItemStack)}，并用
 * {@link ContainerPolicy#capacityFor} 把返回值夹到"不小于该堆叠当前数量"，
 * 以抵消原版 6 处 {@code ItemStack#capCount} 的"插入即截断"行为。
 * <b>本代没有 {@code ItemStack#capCount}</b>，那条代码路径不存在，因此不需要这个夹取
 * （{@code ContainerPolicy#capacityFor} 在本分支保留但无人调用，仅作参考）。
 */
@Mixin(Inventory.class)
public interface InventoryMixin {

    /**
     * 拦截 {@code Inventory#getMaxCountPerStack()}。
     *
     * @param cir 回调对象，用于设置返回值
     */
    @Inject(method = "getMaxCountPerStack", at = @At("HEAD"), cancellable = true)
    private void bis$boostPerStack(CallbackInfoReturnable<Integer> cir) {
        if (ContainerPolicy.isBoosted((Inventory) (Object) this)) {
            cir.setReturnValue(Config.GLOBAL_MAX);
        }
    }
}
