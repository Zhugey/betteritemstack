# 更新日志

本文件记录每个发布版本的变更。发布 Release 时，CI 会自动抽取对应版本号的小节作为 Release 正文。

格式约定：版本小节标题必须是 `## <版本号> — <日期>`，且版本号与推送的 tag 一致（tag `v1.0.1` → 小节 `## 1.0.1`）。

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
