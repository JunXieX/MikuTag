package dev.junxiex.mikutag.config;

import dev.junxiex.mikutag.MikuTagPlugin;
import dev.junxiex.mikutag.model.Requirement;
import dev.junxiex.mikutag.model.Title;
import dev.junxiex.mikutag.storage.DatabaseType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 加载 config.yml 与 messages.yml。
 * <p>
 * 消息以不可变快照整体替换（volatile），读取线程始终看到一致状态；
 * 不含占位符的模板在重载时预渲染为 Component，菜单渲染直接复用，避免重复解析 MiniMessage。
 */
public final class ConfigManager {

    /** 预编译：{@link #plain(Component)} 在渲染中高频调用。 */
    private static final Pattern TAG_PATTERN = Pattern.compile("<[^>]+>");

    /** 纯文本渲染复用同一 MiniMessage 实例，避免每次调用重复获取。 */
    private static final MiniMessage PLAIN_MM = MiniMessage.miniMessage();

    private static final int PAGE_SIZE_MIN = 1;
    private static final int PAGE_SIZE_MAX = 20;

    private final MikuTagPlugin plugin;
    private final MiniMessage mm = MiniMessage.miniMessage();

    private volatile Map<String, String> messages = Map.of();
    private volatile Map<String, Component> constantMessages = Map.of();

    private volatile int pageSize = 8;
    private volatile int clickThrottleMillis = 200;
    private volatile int flushIntervalSeconds = 30;
    private volatile int titleSyncIntervalSeconds = 10;
    private volatile String placeholderEmpty = "";
    private volatile boolean autoEquip = false;

    /** 显示装饰快照：前后缀 + 是否有装饰 + 代号，整体原子替换，供渲染与缓存失效同源读取。 */
    public record Display(Component prefix, Component suffix, boolean hasAffix, long generation) {
        /** 称号展示名：称号名 + 显示前后缀（无装饰时直接复用预解析的名称 Component）。 */
        public Component displayedName(Title title) {
            if (!hasAffix) {
                return title.displayName();
            }
            return Component.join(JoinConfiguration.noSeparators(), prefix, title.displayName(), suffix);
        }
    }

    private static final Display DISPLAY_NONE = new Display(Component.empty(), Component.empty(), false, 0);

    /** 仅在展示时拼接到称号名两侧，不写入称号定义本身（与称号数据解耦）。 */
    private volatile Display display = DISPLAY_NONE;

    private volatile DatabaseType storageType = DatabaseType.SQLITE;
    private volatile String dbHost = "127.0.0.1";
    private volatile int dbPort = 3306;
    private volatile String dbName = "mikutag";
    private volatile String dbUser = "root";
    private volatile String dbPassword = "";
    private volatile String dbParameters = "";

    public ConfigManager(MikuTagPlugin plugin) {
        this.plugin = plugin;
    }

