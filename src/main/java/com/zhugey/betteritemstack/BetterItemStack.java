package com.zhugey.betteritemstack;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 主 Mod 类，用于初始化 Mod 并注册命令。
 *
 * <p>主要职责：加载配置、注册 {@code /bis} 命令（get / set / info / reload）、打印初始化日志。
 *
 * <p><b>国际化</b>：所有面向玩家的文案都通过 {@link Text#translatable} 从语言文件读取，
 * 代码内不硬编码任何自然语言。语言文件位于 {@code assets/betteritemstack/lang/}，
 * 缺失语言由 Minecraft 自动回退到 {@code en_us}。
 */
public class BetterItemStack implements ModInitializer {

    public static final String MOD_ID = "betteritemstack";

    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    /** 翻译键前缀。 */
    private static final String KEY = "betteritemstack.";

    /** 容器键名列表在输出中的分隔符（纯符号，无需翻译）。 */
    private static final String SEP = " · ";

    /** 配置文件在游戏内的相对路径，作为参数传给翻译键中的示例文案。 */
    private static final String CONFIG_HINT = "config/betteritemstack.json";

    /**
     * 从 Mod 元数据读取版本号，避免与 gradle.properties 中的版本号脱节。
     *
     * @return 版本字符串
     */
    private static String version() {
        return FabricLoader.getInstance().getModContainer(MOD_ID)
                .map(container -> container.getMetadata().getVersion().getFriendlyString())
                .orElse("unknown");
    }

    /**
     * 向命令执行者发送一行文本。
     *
     * @param context 命令上下文
     * @param text    文本内容
     */
    private static void send(CommandContext<ServerCommandSource> context, Text text) {
        context.getSource().sendFeedback(() -> text, false);
    }

    /**
     * 构造单个容器条目的文本，形如 {@code 英文键名(本地化名称)}。
     *
     * <p>键名本身是配置里要填的字面量，不翻译；括号内是给人看的名称，走语言文件。
     *
     * @param configKey 容器配置键名
     * @return 可翻译文本
     */
    private static MutableText containerEntry(String configKey) {
        return Text.translatable(KEY + "container.entry",
                Text.literal(configKey),
                Text.translatable(ContainerPolicy.translationKey(configKey)));
    }

    /**
     * 拼接容器键名列表。
     *
     * @param keys 键名列表
     * @return 拼接后的文本；列表为空时返回本地化的"（无）"
     */
    private static MutableText joinKeys(List<String> keys) {
        if (keys == null || keys.isEmpty()) {
            return Text.translatable(KEY + "command.info.empty");
        }
        MutableText result = Text.empty();
        for (int i = 0; i < keys.size(); i++) {
            if (i > 0) {
                result.append(Text.literal(SEP));
            }
            result.append(containerEntry(keys.get(i)));
        }
        return result;
    }

    /**
     * {@code /bis get} 命令执行逻辑：反馈当前全局最大堆叠值。
     *
     * @param context 命令上下文
     * @return 返回 1 表示命令成功
     */
    private static int executeGet(CommandContext<ServerCommandSource> context) {
        send(context, Text.translatable(KEY + "command.current",
                Text.literal(String.valueOf(Config.GLOBAL_MAX)).formatted(Formatting.GOLD)));
        return 1;
    }

    /**
     * {@code /bis info} 命令执行逻辑：展示容器提升规则、可用容器键名，以及配置示例。
     *
     * @param context 命令上下文
     * @return 返回 1 表示命令成功
     */
    private static int executeInfo(CommandContext<ServerCommandSource> context) {
        Config.Containers containers = Config.get().containers;
        boolean whitelist = containers.isWhitelist();

        send(context, Text.translatable(KEY + "command.info.header").formatted(Formatting.GOLD));
        send(context, Text.translatable(KEY + "command.current",
                Text.literal(String.valueOf(Config.GLOBAL_MAX)).formatted(Formatting.GOLD)));

        // 提升规则：模式说明 + 名单
        send(context, Text.translatable(KEY + "command.info.mode",
                Text.translatable(KEY + (whitelist ? "command.info.mode.whitelist" : "command.info.mode.blacklist"))
                        .formatted(Formatting.AQUA)));
        send(context, Text.translatable(KEY + (whitelist ? "command.info.boosted" : "command.info.kept"),
                joinKeys(containers.list).formatted(Formatting.YELLOW)));

        if (!whitelist) {
            send(context, Text.translatable(KEY + "command.info.unknown",
                    Text.translatable(KEY + (containers.unknown ? "command.info.unknown.yes" : "command.info.unknown.no"))
                            .formatted(Formatting.AQUA)));
        }

        // 可用键名：英文键名 + 本地化名称
        send(context, Text.translatable(KEY + "command.info.keys").formatted(Formatting.GRAY));
        send(context, joinKeys(ContainerPolicy.SUPPORTED_KEYS).formatted(Formatting.GRAY));

        // 配置示例
        send(context, Text.translatable(KEY + "command.info.example.header").formatted(Formatting.GOLD));
        send(context, Text.translatable(
                KEY + (whitelist ? "command.info.example.whitelist" : "command.info.example.blacklist"),
                Text.literal(CONFIG_HINT).formatted(Formatting.YELLOW)).formatted(Formatting.WHITE));
        if (!whitelist) {
            // 配置片段是纯 JSON 语法，保持字面量输出（不参与翻译）。
            send(context, Text.literal("  \"containers\": { \"mode\": \"blacklist\", "
                    + "\"list\": [\"hopper\", \"hopper_minecart\", \"barrel\"] }")
                    .formatted(Formatting.DARK_GRAY));
        }
        return 1;
    }

    /**
     * {@code /bis set <global_max>} 命令执行逻辑：修改全局最大堆叠值并写回配置文件。
     *
     * @param context 命令上下文
     * @return 1 表示成功，0 表示失败
     */
    private static int executeSet(CommandContext<ServerCommandSource> context) {
        int value = IntegerArgumentType.getInteger(context, "global_max");

        if (value < 1) {
            context.getSource().sendError(Text.translatable(KEY + "command.set.invalid")
                    .formatted(Formatting.RED));
            return 0;
        }

        int old = Config.GLOBAL_MAX;
        // 由 Config#setGlobalMax 负责"更新内存 -> 写盘 -> 刷新 GLOBAL_MAX"。
        // 旧实现先改内存字段再 reload（从磁盘读回旧值），会导致命令看似生效实则无效。
        int now = Config.get().setGlobalMax(value);

        send(context, Text.translatable(KEY + "command.set.done",
                Text.literal(String.valueOf(old)).formatted(Formatting.GRAY),
                Text.literal(String.valueOf(now)).formatted(Formatting.GOLD)));
        return 1;
    }

    /**
     * {@code /bis reload} 命令执行逻辑：从磁盘重新读取配置。
     *
     * @param context 命令上下文
     * @return 返回 1 表示命令成功
     */
    private static int executeReload(CommandContext<ServerCommandSource> context) {
        Config.reload();
        send(context, Text.translatable(KEY + "command.reload.done",
                Text.literal(String.valueOf(Config.GLOBAL_MAX)).formatted(Formatting.GOLD)));
        return 1;
    }

    /**
     * Mod 初始化入口。
     *
     * <p>主要执行：加载配置、注册命令、打印初始化日志。
     */
    @Override
    public void onInitialize() {
        // 读取配置（文件不存在时会生成默认配置）
        Config.load();

        // 注册命令回调
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {

            // 注册 /bis 主命令
            dispatcher.register(CommandManager.literal("bis")
                    .executes(context -> {
                        // 输出 Mod 版本信息与可用子命令
                        send(context, Text.translatable(KEY + "command.version",
                                Text.literal(version()).formatted(Formatting.GOLD)));
                        send(context, Text.translatable(KEY + "command.help").formatted(Formatting.GRAY));
                        return 1;
                    })
                    // /bis get 子命令
                    .then(CommandManager.literal("get")
                            .executes(BetterItemStack::executeGet))
                    // /bis info 子命令
                    .then(CommandManager.literal("info")
                            .executes(BetterItemStack::executeInfo))
                    // /bis set <global_max> 子命令
                    .then(CommandManager.literal("set")
                            // 仅 OP 可用（等级 1）。
                            // 1.21.11 起 CommandSource#hasPermissionLevel(int) 被新的权限体系取代
                            // （PermissionLevel / PermissionCheck / DefaultPermissions），
                            // 等级 1 现在对应 DefaultPermissions.MODERATORS，
                            // 原版 GameModeCommand 用的就是 .requires(CommandManager.requirePermissionLevel(...)) 这种写法。
                            .requires(CommandManager.requirePermissionLevel(CommandManager.MODERATORS_CHECK))
                            .then(CommandManager.argument("global_max", IntegerArgumentType.integer())
                                    .executes(BetterItemStack::executeSet)))
                    // /bis reload 子命令
                    .then(CommandManager.literal("reload")
                            .requires(CommandManager.requirePermissionLevel(CommandManager.MODERATORS_CHECK))
                            .executes(BetterItemStack::executeReload))
            );
        });

        // 打印初始化日志
        LOGGER.info("BetterItemStack initialized (version {})", version());
    }
}
