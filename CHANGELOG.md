# 更新日志

本文件记录每个发布版本的变更。发布 Release 时，CI 会自动抽取对应版本号的小节作为 Release 正文。

格式约定：版本小节标题必须是 `## <版本号> — <日期>`，且版本号与推送的 tag 一致（tag `v1.0.1` → 小节 `## 1.0.1`）。

> **授权变更（2026-10-08）**：项目授权由 CC0-1.0 改为 **MIT**。
> 上游 ItemStackProMax 本就是 MIT 授权，改用 MIT 既与社区主流一致，也保留了上游要求的版权声明。
> 已经发布的 1.0.x 版本仍按其发布当时的条款有效；此后获取的代码与产物适用 MIT。

---

## 1.3.1 — 2026-10-10

**修复 1.20 – 1.20.4 上「创造模式取出一整摞物品，一打开箱子就没了」的一组缺陷（共三处）。**

实测现象：创造模式取出的整摞物品（`global_max`，默认 9999）**看着**已经堆叠成功，但一打开箱子
那一格就空了。下面三处各自都能单独造成这个结果，全部修掉才恢复正常。

### 缺陷一：数量在传输与存档时被 8 位字节截断

本代原版把**数量存成 8 位字节**，而这两条路径都不经过 Codec：

| 路径 | 原版写法 | 后果 |
|---|---|---|
| 网络包 | `PacketByteBuf#writeItemStack` 里 `writeByte(count)` 写、`readItemStack` 里 `readByte()` 读 | 128 ~ 255 读成负数、256 的整数倍读成 0；`ItemStack#isEmpty()` 对 `count <= 0` 返回 true → **整格消失**。打开容器、拾取、GUI 点击都会做整包同步 |
| 存档 | `ItemStack#writeNbt` 里 `putByte("Count", (byte) count)` | 同上；重进世界后数量错乱 |

1.3.0 漏掉这两条，是因为常规符号核查只问"名字还在不在"，而这两个名字一直都在。

**修复**

- 新增 `PacketByteBufMixin`：网络包里的数量改用 `writeVarInt` / `readVarInt`；
- `ItemStackMixin` 增加落盘数量的处理：`writeNbt` 的 TAIL 补一个 `putInt("Count")` 覆盖原字节，
  `fromNbt` 的 RETURN 用 `getInt` 还原。

### 缺陷二：创造模式取物的服务端 64 硬编码校验

`ServerPlayNetworkHandler#onCreativeInventoryAction` 里有一段防作弊校验，把「客户端声称的数量」
与**字面量 64** 比较（字节码里是一次 `bipush 64`，不是 `getMaxCount()`）：

    boolean valid = stack.isEmpty()
            || stack.getDamage() >= 0 && stack.getCount() <= 64 && !stack.isEmpty();

（1.20.5 起原版才改成 `getCount() <= getMaxCount()`——实测 1.20.5 的对应方法里确实只剩
`getCount()` 与 `getMaxCount()` 两次调用，没有那个常量。）

于是数量 > 64 的堆叠会被**静默丢弃**：既不 `setStack`、也不回包。客户端因为本模组放大了
`getMaxCount()`，点一下就是整整一摞，看起来成功；服务端却根本没接收。一打开箱子触发整包同步
（`InventoryS2CPacket`），客户端背包被服务端的真实状态覆盖，那一格就"消失"了。

**修复**：新增 `ServerPlayNetworkHandlerMixin`，用 `@Redirect` 把校验里唯一的那次
`ItemStack#getCount()` 调用替换成「是否超限」的哨兵值（超限返回 65、合法返回 1），
从而把原版的 `count > 64` 等价改写为 `count > getMaxCount()`。

之所以不改那个常量：`bipush` 只能承载 -128 ~ 127，而 `global_max` 由玩家在游戏内任意调高，
写死任何一个值都会在某个上限下重新失效；也改写不了「把常量换成方法调用」这种操作。
哨兵值方案一次到位，并且保留了原版的防作弊意图（数量超过真实上限仍会被拒）。

### 缺陷三：`compatibilityLevel` 定得过高，低版本 Mixin 会拒收整份配置

