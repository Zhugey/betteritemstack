# BetterItemStack

> 一个可以**按容器类型分别控制**物品堆叠上限的 Fabric 模组。
> 箱子、木桶、潜影盒、玩家背包可以堆到极大值；**漏斗与漏斗矿车保持原版上限**，
> 红石计时器与计数器因此不受影响。

- 支持 Minecraft **1.20 – 1.20.4**（同一个 jar 直接可用，无需按版本分开构建）
- 编译目标 1.20 / Fabric / Java 17
- 基于 [ItemStackProMax](https://github.com/develk-coder/ItemStackProMax) 二次开发
- 仓库：<https://github.com/Zhugey/betteritemstack>

> 本分支对应 Minecraft **1.20 – 1.20.4**。若你使用 **1.20.5 – 1.21.4**，请改用 **1.20.5 分支**的产物；
> 使用 **1.21.5 – 1.21.10** 或 **1.21.11**，请改用 **1.21.5 分支** / **1.21.11 分支**的产物——
> 各分支针对自己的编译目标产出 intermediary 命名空间的 jar，不能互换。

---

## 目录

- [它解决什么问题](#它解决什么问题)
- [工作原理](#工作原理)
- [安装](#安装)
- [配置](#配置)
- [指令](#指令)
- [兼容性与已知限制](#兼容性与已知限制)
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

本模组换了一条路：本代（1.20 – 1.20.4）还没有物品组件，原版上限就声明在
`Item.Settings#maxCount()` 里、由 `Item#getMaxCount()` 暴露，所以直接读**物品级**上限即可：

```java
int limit = stack.getItem().getMaxCount();
```

绕开了 `ItemStack` 上被改写的方法，因此不会受影响；而且**对模组新增物品同样有效**，
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
| | `ItemStack#writeNbt` / `ItemStack#fromNbt` | **落盘**的数量由字节改为 int（见第八节） |
| | `ItemStack#getTooltip` | 追加堆叠数量提示 |
| `PacketByteBufMixin` | `PacketByteBuf#writeItemStack` / `#readItemStack` | **网络包**里的数量由字节改为 VarInt（见第八节） |
| `InventoryMixin` | `Inventory#getMaxCountPerStack` | 按容器类型区分每格容量 |
| `SlotMixin` | `Slot#getMaxItemCount(ItemStack)` | GUI 侧容量，保留槽位自身声明的上限 |
| `HopperBlockEntityMixin` | 漏斗内 4 处 `getMaxCount()` | 判定对象决定上限来源 |
| `ItemEntityMixin` | `ItemEntity#merge` | 掉落物合并上限 |
| `DrawContextMixin`（客户端） | `DrawContext#drawItemInSlot` | 角标数字缩写为 K / M / B |

> 本代（1.20 – 1.20.4）**没有** 1.20.5+ 的那个 `ItemStack.<clinit>` 注入：那一版起原版把数量
> 收窄成 `rangedInt(1, 99)` 才需要重写 Codec；本代原版 Codec 里数量本来就是不限范围的
> `Codec.INT`，不需要动。

### 七、两处安全夹取（防止调低上限时出现负数增量）

`global_max` 可以在游戏内用 `/bis set` 调低。此时若某个格子里的数量已经超过新上限，
原版有几处会据此算出**负数增量**：

> **本代与 1.20.5+ 的差异**：1.20.5 起原版有 6 处 `stack.capCount(this.getMaxCount(stack))`，
> 会把超量堆叠**静默截断**（一次丢掉几千个），那边必须靠 `ContainerPolicy#capacityFor`
> 把返回值夹到不小于当前数量来抵消。**本代还没有 `ItemStack#capCount`**，那条路径不存在，
> 所以这里只剩"负数增量"这一类风险。

1. `SlotMixin#getMaxItemCount(ItemStack)`：保证 GUI 侧一次最多放入上限以内的数量，
   返回值同时夹到不小于槽内当前数量（`Slot#insertStack` 会据此算 `上限 - 当前数量`）；
2. `HopperBlockEntityMixin#transfer`：保证漏斗传输的增量恒为非负
   （`ItemStack#split(负数)` / `increment(负数)` 会让数量朝**反方向**变化，可被用于复制物品）。

正常情形下（当前数量不超过上限）这三处夹取不产生任何影响。

### 八、数量本身也要装得下：两处 8 位上限

把上限抬到几千之后还有一件容易忽略的事：**原版存放数量的地方只有 8 位**，而且本代这两处
都不经过 Codec，所以必须逐个处理，漏一个就会出现"数量错乱甚至整格消失"。

| 路径 | 原版写法 | 截断后果 | 本模组的改法 |
|---|---|---|---|
| 网络包 | `PacketByteBuf#writeItemStack` 里 `writeByte(count)`；`readItemStack` 里 `readByte()` | 128 ~ 255 读成负数、256 的整数倍读成 0；`ItemStack#isEmpty()` 对 `count <= 0` 为 true，于是**整格物品消失**（打开箱子、拾取、GUI 点击都会触发整包同步） | `PacketByteBufMixin` 改成 `writeVarInt` / `readVarInt` |
| 存档（区块、玩家数据、掉落物实体） | `ItemStack#writeNbt` 里 `putByte("Count", (byte) count)` | 同上；重进世界后数量错乱 | `ItemStackMixin` 在 `writeNbt` 的 TAIL 补一个 `putInt("Count")` 覆盖，并在 `fromNbt` 的 RETURN 用 `getInt` 还原 |

**为什么读侧要用 `@ModifyVariable` 而不是等方法返回后再修**：原版拿到被截断的字节后会立刻
`new ItemStack(item, 截断值)`；若截断值恰好是 0，这个栈已经成了空栈、物品信息丢失，
返回后再改也救不回来。必须在"存进局部变量"那一步就把真实数量换回去。

**兼容性**：VarInt 对 0 ~ 127 就是单个字节，取值与原 `writeByte` 完全一致；NBT 侧同理
（`putInt(64)` 与 `putByte((byte) 64)` 读出来都是 64）。因此对端没装本模组时，只要数量没超过
127 就仍能正确解析，不会错位；只有超过 127 才会不一致——而那本来也只有装了本模组才会出现。

---

## 安装

1. 安装 [Fabric Loader](https://fabricmc.net/use/installer/)（≥ 0.14.21）
2. 把 [Fabric API](https://modrinth.com/mod/fabric-api) 与本模组的 jar 一起放进 `mods/`
3. 需要 **Java 17**，Minecraft 需为 **1.20 – 1.20.4** 之一

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
| **1.20 – 1.20.4** | ✅ **本分支的 jar** | 全部符号与字节码调用画像一致 |
| 1.20.5 – 1.21.4 | ❌ **本分支不适用** | 本代还没有"栈上限重构"（缺 `Inventory#getMaxCount(ItemStack)`，物品组件也不存在），本构建在新一代上会因符号缺失而无法启动。请改用 **1.20.5 分支**的产物 |
| 1.21.5 – 1.21.10 | ❌ **本分支不适用** | 请改用 **1.21.5 分支**的产物 |
| 1.21.11 | ❌ **本分支不适用** | 请改用 **1.21.11 分支**的产物 |

上述结论都能用**静态检查**复现，不需要实机逐个版本运行。三项检查分别是：

1. **注入目标与符号引用核对** —— 从构建产物中提取 **Mixin 的注入目标**与 **class 常量池里的全部
   intermediary 引用**，逐个到 1.20 – 1.21.11 的 intermediary 映射中核对存在性与描述符。
   注入目标的存放形式随 Loom 版本而变，两种都要覆盖：旧 Loom 放在 `refmap.json` 里，
   新 Loom（≥1.12）不再生成 refmap、而是把目标**就地重映射进注解**
   （`@At(target="Lnet/minecraft/class_1799;method_7914()I")`），此时须用 `javap -v` 从注解里读取。
   编译器以"静态接收类型"发出成员引用（如 `ItemStack.toString()`），而映射只收录声明该成员的类，
   因此继承/桥接方法要回退到全表查找，否则会误报。
2. **`@Shadow` 成员核对** —— Mixin 的 `@Shadow` 成员在构建时会被改名成目标类的 intermediary 名
   （`field_xxxxx` / `method_xxxxx`），必须逐个确认该名称在目标版本里仍然存在。
   这一类引用**不在常量池里**（声明属于 mixin 自身，而不是对目标类的引用），因此要单独扫出来。
   **这一项极易遗漏**：1.21.1 分支的 `ItemStack#ITEM_CODEC` 就是例子——该字段在 1.21.2 起被移除，
   而只查常量池与注解的检查完全看不出来。
3. **`@Redirect` 调用次数核对** —— `@Redirect` 要求目标方法内**恰好调用一次**被重定向的方法，
   这类问题靠符号存在性检查发现不了，必须读字节码。直接取 Mojang 客户端产物，
   逐方法统计目标方法的调用次数：漏斗那几处 `ItemStack#getMaxCount()` 用
   `redirect_count_check.py`，网络侧 `PacketByteBuf#writeItemStack` / `#readItemStack` 里的数量读写
   用 `packet_count_check.py`。后者还会断言**两个写变体里恰好命中一个**——写侧之所以有两个变体，
   见[第八节](#八数量本身也要装得下两处-8-位上限)与 `PacketByteBufMixin` 的类注释。

> 已知盲区：**构造函数与 `<clinit>`** 不在 intermediary 映射文件里（实测收录 0 条），
> 无法用上述方式核对，需人工用 `javap` 取目标类签名再与 Yarn 映射对照。
> 另有两类它**查不出**的问题：
>
> - 同一个 intermediary 类的 **Yarn 包名/类名被改动**（`class_1836` 在 1.20.5 叫
>   `client.item.TooltipType`、1.21 起叫 `item.tooltip.TooltipType`）；
> - **中介名不变、描述符变了**。这条最阴：1.20.2 起 `PacketByteBuf#writeNbt` 的中介名仍是
>   `method_10794`，参数却从 `NbtCompound`（`class_2487`）换成了 `NbtElement`（`class_2520`）；
>   `PacketByteBuf#writeByte` 的**返回类型**也从 `ByteBuf` 变成了 `PacketByteBuf`
>   （1.20.2 补了一批"返回自身"的协变重载）。按旧版本编译的调用会直接
>   `NoSuchMethodError`，而符号核查只看"名字还在不在"。
>
> 另外，被重定向的目标若**继承自 Netty**（`writeByte` / `readByte` 这类），它不在
> intermediary 映射表里（该表只覆盖 Minecraft 自己的类），符号核查会把它归入"无法自动归类的残留项"，
> 需由 `packet_count_check.py` 补上。
> 典型就是工具提示的类型参数 `class_1836`：本代（1.20 – 1.20.4）叫
> `net.minecraft.client.item.TooltipContext`，1.20.5 改名 `TooltipType`，1.21 又被挪到
> `net.minecraft.item.tooltip` 包。符号核查全程通过（中介名没变），
> 但源码按另一代的包名写就会因"程序包不存在"而编译失败。
> **结论：符号核查负责"能不能运行"，"能不能编译"仍以编译器为准。**

> 另一类只靠符号核查也发现不了的问题：**符号还在、但位置变了**。
> 本分支就踩到一次 —— 1.20.5 起"目标容器是否已满"的判定写在
> `isInventoryFull(Inventory, Direction)` 本体里，而本代它被编译进了合成 lambda
> `method_17769(Inventory, int)`。`@Redirect(method = "isInventoryFull")` 在 1.20 上会因
> "目标方法内找不到该调用"而注入失败，**必须改指向那个 lambda**。
> 好在 `mc_compat_check.py` 会核对注入目标本身，能提前把这类问题拦下来。

> **五条分支的分工**：本分支 `1.20 – 1.20.4`（Java 17、无物品组件）；
> **1.20.5 分支** `1.20.5 – 1.21.4`（栈上限重构 + Java 21）；
> **1.21.5 分支** `1.21.5 – 1.21.10`（组件读取换接口体系）；
> **1.21.11 分支** 仅 1.21.11（权限体系换代）。
> 每条分支都针对自己的编译目标产出 intermediary jar，**不能互换**。

### `gradle.properties` 里的版本号分别管什么

这几个**都只影响构建环境，玩家看不到**，但它们对"玩家能不能装"的作用并不相同：
只有 `loader_version` 和 `minecraft_version` 会进入 `fabric.mod.json` 的 `depends`，
成为**玩家可见的硬门槛**。

| 配置 | 玩家可见 | 作用 | 取值原则 |
|---|---|---|---|
| `loader_version` | **✅ 会写进 `depends.fabricloader`** | 编译期依赖 **+** 玩家门槛 | 取**能工作的最低版本**，不要用"当前最新"——否则白白挡住老玩家 |
| `minecraft_version` / `_min` / `_max` | **✅ 会写进 `depends.minecraft`** | 编译目标 / 声明区间 | 见上一节，按符号验证结果定 |
| `fabric_version` | ❌ | 编译期 Fabric API，决定"代码最多能用多新的 API" | 取该 MC 分支里**较早**的一版（现为 `0.83.0+1.20`；该分支最早是 `0.76.1+1.20`） |
| `loom_version` | ❌ | Gradle 构建插件（反编译 / 重映射 / 开发环境） | 见下 |
| `yarn_mappings` | ❌ | 编译期把混淆名映射为可读名 | 只有更换 `minecraft_version` 时才需同步改 |

**`loader_version` 的取值**：本 Mod 用到的 loader 成员只有 7 个
（`FabricLoader#getInstance/getConfigDir/getModContainer`、`ModContainer#getMetadata`、
`ModMetadata#getVersion`、`Version#getFriendlyString`、`ModInitializer#onInitialize`），
全部自 2019 年起就存在。当前取 **0.14.21**——即 MC 1.20 发布（2023-06-02）前后的同期 loader，
编译通过本身就是"这些成员在 0.14.21 中仍然存在"的证据。
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

本项目用 **Gradle 8.14.2 + JDK 21**，能用的最高 Loom 即 **1.13.6**（当前取值）。
升到 1.14+ 需同步升 Gradle，1.18 还要 JDK 25，而这对玩家零收益。

另外**不要用 `-SNAPSHOT`**：它是浮动版本，同一份代码在不同时间可能拉到不同快照，构建不可复现；
永远固定到正式版。注意**产物结构随 Loom 版本变化**：1.11.x 仍生成 refmap，1.12 起不再生成，
改为把注解里的注入目标就地重映射为 intermediary（实测：Loom 1.11.8 有 refmap，1.12.7 与 1.13.6 都没有）
——上层提到的 `mc_compat_check.py` 已同时支持这两种形式。

**Fabric API 是向后兼容的**：新版保留旧 API，因此用旧 API 编译的模组在新版 Fabric API 上照常运行；
反过来才可能出问题。本 Mod 的 jar 里只引用了两个 Fabric API 类——
`CommandRegistrationCallback` 与它的父类型 `Event`（2019 年即存在），
所以 Fabric API 从 `0.83.0+1.20` 到该分支最新版都能运行。
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
6. 本模组不提供物品上限数据表——原版上限直接读自物品（`Item#getMaxCount()`），对模组物品同样有效。
7. **调低 `global_max` 不会销毁已有物品**：已经超过新上限的堆叠会原样保留，
   只是此后无法再把更多物品放进该格（GUI 侧一次最多放入新上限以内的数量）。
   这与玩家背包的行为一致，详见[第七节](#七两处安全夹取防止调低上限时出现负数增量)。
8. **数量在网络与存档里都不再受 8 位限制**：本代原版用字节存放数量，超过 127 会截断
   （打开容器时表现为数量错乱甚至整格消失）。本模组把网络包与 NBT 两条路都改成 VarInt / int，
   详见[第八节](#八数量本身也要装得下两处-8-位上限)。

---

## 从源码构建

```bash
./gradlew build        # 构建，产物在 build/libs/
./gradlew runClient    # 启动开发环境客户端
./gradlew runServer    # 启动开发环境服务端
```

产物位于 `build/libs/`，只有一个文件：

- `BetterItemStack-<Mod版本>-<MC区间>.jar` — 模组文件本身（如 `BetterItemStack-1.3.0-1.20-1.20.4.jar`）

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
   git tag v1.3.0
   git push origin v1.3.0
   ```

工作流会构建、从 `CHANGELOG.md` 抽取 `## 1.3.0` 一节作为 Release 正文，
并把 `build/libs/*.jar` 作为附件上传。同一个 tag 重跑时会**更新**已有 Release，不会报错。

tag 名带不带 `v` 前缀都可以（`v1.3.0` 与 `1.3.0` 等价，抽取时会自动剥掉前缀）；
`+` 之后的内容会被忽略，所以将来若要写 `v1.3.0+mc1.20-1.20.4` 这类 semver 元数据也能正确抽取。

> **tag 只用版本号，不要把 MC 区间写进去。** 区间已经出现在三个更合适的位置：产物文件名、
> Release 标题（CI 会自动拼成 `v1.3.0 (Minecraft 1.20–1.20.4)`），以及上文的兼容性表。
> 把区间塞进 tag（例如 `V1.0.1_1.21.1-1.21.4`）会有实际代价：CI 是拿 tag 名去 `CHANGELOG.md`
> 里找 `## <版本号>` 小节，归一化后的名字匹配不上，就会**静默回退**成把整份更新日志当成 Release 正文。
> 主流模组（AppleSkin、JEI、REI、Botania、Mod Menu 等）同样只用纯版本号作 tag。

在 IntelliJ IDEA 里用界面完成同样的事（无需命令行）：

| 步骤 | 操作 |
|---|---|
| 推送代码 | 右上角工具栏的 **↑（Push）**，或菜单 `Git` → `Push...`（`Ctrl+Shift+K`）；对话框里确认提交后点 `Push` |
| 打 tag | `Git` → `New Tag...`（旧版在 `VCS` → `Git` → `New Tag...`）；或在 Git 日志中**右键最新提交** → `New Tag...`，填 `v1.3.0` |
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
├── VanillaMax.java                   读取"原版上限"（读 Item#getMaxCount()，不受本模组改写影响）
└── mixin/
    ├── ItemStackMixin.java           物品上限、落盘数量、堆叠数量提示
    ├── PacketByteBufMixin.java       网络包里的数量改用 VarInt
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
