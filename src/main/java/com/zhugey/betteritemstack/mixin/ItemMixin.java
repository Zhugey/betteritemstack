package com.zhugey.betteritemstack.mixin;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.tooltip.TooltipType;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * <p>对 {@link Item} 的 Mixin，用于在物品堆叠数量较大时显示堆叠数 Tooltip。</p>
 * <p>当物品堆叠数量达到一定阈值时，在鼠标悬停时显示当前数量。</p>
 */
@Mixin(Item.class)
public abstract class ItemMixin {

    /**
     * <p>在物品 Tooltip 添加堆叠数量信息。</p>
     * <p>如果堆叠数量大于等于配置中设定的阈值，则在 Tooltip 中显示当前堆叠数。</p>
     *
     * @param stack   当前 ItemStack
     * @param context Tooltip 上下文
     * @param tooltip Tooltip 文本列表
     * @param type    Tooltip 类型
     * @param ci      Mixin 回调信息
     */
    @Inject(method = "appendTooltip", at = @At("HEAD"))
    private void addStackCountTooltip(ItemStack stack, Item.TooltipContext context, List<Text> tooltip, TooltipType type, CallbackInfo ci) {
        // 如果物品数量达到1000，添加 Tooltip 显示数量
        if (stack.getCount() >= 1000) {
            tooltip.add(Text.translatable("tooltip.betteritemstack.stackcount", stack.getCount()));
        }
    }
}
