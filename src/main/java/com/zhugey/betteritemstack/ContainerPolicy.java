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
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.ScreenHandlerType;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

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

    /**
     * 客户端 GUI 镜像容器的登记表：键是界面里那个临时的容器实例，值是它镜像的方块容器配置键。
     *
     * <p><b>为什么需要它</b>：原版客户端打开方块容器时走的是
     * {@code GenericContainerScreenHandler#createGeneric9xN(int, PlayerInventory)} 这类
     * <b>客户端专用</b>工厂，其实现是 {@code new SimpleInventory(9 * rows)}——也就是说客户端那一侧的
     * {@code Slot#inventory} 并不是箱子方块实体，而是一个临时的 {@link SimpleInventory} 镜像。
     * 服务端走的则是 {@code createGeneric9xN(int, PlayerInventory, Inventory)}，容器是真实的
     * {@code ChestBlockEntity}。
     *
     * <p>{@link #keyOf(Inventory)} 的 {@code instanceof} 链只认识真实方块实体，对
     * {@link SimpleInventory} 只能返回 {@code null}。于是同一个箱子在两侧被判定成不同的容器：
     * 服务端为 {@code global_max}，客户端却因"未识别容器"（{@code unknown=false}）退回 64。
     * 两侧不一致后会有两处可见症状：
     * <ol>
     *   <li>服务端同步过来的大堆叠，在客户端被
     *       {@code SimpleInventory#setStack} 里的 {@code setCount(getMaxCountPerStack())} 截断成 64
     *       ——格子显示 64，但服务端数据完好；</li>
     *   <li>客户端 {@code HandledScreen} 的拖拽预览按 64 计算。</li>
     * </ol>
     *
     * <p>因此由 {@code ScreenHandlerMixin} 在每个槽位挂载时调用
     * {@link #rememberMirrorContainer(ScreenHandlerType, Inventory)}，按界面的
     * {@link ScreenHandlerType} 反推它镜像的是哪类方块容器，{@link #keyOf(Inventory)} 优先查本表。
     *
     * <p><b>不会误伤服务端</b>：真实方块容器都不是 {@link SimpleInventory}，天然不入表；真正以
     * {@link SimpleInventory} 作为数据源的马匹（其界面类型为 {@code null}）、商人、信标等界面，
     * 则因为其 {@code ScreenHandlerType} 不在 {@link #keyOfHandlerType} 的映射表内而不登记。
     *
     * <p>用 {@code WeakHashMap} 是为了让界面关闭后镜像容器能被正常回收；这类对象只被当前
     * {@code ScreenHandler} 持有，不存在长期存活的引用。
     */
    private static final Map<Inventory, String> MIRROR_CONTAINERS =
            Collections.synchronizedMap(new WeakHashMap<>());

    private ContainerPolicy() {
    }

    /**
     * 把客户端界面的 {@link ScreenHandlerType} 映射为容器配置键名。
     *
     * <p>客户端只知道"打开的是哪种界面"，并不知道方块实体类型，所以这个映射必然是近似的：
     * {@code GENERIC_9X1..6} 同时覆盖箱子、大箱子、木桶与末影箱，这里统一按 {@code chest} 处理。
     * 该近似只影响客户端侧的容量显示与拖拽预览；真正的增删改始终由服务端的真实方块实体决定，
     * 因此不会造成任何数据差异。
     *
     * @param type 界面类型，允许为 {@code null}（例如马匹界面就没有注册类型）
     * @return 对应的容器键名；不属于可提升方块容器的界面返回 {@code null}
     */
    public static String keyOfHandlerType(ScreenHandlerType<?> type) {
        if (type == null) {
            return null;
        }
        if (type == ScreenHandlerType.GENERIC_9X1
                || type == ScreenHandlerType.GENERIC_9X2
                || type == ScreenHandlerType.GENERIC_9X3
                || type == ScreenHandlerType.GENERIC_9X4
                || type == ScreenHandlerType.GENERIC_9X5
                || type == ScreenHandlerType.GENERIC_9X6) {
            return "chest";
        }
        if (type == ScreenHandlerType.SHULKER_BOX) {
            return "shulker_box";
        }
        if (type == ScreenHandlerType.HOPPER) {
            return "hopper";
        }
        if (type == ScreenHandlerType.GENERIC_3X3) {
            return "dispenser";
        }
        if (type == ScreenHandlerType.FURNACE
                || type == ScreenHandlerType.BLAST_FURNACE
                || type == ScreenHandlerType.SMOKER) {
            return "furnace";
        }
        if (type == ScreenHandlerType.BREWING_STAND) {
            return "brewing_stand";
        }
        return null;
    }

    /**
     * 登记一个"客户端 GUI 镜像容器"，由 {@code ScreenHandlerMixin} 在挂载槽位时调用。
     *
     * @param type      当前界面的类型
     * @param inventory 该槽位所属的容器
     */
    public static void rememberMirrorContainer(ScreenHandlerType<?> type, Inventory inventory) {
        if (!(inventory instanceof SimpleInventory)) {
            // 真实方块容器不是 SimpleInventory，服务端调用会在这里直接返回。
            return;
        }
        String key = keyOfHandlerType(type);
        if (key != null) {
            MIRROR_CONTAINERS.put(inventory, key);
        }
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
        // 客户端 GUI 的镜像容器（SimpleInventory）不是任何方块实体，落到下面的 instanceof 链
        // 只会返回 null，导致客户端与服务端的容量判定不一致——它在链尾兜底处理。
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
        //
        // 链上都没命中，最后才查客户端 GUI 的镜像容器登记表。放在这里而不是开头，是因为
        // 真实方块容器在上面就已经返回，没必要为了让镜像容器早识别而在 GUI 的高频渲染路径上
        // （每个槽位每帧都会走 Slot#getMaxItemCount）多做一次同步表查询。
        return MIRROR_CONTAINERS.get(inventory);
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