    /** 加载全部配置。 */
    public void loadAll() {
        saveDefault("config.yml");
        saveDefault("messages.yml");

        YamlConfiguration config = YamlConfiguration.loadConfiguration(
                new File(plugin.getDataFolder(), "config.yml"));

        int size = config.getInt("menu.page-size", 8);
        if (size < PAGE_SIZE_MIN || size > PAGE_SIZE_MAX) {
            plugin.getLogger().warning("config.yml 的 menu.page-size=" + size + " 超出允许范围 "
                    + PAGE_SIZE_MIN + "-" + PAGE_SIZE_MAX + "，已自动钳制");
        }
        pageSize = Math.max(PAGE_SIZE_MIN, Math.min(PAGE_SIZE_MAX, size));
        clickThrottleMillis = Math.max(0, config.getInt("menu.click-throttle-ms", 200));
        flushIntervalSeconds = Math.max(5, config.getInt("storage.flush-interval-seconds", 30));
        titleSyncIntervalSeconds = Math.max(2, config.getInt("sync.title-interval-seconds", 10));
        placeholderEmpty = config.getString("placeholder.empty", "");
        autoEquip = config.getBoolean("behavior.auto-equip", false);

        String prefixCfg = config.getString("display.prefix", "");
        String suffixCfg = config.getString("display.suffix", "");
        display = new Display(prefixCfg.isEmpty() ? Component.empty() : mm.deserialize(prefixCfg),
                suffixCfg.isEmpty() ? Component.empty() : mm.deserialize(suffixCfg),
                !prefixCfg.isEmpty() || !suffixCfg.isEmpty(),
                display.generation() + 1);

        storageType = DatabaseType.parse(config.getString("storage.type", "sqlite"));
        String section = storageType.isRemote() ? "storage." + storageType.id() + "." : "";
        if (storageType.isRemote()) {
            dbHost = config.getString(section + "host", "127.0.0.1");
            dbPort = config.getInt(section + "port", 3306);
            dbName = config.getString(section + "database", "mikutag");
            dbUser = config.getString(section + "user", "root");
            dbPassword = config.getString(section + "password", "");
            dbParameters = config.getString(section + "parameters", "");
        }

        // 以 JAR 内置的 messages.yml 作为默认值：磁盘上缺失的键（例如插件升级后新增的键）
        // 自动回退到内置文本，无需用户删除 messages.yml 重新生成；磁盘上已有的键优先。
        YamlConfiguration msg = YamlConfiguration.loadConfiguration(
                new File(plugin.getDataFolder(), "messages.yml"));
        YamlConfiguration defaults = loadBundledMessages();
        if (defaults != null) {
            msg.setDefaults(defaults);
        }

        Set<String> keys = new LinkedHashSet<>();
        if (defaults != null) {
            keys.addAll(defaults.getKeys(true));
        }
        keys.addAll(msg.getKeys(true));

        Map<String, String> newMessages = new HashMap<>();
        for (String key : keys) {
            if (msg.isConfigurationSection(key)
                    || (defaults != null && defaults.isConfigurationSection(key))) {
                continue;
            }
            String value = msg.getString(key);
            if (value != null) {
                newMessages.put(key, value);
            }
        }
        messages = Map.copyOf(newMessages);

        Map<String, Component> constants = new HashMap<>();
        for (Map.Entry<String, String> e : newMessages.entrySet()) {
            if (e.getValue().indexOf('{') < 0) {
                constants.put(e.getKey(), mm.deserialize(applyPrefix(e.getValue())));
            }
        }
        constantMessages = Map.copyOf(constants);
    }

    /** 取 MiniMessage 消息并渲染为 Component。 */
    public Component message(String key, Map<String, String> placeholders) {
        Component constant = constantMessages.get(key);
        if (constant != null) {
            return constant;
        }
        return mm.deserialize(raw(key, placeholders));
    }

    public Component message(String key) {
        return message(key, null);
    }

    /**
     * 把消息模板中的 {componentKey} 直接替换为给定 Component，
     * 避免"渲染成 Component → 序列化回字符串 → 再反序列化"的往返损耗。
     * <p>
     * 注意：token 前后的模板会被分别解析为两个 MiniMessage 片段，
     * 因此不要在 token 后面紧跟闭合标签（如 {@code <white>{key}</white>}），
     * 否则孤立的闭合标签会原样显示；用开启标签（{@code <white>{key}<gray>…}）切换样式即可。
     */
    public Component message(String key, Map<String, String> placeholders,
                             String componentKey, Component component) {
        String raw = raw(key, placeholders);
        String token = "{" + componentKey + "}";
        int idx = raw.indexOf(token);
        if (idx < 0) {
            return message(key, placeholders);
        }
        Component head = mm.deserialize(raw.substring(0, idx));
        Component tail = mm.deserialize(raw.substring(idx + token.length()));
        return Component.join(JoinConfiguration.noSeparators(), head, component, tail);
    }

    /** 将毫秒时长渲染为可配置模板的 Component。 */
    public Component duration(long millis) {
        if (millis <= 0) {
            return message("time.zero");
        }
        long s = millis / 1000;
        long days = s / 86400;
        long hours = (s % 86400) / 3600;
        long minutes = (s % 3600) / 60;
        long seconds = s % 60;
        List<Component> parts = new ArrayList<>();
        if (days > 0) {
            parts.add(message("time.day", Map.of("value", String.valueOf(days))));
        }
        if (hours > 0) {
            parts.add(message("time.hour", Map.of("value", String.valueOf(hours))));
        }
        if (minutes > 0) {
            parts.add(message("time.minute", Map.of("value", String.valueOf(minutes))));
        }
        if (seconds > 0 || parts.isEmpty()) {
            parts.add(message("time.second", Map.of("value", String.valueOf(seconds))));
        }
        return Component.join(JoinConfiguration.noSeparators(), parts);
    }

    /** 称号有效期展示：永久 / N 天。 */
    public Component durationLabel(int days) {
        if (days <= 0) {
            return message("title.duration-permanent");
        }
        return message("title.duration-days", Map.of("days", String.valueOf(days)));
    }

