# BetterItemStack

> 一个可以**按容器类型分别控制**物品堆叠上限的 Fabric 模组。
> 箱子、木桶、潜影盒、玩家背包可以堆到极大值；**漏斗与漏斗矿车保持原版上限**，
> 红石计时器与计数器因此不受影响。

- 支持 Minecraft **1.21.5 – 1.21.10**（同一个 jar 直接可用，无需按版本分开构建）
- 编译目标 1.21.5 / Fabric / Java 21
- 基于 [ItemStackProMax](https://github.com/develk-coder/ItemStackProMax) 二次开发
- 仓库：<https://github.com/Zhugey/betteritemstack>

> 本分支对应 Minecraft **1.21.5 – 1.21.10**。若你使用 **1.21.1 – 1.21.4**，请改用 **1.21.1 分支**的产物；
> 使用 **1.21.11** 则改用 **1.21.11 分支**的产物——
> 各分支针对自己的编译目标产出 intermediary 命名空间的 jar，不能互换。

---

## 目录

- [它解决什么问题](#它解决什么问题)
- [工作原理](#工作原理)
- [安装](#安装)
- [配置](#配置)
- [指令](#指令)
- [兼容性与已知限制](#兼容性与已知限制)
- [与其他模组的冲突风险](#与其他模组的冲突风险)
- [从源码构建](#从源码构建)
- [项目结构](#项目结构)
- [许可与致谢](#许可与致谢)

---

## 它解决什么问题

原版把"每格最多 64 件"写死在多个层面里。常见的堆叠模组会把上限全局拉高，但这会带来一个
副作用：**漏斗也是靠同一套数值判定"装满了没有"**。漏斗一旦不再"装满即停"，以 16 / 64
计数的红石计时器、计数器就全部失效——因为漏斗会一直往外搬东西。

本模组把这两件事拆开：

| 容器 | 默认行为 |
|---|---|
| 玩家背包、箱子、陷阱箱、大箱子、木桶、潜影盒、末影箱、运输矿车 | **堆到 `global_max`（默认 9999）** |
| 漏斗、漏斗矿车 | **保持原版上限**（普通物品 64、鸡蛋/雪球等 16、不可堆叠物品 1） |
| 其它未被识别的容器 | 默认不提升（可配置） |

并且：

- 哪些容器提升、哪些不提升，**完全由配置文件决定**；
- **漏斗向箱子等提升容器推送时，可以把每一格填满到 `global_max`**，而不是停在 64；
- 不需要维护任何"物品原版上限"数据表，对模组物品同样有效。

---

## 工作原理

### 一、原版的容量公式有两个来源

原版判定某格能放多少件，用的是：

```
min(容器自身每格上限,  物品上限)
```

而"物品上限"就是 `ItemStack#getMaxCount()`。关键在于**同一个 `getMaxCount()` 会被两类完全
不同的逻辑使用**：

| 使用方 | 典型调用点 | 期望值 |
|---|---|---|
| 玩家 / GUI 侧 | `Slot#getMaxItemCount`、`Inventory#getMaxCount` | 提升后的上限 |
| 机器逻辑侧 | `HopperBlockEntity` 的 `isFull` / `isInventoryFull` / `transfer` / `canMergeItems` | **原版上限** |

`ItemStack` 本身不知道自己在哪个容器里，所以单纯改写 `getMaxCount()` 必然同时影响两边。
本模组只改写右边那一项，并**在左边那一项上保留原版语义**：

```
min(容器自身的 getMaxCountPerStack() / 槽位自身的 getMaxItemCount(),  itemCapFor(容器, 物品))
```

- `itemCapFor(容器, 物品)`：提升容器返回 `global_max`，其它容器返回该物品的**原版上限**；
- 左边那一项**一个字都不能丢**（原因见下文「单件槽位」）。

### 二、漏斗单独处理

漏斗的容量判定**完全不经过** `Inventory#getMaxCountPerStack()`，而是直接读
`ItemStack#getMaxCount()`。所以只改容器接口对漏斗无效，必须逐个重定向：

| 方法 | 判定对象 | 采用的上限 |
|---|---|---|
| `isFull()` | 漏斗**自己** | 漏斗的分类（默认不提升）→ 原版 64 / 16 / 1 |
| `isInventoryFull(target)` | **目标容器** | 该容器的分类 → 箱子为 `global_max` |
| `transfer(from, to, …)` | **目标容器** | 同上，这是真正决定"一次能搬多少"的闸门 |
| `canMergeItems(a, b)` | — | 只判"是否同种物品"，计数交给 `transfer` |

由此，**漏斗自己**装满原版上限后就会停下（计时器正常），而**它往箱子里推**的时候可以把
箱子填到 `global_max`。

### 三、不需要物品上限数据表

改写 `getMaxCount()` 之后，"这件物品原本能堆多少"在游戏内就不可考了——这也是很多同类模组
不得不维护一张上千条 JSON 表的原因。

本模组换了一条路：`ItemStack#getMaxCount()` 的实现本质上就是读取组件
`minecraft:max_stack_size`，所以直接读组件即可拿到物品自己的真实上限：

```java
Integer limit = stack.get(DataComponentTypes.MAX_STACK_SIZE);
```

不依赖方法调用，因此不会被本模组自己的改写影响；而且**对模组新增物品同样有效**，
无需任何维护。

### 四、为什么必须保留"槽位自身声明的上限"

有些槽位天生只接受 1 件，它们通过覆写 `Slot#getMaxItemCount()` 来声明：

```java
// EnchantmentScreenHandler：附魔台的输入槽
this.addSlot(new Slot(this.inventory, 0, 15, 47) {
    @Override public int getMaxItemCount() { return 1; }
});
```

附魔台与附魔灌注台的判定都要求槽内**恰好 1 件**（`Item#isEnchantable` 检查
`getMaxCount() == 1`、`BookItem#isEnchantable` 检查 `getCount() == 1`）。一旦绕过这个 1，
整摞物品就能被 shift-click 塞进单件槽位，判定随即失败——表现为**附魔台不显示附魔、
附魔灌注台无法使用**。

因此本模组在这一项上完全保留原版语义，并额外保证：若某槽位子类**覆写了带参数的重载**，
本模组的注入不会执行，其自定义上限天然生效。

### 五、堆叠数量提示

数量 ≥ 1000 时，鼠标悬停会在物品名下方追加一行 `堆叠: N`。

该提示注入在 `ItemStack#getTooltip`（所有物品提示的唯一汇总入口），而**不是**
`Item#appendTooltip`。原因：原版有 14 个物品覆写了 `appendTooltip` 却**没有调用
`super.appendTooltip(...)`**——烟花、药水箭、旗帜、旗帜图案、盾牌、弩、收纳袋、药水、
滞留药水、成书、已探索地图、鱼桶、烟花之星、唱片碎片。注入在被覆写的父类方法上，
对这些物品不会执行。

格子**角标数字**走的是另一条路径（`DrawContext#drawItemInSlot`），与物品类型无关。

### 六、注入点一览

| Mixin | 目标 | 作用 |
|---|---|---|
| `ItemStackMixin` | `ItemStack#getMaxCount` | 把可堆叠物品的上限提升为 `global_max` |
| | `ItemStack.<clinit>` | 重写序列化 Codec，使数量可超过 99 |
| | `ItemStack#getTooltip` | 追加堆叠数量提示 |
| `InventoryMixin` | `Inventory#getMaxCountPerStack` / `#getMaxCount(ItemStack)` | 按容器类型区分每格容量；返回值夹到不小于当前数量（见第七节） |
| `SlotMixin` | `Slot#getMaxItemCount(ItemStack)` | GUI 侧容量，保留槽位自身声明的上限 |
| `HopperBlockEntityMixin` | 漏斗内 4 处 `getMaxCount()` | 判定对象决定上限来源 |
| `ItemEntityMixin` | `ItemEntity#merge` | 掉落物合并上限 |
| `DrawContextMixin`（客户端） | `DrawContext#drawItemInSlot` | 角标数字缩写为 K / M / B |

### 七、三处安全夹取（防止调低上限时丢物品）

`global_max` 可以在游戏内用 `/bis set` 调低。此时若某个格子里的数量已经超过新上限，
原版有几处会据此截断、或计算出负数：

1. **`Inventory#getMaxCount(ItemStack)` 的返回值夹到不小于该堆叠当前数量**
   （`ContainerPolicy#capacityFor`）。这是最关键的一处。

   原版有 6 处把它直接当作 `stack.capCount(this.getMaxCount(stack))` 的参数，
   而 `ItemStack#capCount` 的实现是 `setCount(maxCount)`——**直接覆盖数量，超出的部分被销毁且不返还**。
   若不夹取，把上限从 9999 调到 1000 之后，箱子（以及木桶、潜影盒、熔炉、发射器等
   所有 `LockableContainerBlockEntity` 子类）里任何一次 `setStack` 都会把已有的 9999 截成 1000，
   **一次丢掉 8999 个**。

   玩家背包不会出现该现象，因为 `PlayerInventory#setStack` **根本不调用 `capCount`**
   （只做 `defaultedList.set(slot, stack)`）。夹取之后两者行为一致：
   **已有堆叠不会被回溯截断，容量只在"插入时"生效。**

2. `SlotMixin#getMaxItemCount(ItemStack)`：保证 GUI 侧一次最多放入上限以内的数量；
3. `HopperBlockEntityMixin#transfer`：保证漏斗传输的增量恒为非负
   （`ItemStack#split(负数)` / `increment(负数)` 会让数量朝**反方向**变化，可被用于复制物品）。

正常情形下（当前数量不超过上限）这三处夹取不产生任何影响。

---

## 安装

1. 安装 [Fabric Loader](https://fabricmc.net/use/installer/)（≥ 0.16.11）
2. 把 [Fabric API](https://modrinth.com/mod/fabric-api) 与本模组的 jar 一起放进 `mods/`
3. 需要 **Java 21**，Minecraft 需为 **1.21.5 – 1.21.10** 之一

---

## 配置

配置文件：`.minecraft/config/betteritemstack.json`

```json
{
  "configVersion": 2,
  "global_max": 9999,
  "nonStackableItems": [],
  "containers": {
    "mode": "blacklist",
    "list": ["hopper", "hopper_minecart"],
    "unknown": false
  }
}
```

### `containers` 容器提升策略

| 字段 | 说明 |
|---|---|
| `mode` | `"blacklist"`（默认）：除 `list` 中列出的容器外，全部提升；`"whitelist"`：只提升 `list` 中的容器 |
| `list` | 容器键名列表，见下表 |
| `unknown` | 仅 `blacklist` 模式有效：未识别的容器（含其它模组新增的容器）是否也提升，**默认 `false`** |

> **为什么 `unknown` 默认是 `false`**：未识别的容器往往带有自己的容量语义——很多 GUI 模组
> 会给输入槽单独声明"只接受 1 件"。把它们一起提升会绕过这类保护，导致模组功能失效。
> 需要为其它模组的存储容器也开启提升时，再改为 `true`。

### 容器键名

| 键名 | 对应容器 |
|---|---|
| `player_inventory` | 玩家背包 |
| `chest` | 箱子 · 陷阱箱 · 大箱子 |
| `barrel` | 木桶 |
| `shulker_box` | 潜影盒 |
| `ender_chest` | 末影箱 |
| `chest_minecart` | 运输矿车 |
| `hopper` | 漏斗 |
| `hopper_minecart` | 漏斗矿车 |
| `dropper` | 投掷器 |
| `dispenser` | 发射器 |
| `furnace` | 熔炉 · 高炉 · 烟熏炉 |
| `brewing_stand` | 酿造台 |
| `crafter` | 合成器 |

### 其它字段

| 字段 | 说明 |
|---|---|
| `global_max` | 提升后的每格上限，必须 ≥ 1，默认 9999 |
| `nonStackableItems` | 不参与提升的物品 ID 列表（如 `minecraft:bed`），保持各自的原版上限 |

### 配置示例

**让木桶也保持原版上限**（`blacklist` 模式）：

```json
"containers": {
  "mode": "blacklist",
  "list": ["hopper", "hopper_minecart", "barrel"]
}
```

**只提升指定的几个容器**（白名单模式）：

```json
"containers": {
  "mode": "whitelist",
  "list": ["player_inventory", "chest", "barrel", "shulker_box"]
}
```

**让床、鞍等不可堆叠物品保持原样**：

```json
"nonStackableItems": ["minecraft:bed", "minecraft:saddle", "minecraft:cod_bucket"]
```

修改配置后可在游戏内执行 `/bis reload` 立即生效。

---

## 指令

所有输出都已国际化：文案从语言文件读取，游戏语言为中文时显示中文，其它语言显示对应译文，
未收录的语言自动回退到英文。详见 [语言](#语言)。

| 指令 | 权限 | 说明 |
|---|---|---|
| `/bis` | 所有人 | 显示 Mod 版本与可用指令 |
| `/bis get` | 所有人 | 显示当前堆叠上限 |
| `/bis info` | 所有人 | 显示提升规则、可用容器键名与配置示例 |
| `/bis set <数量>` | OP | 设置堆叠上限并写回配置文件 |
| `/bis reload` | OP | 从磁盘重新读取配置 |

`/bis info` 输出示例（游戏语言为简体中文）：

```
=== BetterItemStack 设置 ===
当前堆叠上限: 9999
提升规则: 黑名单模式 - 下列容器保持原版，其余全部提升
保持原版的容器: hopper（漏斗） · hopper_minecart（漏斗矿车）
未识别的容器（含其它模组）: 保持原版
可用容器键名:
player_inventory（物品栏） · chest（箱子 · 陷阱箱） · barrel（木桶） · shulker_box（潜影盒） ·
ender_chest（末影箱） · chest_minecart（运输矿车） · hopper（漏斗） · hopper_minecart（漏斗矿车） ·
dropper（投掷器） · dispenser（发射器） · furnace（熔炉 · 高炉 · 烟熏炉） · brewing_stand（酿造台） ·
crafter（合成器）
--- 示例: 如何让更多容器保持原版 ---
黑名单模式下，想让木桶也保持原版上限，就在 config/betteritemstack.json 的 containers.list 里
加入 "barrel"，改完执行 /bis reload。
  "containers": { "mode": "blacklist", "list": ["hopper", "hopper_minecart", "barrel"] }
```

同一份输出在英文环境下：

```
=== BetterItemStack settings ===
Current stack limit: 9999
Boost rule: blacklist - the containers listed below keep vanilla limits, all others are boosted
Containers kept vanilla: hopper(Hopper) · hopper_minecart(Minecart with Hopper)
Unrecognized containers (including other mods): kept vanilla
Available container keys:
player_inventory(Inventory) · chest(Chest / Trapped Chest) · barrel(Barrel) · ...
```

---

## 版本兼容性

本 Mod 的 jar 是 **intermediary 命名空间**的产物：Mixin 目标与 API 调用在发布时已被重映射为
`class_xxxx` / `method_xxxxx`，运行期由 Fabric 映射到当前版本的混淆名。
**因此只要这些符号在目标版本里依旧存在，同一个 jar 就能直接运行，无需针对每个版本重新编译。**

据此逐版本核验的结果：

| Minecraft | 结论 | 原因 |
|---|---|---|
| **1.21.5 – 1.21.10** | ✅ **同一个 jar 直接可用** | 全部符号与字节码调用画像一致 |
| 1.21.11 | ❌ **本分支不适用** | 权限体系换代：`CommandSource#hasPermissionLevel(int)` 被新的 `PermissionPredicate` 体系取代，本构建在 1.21.11 上会因符号不存在而无法启动。请改用 **1.21.11 分支**的产物 |
| 1.21.4 及以下 | ❌ 不适用 | 本构建针对 1.21.5 编译。1.21.5 把组件读取方法迁移到了新的接口体系（`ComponentHolder#get` 从声明方法变为 default 方法），intermediary 名随之变化，旧版本里不存在。请改用 **1.21.1 分支**的产物 |

上述结论都能用**静态检查**复现，不需要实机逐个版本运行。三项检查分别是：

1. **注入目标与符号引用核对** —— 从构建产物中提取 **Mixin 的注入目标**与 **class 常量池里的全部
   intermediary 引用**，逐个到 1.21.5 – 1.21.11 的 intermediary 映射中核对存在性与描述符。
   注入目标的存放形式随 Loom 版本而变，两种都要覆盖：旧 Loom 放在 `refmap.json` 里，
   新 Loom（≥1.12）不再生成 refmap、而是把目标**就地重映射进注解**
   （`@At(target="Lnet/minecraft/class_1799;method_7914()I")`），此时须用 `javap -v` 从注解里读取。
   编译器以"静态接收类型"发出成员引用（如 `ItemStack.toString()`），而映射只收录声明该成员的类，
   因此继承/桥接方法要回退到全表查找，否则会误报。
   **注意**：映射里找不到的 Yarn 名不会被报错，而是被**原样保留在注解里**——本项目在 1.21.5 上就
   遇到过这种情况（`drawItemInSlot` 被改名为 `drawStackOverlay`），表现为"构建通过但运行期注入失败"。
2. **`@Shadow` 成员核对** —— Mixin 的 `@Shadow` 成员在构建时会被改名成目标类的 intermediary 名
   （`field_xxxxx` / `method_xxxxx`），必须逐个确认该名称在目标版本里仍然存在。
   这一类引用**不在常量池里**（声明属于 mixin 自身，而不是对目标类的引用），因此要单独扫出来。
   **这一项极易遗漏**：`ItemStack#ITEM_CODEC` 就是例子——该字段在 1.21.2 起被移除，
   而只查常量池与注解的检查完全看不出来。
3. **`@Redirect` 调用次数核对** —— `@Redirect` 要求目标方法内**恰好调用一次**被重定向的方法，
   这类问题靠符号存在性检查发现不了，必须读字节码。直接取 Mojang 客户端产物，
   逐方法统计 `ItemStack#getMaxCount()` 的调用次数，确认区间两端（1.21.5 与 1.21.10）的调用画像
   均为 1 次、与本模组的历史版本一致。

> 已知盲区：**构造函数与 `<clinit>`** 不在 intermediary 映射文件里（实测收录 0 条），
> 无法用上述方式核对，需人工用 `javap` 取目标类签名再与 Yarn 映射对照。
> 本分支已人工核对 1.21.5 的 `ItemStack`：`<clinit>` 存在，且 `(RegistryEntry, int, ComponentChanges)`
> 构造函数存在。

> **1.21.11 已经单独出一条分支**（见 **1.21.11 分支**）：那一版权限体系换代，
> 需要把 `/bis set`、`/bis reload` 的权限判定改写成 `CommandManager.MODERATORS_CHECK`，
> 属于跨版本适配，不在 1.1.x 的范围。

### `gradle.properties` 里的版本号分别管什么

这几个**都只影响构建环境，玩家看不到**，但它们对"玩家能不能装"的作用并不相同：
只有 `loader_version` 和 `minecraft_version` 会进入 `fabric.mod.json` 的 `depends`，
成为**玩家可见的硬门槛**。

| 配置 | 玩家可见 | 作用 | 取值原则 |
|---|---|---|---|
| `loader_version` | **✅ 会写进 `depends.fabricloader`** | 编译期依赖 **+** 玩家门槛 | 取**能工作的最低版本**，不要用"当前最新"——否则白白挡住老玩家 |
| `minecraft_version` / `_min` / `_max` | **✅ 会写进 `depends.minecraft`** | 编译目标 / 声明区间 | 见上一节，按符号验证结果定 |
| `fabric_version` | ❌ | 编译期 Fabric API，决定"代码最多能用多新的 API" | 取该 MC 分支里**较早**的一版（现为 `0.119.6+1.21.5`；该分支最早是 `0.114.1+1.21.5`） |
| `loom_version` | ❌ | Gradle 构建插件（反编译 / 重映射 / 开发环境） | 见下 |
| `yarn_mappings` | ❌ | 编译期把混淆名映射为可读名 | 只有更换 `minecraft_version` 时才需同步改 |

**`loader_version` 的取值**：本 Mod 用到的 loader 成员只有 7 个
（`FabricLoader#getInstance/getConfigDir/getModContainer`、`ModContainer#getMetadata`、
`ModMetadata#getVersion`、`Version#getFriendlyString`、`ModInitializer#onInitialize`），
全部自 2019 年起就存在。当前取 **0.16.11**——即 MC 1.21.5 发布前后的同期 loader，
已核验上述成员在 0.16.x 中全部存在。
**Fabric Loader 同样是向后兼容的**（新版能跑旧模组），所以门槛设低只会放宽、不会收紧。

**Loom 的取向与它们相反**：Loom 的版本要按**你打算编译的最高 MC 版本**来选——
**新版 Loom 能处理旧 MC，旧版 Loom 处理不了更新的 MC**。所以不要为了"兼容玩家"而降级 Loom
（玩家根本不会运行它）。Fabric 官网对每个 MC 版本都显示同一个 Loom，正是因为
Loom 与 MC 版本解耦（官方原话：*Loom is version-independent*）。

但 Loom 硬性耦合 **Gradle** 与 **JDK**（取自 maven 元数据的
`org.gradle.plugin.api-version` / `org.gradle.jvm.version` 字段）：

| Loom | 要求的 Gradle | 要求的 JDK |
|---|---|---|
| 1.11.8 / 1.12.7 / **1.13.6** | **8.14** | **21** |
| 1.14.10 / 1.15.5 | 9.2.0 | 21 |
| 1.16.3 | 9.4.0 | 21 |
| 1.17.21 | 9.5.0 | 21 |
| 1.18.3 | 9.7.0 | 25 |

本项目用 **Gradle 8.14.2 + JDK 21**，能用的最高 Loom 即 **1.13.6**（当前取值，
已在 1.21.5 上实测可用；该版本发布于 1.21.5 之后，Loom 与 MC 版本解耦所以不存在"太老"的问题）。
升到 1.14+ 需同步升 Gradle，1.18 还要 JDK 25，而这对玩家零收益。

另外**不要用 `-SNAPSHOT`**：它是浮动版本，同一份代码在不同时间可能拉到不同快照，构建不可复现；
永远固定到正式版。注意**产物结构随 Loom 版本变化**：1.11.x 仍生成 refmap，1.12 起不再生成，
改为把注解里的注入目标就地重映射为 intermediary（实测：Loom 1.11.8 有 refmap，1.12.7 与 1.13.6 都没有）
——上层提到的 `mc_compat_check.py` 已同时支持这两种形式。

**Fabric API 是向后兼容的**：新版保留旧 API，因此用旧 API 编译的模组在新版 Fabric API 上照常运行；
反过来才可能出问题。本 Mod 的 jar 里只引用了两个 Fabric API 类——
`CommandRegistrationCallback` 与它的父类型 `Event`（2019 年即存在），
所以 Fabric API 从 `0.119.6+1.21.5` 到该分支最新版都能运行。
`depends.fabric-api` 因此保持 `*`（允许任意版本），不额外设下限以免误伤。

### 在哪里查版本

- <https://fabricmc.net/develop/> —— 官方推荐页，选定 MC 版本后直接给出 yarn / loader / Fabric API / Loom
- 机器可读的 meta API：
  - `https://meta.fabricmc.net/v2/versions/yarn/<MC版本>`
  - `https://meta.fabricmc.net/v2/versions/loader/<MC版本>`
- Maven 目录（可浏览全部历史版本，与发布日期）：
  - Fabric API `https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-api/`
  - Loom `https://maven.fabricmc.net/net/fabricmc/fabric-loom/`
  - Yarn `https://maven.fabricmc.net/net/fabricmc/yarn/`
  - Loader `https://maven.fabricmc.net/net/fabricmc/fabric-loader/`

---

## 兼容性与已知限制

1. **漏斗自己保持原版**：只能装到原版上限，因此以 16 / 64 计数的红石计时器与计数器不受影响。
   漏斗向提升容器推送时会把每格填到 `global_max`。
2. **单件槽位不受影响**：附魔台输入槽、铁砧、砂轮等通过覆写 `Slot#getMaxItemCount()` 声明的
   上限被完整保留，附魔台与附魔灌注台可正常工作。
3. **未识别的容器默认不提升**。其它模组的存储容器若不在键名表内会保持原版上限；
   需要提升时把 `containers.unknown` 改为 `true`。
4. 原版上限为 1 且不可损坏的物品（床、鞍、鱼桶、唱片等）会变为可堆叠。
   如不需要，请加入 `nonStackableItems`。
5. 堆叠数量提示在数量 ≥ 1000 时显示；角标数字在 ≥ 1000 时缩写为 K / M / B。
6. 本模组不提供物品上限数据表——原版上限直接读自物品组件，对模组物品同样有效。
7. **调低 `global_max` 不会销毁已有物品**：已经超过新上限的堆叠会原样保留，
   只是此后无法再把更多物品放进该格（GUI 侧一次最多放入新上限以内的数量）。
   这与玩家背包的行为一致，详见[第七节](#七三处安全夹取防止调低上限时丢物品)。

---

## 与其他模组的冲突风险

本模组直接改写原版行为，**凡是触及同一批原版目标的模组都可能互相干扰**。
下面把本分支实际改动的原版位置全部列出，方便出问题时对号入座。

### 本分支触及的原版目标

| Mixin | 改动的原版位置 |
|---|---|
| `ItemStackMixin` | `ItemStack.<clinit>`（重写 `CODEC`，把 count 的范围从 `rangedInt(1, 99)` 放宽到 `rangedInt(1, Integer.MAX_VALUE)`）、`getMaxCount`、`getTooltip` |
| `InventoryMixin` | `Inventory#getMaxCountPerStack`、`Inventory#getMaxCount(ItemStack)` |
| `SlotMixin` | `Slot#getMaxItemCount(ItemStack)` |
| `HopperBlockEntityMixin` | `HopperBlockEntity` 的 `isInventoryFull`、`transfer`、`canMergeItems`、`isFull` 里的 `ItemStack#getMaxCount()` |
| `ItemEntityMixin` | `ItemEntity#merge` |
| `client.DrawContextMixin` | `DrawContext#drawItemInSlot` |

### 几类最容易冲突的模组

1. **同类堆叠模组**——风险最高。它们与本模组抢的是同一批目标，尤其 `ItemStack#getMaxCount()`
   与 `ItemStack.CODEC`。两个模组都改同一处时，**后执行的那个覆盖先执行的**，而且**不会有任何报错**
   ——表现为"其中一个完全没起作用"。**请勿与本模组同时安装其它堆叠模组。**
2. **重写 `ItemStack.CODEC` 的模组**——见下方特别提示。
3. **漏斗 / 物流 / 红石类模组**——它们常改写 `HopperBlockEntity`，与本模组的四处改动重合。
4. **容器界面类模组**——改动 `Slot#getMaxItemCount`、容器容量或数量文字绘制的模组，
   可能与本模组在 GUI 侧的改动叠加。

### 冲突会以什么形式出现

- **启动即崩**：日志里出现 `Mixin apply for mod betteritemstack failed` 或
  `Critical injection failure`。这类多半是 `@Redirect` 找不到目标——目标已被另一个模组改掉。
  这是**最好的一种**，问题立刻可见。
- **静默失效**：两个模组都注入成功、语义互相覆盖，游戏照常启动，只是行为不对。最难查。
- **运行期错乱**：数量显示或同步异常、整格物品消失、GUI 行为怪异。

### 排查步骤

1. 先看 `logs/latest.log` 里有没有 Mixin 相关的 `ERROR`；
2. 只留本模组（+ 原版）跑一次，确认单独工作正常；
3. 二分法把其它模组加回来，定位到具体哪一个。

### 特别提示：本分支重写了 `ItemStack.CODEC`

1.21.5 起原版用 **Codec** 序列化物品，其中 `count` 字段被限定为 `rangedInt(1, 99)`；
本模组在 `ItemStack.<clinit>` 的末尾把整个 `CODEC` 替换成放宽了范围的版本。由此带来两类风险：

- **任何同样替换 `ItemStack.CODEC` 的模组会与本模组互相覆盖**——后执行的生效、不报错，
  表现为其中一个失效；
- **联机时两端都建议装本模组**：数量超过 99 时，未装的一端仍按原版 `rangedInt(1, 99)` 校验，
  解码会失败；**数量 ≤99 时完全兼容**。

---

## 从源码构建

```bash
./gradlew build        # 构建，产物在 build/libs/
./gradlew runClient    # 启动开发环境客户端
./gradlew runServer    # 启动开发环境服务端
```

产物位于 `build/libs/`，只有一个文件：

- `BetterItemStack-<Mod版本>-<MC区间>.jar` — 模组文件本身（如 `BetterItemStack-1.1.0-1.21.5-1.21.10.jar`）

本项目**不生成 `-sources.jar`**（Fabric 官方示例模板里的 `withSourcesJar()` 已去掉）：源码本来就在
仓库里公开，那个文件只对"把本模组当依赖库引用、需要在 IDE 里挂源码"的开发者有意义，
对下载安装的玩家没有用处，主流模组发布时也不附它。

产物名里的版本区间取自 `gradle.properties` 的 `minecraft_version_min` / `minecraft_version_max`，
与 `fabric.mod.json` 中 `depends.minecraft` 的区间同源。

提交或拉取请求后，`.github/workflows/build.yml` 会在 GitHub Actions 上自动构建，
并把产物作为 Artifacts 上传（保留 90 天）。

### 发布新版本

工作流在**推送 tag** 时会自动创建 GitHub Release，并附上该版本的更新日志：

1. 在 `CHANGELOG.md` 里新增一节，标题格式为 `## <版本号> — <日期>`（版本号需与 tag 一致）
2. 把 `gradle.properties` 的 `mod_version` 改成同一版本号
3. 提交并推送，然后打 tag 推送：

   ```bash
   git tag v1.1.0
   git push origin v1.1.0
   ```

工作流会构建、从 `CHANGELOG.md` 抽取 `## 1.1.0` 一节作为 Release 正文，
并把 `build/libs/*.jar` 作为附件上传。同一个 tag 重跑时会**更新**已有 Release，不会报错。

tag 名带不带 `v` 前缀都可以（`v1.1.0` 与 `1.1.0` 等价，抽取时会自动剥掉前缀）；
`+` 之后的内容会被忽略，所以将来若要写 `v1.1.0+mc1.21.5-1.21.10` 这类 semver 元数据也能正确抽取。

> **tag 只用版本号，不要把 MC 区间写进去。** 区间已经出现在三个更合适的位置：产物文件名、
> Release 标题（CI 会自动拼成 `v1.1.0 (Minecraft 1.21.5–1.21.10)`），以及上文的兼容性表。
> 把区间塞进 tag（例如 `V1.0.1_1.21.1-1.21.4`）会有实际代价：CI 是拿 tag 名去 `CHANGELOG.md`
> 里找 `## <版本号>` 小节，归一化后的名字匹配不上，就会**静默回退**成把整份更新日志当成 Release 正文。
> 主流模组（AppleSkin、JEI、REI、Botania、Mod Menu 等）同样只用纯版本号作 tag。

> **tag 在仓库内全局唯一**，不区分分支。本分支的版本号与 1.21.1 分支的 `1.0.x` 线分开编（现为 `1.1.0`），
> 既避免 tag 冲突，也避免 CHANGELOG 按 tag 名抽取小节时取到另一分支的同号历史记录。

在 IntelliJ IDEA 里用界面完成同样的事（无需命令行）：

| 步骤 | 操作 |
|---|---|
| 推送代码 | 右上角工具栏的 **↑（Push）**，或菜单 `Git` → `Push...`（`Ctrl+Shift+K`）；对话框里确认提交后点 `Push` |
| 打 tag | `Git` → `New Tag...`（旧版在 `VCS` → `Git` → `New Tag...`）；或在 Git 日志中**右键最新提交** → `New Tag...`，填 `v1.1.0` |
| 推送 tag | 再次 `Git` → `Push...`，**务必勾选对话框底部的 `Push tags`**，下拉选 `All`，再点 `Push` |

> **最容易漏掉的一步是最后一个**：tag 只创建在本地时不会触发任何 CI，"产物没更新"往往就出在这里。

### 推了 tag 却没有 Release，怎么排查

1. 确认 tag **真的到了远程**：浏览器打开 `<仓库地址>/tags`，能看到的才是已推送的；
   或 `git ls-remote --tags origin`。
2. 到 `Actions` 页看是否多了一次运行；没有的话就是 tag 没推送成功（回到上表的第三步）。
3. 有运行但 `create release` 被 `skipped`：说明该运行不是由 tag 触发的。
4. 有运行且失败：点进去看 `create release` 的日志——常见原因是仓库没有开启 Actions 的写权限
   （`Settings` → `Actions` → `General` → `Workflow permissions` 选 `Read and write permissions`）。

> 注意：普通的 push / PR **不会**创建 Release，只上传 Actions Artifact。
> Artifact 需要登录 GitHub 才能下载且 90 天后过期，对外发布请以 Release 为准。

---

## 项目结构

```
src/main/java/com/zhugey/betteritemstack/
├── BetterItemStack.java              入口：加载配置、注册 /bis 指令（全部输出走翻译键）
├── Config.java                       配置读写与静态 global_max
├── ContainerPolicy.java              容器分类：是否提升、物品层上限、格子层上限
├── VanillaMax.java                   读取"原版上限"（直接读物品组件，不受本模组改写影响）
└── mixin/
    ├── ItemStackMixin.java           物品上限、序列化 Codec、堆叠数量提示
    ├── InventoryMixin.java           容器每格上限按类型区分
    ├── SlotMixin.java                GUI 槽位容量（保留槽位自身声明的上限）
    ├── HopperBlockEntityMixin.java   漏斗容量判定接回容器感知逻辑
    ├── ItemEntityMixin.java          掉落物合并上限
    └── client/DrawContextMixin.java  角标数字缩写

src/main/resources/assets/betteritemstack/lang/
├── en_us.json                        英文（回退语言，必须存在）
├── zh_cn.json / zh_tw.json           简体 / 繁体中文
├── ja_jp.json / ko_kr.json           日文 / 韩文
└── ru_ru.json / de_de.json / fr_fr.json / es_es.json / pt_br.json
```

容器名不是手写的，而是生成时从 Minecraft 官方语言文件里取 `block.minecraft.*` 的权威译名
（凭记忆写容易出错，例如德语里 `dropper` 是 `Spender`、`dispenser` 反而是 `Werfer`）。
生成时会校验各语言的键集合一致、且每个键的 `%s` 占位符个数与代码中的调用一致。

---

## 许可与致谢

- **License**：MIT
- 基于 [develk-coder/ItemStackProMax](https://github.com/develk-coder/ItemStackProMax) 二次开发，
  原作者 develk。上游项目同样采用 MIT 授权，本项目一并保留其版权与许可声明。

### 相关模组

- [Staaaaaaaaaaaack](https://modrinth.com/mod/staaaaaaaaaaaack) — 在物品实体生成前完成合并，
  并优化了背包爆炸运算，与本模组搭配效果良好

### 语言

已内置 10 种语言，未收录的语言自动回退到 `en_us`：

| 语言 | 代码 | 语言 | 代码 |
|---|---|---|---|
| English (US) | `en_us` | Русский | `ru_ru` |
| 简体中文 | `zh_cn` | Deutsch | `de_de` |
| 繁體中文 | `zh_tw` | Français | `fr_fr` |
| 日本語 | `ja_jp` | Español | `es_es` |
| 한국어 | `ko_kr` | Português (Brasil) | `pt_br` |

添加/修正语言：直接编辑 `assets/betteritemstack/lang/<locale>.json`。注意两点——
各语言的**键集合要保持一致**（缺键会显示原始键名），以及每个键的 `%s` 个数要与代码中的调用匹配。
非中英文的译文欢迎在 Issues 里提出修正。