`betteritemstack.mixins.json` 里写的是 `"compatibilityLevel": "JAVA_21"`。
`depends.fabricloader` 声明的最低版本是 **0.14.21**（随附 Mixin 0.8.5），而 Mixin 0.8.5
只认识到 `JAVA_17`——遇到 `JAVA_21` 会直接报
`MixinInitialisationError: ... JAVA_21 which is not recognised` 并**拒收整份 mixin 配置**，
后果是**全部 Mixin 一起失效**（模组看起来正常加载，却什么都不起作用）。
改成 `JAVA_17`：与本分支的编译目标一致，也兼容更低版本的加载器。

### 兼容性

VarInt 对 0 ~ 127 就是单个字节，NBT 侧 `putInt` 与 `putByte` 读出来也一样，因此对端未装本模组时
数量 ≤ 127 仍能正确解析、不会错位；只有 > 127 才会不一致——而那本来也只有装了本模组才会出现。

### 一并处理的细节

- 写侧为什么是**两个** `require = 0` 的变体：1.20.2 给 `PacketByteBuf` 补了一批"返回自身"的协变重载，
  写数量调用的返回类型由 `ByteBuf` 变成 `PacketByteBuf`，而 `@Redirect` 处理器的返回类型必须与目标一致，
  一个 handler 覆盖不了两种形态。每个版本恰好命中一个。
- 读侧用 `@ModifyVariable` 而非"方法返回后再修"：原版拿到截断值后会立刻 `new ItemStack(item, 截断值)`，
  截断值恰为 0 时物品信息已丢失，返回后再改救不回来。
- 新增离线校验脚本 `packet_count_check.py`，逐个版本断言"恰好命中一个写变体、调用次数为 1"，
  把 `require = 0` 带来的静默风险变成可验证项。
- 新增离线校验脚本 `creative_count_check.py`：`onCreativeInventoryAction` 在中介映射里
  **只挂在接口 `ServerPlayPacketListener` 名下**，宿主类的映射块里根本没有它，所以不能直接在
  宿主类里查成员。脚本改为从接口取 (混淆名, 混淆描述符)、再回宿主类核对"该方法确实声明在自身"，
  并断言那句 `getCount()` 在 1.20 ~ 1.20.4 **每个版本都恰好被调用一次**（`@Redirect` 的硬性前提）。
  运行期之所以没问题，是因为 Fabric Loader 用 TinyRemapper 把游戏 jar 重映射到 intermediary 时
  会把接口成员名传播到实现类。
- README 更正：本分支**没有** `ItemStack.<clinit>` 的 Codec 重写注入——那是 1.20.5 起原版把数量
  收窄成 `rangedInt(1, 99)` 才需要的，本代 Codec 里的数量本来就不限范围。

---

## 1.3.0 — 2026-10-08

**1.20 分支的首个版本**，只支持 **Minecraft 1.20 – 1.20.4**。
这一代与 1.20.5+ 属于两套 API，必须单独一条分支：本代还没有"栈上限重构"
（没有 `Inventory#getMaxCount(ItemStack)`，也没有物品组件），而且运行环境是 **Java 17**
（1.20.5 起才升到 Java 21）。

### 与 1.20.5+ 分支的代码差异

| 位置 | 1.20 – 1.20.4（本分支） | 1.20.5+ |
|---|---|---|
| `VanillaMax` | 读 `Item#getMaxCount()`（物品级） | 读 `minecraft:max_stack_size` 组件 |
| `ItemStackMixin` 的 CODEC 重写 | **不需要**：本代 `ItemStack.CODEC` 的 count 就是 `Codec.INT`（字段名 `Count`，不限范围） | 需要：1.20.5 起改成 `rangedInt(1, 99)` |
| 可损坏物品判定 | `getItem().isDamageable()`（纯物品级） | `stack.contains(MAX_DAMAGE)` |
| `getTooltip` 注入签名 | `(PlayerEntity, TooltipContext)` | 多一个 `Item.TooltipContext` 形参 |
| `InventoryMixin` | 只拦截 `getMaxCountPerStack()` | 还要拦截 `getMaxCount(ItemStack)` |
| 漏斗的目标容器判定 | 在合成 lambda `method_17769(Inventory,int)` 里 | 在 `isInventoryFull` 方法本体里 |
| `ContainerPolicy` | 无 `CrafterBlockEntity`（1.21 才有） | 有 |
| 编译目标 / 运行环境 | Java 17 | Java 21 |

### 两个值得记下的坑

- **`isInventoryFull` 那处 `@Redirect` 在 1.20 上不能照搬**：本代"目标容器是否已满"的判定
  被编译进了合成 lambda `method_17769(Inventory, int)`（即
  `stack.getCount() >= stack.getMaxCount()`），写在 `isInventoryFull` 上会因
  "目标方法内找不到该调用"而**注入失败**。已改为指向那个 lambda。
