package com.zhugey.betteritemstack;

import net.fabricmc.api.ModInitializer;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class BetterItemStack implements ModInitializer {
	public static final String MOD_ID = "betteritemstack";

	// This logger is used to write text to the console and the log file.
	// It is considered best practice to use your mod id as the logger's name.
	// That way, it's clear which mod wrote info, warnings, and errors.
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	public static Config CONFIG = Config.load();


	private static int executeGet(CommandContext<ServerCommandSource> context) {
		context.getSource().sendFeedback(() -> Text.literal("global_max: " + Config.GLOBAL_MAX), false);
		return 1;
	}

	private static int executeSet(CommandContext<ServerCommandSource> context) {
		int value = IntegerArgumentType.getInteger(context, "global_max");
		if (value >= 1) {
			int old_global_max = Config.GLOBAL_MAX;
			CONFIG.global_max = value;
			CONFIG.reload();
			context.getSource().sendFeedback(() -> Text.literal("global_max: " + old_global_max + " -> " + Config.GLOBAL_MAX), false);
			return 1;
		}
		context.getSource().sendFeedback(() -> Text.literal("Numbers must be in the range of [1, 2,147,483,647]").formatted(Formatting.RED), false);
		return 0;
	}

	@Override
	public void onInitialize() {
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
			dispatcher.register(CommandManager.literal("bis")
					.executes(context -> {
						context.getSource().sendFeedback(() -> Text.literal("BetterItemStack Version: 1.0.0"), false);
						return 1;
					})
					.then(CommandManager.literal("get")
							.executes(BetterItemStack::executeGet))
					.then(CommandManager.literal("set")
							.then(CommandManager.argument("global_max", IntegerArgumentType.integer())
									.requires(source -> source.hasPermissionLevel(1))
									.executes(BetterItemStack::executeSet)
							)
							.requires(source -> source.hasPermissionLevel(1)))
			);
		});
		LOGGER.info("BetterItemStack Initialized");
	}

}