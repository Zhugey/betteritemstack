package com.zhugey.betteritemstack;

import net.minecraft.component.DataComponentTypes;
import net.minecraft.item.ItemStack;

/**
 * 「原版上限」查询工具。
 *
 * <p>背景：{@code ItemStack#getMaxCount()} 被 {@code ItemStackMixin} 全局改写为
 * {@link Config#GLOBAL_MAX}，因此任何调用它的原版逻辑（尤其是漏斗的容量判定）都会
 * 拿到被放大的值。凡是需要"这件物品原本能堆多少"的地方，都必须走本类。
 *
 * <p>实现方式：原版 {@code ItemStack#getMaxCount()} 的等价逻辑就是读取组件
 * {@code minecraft:max_stack_size}。这里直接读组件，绕过被改写的方法，因此：
 * <ul>
 *   <li>不需要维护任何物品上限数据表；</li>
 *   <li>对模组新增物品同样有效；</li>
 *   <li>与物品自己的 {@code Item.Settings#maxCount()} / {@code maxDamage()} 声明完全一致
 *       （64 / 16 / 1 等）。</li>
 * </ul>
 */
public final class VanillaMax {

    /** 取不到上限时的兜底值，与原版默认一致。 */
    private static final int FALLBACK = 1;

    private VanillaMax() {
    }

    /**
     * @param stack 物品堆叠，允许为 null
     * @return 该堆叠的原版上限；空堆叠或数据缺失时返回 1
     */
    public static int of(ItemStack stack) {
        if (stack == null) {
            return FALLBACK;
        }
        Integer limit = stack.get(DataComponentTypes.MAX_STACK_SIZE);
        return limit == null || limit < 1 ? FALLBACK : limit;
    }
}
