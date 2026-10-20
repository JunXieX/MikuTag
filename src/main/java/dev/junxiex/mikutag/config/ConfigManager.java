package dev.junxiex.mikutag.config;

import dev.junxiex.mikutag.MikuTagPlugin;
import dev.junxiex.mikutag.model.Requirement;
import dev.junxiex.mikutag.model.Title;
import dev.junxiex.mikutag.storage.DatabaseType;
import dev.junxiex.mikutag.util.Text;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
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
 * <p>
 * 所有文本都经 {@link Text} 解析，因此 MiniMessage 与 {@code &}/{@code §} 旧式颜色码均可使用。
 */
public final class ConfigManager {

    /**
     * 预编译：{@link #plain(Component)} 在渲染中高频调用。
     * 负向后行断言排除 MiniMessage 转义后的字面 “&lt;”，否则 “a &lt; b” 后面紧跟颜色标签时会被整段吃掉。
     */
    private static final Pattern TAG_PATTERN = Pattern.compile("(?<!\\\\)<[^>]+>");

    /** 纯文本渲染复用同一 MiniMessage 实例，避免每次调用重复获取。 */
    private static final MiniMessage PLAIN_MM = MiniMessage.miniMessage();

    private static final int PAGE_SIZE_MIN = 1;
    private static final int PAGE_SIZE_MAX = 20;

    private final MikuTagPlugin plugin;

    /**
     * 消息快照：原始模板与预渲染常量打包为一个不可变对象整体替换。
     * <p>
     * 二者必须同源读取——若拆成两个 volatile 字段分别写入，重载瞬间可能出现
     * "新模板 + 旧预渲染常量"的撕裂，导致同一条消息时新时旧。
     */
    private record Messages(Map<String, String> raw, Map<String, Component> constants) {
        static final Messages EMPTY = new Messages(Map.of(), Map.of());
    }

    private volatile Messages messages = Messages.EMPTY;

    private volatile int pageSize = 8;
    private volatile int clickThrottleMillis = 200;
    private volatile int flushIntervalSeconds = 30;
    private volatile int titleSyncIntervalSeconds = 10;
    /** 未佩戴称号时的占位符返回值，按三种形态预渲染（配置在重载时一次性解析，避免高频求值时重复解析）。 */
    private volatile String placeholderEmptyLegacy = "";
    private volatile String placeholderEmptyStripped = "";
    private volatile String placeholderEmptyMiniMessage = "";
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

    /** 是否已经加载过一次（首次加载时切换开关不需要提示重启）。 */
    private boolean loadedBefore;

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

        // 必须在任何 Text.parse 之前生效：display 前后缀与 messages 都在本方法内预渲染
        boolean legacyColors = config.getBoolean("text.legacy-colors", true);
        if (Text.legacyColorsEnabled() != legacyColors) {
            if (loadedBefore) {
                // 称号的展示 Component 在快照重建时生成；库内容未变化时 reload 不会重建快照，
                // 因此切换开关后需要重启才能让已加载的称号按新设置重新渲染。
                plugin.getLogger().warning("config.yml 的 text.legacy-colors 已变更，"
                        + "已加载称号的展示内容需重启服务端后才会按新设置重新渲染。");
            }
            Text.setLegacyColorsEnabled(legacyColors);
        }
        loadedBefore = true;

        int size = config.getInt("menu.page-size", 8);
        if (size < PAGE_SIZE_MIN || size > PAGE_SIZE_MAX) {
            plugin.getLogger().warning("config.yml 的 menu.page-size=" + size + " 超出允许范围 "
                    + PAGE_SIZE_MIN + "-" + PAGE_SIZE_MAX + "，已自动钳制");
        }
        pageSize = Math.max(PAGE_SIZE_MIN, Math.min(PAGE_SIZE_MAX, size));
        // 上限 10 秒：误填成很大的值（如 20000）会让菜单几乎完全点不动，且极难自查
        clickThrottleMillis = Math.max(0, Math.min(10_000, config.getInt("menu.click-throttle-ms", 200)));
        flushIntervalSeconds = Math.max(5, config.getInt("storage.flush-interval-seconds", 30));
        titleSyncIntervalSeconds = Math.max(2, config.getInt("sync.title-interval-seconds", 10));
        String placeholderEmptyCfg = config.getString("placeholder.empty", "");
        Component emptyComponent = Text.parse(placeholderEmptyCfg);
        placeholderEmptyLegacy = LegacyComponentSerializer.legacySection().serialize(emptyComponent);
        placeholderEmptyStripped = plain(emptyComponent);
        placeholderEmptyMiniMessage = Text.toMiniMessage(placeholderEmptyCfg);
        autoEquip = config.getBoolean("behavior.auto-equip", false);