- **核查工具的一处误报已修**：`redirect_count_check.py` 原先只按方法名匹配 `getMaxCount`，
  把同名的 `ItemStack#increment(int)`（混淆名同为 `g`、描述符为 `(I)V`）也算了进去，
  于是 1.20 的 `transfer` 被误报为"调用了 2 次"。现在会连描述符一起比对。

### 兼容性

- ✅ **1.20 / 1.20.1 / 1.20.2 / 1.20.3 / 1.20.4** —— 共 5 个正式版，同一个 jar 直接可用。
- ❌ **1.20.5 及以上**：属于新一代 API，见 **1.20.5 分支** / **1.21.5 分支** / **1.21.11 分支**。
- 需要 Fabric Loader **≥ 0.14.21**、Fabric API、**Java 17**。

### 验证

- **符号核查**：1.20 – 1.20.4 连续 5 个版本与基线符号画像完全一致；1.20.5+ 报出具体缺失项。
- **注入目标核查**：14 个注入目标（含那个 lambda）在 1.20 – 1.20.4 全部可解析。
- **`@Redirect` 调用次数**：区间两端（1.20 与 1.20.4）三处目标方法内
  `ItemStack#getMaxCount()` 的调用次数均为 1 次。
- 构建 **0 诊断**。

---

## 1.0.3 — 2026-10-08

**支持区间由 1.21.1 – 1.21.4 扩到 1.20.5 – 1.21.4**，编译目标改为区间下限 **1.20.5**。

### 为什么下限能降到 1.20.5

1.20.5 是分界点：这一版做了"栈上限重构"——新增 `Inventory#getMaxCount(ItemStack)`，
物品堆叠上限改由 `minecraft:max_stack_size` 组件承载；同时把运行环境从 **Java 17 提到 Java 21**。
本模组正是基于这套 API 写的，所以 1.20.5 / 1.20.6 与 1.21.x 的符号画像**完全一致**；
而 1.20 – 1.20.4 属于更早的一代（没有这套 API，且运行在 Java 17），需要另行单独适配。

### 改动

- 编译目标与声明下限改为 **1.20.5**；`yarn_mappings` 用 `1.20.5+build.1`（该版本只有这一个 build）。
- **一处源码改动**：`ItemStackMixin` 里工具提示类型参数 `TooltipType` 的导入，
  由 `net.minecraft.item.tooltip.TooltipType` 改为 `net.minecraft.client.item.TooltipType`。
  这是同一个 intermediary 类（`class_1836`），只是 Yarn 在 1.21 改了包名——
  这类"改名"符号核查查不出来，只有编译器能发现。
- `loader_version` 由 0.16.1 降为 **0.15.11**（1.20.5 同期），玩家门槛随之放宽。
- `fabric_version` 取该 MC 分支里较早的 `0.97.6+1.20.5`。
- 产物名与显示名随区间变为 `BetterItemStack-1.0.3-1.20.5-1.21.4.jar` / `BetterItemStack 1.20.5-1.21.4`。

### 兼容性

- ✅ **1.20.5 / 1.20.6 / 1.21 / 1.21.1 / 1.21.2 / 1.21.3 / 1.21.4** —— 共 7 个正式版，同一个 jar 直接可用。
- ❌ **1.20 – 1.20.4**：缺 `Inventory#getMaxCount(ItemStack)`，组件类也尚不存在，且运行环境是 Java 17。
- ❌ **1.21.5 及以上**：1.21.5 起组件读取方法换了接口体系（见 1.21.5 分支）；
  1.21.11 另有权限体系换代（见 1.21.11 分支）。
- 需要 Fabric Loader **≥ 0.15.11**、Fabric API、**Java 21**。

### 验证

- **符号核查**：1.20.5 ~ 1.21.4 连续 7 个版本与基线符号画像完全一致；
  1.20 – 1.20.4 与 1.21.5+ 各自报出具体缺失项，边界清晰。
- **`@Redirect` 调用次数**：区间两端（1.20.5 与 1.21.4）三处目标方法内
  `ItemStack#getMaxCount()` 的调用次数均为 1 次。
- 构建 **0 诊断**。

---

## 1.0.2 — 2026-10-08

