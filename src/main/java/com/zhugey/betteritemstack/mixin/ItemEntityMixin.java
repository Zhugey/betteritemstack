package com.zhugey.betteritemstack.mixin;


import com.zhugey.betteritemstack.Config;
import net.minecraft.entity.ItemEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(ItemEntity.class)
public class ItemEntityMixin {
    @ModifyVariable(
            method = "merge(Lnet/minecraft/item/ItemStack;Lnet/minecraft/item/ItemStack;I)Lnet/minecraft/item/ItemStack;",
            at = @At("HEAD"),
            index = 2,
            argsOnly = true
    )
    private static int overrideMaxCount(int original) {
        return Config.GLOBAL_MAX;
    }
}
