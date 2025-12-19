package com.zhugey.betteritemstack.mixin;

import com.zhugey.betteritemstack.Config;
import net.minecraft.entity.ItemEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * <p>这是针对 ItemEntity 的 Mixin，用于修改物品实体在合并堆叠时的最大堆叠数限制。
 * <p>在原版中，物品实体合并时会受 ItemStack 的最大堆叠数限制，这里将其统一替换为 Config 中配置的全局最大值，
 * <p>从而实现超过默认 64 的堆叠数。
 */
@Mixin(ItemEntity.class)
public class ItemEntityMixin {

    /**
     * <p>在 ItemEntity.merge 方法中修改第三个参数（合并时的最大堆叠数）。
     * <p>原方法：
     * <pre>merge(ItemStack stack1, ItemStack stack2, int maxCount)</pre>
     * <p>这里将 maxCount 替换为 Config.GLOBAL_MAX，从而允许更大的堆叠。
     *
     * @param original 原始方法传入的最大堆叠数
     * @return 替换后的最大堆叠数
     */
    @ModifyVariable(
            method = "merge(Lnet/minecraft/item/ItemStack;Lnet/minecraft/item/ItemStack;I)Lnet/minecraft/item/ItemStack;",
            at = @At("HEAD"),
            index = 2,      // 第三个参数就是 maxCount
            argsOnly = true // 只修改参数，不改变方法返回值
    )
    private static int overrideMaxCount(int original) {
        return Config.GLOBAL_MAX;
    }
}
