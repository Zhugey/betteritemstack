package com.zhugey.betteritemstack;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class Config {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH = FabricLoader.getInstance().getConfigDir().resolve("betteritemstack.json");
    public static int GLOBAL_MAX = Integer.MAX_VALUE;
    public int configVersion = 1;
    public int global_max = 9999;
    // 添加一个新的字段，用于存储不需要堆叠的物品ID列表
    public List<String> nonStackableItems = new ArrayList<>();

    public static Config load() {
        File configFile = CONFIG_PATH.toFile();
        Config config = new Config();

        if (configFile.exists()) {
            try (FileReader reader = new FileReader(configFile)) {
                config = GSON.fromJson(reader, Config.class);
            } catch (IOException e) {
                System.err.println("Failed to load config: " + e.getMessage());
            }
        }

        // 确保列表不为空，防止空指针异常
        if (config.nonStackableItems == null) {
            config.nonStackableItems = new ArrayList<>();
        }

        // 如果是第一次运行，为列表添加一些默认值
        if (config.nonStackableItems.isEmpty()) {
            config.nonStackableItems.add("minecraft:wooden_sword");
            config.nonStackableItems.add("minecraft:stone_sword");
            config.nonStackableItems.add("minecraft:iron_sword");
            config.nonStackableItems.add("minecraft:golden_sword");
            config.nonStackableItems.add("minecraft:diamond_sword");
            config.nonStackableItems.add("minecraft:netherite_sword");
            config.nonStackableItems.add("minecraft:wooden_shovel");
            config.nonStackableItems.add("minecraft:stone_shovel");
            config.nonStackableItems.add("minecraft:iron_shovel");
            config.nonStackableItems.add("minecraft:golden_shovel");
            config.nonStackableItems.add("minecraft:diamond_shovel");
            config.nonStackableItems.add("minecraft:netherite_shovel");
            config.nonStackableItems.add("minecraft:wooden_pickaxe");
            config.nonStackableItems.add("minecraft:stone_pickaxe");
            config.nonStackableItems.add("minecraft:iron_pickaxe");
            config.nonStackableItems.add("minecraft:golden_pickaxe");
            config.nonStackableItems.add("minecraft:diamond_pickaxe");
            config.nonStackableItems.add("minecraft:netherite_pickaxe");
            config.nonStackableItems.add("minecraft:wooden_axe");
            config.nonStackableItems.add("minecraft:stone_axe");
            config.nonStackableItems.add("minecraft:iron_axe");
            config.nonStackableItems.add("minecraft:golden_axe");
            config.nonStackableItems.add("minecraft:diamond_axe");
            config.nonStackableItems.add("minecraft:netherite_axe");
            config.nonStackableItems.add("minecraft:wooden_hoe");
            config.nonStackableItems.add("minecraft:stone_hoe");
            config.nonStackableItems.add("minecraft:iron_hoe");
            config.nonStackableItems.add("minecraft:golden_hoe");
            config.nonStackableItems.add("minecraft:diamond_hoe");
            config.nonStackableItems.add("minecraft:netherite_hoe");
            config.nonStackableItems.add("minecraft:leather_helmet");
            config.nonStackableItems.add("minecraft:chainmail_helmet");
            config.nonStackableItems.add("minecraft:iron_helmet");
            config.nonStackableItems.add("minecraft:golden_helmet");
            config.nonStackableItems.add("minecraft:diamond_helmet");
            config.nonStackableItems.add("minecraft:netherite_helmet");
            config.nonStackableItems.add("minecraft:leather_chestplate");
            config.nonStackableItems.add("minecraft:chainmail_chestplate");
            config.nonStackableItems.add("minecraft:iron_chestplate");
            config.nonStackableItems.add("minecraft:golden_chestplate");
            config.nonStackableItems.add("minecraft:diamond_chestplate");
            config.nonStackableItems.add("minecraft:netherite_chestplate");
            config.nonStackableItems.add("minecraft:leather_leggings");
            config.nonStackableItems.add("minecraft:chainmail_leggings");
            config.nonStackableItems.add("minecraft:iron_leggings");
            config.nonStackableItems.add("minecraft:golden_leggings");
            config.nonStackableItems.add("minecraft:diamond_leggings");
            config.nonStackableItems.add("minecraft:netherite_leggings");
            config.nonStackableItems.add("minecraft:leather_boots");
            config.nonStackableItems.add("minecraft:chainmail_boots");
            config.nonStackableItems.add("minecraft:iron_boots");
            config.nonStackableItems.add("minecraft:golden_boots");
            config.nonStackableItems.add("minecraft:diamond_boots");
            config.nonStackableItems.add("minecraft:netherite_boots");
            config.nonStackableItems.add("minecraft:bow");
            config.nonStackableItems.add("minecraft:crossbow");
            config.nonStackableItems.add("minecraft:fishing_rod");
            config.nonStackableItems.add("minecraft:carrot_on_a_stick");
            config.nonStackableItems.add("minecraft:warped_fungus_on_a_stick");
            config.nonStackableItems.add("minecraft:trident");
            config.nonStackableItems.add("minecraft:shears");
            config.nonStackableItems.add("minecraft:mace");
            config.nonStackableItems.add("minecraft:shield");
            config.nonStackableItems.add("minecraft:spyglass");
            config.nonStackableItems.add("minecraft:turtle_helmet");
            config.nonStackableItems.add("minecraft:elytra");
            config.nonStackableItems.add("minecraft:brush");

            // 你可以添加更多你认为不应该堆叠的物品
        }

        GLOBAL_MAX = config.global_max;

        config.save();
        return config;
    }

    public void save() {
        try (FileWriter writer = new FileWriter(CONFIG_PATH.toFile())) {
            GSON.toJson(this, writer);
        } catch (IOException e) {
            System.err.println("Failed to save config: " + e.getMessage());
        }
    }

    public void reload() {
        save();
        load();
    }
}