> **本 Release 为重新发布。** 与原发布相比唯一的变化是**项目授权由 CC0-1.0 改为 MIT**
> （上游 `ItemStackProMax` 本就是 MIT 授权），产物内含的许可声明随之更新，**游戏内行为完全不变**，
> 已经装好原版 1.0.2 的玩家无需更换。

本版本的核心是把**支持的 Minecraft 版本从 1.21.1 扩展到 1.21.1 – 1.21.4**，
并放宽了对 Fabric Loader 的要求。**没有任何游戏内行为变更**：堆叠逻辑、指令、配置格式
都与 1.0.1 完全一致，直接用新 jar 覆盖即可。

### 修复

- **修复在 1.21.2 / 1.21.3 / 1.21.4 上启动即崩溃。** 本模组通过 `@Shadow` 引用原版的
  `ItemStack#ITEM_CODEC`，而该字段**在 1.21.2 起被移除**（其定义被内联进 `CODEC`）。
  Mixin 应用时抛
  `InvalidMixinException: @Shadow field field_47312 was not located in the target class net.minecraft.class_1799`，
  客户端在 Bootstrap 阶段直接崩溃（1.0.1 因为把 `minecraft` 锁死在 1.21.1，掩盖了这个问题）。
  现改为直接调用 `Registries.ITEM.getEntryCodec()`——这正是 1.21.1 原版构建 `ITEM_CODEC` 所用的 API，
  在 1.21.1 – 1.21.4 均存在。1.21.1 上的行为不变（唯一差异：不再额外校验"物品 id 不得为
  `minecraft:air`"，该差异只影响手工构造的非法存档数据）。
- 上述版本区间结论已重新核对，这次把 **`@Shadow` 成员**也纳入了检查范围——此前遗漏的正是这一类，
  它导致"1.21.4 可用"的结论一度错误。

### 兼容性

- **支持的 Minecraft 版本由 1.21.1 扩展为 1.21.1 – 1.21.4**。1.0.1 的元数据把 `minecraft`
  精确锁定为 `1.21.1`，导致使用 1.21.2 / 1.21.3 / 1.21.4 的玩家一安装就被 Fabric Loader 拦下，
  提示需要 1.21.1。现在改为区间声明。
  该区间是**静态验证**得出的，不是估计：模组产物运行在 intermediary 命名空间，
  只要用到的符号在目标版本里仍然存在就能直接运行，无需重新编译。逐版本核对结果为——
  1.21.2 / 1.21.3 / 1.21.4 与 1.21.1 的符号画像**完全一致**，且三处 `@Redirect` 的目标方法内
  被重定向调用的次数在四个版本中均为 1 次，字节码结构未变。
- **Fabric Loader 门槛由 ≥ 0.16.14 放宽为 ≥ 0.16.1**，让使用较旧 Loader 的玩家也能安装
  （Loader 向后兼容，新版能跑旧模组，因此门槛设低只会放宽、不会收紧）。
- **1.21.5 及以上仍不可用**。从 1.21.5 起 `ComponentHolder#get(ComponentType)` 与
  `getOrDefault(...)` 被移除（该类只剩 `contains` 与 `getComponents`），本模组读取
  `minecraft:max_stack_size` 组件的方式失效；1.21.11 另有一处权限 API 变更。
  适配这两个断层需要修改代码，不在本次范围。

### 其它

- 模组显示名改为 `BetterItemStack 1.21.1-1.21.4`，产物文件名同步为
  `BetterItemStack-1.0.2-1.21.1-1.21.4.jar`，便于与旧文件区分。
- 构建工具链更新并固定版本（Fabric Loom 1.11 → 1.13.6，不再使用浮动版本），
  产物结构随之变化（不再附带 refmap，注入目标改为直接写入注解字节码）。
  **这一点只影响构建过程，对玩家无影响。**
- README 补充「版本兼容性」与「各版本号的取值策略」两节，并说明如何自行复核上述结论。

### 依然建议配合使用

- 需要 Java 21；Minecraft 需为 **1.21.1 – 1.21.4** 之一。

---

## 1.0.1 — 2026-10-07

相较于 1.0.0 的完整变更。

### 修复

