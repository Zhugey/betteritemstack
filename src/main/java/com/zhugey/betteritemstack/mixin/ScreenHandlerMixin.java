package com.zhugey.betteritemstack.mixin;

import com.zhugey.betteritemstack.ContainerPolicy;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.screen.slot.Slot;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Set;

/**
 * <p>针对 {@link ScreenHandler} 的两处修正，都服务于同一个目标：
 * <b>让客户端那一侧的容量判定与服务端保持一致</b>。
 *
 * <h2>一、登记客户端 GUI 的镜像容器（{@code addSlot}）</h2>
 *
 * <p>原版客户端打开方块容器时走的是 <b>客户端专用</b>工厂，
 * 例如 {@code GenericContainerScreenHandler#createGeneric9x3(int, PlayerInventory)}，
 * 其实现是 {@code new SimpleInventory(9 * rows)}。服务端走的则是
 * {@code createGeneric9x3(int, PlayerInventory, Inventory)}，容器是真实的方块实体。
 *
 * <p>于是同一个箱子在两侧的类型并不相同：服务端是 {@code ChestBlockEntity}（被
 * {@code ContainerPolicy#keyOf} 识别为 {@code chest}，容量 = {@code global_max}），
 * 客户端却是一个临时的 {@code SimpleInventory}（不在 {@code keyOf} 的识别链里，
 * 在 {@code unknown=false} 下被判为"未识别容器不提升"，容量退回 64）。
 *
 * <p>这里在 {@code addSlot} 的时刻，按界面自身的 {@link ScreenHandlerType} 反推它镜像的是哪类
 * 方块容器，交给 {@link ContainerPolicy#rememberMirrorContainer} 登记。之后
 * {@code ContainerPolicy#keyOf} 就能认出这些镜像容器，客户端与服务端的判定随之统一。
 *
 * <p>之所以选 {@code addSlot} 作为挂载点，是因为它是所有界面构建槽位的<b>唯一入口</b>：
 * 无论箱子、木桶、潜影盒、漏斗、发射器还是熔炉，客户端构造器最终都会走到这里。
 * 而服务端的真实方块容器都不是 {@code SimpleInventory}，会被
 * {@link ContainerPolicy#rememberMirrorContainer} 直接忽略。
 *
 * <h2>二、修正拖拽分堆的每格数量（{@code calculateStackSize}）</h2>
 *
 * <p>原版：
 * <pre>
 * case 0 -&gt; MathHelper.floor(stack.getCount() / slots.size()); // 左键拖拽：均分
 * case 1 -&gt; 1;                                                  // 右键拖拽：每格 1 个
 * case 2 -&gt; stack.getItem().getMaxCount();                       // 中键拖拽：每格一"满堆"
 * </pre>
 *
 * <p><b>问题在 case 2</b>：它读的是 {@code Item#getMaxCount()}（<b>物品级</b>上限，恒为 64），
 * 而不是被本 Mod 放大的 {@code ItemStack#getMaxCount()}。于是中键拖拽分堆时，每个格子只能落下
 * 64 个——即使拖拽预览的其它部分都已经按 {@code global_max} 计算。
 *
 * <p>改为跟随 {@code stack.getMaxCount()} 之后语义反而更自洽："每格放满一摞"，而"一摞"是多少
 * 由本 Mod 决定。安全性由调用方保证：服务端 {@code internalOnSlotClick} 与客户端
 * {@code HandledScreen} 都会把结果再与
 * {@code min(cursorStack.getMaxCount(), slot.getMaxItemCount(cursorStack))} 取一次最小值，
 * 因此漏斗、附魔台这类<b>不提升</b>的容器依旧只能落下原版数量。带耐久度的物品与黑名单物品的
 * {@code getMaxCount()} 本来就是原版值，行为完全不变。
 */
@Mixin(ScreenHandler.class)
public abstract class ScreenHandlerMixin {

    /** 界面类型，用于反推镜像的是哪类方块容器；部分界面（如马匹）注册为 {@code null}。 */
    @Shadow
    @Final
    private ScreenHandlerType<?> type;

    /**
     * <p>注意 handler 的最后一个参数必须是 {@code CallbackInfoReturnable<Slot>} 而不是
     * {@code CallbackInfo}——{@code addSlot} <b>有返回值</b>（返回刚挂载的 {@link Slot}）。
     * 用错类型不会影响编译，但会在启动时被 Mixin 以
     * {@code InvalidInjectionException: CallbackInfoReturnable is required!} 拒收，
     * 进而连带 {@code ScreenHandler} 类加载失败、整个游戏起不来。
     *
     * @param slot 正在挂载的槽位
     * @param cir  回调对象（本处不取消，仅旁路登记）
     */
    @Inject(method = "addSlot(Lnet/minecraft/screen/slot/Slot;)Lnet/minecraft/screen/slot/Slot;",
            at = @At("HEAD"))
    private void bis$rememberMirrorContainer(Slot slot, CallbackInfoReturnable<Slot> cir) {
        // 只登记客户端 GUI 的 SimpleInventory 镜像；服务端的方块容器不是 SimpleInventory，
        // 马匹/商人/信标等真正使用 SimpleInventory 的界面也会因其界面类型不在映射表内而被跳过。
        ContainerPolicy.rememberMirrorContainer(this.type, slot.inventory);
    }

    /**
     * 把 case 2（中键拖拽）里的物品级上限换成堆叠自身的上限。
     *
     * @param item  原调用的接收者（{@code stack.getItem()}）
     * @param slots 目标方法参数：本次拖拽涉及的槽位
     * @param mode  目标方法参数：拖拽按钮类型（0 左 / 1 右 / 2 中）
     * @param stack 目标方法参数：鼠标上持有的堆叠
     * @return 每格应落下的数量
     */
    @Redirect(method = "calculateStackSize(Ljava/util/Set;ILnet/minecraft/item/ItemStack;)I",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/item/Item;getMaxCount()I"))
    private static int bis$craftSizeFollowsStackCap(Item item, Set<Slot> slots, int mode, ItemStack stack) {
        return stack == null || stack.isEmpty() ? item.getMaxCount() : stack.getMaxCount();
    }
}