        String prefixCfg = config.getString("display.prefix", "");
        String suffixCfg = config.getString("display.suffix", "");
        display = new Display(prefixCfg.isEmpty() ? Component.empty() : Text.parse(prefixCfg),
                suffixCfg.isEmpty() ? Component.empty() : Text.parse(suffixCfg),
                !prefixCfg.isEmpty() || !suffixCfg.isEmpty(),
                display.generation() + 1);

        String rawType = config.getString("storage.type", "sqlite");
        storageType = DatabaseType.parse(rawType);
        if (rawType == null || !storageType.id().equalsIgnoreCase(rawType.trim())) {
            // 拼写错误（如 postgres/oracle）会被静默当作 sqlite，导致数据写进本地文件而非远端库，
            // 这类问题极难自查，故必须显式告警。
            plugin.getLogger().warning("config.yml 的 storage.type=\"" + rawType
                    + "\" 无法识别（支持 sqlite / mysql / mariadb），已回退为 sqlite。");
        }
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
        // 先建出快照再预渲染：预渲染时的 <prefix> 展开必须与最终快照使用同一份 prefix
        Messages snapshot = new Messages(Map.copyOf(newMessages), Map.of());
        Map<String, Component> constants = new HashMap<>();
        for (Map.Entry<String, String> e : newMessages.entrySet()) {
            if (e.getValue().indexOf('{') < 0) {
                constants.put(e.getKey(), Text.parse(applyPrefix(e.getValue(), snapshot)));
            }
        }
        // 模板与预渲染常量一次性整体替换，避免重载瞬间读到跨版本的组合
        messages = new Messages(snapshot.raw(), Map.copyOf(constants));
    }

    /** 取 MiniMessage 消息并渲染为 Component。 */
    public Component message(String key, Map<String, String> placeholders) {
        Component constant = messages.constants().get(key);
        if (constant != null) {
            return constant;
        }
        return Text.parse(raw(key, placeholders));
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
        Component head = Text.parse(raw.substring(0, idx));
        Component tail = Text.parse(raw.substring(idx + token.length()));
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
        // MiniMessage 序列化会把字面 < 转义成 \<；剥标签时不能把它当标签起点，剥完再还原转义
        return TAG_PATTERN.matcher(PLAIN_MM.serialize(component))
                .replaceAll("")
                .replace("\\<", "<")
                .replace("\\\\", "\\");
    }

    private String raw(String key, Map<String, String> placeholders) {
        // 整段取同一快照：模板与 prefix 必须来自同一次 loadAll，避免重载途中混用新旧文本
        Messages snap = messages;
        String raw = snap.raw().get(key);
        if (raw == null) {
            raw = snap.raw().getOrDefault("error.missing-message", "<red>Missing message: {key}");
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
        return applyPrefix(raw, snap);
    }

    /** 展开 {@code <prefix>} 标签；prefix 取自与模板同一份消息快照。 */
    private static String applyPrefix(String raw, Messages snap) {
        if (raw.indexOf("<prefix>") < 0) {
            return raw;
        }
        return raw.replace("<prefix>", snap.raw().getOrDefault("prefix", ""));
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

    /** 未佩戴称号时 {@code %mikutag_title%} 的返回值（旧式 § 颜色码形态）。 */
    public String placeholderEmptyLegacy() {
        return placeholderEmptyLegacy;
    }

    /** 未佩戴称号时 {@code %mikutag_title_strip%} 的返回值（纯文本）。 */
    public String placeholderEmptyStripped() {
        return placeholderEmptyStripped;
    }

    /** 未佩戴称号时 {@code %mikutag_title_raw%} 的返回值（规范化 MiniMessage）。 */
    public String placeholderEmptyMiniMessage() {
        return placeholderEmptyMiniMessage;
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
