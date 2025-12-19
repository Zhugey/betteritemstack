package com.zhugey.betteritemstack.mixin;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.zhugey.betteritemstack.BetterItemStack;
import com.zhugey.betteritemstack.Config;
import net.minecraft.component.ComponentChanges;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.util.dynamic.Codecs;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * <p>对 {@link ItemStack} 的 Mixin，用于修改默认最大堆叠数量和序列化 Codec。</p>
 * <p>可以为大多数物品应用全局最大堆叠数量，非堆叠物品或可损坏物品保持原始数量。</p>
 * <p>同时重写 ItemStack 内部的 CODEC，以支持堆叠数量超过 64 的情况。</p>
 */
@Mixin(ItemStack.class)
public abstract class ItemStackMixin {

    @Mutable
    @Shadow
    @Final
    public static Codec<ItemStack> CODEC;

    @Shadow
    @Final
    public static Codec<RegistryEntry<Item>> ITEM_CODEC;

    /**
     * <p>重写 ItemStack 的静态初始化方法中的 CODEC，使其支持最大堆叠数量为 Integer.MAX_VALUE。</p>
     * <p>保证大堆叠的物品可以正常序列化和反序列化。</p>
     *
     * @param ci Mixin 注入所需的 CallbackInfo
     */
    @Inject(method = "<clinit>", at = @At("TAIL"))
    private static void overrideITEM_CODEC(CallbackInfo ci) {
        CODEC = Codec.lazyInitialized(
                () -> RecordCodecBuilder.create(
                        instance -> instance.group(
                                        ITEM_CODEC.fieldOf("id").forGetter(ItemStack::getRegistryEntry),
                                        Codecs.rangedInt(1, Integer.MAX_VALUE).fieldOf("count").orElse(1).forGetter(ItemStack::getCount),
                                        ComponentChanges.CODEC.optionalFieldOf("components", ComponentChanges.EMPTY).forGetter(ItemStack::getComponentChanges)
                                )
                                .apply(instance, ItemStack::new)
                )
        );
    }

    /**
     * <p>重写 {@link ItemStack#getMaxCount()} 方法，返回全局最大堆叠数量。</p>
     * <p>非堆叠物品列表中的物品或可损坏物品将保持原始最大堆叠数量。</p>
     *
     * @param cir Mixin 注入所需的 CallbackInfoReturnable，用于设置返回值
     */
    @Inject(method = "getMaxCount", at = @At("HEAD"), cancellable = true)
    private void overrideMaxCount(CallbackInfoReturnable<Integer> cir) {
        ItemStack stack = (ItemStack) (Object) this;
        String itemId = Registries.ITEM.getId(stack.getItem()).toString();

        if (!BetterItemStack.CONFIG.nonStackableItems.contains(itemId) && !stack.isDamageable()) {
            cir.setReturnValue(Config.GLOBAL_MAX);
        }
    }
}