    /** 称号展示名：称号名 + 配置的显示前后缀（前后缀为空时直接复用预解析的名称 Component）。 */
    public Component displayedName(Title title) {
        return display.displayedName(title);
    }

    /** 当前显示装饰快照（渲染缓存需成对读取前后缀与代号时使用）。 */
    public Display currentDisplay() {
        return display;
    }

    /** 称号的获取条件展示行：空条件 = 免费领取；每个条件一行。 */
    public List<Component> requirementLines(Title title) {
        if (title.requirements().isEmpty()) {
            return List.of(message("detail.free"));
        }
        List<Component> lines = new ArrayList<>(title.requirements().size());
        for (Requirement r : title.requirements()) {
            lines.add(requirementLine(r));
        }
        return lines;
    }

    /** 单个购买条件的展示行。 */
    private Component requirementLine(Requirement r) {
        return switch (r.type()) {
            case VAULT -> message("detail.price", Map.of("price", plugin.economyHook().format(r.amount())));
            case PERMISSION -> message("detail.permission", Map.of("permission", r.permission()));
        };
    }

    /** 多条件的单行摘要（以 " + " 连接），用于 tooltip 与指令反馈。 */
    public Component requirementsSummary(Title title) {
        List<Component> lines = requirementLines(title);
        if (lines.size() == 1) {
            return lines.get(0);
        }
        List<Component> joined = new ArrayList<>(lines.size() * 2 - 1);
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) {
                joined.add(Component.text(" + "));
            }
            joined.add(lines.get(i));
        }
        return Component.join(JoinConfiguration.noSeparators(), joined);
    }

    /** 将 Component 渲染为纯文本（剥离样式），用于占位符与配置解析阶段。 */
    public static String plain(Component component) {
        return TAG_PATTERN.matcher(PLAIN_MM.serialize(component)).replaceAll("");
    }

    private String raw(String key, Map<String, String> placeholders) {
        String raw = messages.get(key);
        if (raw == null) {
            raw = messages.getOrDefault("error.missing-message", "<red>Missing message: {key}");
            Map<String, String> merged = new HashMap<>();
            if (placeholders != null) {
                merged.putAll(placeholders);
            }
            merged.put("key", key);
            placeholders = merged;
        }
        if (placeholders != null) {
            for (Map.Entry<String, String> e : placeholders.entrySet()) {
                raw = raw.replace("{" + e.getKey() + "}", e.getValue());
            }
        }
        return applyPrefix(raw);
    }

    private String applyPrefix(String raw) {
        if (raw.indexOf("<prefix>") < 0) {
            return raw;
        }
        return raw.replace("<prefix>", messages.getOrDefault("prefix", ""));
    }

    private void saveDefault(String name) {
        File f = new File(plugin.getDataFolder(), name);
        if (!f.exists()) {
            plugin.saveResource(name, false);
        }
    }

    /** 读取 JAR 内置的 messages.yml 作为默认值来源；读取失败返回 null（不影响已有键生效）。 */
    private YamlConfiguration loadBundledMessages() {
        try (InputStream in = plugin.getResource("messages.yml")) {
            if (in == null) {
                return null;
            }
            return YamlConfiguration.loadConfiguration(
                    new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (IOException ex) {
            plugin.getLogger().warning("读取内置 messages.yml 失败: " + ex.getMessage());
            return null;
        }
    }

    public int pageSize() {
        return pageSize;
    }

    public int clickThrottleMillis() {
        return clickThrottleMillis;
    }

    public int flushIntervalSeconds() {
        return flushIntervalSeconds;
    }

    public int titleSyncIntervalSeconds() {
        return titleSyncIntervalSeconds;
    }

    public String placeholderEmpty() {
        return placeholderEmpty;
    }

    /** 获得新称号时是否自动佩戴（当前未佩戴才生效）。 */
    public boolean autoEquip() {
        return autoEquip;
    }

    public DatabaseType storageType() {
        return storageType;
    }

    public String dbHost() {
        return dbHost;
    }

    public int dbPort() {
        return dbPort;
    }

    public String dbName() {
        return dbName;
    }

    public String dbUser() {
        return dbUser;
    }

    public String dbPassword() {
        return dbPassword;
    }

    public String dbParameters() {
        return dbParameters;
    }

    public String dbSummary() {
        return storageType.isRemote()
                ? storageType.id() + "://" + dbHost + ":" + dbPort + "/" + dbName
                : "sqlite";
    }
}
