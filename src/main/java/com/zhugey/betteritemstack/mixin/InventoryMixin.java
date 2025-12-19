package com.zhugey.betteritemstack.mixin;

import com.zhugey.betteritemstack.Config;
import net.minecraft.inventory.Inventory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * <p>这是针对 Inventory 的 Mixin，用于修改玩家背包或任何物品栏的最大堆叠数。
 * <p>在原版中，Inventory.getMaxCountPerStack 返回固定值（通常是 64），这里统一替换为 Config.GLOBAL_MAX，
 * <p>从而让背包中的物品堆叠数量可以突破默认限制。
 */
@Mixin(Inventory.class)
public interface InventoryMixin {

    /**
     * <p>拦截 Inventory.getMaxCountPerStack 方法的头部调用。
     * <p>通过 cancellable=true，可以直接返回我们自定义的全局最大堆叠数。
     *
     * @param cir 回调对象，用于修改返回值
     */
    @Inject(method = "getMaxCountPerStack", at = @At("HEAD"), cancellable = true)
    private void overrideMaxStackSize(CallbackInfoReturnable<Integer> cir) {
        cir.setReturnValue(Config.GLOBAL_MAX);
    }
}
