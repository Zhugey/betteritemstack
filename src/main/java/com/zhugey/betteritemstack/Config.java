package com.zhugey.betteritemstack;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 配置管理类。
 *
 * <p>主要职责：
 * <ol>
 *   <li>读写 {@code config/betteritemstack.json}</li>
 *   <li>对外提供全局最大堆叠数 {@link #GLOBAL_MAX}</li>
 *   <li>提供不参与提升的物品黑名单</li>
 *   <li>提供"哪些容器享受提升"的容器策略（见 {@link Containers}）</li>
 * </ol>
 *
 * <p>注意：本类不再持有"物品原版上限"数据表。物品自己的原版上限可以直接从
 * 物品堆叠的组件中读到（见 {@link VanillaMax}），因此无需外部 JSON 表，
 * 并且对模组物品同样有效。
 */
public class Config {

    /** 缺省全局最大堆叠数。 */
    public static final int DEFAULT_GLOBAL_MAX = 9999;

    /** 当前配置结构版本，见 {@link #configVersion}。 */
    public static final int CURRENT_CONFIG_VERSION = 2;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private static final Path CONFIG_PATH = FabricLoader.getInstance().getConfigDir()
            .resolve("betteritemstack.json");

    /** 全局最大堆叠数。由 {@link #load()} 与 {@link #setGlobalMax(int)} 刷新。 */
    public static volatile int GLOBAL_MAX = DEFAULT_GLOBAL_MAX;

    /** 当前生效的配置实例。初始为默认值，保证混入代码在配置加载前也能安全读取。 */
    private static volatile Config INSTANCE = new Config();

    // ---- 持久化字段 ----

    /** 配置结构版本，每次加载都会刷新为 {@link #CURRENT_CONFIG_VERSION}，供将来做迁移。 */
    public int configVersion = CURRENT_CONFIG_VERSION;

    /** 全局最大堆叠数。 */
    public int global_max = DEFAULT_GLOBAL_MAX;

    /** 不参与提升的物品 ID 列表（原版命名空间形式，如 {@code minecraft:shulker_box}）。 */
    public List<String> nonStackableItems = new ArrayList<>();

    /** 容器提升策略。 */
    public Containers containers = new Containers();

    /**
     * 容器提升策略。
     *
     * <ul>
     *   <li>{@code mode = "blacklist"}（默认）：除 {@link #list} 中列出的容器外，全部提升。</li>
     *   <li>{@code mode = "whitelist"}：只提升 {@link #list} 中列出的容器。</li>
     * </ul>
     *
     * <p>{@link #unknown} 仅在 blacklist 模式下有意义，表示未识别的容器（例如其它模组
     * 新增的容器、以及装饰罐这类原版单格容器）是否也算作提升对象。
     *
     * <p><b>{@link #unknown} 默认为 false（不提升）</b>，这是刻意的保守取值：
     * 未识别的容器往往带有自己的容量语义（例如只接受 1 件的输入槽），一旦被提升就会破坏
     * 它们的判定。需要为其它模组的存储容器也开启提升时，再改为 true。
     *
     * <p>合法的容器键名见 {@link ContainerPolicy#SUPPORTED_KEYS}。
     */
    public static class Containers {

        public String mode = "blacklist";

        public List<String> list = new ArrayList<>(List.of("hopper", "hopper_minecart"));

        /** 未识别的容器是否也提升。默认 false：只提升能明确识别的容器。 */
        public boolean unknown = false;

        public Containers() {
        }

        public boolean isWhitelist() {
            return "whitelist".equalsIgnoreCase(mode);
        }

        private void normalize() {
            if (mode == null || (!isWhitelist() && !"blacklist".equalsIgnoreCase(mode))) {
                BetterItemStack.LOGGER.warn("Unknown containers.mode, falling back to \"blacklist\"");
                mode = "blacklist";
            }
            if (list == null) {
                list = new ArrayList<>();
            }
            List<String> unknownKeys = new ArrayList<>();
            for (String key : list) {
                if (key == null || !ContainerPolicy.SUPPORTED_KEYS.contains(key)) {
                    unknownKeys.add(String.valueOf(key));
                }
            }
            if (!unknownKeys.isEmpty()) {
                BetterItemStack.LOGGER.warn("Unrecognized key(s) in containers.list: {} (available: {})",
                        unknownKeys, ContainerPolicy.SUPPORTED_KEYS);
            }
        }
    }

    // ---- 对外入口 ----

    /** @return 当前生效的配置实例（永不为 null）。 */
    public static Config get() {
        return INSTANCE;
    }

    /**
     * 从磁盘读取配置；文件不存在或字段缺失时补全默认值并写回。
     *
     * <p>读取失败（JSON 损坏等）时不会中断启动，而是退回默认值并给出日志。
     *
     * @return 本次生效的配置实例
     */
    public static synchronized Config load() {
        Config config = new Config();

        if (Files.exists(CONFIG_PATH)) {
            try (Reader reader = Files.newBufferedReader(CONFIG_PATH, StandardCharsets.UTF_8)) {
                Config read = GSON.fromJson(reader, Config.class);
                if (read != null) {
                    config = read;
                }
            } catch (Exception e) {
                BetterItemStack.LOGGER.error("Failed to read config, using defaults: {}", e.toString());
            }
        }

        config.normalize();
        config.save();

        INSTANCE = config;
        GLOBAL_MAX = config.global_max;
        return config;
    }

    /** 重新从磁盘加载配置。 */
    public static void reload() {
        load();
    }

    /**
     * 修改全局最大堆叠数：先更新内存，再写回磁盘，最后刷新 {@link #GLOBAL_MAX}。
     *
     * <p>此方法取代了旧实现中"先改内存字段、再 reload（从磁盘读回旧值）"的顺序，
     * 旧顺序会导致修改值被磁盘内容覆盖，命令看似成功但实际不生效。
     *
     * @param value 新的上限，必须 &gt;= 1
     * @return 生效后的上限
     */
    public int setGlobalMax(int value) {
        if (value < 1) {
            throw new IllegalArgumentException("global_max 必须 >= 1，实际为 " + value);
        }
        this.global_max = value;
        this.save();
        INSTANCE = this;
        GLOBAL_MAX = value;
        return GLOBAL_MAX;
    }

    /** 写回磁盘。 */
    public void save() {
        try {
            Path parent = CONFIG_PATH.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            try (Writer writer = Files.newBufferedWriter(CONFIG_PATH, StandardCharsets.UTF_8)) {
                GSON.toJson(this, writer);
            }
        } catch (Exception e) {
            BetterItemStack.LOGGER.error("Failed to save config: {}", e.toString());
        }
    }

    private void normalize() {
        // 写回当前版本号：该字段用于将来做配置迁移，每次加载都刷新到最新版本。
        configVersion = CURRENT_CONFIG_VERSION;

        if (global_max < 1) {
            BetterItemStack.LOGGER.warn("global_max = {} is invalid, falling back to {}",
                    global_max, DEFAULT_GLOBAL_MAX);
            global_max = DEFAULT_GLOBAL_MAX;
        }
        if (nonStackableItems == null) {
            nonStackableItems = new ArrayList<>();
        }
        if (containers == null) {
            containers = new Containers();
        }
        containers.normalize();
    }
}
