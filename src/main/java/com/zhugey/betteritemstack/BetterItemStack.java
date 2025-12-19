package com.zhugey.betteritemstack;

import net.fabricmc.api.ModInitializer; // Fabric 初始化接口
import com.mojang.brigadier.arguments.IntegerArgumentType; // 命令参数解析
import com.mojang.brigadier.context.CommandContext; // 命令上下文
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback; // 命令注册回调
import net.minecraft.server.command.CommandManager; // 命令管理器
import net.minecraft.server.command.ServerCommandSource; // 命令执行源
import net.minecraft.text.Text; // 文本显示
import net.minecraft.util.Formatting; // 文本颜色
import org.slf4j.Logger; // 日志接口
import org.slf4j.LoggerFactory; // 日志工厂

/**
 * 主 Mod 类，用于初始化 Mod 并注册命令
 * <p>
 * 主要职责：
 * <p>
 * 1. 加载配置文件 Config
 * <p>
 * 2. 注册 /bis 命令及子命令（get/set）
 * <p>
 * 3. 打印初始化日志
 */
public class BetterItemStack implements ModInitializer {

	// Mod ID
	public static final String MOD_ID = "betteritemstack";

	// 日志对象，用于打印 Mod 信息
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	// 配置对象，Mod 加载时读取配置
	public static Config CONFIG = Config.load();

	/**
	 * /bis get 命令执行逻辑
	 * <p>
	 * 作用：向命令执行者反馈当前全局最大堆叠值 GLOBAL_MAX
	 * @param context 命令上下文
	 * @return 返回 1 表示命令成功
	 */
	private static int executeGet(CommandContext<ServerCommandSource> context) {
		// 向执行者发送当前 global_max
		context.getSource().sendFeedback(() ->
				Text.literal("global_max: " + Config.GLOBAL_MAX), false);
		return 1; // 命令成功
	}

	/**
	 * /bis set <global_max> 命令执行逻辑
	 * <p>
	 * 作用：修改全局最大堆叠值，并刷新配置
	 * @param context 命令上下文
	 * @return 1 表示成功，0 表示失败
	 */
	private static int executeSet(CommandContext<ServerCommandSource> context) {
		// 获取输入的整数参数
		int value = IntegerArgumentType.getInteger(context, "global_max");

		// 检查输入是否合法
		if (value >= 1) {
			int old_global_max = Config.GLOBAL_MAX; // 保存旧值
			CONFIG.global_max = value; // 更新配置对象
			CONFIG.reload(); // 重新加载配置，刷新 GLOBAL_MAX
			context.getSource().sendFeedback(() ->
					Text.literal("global_max: " + old_global_max + " -> " + Config.GLOBAL_MAX), false);
			return 1; // 命令成功
		}

		// 输入不合法，发送错误提示
		context.getSource().sendFeedback(() ->
				Text.literal("数字必须在 [1, 2147483647] 范围内").formatted(Formatting.RED), false);
		return 0; // 命令失败
	}

	/**
	 * Mod 初始化入口
	 * <p>
	 * 主要执行：
	 * <p>
	 * 1. 注册命令
	 * <p>
	 * 2. 打印初始化日志
	 */
	@Override
	public void onInitialize() {
		// 注册命令回调
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {

			// 注册 /bis 主命令
			dispatcher.register(CommandManager.literal("bis")
					.executes(context -> {
						// 输出 Mod 版本信息
						context.getSource().sendFeedback(() ->
								Text.literal("BetterItemStack Version: 1.0.0"), false);
						return 1;
					})
					// /bis get 子命令
					.then(CommandManager.literal("get")
							.executes(BetterItemStack::executeGet))
					// /bis set <global_max> 子命令
					.then(CommandManager.literal("set")
							.then(CommandManager.argument("global_max", IntegerArgumentType.integer())
									.requires(source -> source.hasPermissionLevel(1)) // 仅 OP 可用
									.executes(BetterItemStack::executeSet))
							.requires(source -> source.hasPermissionLevel(1))) // OP 检查
			);
		});

		// 打印初始化日志
		LOGGER.info("BetterItemStack Initialized");
	}
}
