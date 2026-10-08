# 更新日志

本文件记录每个发布版本的变更。发布 Release 时，CI 会自动抽取对应版本号的小节作为 Release 正文。

格式约定：版本小节标题必须是 `## <版本号> — <日期>`，且版本号与推送的 tag 一致（tag `v1.0.1` → 小节 `## 1.0.1`）。

---

## 1.2.0 — 2026-10-08

**1.21.11 分支的首个版本**，只支持 **Minecraft 1.21.11**。
下文 1.1.x 是 1.21.5 分支（1.21.5 – 1.21.10）的发布历史，1.0.x 是 1.21.1 分支（1.21.1 – 1.21.4）
的发布历史，三条线并行维护。

### 为什么必须单独出一条分支

1.21.11 是 1.21.x 系列的最后一个正式版，也是**权限体系换代**的那一版：
原先用来判断 OP 的 `CommandSource#hasPermissionLevel(int)`（intermediary `method_9259`）
被整套新的权限模型取代（`PermissionLevel` / `PermissionCheck` / `DefaultPermissions` /
`PermissionPredicate` 等）。1.1.x 的产物里带着对旧方法的调用，在 1.21.11 上会因符号不存在而无法启动，
所以 1.1.x 的 jar 不适用于本版本。

### 适配内容

- **权限判定改写为新 API**：`/bis set` 与 `/bis reload` 原来的 `hasPermissionLevel(1)`
  改为 `CommandManager.requirePermissionLevel(CommandManager.MODERATORS_CHECK)`。
  等级 1 在新体系里就是 `PermissionLevel.MODERATORS`（0 = ALL、1 = MODERATORS、2 = GAMEMASTERS、
  3 = ADMINS、4 = OWNERS），写法与原版 `GameModeCommand` 完全一致，**语义与改动前相同**。
- 除这一处外**没有任何代码改动**：堆叠逻辑、容器策略、指令、配置格式、语言文件均与 1.1.0 一致。
- 构建版本号：编译目标与声明区间均为 1.21.11，Yarn `1.21.11+build.6`，Fabric API `0.139.5+1.21.11`，
  Loom 仍为 1.13.6（Loom 与 MC 版本解耦，无需跟着换）。

### 兼容性

- **仅支持 Minecraft 1.21.11**。产物名与 Mod 显示名都不再重复写两遍版本号
  （单版本分支：`BetterItemStack-1.2.0-1.21.11.jar`、显示名 `BetterItemStack 1.21.11`）。
- **不适用于 1.21.10 及以下**：本构建引用的新权限 API 在那些版本里不存在，
  请改用 **1.21.5 分支**（1.21.5 – 1.21.10）或 **1.21.1 分支**（1.21.1 – 1.21.4）的产物。
- **不适用于 26.1 及以上**：权限 API 又有变动；而且 Minecraft 从 26.1 起**运行时要求 Java 25**。
- 需要 Fabric Loader **≥ 0.18.2**、Fabric API、**Java 21**（1.21.11 仍属 Java 21 区间，
  Java 25 从 26.1 才开始要求）。

### 验证

- **符号核查**：本构建用到的全部 intermediary 类与成员在 1.21.11 中均存在（1.21.11 ✅）。
- **`@Redirect` 调用次数**：三处目标方法（漏斗的 `transfer` / `isFull` / `isInventoryFull`）内
  `ItemStack#getMaxCount()` 的调用次数均为 **1 次**，与历史版本一致。
- **注入点重映射**：六处 Mixin 的注入目标已全部就地重映射为 intermediary
  （换编译目标后最易踩的静默坑，本次逐条确认）。
- **人工核对工具盲区**：1.21.11 的 `ItemStack` 存在 `<clinit>`、`(RegistryEntry, int, ComponentChanges)`
  构造函数存在、`CODEC` 字段仍在。
- 构建 **0 诊断**。

---

**1.21.5 分支的首个版本**，支持 **Minecraft 1.21.5 – 1.21.10**。
下文的 1.0.x 小节记录的是 1.21.1 分支（支持 1.21.1 – 1.21.4）的发布历史，两线并行维护。

### 兼容性

- **新增对 Minecraft 1.21.5 – 1.21.10 的支持**（共 6 个正式版）。区间来自逐版本静态核对：
  用到的 intermediary 符号在这 6 个版本中均存在，且三处 `@Redirect` 的目标方法内
  `ItemStack#getMaxCount()` 调用次数在区间两端（1.21.5 与 1.21.10）均为 1 次，与旧版一致。
- **1.21.11 起不可用**：`CommandSource#hasPermissionLevel(int)` 被新的权限体系取代
  （`ServerCommandSource#permissions` / `withPermissions`），`/bis set`、`/bis reload` 的权限判定需改写。
- **不适用于 1.21.4 及以下**：本构建针对 1.21.5 编译，引用的 intermediary 符号在旧版本中不存在
  （例如 1.21.5 把组件读取方法迁移到了新的接口体系）。请使用 1.21.1 分支对应的产物。
- 需要 Fabric Loader **≥ 0.16.11**、Fabric API、**Java 21**。

### 适配 1.21.5 所需的两处改动

- **格子角标的数字缩写**：注入目标方法在 1.21.1 – 1.21.4 名为 `drawItemInSlot`，
  1.21.5 起改名为 `drawStackOverlay`（同一成员，intermediary 名 `method_51432` 与描述符都没变）。
  Mixin 注解只能写 Yarn 名，而映射里找不到旧名时**不会报错**，只会把字符串原样留在注解中、
  运行期注入失败，因此必须同步改名。已确认语义未变：1.21.5 的 `drawStackOverlay` 只是把表示
  "数量覆盖文本"的字符串参数原样转发给新拆出的 `drawStackCount`。
- 读取 `minecraft:max_stack_size` 组件的方式在 1.21.5 发生了内部迁移
  （`ComponentHolder#get` 由声明方法变为新接口体系下的 default 方法，intermediary 名随之变化）。
  源码写法不变，仅重新编译即可指向新符号。

### 与 1.0.2 相同的部分

堆叠逻辑、容器策略、指令、配置格式、语言文件与显示行为**均与 1.0.2 完全一致**，
没有新增或移除任何功能。这是纯粹的跨版本适配。

### 其他

- **授权由 CC0-1.0 改为 MIT**。上游 `ItemStackProMax` 本就是 MIT 授权，改为 MIT 既与社区主流一致，
  也保留了上游要求的版权与许可声明。此前已发布的 1.0.x 版本仍按其发布时的条款有效。
- Release 标题开始带上本分支的 MC 支持区间，形如 `v1.1.0 (Minecraft 1.21.5–1.21.10)`。

---

## 1.0.2 — 2026-10-08

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
