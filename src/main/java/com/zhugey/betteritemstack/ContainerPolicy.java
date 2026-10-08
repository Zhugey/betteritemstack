package com.zhugey.betteritemstack;

import net.minecraft.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.block.entity.BarrelBlockEntity;
import net.minecraft.block.entity.BrewingStandBlockEntity;
import net.minecraft.block.entity.ChestBlockEntity;
import net.minecraft.block.entity.DispenserBlockEntity;
import net.minecraft.block.entity.DropperBlockEntity;
import net.minecraft.block.entity.HopperBlockEntity;
import net.minecraft.block.entity.ShulkerBoxBlockEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.entity.vehicle.ChestMinecartEntity;
import net.minecraft.entity.vehicle.HopperMinecartEntity;
import net.minecraft.inventory.DoubleInventory;
import net.minecraft.inventory.EnderChestInventory;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;

import java.util.List;

/**
 * 容器提升策略。
 *
 * <p>负责回答三个问题：
 * <ol>
 *   <li>某个容器是否享受"最大堆叠提升"（{@link #isBoosted(Inventory)}）；</li>
 *   <li><b>物品层</b>上限：某个物品放在该容器里最多能堆多少（{@link #itemCapFor}）；</li>
 *   <li><b>格子层</b>上限：在 {@link #itemCapFor} 基础上再受容器自身每格上限约束
 *       （{@link #capacityFor}）。</li>
 * </ol>
 *
 * <p><b>为什么必须区分第 2 与第 3 层</b>：原版把容量写成
 * {@code min(容器每格上限, 物品上限)}。本 Mod 把"物品上限"换成了"物品上限 + 容器类型判定"，
 * 但<b>绝不能顺手丢掉容器自身声明的那个上限</b>——像附魔台的输入槽就把
 * {@code Slot#getMaxItemCount()} 覆写成固定 1，语义是"这个槽位天生只接受 1 件"。
 * 一旦绕过它，整摞物品就能塞进单件槽位，附魔台 / 附魔灌注台这类要求"恰好 1 件"的判定全部失效。
 *
 * <p>关键点：漏斗（以及漏斗矿车）默认<b>不</b>提升。原因见 {@code HopperBlockEntityMixin}：
 * 漏斗的容量判定直接依赖 {@code ItemStack#getMaxCount()}，一旦被放大，漏斗就不再具备
 * "装满 16 / 64 即停止"的行为，红石计时器与计数器随之失效。
 */
public final class ContainerPolicy {

    /** 配置文件翻译键前缀，用于 {@link #translationKey(String)}。 */
    private static final String LANG_PREFIX = "betteritemstack.container.";

    /**
     * 配置文件中可以使用的容器键名，顺序即命令输出顺序。
     *
     * <p>使用字符串键而非类名，是因为生产环境下 Minecraft 类名会被重映射为 intermediary 名，
     * 配置文件里的字面类名并不可靠；类名匹配统一在 {@link #keyOf(Inventory)} 里用
     * {@code instanceof} 完成（会被构建期重映射，安全）。
     *
     * <p>每个键名对应的显示名走语言文件（{@link #translationKey(String)}），
     * 因此不会把中文硬编码进代码。
     */
    public static final List<String> SUPPORTED_KEYS = List.of(
            "player_inventory",
            "chest",
            "barrel",
            "shulker_box",
            "ender_chest",
            "chest_minecart",
            "hopper",
            "hopper_minecart",
            "dropper",
            "dispenser",
            "furnace",
            "brewing_stand"
    );

    private ContainerPolicy() {
    }

    /**
     * @param configKey 容器配置键名
     * @return 该键名对应的翻译键
     */
    public static String translationKey(String configKey) {
        return LANG_PREFIX + configKey;
    }

    /**
     * @param inventory 待识别的容器
     * @return 该容器对应的配置键名；无法识别时返回 {@code null}
     */
    public static String keyOf(Inventory inventory) {
        if (inventory == null) {
            return null;
        }
        // 注意顺序：DropperBlockEntity 继承自 DispenserBlockEntity，必须先判子类。
        // DoubleInventory（大箱子）内部委托给 ChestBlockEntity。
        if (inventory instanceof PlayerInventory) {
            return "player_inventory";
        }
        if (inventory instanceof ChestBlockEntity) {
            return "chest";
        }
        if (inventory instanceof DoubleInventory) {
            return "chest";
        }
        if (inventory instanceof BarrelBlockEntity) {
            return "barrel";
        }
        if (inventory instanceof ShulkerBoxBlockEntity) {
            return "shulker_box";
        }
        if (inventory instanceof EnderChestInventory) {
            return "ender_chest";
        }
        if (inventory instanceof ChestMinecartEntity) {
            return "chest_minecart";
        }
        if (inventory instanceof HopperBlockEntity) {
            return "hopper";
        }
        if (inventory instanceof HopperMinecartEntity) {
            return "hopper_minecart";
        }
        if (inventory instanceof DropperBlockEntity) {
            return "dropper";
        }
        if (inventory instanceof DispenserBlockEntity) {
            return "dispenser";
        }
        if (inventory instanceof AbstractFurnaceBlockEntity) {
            return "furnace";
        }
        if (inventory instanceof BrewingStandBlockEntity) {
            return "brewing_stand";
        }
        // 注：1.21 起还有 CrafterBlockEntity（合成器），本分支的支持区间（1.20 – 1.20.4）
        // 没有这个方块，故不列出，否则会引到一个不存在的类。
        return null;
    }