- **修复调低堆叠上限会销毁已有物品**。原版有 6 处写成 `stack.capCount(this.getMaxCount(stack))`，而 `ItemStack#capCount` 的实现是直接覆盖数量、**超出部分销毁且不返还**（`LockableContainerBlockEntity#setStack` 等）。把上限从 9999 调到 1000 后，箱子里已有的 9999 会被静默截成 1000——一次丢失 8999 个。玩家背包因为不含这一步而幸免，表现为"箱子丢、背包不丢"。现在两者行为一致：**已有堆叠不会被回溯截断，容量只在插入时生效**。
- **修复附魔台不显示附魔、附魔灌注台（Enchanting Infuser）无法使用**。附魔台输入槽通过覆写无参 `Slot#getMaxItemCount()` 声明"只接受 1 件"，若覆写 `Slot#getMaxItemCount(ItemStack)` 时丢掉这一项，整摞物品就能被塞进单件槽位，`Item#isEnchantable`（要求 `getMaxCount() == 1`）与 `BookItem#isEnchantable`（要求 `getCount() == 1`）随之失败。
- **修复带 `Unbreakable` 的装备无法附魔**。原判定用 `isDamageable()`，会漏掉这类装备从而误放大其上限；改为 `contains(MAX_DAMAGE)`。
- **修复烟花、药水箭等物品堆叠后不显示数量提示**。原因不是提示过多，而是原注入点 `Item#appendTooltip` 被原版 14 个物品覆写且未调用 `super.appendTooltip(...)`，注入在其中完全不执行（受影响：烟花、药水箭、旗帜、旗帜图案、盾牌、弩、收纳袋、药水、滞留药水、成书、已探索地图、鱼桶、烟花之星、唱片碎片）。改为注入所有分支必经的汇总入口 `ItemStack#getTooltip`。
- **修复 `/bis set` 完全无效**。旧实现先改内存字段、再 `reload()` 从磁盘读回旧值，导致命令回显新旧值相同。
- 修复构建脚本的 2 条 Gradle 弃用告警，`--warning-mode all` 下 0 诊断。

### 新增

- **按容器类型分别控制堆叠上限**：箱子 / 陷阱箱 / 大箱子、木桶、潜影盒、末影箱、玩家背包、运输矿车、漏斗矿车、投掷器、发射器、熔炉 / 高炉 / 烟熏炉、酿造台、合成器。
- **漏斗与漏斗矿车默认保持原版上限**，以 16 / 64 计数的红石计时器、计数器不再失效；同时**漏斗向提升容器推送时可以把目标容器每格填满到上限**（判定对象决定采用哪一方的上限）。
- **配置项 `containers`**：`mode`（`blacklist` / `whitelist`）、`list`、`unknown`，共 13 个容器键名，游戏内 `/bis info` 可直接查看规则、键名与配置示例。
- **命令输出全面国际化**：新增 10 种语言——English (US)、简体中文、繁體中文、日本語、한국어、Русский、Deutsch、Français、Español、Português (Brasil)；未收录的语言自动回退到英文。容器显示名取自 Minecraft 官方译名。
- 堆叠数量提示（数量 ≥ 1000 时在物品名下方显示），格子角标数字在 ≥ 1000 时缩写为 K / M / B。

### 移除

- **移除 `DataComponentTypesMixin`**。它改写 `DEFAULT_ITEM_COMPONENTS.MAX_STACK_SIZE`，使几乎所有物品的原版上限在游戏内变得不可考，是不得不维护上千条 JSON 数据表的根因。
- **移除 1333 条物品上限数据表** `item_original_max_counts.json`。原版上限现在直接读自物品堆叠自身的 `minecraft:max_stack_size` 组件，**不需要任何数据表，且对模组物品同样有效**。
- 移除空的 datagen 入口 `BetterItemStackDataGenerator` 及其构建配置；移除 `build.gradle` 中无用的 `maven-publish` / 空 `repositories` 块与模板样板代码。

### 兼容性与已知行为

- 需要 Minecraft **1.21.1**、Fabric Loader **≥ 0.16.14**、Fabric API、**Java 21**。
- 原版上限为 1 且不可损坏的物品（床、鞍、鱼桶、唱片等）会变为可堆叠；不需要时加入 `nonStackableItems`。
- 未识别的容器默认不提升（`containers.unknown = false`）。其它模组的存储容器需要提升时改为 `true`；注意部分 GUI 模组的输入槽依赖自己的单件限制，开启前建议回归测试。
- 调低 `global_max` 不会销毁已有物品。

---

## 1.0.0 — 2025-09-16

首个发布版本，基于 [ItemStackProMax](https://github.com/develk-coder/ItemStackProMax) 的物品堆叠上限全局提升。
