package com.zhugey.betteritemstack.mixin;

import com.zhugey.betteritemstack.Config;
import com.zhugey.betteritemstack.ContainerPolicy;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * <p>针对 {@link Inventory} 的 Mixin，用于把"每格容量"按容器类型区分对待。
 *
 * <p>原版语义：
 * <pre>
 * default int getMaxCountPerStack()        { return 99; }
 * default int getMaxCount(ItemStack stack) { return Math.min(getMaxCountPerStack(), stack.getMaxCount()); }
 * </pre>
 *
 * <p>本 Mod 的处理：
 * <ul>
 *   <li><b>提升容器</b>（默认：玩家背包、箱子、木桶、潜影盒等，见配置
 *       {@code containers}）：两个方法都返回 {@link Config#GLOBAL_MAX}。</li>
 *   <li><b>非提升容器</b>（默认：漏斗、漏斗矿车）：
 *       <ul>
 *         <li>{@code getMaxCountPerStack()} 不拦截，保持原版默认 99；</li>
 *         <li>{@code getMaxCount(stack)} 复刻原版公式，但把被放大的
 *             {@code stack.getMaxCount()} 换成 {@link com.zhugey.betteritemstack.VanillaMax}
 *             提供的原版上限。这样漏斗的 {@code setStack → capCount(getMaxCount(stack))}
 *             会精确还原为原版行为（普通物品 64、鸡蛋等 16、不可堆叠物品 1）。</li>
 *       </ul>
 *   </li>
 * </ul>
 *
 * <p>注意：这里刻意<b>不</b>拦截 {@code getMaxCountPerStack} 的非提升分支。原版默认值 99
 * 本身没有意义（有效的格子容量总是被 {@code min(…, stack.getMaxCount())} 收窄），
 * 一旦在这里改成 64，反而会让 16 上限的物品（鸡蛋、雪球等）被错误地允许堆到 64。
 *
 * <p><b>{@code getMaxCount(ItemStack)} 的返回值必须夹到"不小于该堆叠当前数量"</b>，
 * 这一点由 {@link ContainerPolicy#capacityFor} 实现。原因：原版有 6 处把它直接喂给
 * {@code ItemStack#capCount}（{@code LockableContainerBlockEntity#setStack} 等），
 * 而 {@code capCount} 是"直接覆盖数量、超出部分销毁不返还"。若不夹取，
 * 游戏内把 {@code global_max} 调低后，箱子/木桶/潜影盒里的超量堆叠会在下一次
 * {@code setStack} 时被静默截断（玩家背包因为不含这一步 capCount 而幸免）。
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

    /**
     * 拦截 {@code Inventory#getMaxCount(ItemStack)}，按容器类型给出每格容量上限。
     *
     * <p>返回值保证不小于 {@code stack} 的当前数量，详见
     * {@link ContainerPolicy#capacityFor}——这是"调低 global_max 后箱子物品不丢失"的关键。
     *
     * @param stack 目标堆叠
     * @param cir   回调对象，用于设置返回值
     */
    @Inject(method = "getMaxCount(Lnet/minecraft/item/ItemStack;)I", at = @At("HEAD"), cancellable = true)
    private void bis$capacityFor(ItemStack stack, CallbackInfoReturnable<Integer> cir) {
        cir.setReturnValue(ContainerPolicy.capacityFor((Inventory) (Object) this, stack));
    }
}
