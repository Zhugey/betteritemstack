package com.zhugey.betteritemstack;

import net.minecraft.item.ItemStack;

/**
 * 「原版上限」查询工具。
 *
 * <p>背景：{@code ItemStack#getMaxCount()} 被 {@code ItemStackMixin} 全局改写为
 * {@link Config#GLOBAL_MAX}，因此任何调用它的原版逻辑（尤其是漏斗的容量判定）都会
 * 拿到被放大的值。凡是需要"这件物品原本能堆多少"的地方，都必须走本类。
 *
 * <p>实现方式：本代（1.20 – 1.20.4）还没有物品组件，原版上限就写在
 * {@code Item.Settings#maxCount()} 里、由 {@code Item#getMaxCount()} 暴露。
 * 这里直接读<b>物品级</b>的上限，绕过被改写的 {@code ItemStack#getMaxCount()}，因此：
 * <ul>
 *   <li>不需要维护任何物品上限数据表；</li>
 *   <li>对模组新增物品同样有效；</li>
 *   <li>与物品自己的 {@code Item.Settings#maxCount()} 声明完全一致（64 / 16 / 1 等）。</li>
 * </ul>
 *
 * <p>（1.20.5+ 分支里 {@code ItemStack} 自己带了 {@code minecraft:max_stack_size} 组件，
 * 那边读的是组件；本代没有组件，只能读物品。）
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
        if (stack == null || stack.isEmpty()) {
            return FALLBACK;
        }
        int limit = stack.getItem().getMaxCount();
        return limit < 1 ? FALLBACK : limit;
    }
}

