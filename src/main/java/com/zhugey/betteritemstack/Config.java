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

/**
 * 配置管理类
 * <p>
 * 主要职责：
 * <p>
 * 1. 读取 JSON 配置文件
 * <p>
 * 2. 提供全局堆叠上限 GLOBAL_MAX
 * <p>
 * 3. 提供可选不堆叠物品列表
 * <p>
 * 4. 保存和重新加载配置
 */
public class Config {

    // Gson 对象，用于 JSON 读写
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    // 配置文件路径
    private static final Path CONFIG_PATH = FabricLoader.getInstance().getConfigDir()
            .resolve("betteritemstack.json");

    // 静态全局最大堆叠值
    public static int GLOBAL_MAX = Integer.MAX_VALUE;

    // 配置版本（可扩展）
    public int configVersion = 1;

    // 配置中的 global_max 初始值
    public int global_max = 9999;

    // 可选黑名单列表，存储不允许堆叠的物品 ID
    public List<String> nonStackableItems = new ArrayList<>();

    /**
     * 加载配置文件
     * <p>
     * 主要逻辑：
     * <p>
     * 1. 文件存在则读取 JSON
     * <p>
     * 2. 初始化默认值（防止空指针）
     * <p>
     * 3. 设置 GLOBAL_MAX
     * <p>
     * 4. 保存配置文件（首次运行会生成 JSON）
     *
     * @return 返回配置对象
     */
    public static Config load() {
        File configFile = CONFIG_PATH.toFile();
        Config config = new Config();

        // 如果配置文件存在，读取 JSON
        if (configFile.exists()) {
            try (FileReader reader = new FileReader(configFile)) {
                config = GSON.fromJson(reader, Config.class);
            } catch (IOException e) {
                System.err.println("Failed to load config: " + e.getMessage());
            }
        }

        // 确保黑名单列表不为空
        if (config.nonStackableItems == null) {
            config.nonStackableItems = new ArrayList<>();
        }

        // 设置全局最大堆叠
        GLOBAL_MAX = config.global_max;

        // 保存配置文件（首次运行生成）
        config.save();

        return config;
    }

    /**
     * 保存配置文件
     */
    public void save() {
        try (FileWriter writer = new FileWriter(CONFIG_PATH.toFile())) {
            GSON.toJson(this, writer);
        } catch (IOException e) {
            System.err.println("Failed to save config: " + e.getMessage());
        }
    }

    /**
     * 重新加载配置
     * <p>
     * 作用：刷新 GLOBAL_MAX
     */
    public void reload() {
        load(); // 重新加载 JSON 配置
    }
}
