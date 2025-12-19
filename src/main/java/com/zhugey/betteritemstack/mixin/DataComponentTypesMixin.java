package com.zhugey.betteritemstack.mixin;

import com.zhugey.betteritemstack.Config;
import net.minecraft.component.ComponentMap;
import net.minecraft.component.ComponentType;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.AttributeModifiersComponent;
import net.minecraft.component.type.ItemEnchantmentsComponent;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.util.Rarity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * <p>通过 Mixin 修改 DataComponentTypes 类，主要目的是覆盖默认物品组件中的最大堆叠数量。
 * <p>将 DEFAULT_ITEM_COMPONENTS 的 MAX_STACK_SIZE 替换为 Config 中设置的全局最大值。
 * <p>其他组件保持原有默认值。
 */
@Mixin(DataComponentTypes.class)
public class DataComponentTypesMixin {

    @Mutable
    @Shadow
    @Final
    public static ComponentMap DEFAULT_ITEM_COMPONENTS;

    @Shadow
    @Final
    public static ComponentType<Integer> MAX_STACK_SIZE;

    @Shadow
    @Final
    public static ComponentType<LoreComponent> LORE;

    @Shadow
    @Final
    public static ComponentType<ItemEnchantmentsComponent> ENCHANTMENTS;

    @Shadow
    @Final
    public static ComponentType<Integer> REPAIR_COST;

    @Shadow
    @Final
    public static ComponentType<AttributeModifiersComponent> ATTRIBUTE_MODIFIERS;

    @Shadow
    @Final
    public static ComponentType<Rarity> RARITY;

    /**
     * <p>在类初始化尾部注入，覆盖默认物品组件。
     * <p>主要修改 MAX_STACK_SIZE 为全局最大堆叠值，其余组件保持默认。
     *
     * @param ci Mixin 回调对象
     */
    @Inject(method = "<clinit>", at = @At("TAIL"))
    private static void overrideMaxStackSize(CallbackInfo ci) {
        DEFAULT_ITEM_COMPONENTS = ComponentMap.builder()
                .add(MAX_STACK_SIZE, Config.GLOBAL_MAX) // 设置全局最大堆叠
                .add(LORE, LoreComponent.DEFAULT) // 保持默认 Lore
                .add(ENCHANTMENTS, ItemEnchantmentsComponent.DEFAULT) // 保持默认附魔
                .add(REPAIR_COST, 0) // 默认修复成本
                .add(ATTRIBUTE_MODIFIERS, AttributeModifiersComponent.DEFAULT) // 默认属性修饰器
                .add(RARITY, Rarity.COMMON) // 默认稀有度为普通
                .build();
    }
}