    /**
     * @param inventory 待判定的容器
     * @return 该容器是否享受最大堆叠提升
     */
    public static boolean isBoosted(Inventory inventory) {
        if (inventory == null) {
            return false;
        }
        Config config = Config.get();
        if (config == null || config.containers == null) {
            return false;
        }
        Config.Containers policy = config.containers;

        String key = keyOf(inventory);
        boolean listed = key != null && policy.list != null && policy.list.contains(key);

        if (policy.isWhitelist()) {
            return listed;
        }
        // 黑名单模式：未识别的容器由 unknown 开关决定
        return key == null ? policy.unknown : !listed;
    }

    /**
     * <b>物品层</b>上限：该物品放在该容器中最多能堆到多少。
     *
     * <p>提升容器：返回该堆叠自身的上限（普通物品已被 {@code ItemStackMixin} 放大为
     * {@code global_max}；带耐久度的物品与黑名单物品仍是原版值）。
     * 非提升容器：返回该物品的<b>原版上限</b>（64 / 16 / 1）。
     *
     * @param inventory 容器，允许为 null
     * @param stack     物品堆叠
     * @return 该物品在此容器中的上限
     */
    public static int itemCapFor(Inventory inventory, ItemStack stack) {
        if (isBoosted(inventory)) {
            return stack == null || stack.isEmpty() ? Config.GLOBAL_MAX : stack.getMaxCount();
        }
        return VanillaMax.of(stack);
    }

    /**
     * <b>格子层</b>上限：复刻原版 {@code min(容器自身每格上限, 物品上限)}，
     * 仅把"物品上限"换成本 Mod 的容器感知值。
     *
     * <p><b>返回值永不小于 {@code stack} 当前数量。</b>这一夹取不是可选的，它修的是本 Mod
     * 最容易毁档的一类问题：{@code global_max} 可以在游戏内用 {@code /bis set} 调低，
     * 而原版有 6 处把它当作
     * <pre>
     * stack.capCount(this.getMaxCount(stack));   // LockableContainerBlockEntity#setStack
     * </pre>
     * 的参数。{@code ItemStack#capCount} 的实现是 {@code setCount(maxCount)}——<b>直接覆盖，
     * 超出的部分被销毁且不返还</b>。于是把上限从 9999 调到 1000 后，箱子（以及木桶、潜影盒、
     * 熔炉、发射器等所有 {@code LockableContainerBlockEntity} 子类）里任何一次 {@code setStack}
     * 都会把已有的 9999 截成 1000，一次性丢掉 8999 个。
     *
     * <p>玩家背包不会出现该现象：{@code PlayerInventory#setStack} 只做
     * {@code defaultedList.set(slot, stack)}，<b>根本没有这一步 capCount</b>。
     * 把返回值夹到"不小于当前数量"之后，{@code capCount} 自然变成 no-op，
     * 箱子与玩家背包的行为就此一致：<b>已有堆叠不会被回溯截断，容量只在"插入时"生效</b>
     * （GUI 侧由 {@code SlotMixin} 保证每次最多放入上限以内的数量）。
     *
     * <p>副作用是良性的：那些用该返回值计算"还能不能合并 / 还剩多少空间"的原版逻辑
     * （{@code PlayerInventory:79}、{@code PlayerInventory:217}、{@code DispenserBlockEntity:48}、
     * {@code SimpleInventory:253}、{@code ScreenHandler:1014}）会一致地把超量堆叠视为"已满"，
     * 且增量恒为非负——若返回裸容量，{@code cap - 当前数量} 会变成负数。
     *
     * @param inventory 容器，允许为 null
     * @param stack     物品堆叠
     * @return 该格允许的最大数量，且不小于 {@code stack} 的当前数量
     */
    public static int capacityFor(Inventory inventory, ItemStack stack) {
        int capacity = inventory == null
                ? itemCapFor(null, stack)
                : Math.min(inventory.getMaxCountPerStack(), itemCapFor(inventory, stack));

        if (stack != null && !stack.isEmpty()) {
            capacity = Math.max(capacity, stack.getCount());
        }
        return capacity;
    }
}
