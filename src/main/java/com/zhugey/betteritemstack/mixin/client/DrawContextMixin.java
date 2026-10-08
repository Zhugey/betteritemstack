package com.zhugey.betteritemstack.mixin.client;

import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.item.ItemStack;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * <p>通过 Mixin 修改 {@code DrawContext} 在物品栏格子里显示堆叠数的方式，把大数字格式化为
 * K / M / B 单位并着色。</p>
 *
 * <p><b>1.21.5 的方法改名（重要）</b>：本注入的目标方法在 1.21.1 – 1.21.4 叫
 * {@code drawItemInSlot}，1.21.5 起改名为 {@code drawStackOverlay}——但它是<b>同一个成员</b>
 * （intermediary 名始终是 {@code method_51432}，描述符也一致）。
 * Mixin 注解里必须写 Yarn 名，而 Loom 的映射里找不到旧名时**不会报错**，只会把字符串原样留在
 * 注解里，导致运行期注入失败。故本分支必须用新名 {@code drawStackOverlay}。</p>
 *
 * <p>语义未变：1.21.5 的 {@code drawStackOverlay} 只是把那个表示"数量覆盖文本"的
 * {@code String} 参数原样转发给新拆出的 {@code drawStackCount}，因此改注入点后行为与旧版一致。</p>
 */
@Mixin(DrawContext.class)
public class DrawContextMixin {

    /**
     * <p>修改显示堆叠数量时用到的字符串。</p>
     * <p>当物品数量大于或等于 1000 时，按千、百万、十亿缩写显示，并添加颜色标识。</p>
     *
     * @param original      原值（即原版传入的数量覆盖文本，通常为 null）
     * @param textRenderer  文本渲染器对象
     * @param stack         物品堆栈对象
     * @param x             x 坐标
     * @param y             y 坐标
     * @param countOverride 可选的数量覆盖字符串
     * @return 格式化后的数量字符串
     */
    @ModifyVariable(
            method = "drawStackOverlay(Lnet/minecraft/client/font/TextRenderer;Lnet/minecraft/item/ItemStack;IILjava/lang/String;)V",
            at = @At("HEAD"),
            argsOnly = true
    )
    public String itemStackTextStringFormat(String original, TextRenderer textRenderer, ItemStack stack, int x, int y, @Nullable String countOverride) {
        int stackCount = stack.getCount();

        if (stackCount >= 1_000_000_000) { // 十亿以上
            double value = stackCount / 1_000_000_000.0;
            return formatNumber(value) + "§6B";
        } else if (stackCount >= 1_000_000) { // 百万以上
            double value = stackCount / 1_000_000.0;
            return formatNumber(value) + "§3M";
        } else if (stackCount >= 1_000) { // 千以上
            double value = stackCount / 1_000.0;
            return formatNumber(value) + "§9K";
        } else { // 小于千，保持原始显示
            return original;
        }
    }

    /**
     * <p>格式化大数字，保留一位小数，如果小数为 0 则去掉小数点。
     *
     * @param value 要格式化的数字
     * @return 格式化后的字符串
     */
    @Unique
    private String formatNumber(double value) {
        if (value == (int) value || value > 100D) {
            return String.valueOf((int) value);
        }
        String formatted = String.format("%.1f", value);
        if (formatted.charAt(formatted.indexOf('.') + 1) == '0') {
            return String.valueOf((int) value);
        }
        return formatted;
    }
}
