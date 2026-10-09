package dev.junxiex.mikutag.migration;

import dev.junxiex.mikutag.MikuTagPlugin;
import dev.junxiex.mikutag.model.Requirement;
import dev.junxiex.mikutag.model.Title;
import dev.junxiex.mikutag.storage.Storage;
import dev.junxiex.mikutag.storage.WriteOp;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 从 PlayerTitle 迁移称号数据到 MikuTag（一次性操作，可重复执行且幂等）。
 * <p>
 * 数据源自动探测：读取 {@code plugins/PlayerTitle/storage.yml}，
 * SQLite 直连其数据文件，MySQL 按其参数临时建连接（要求 MikuTag 自身的
 * storage.type 加载了对应 JDBC 驱动，否则给出明确错误）。
 * <p>
 * 映射规则：
 * <ul>
 *   <li>称号 ID 取 {@code pt_<PlayerTitle自增id>}，重复导入映射到同一 ID，天然幂等；</li>
 *   <li>名称/描述中的 {@code &} 旧颜色码转换为 MiniMessage 标签；</li>
 *   <li>获取条件读 {@code title_require}（无行时回退 {@code title_list} 的废弃旧列）：
 *       vault→VAULT、permission→PERMISSION（节点在 buy_data）、not→免费、
 *       activity→免费且不进商城；MikuTag 不支持的类型（coin/playerPoints/itemStack/
 *       player_currency）丢弃该条件并警告；</li>
 *   <li>玩家持有读 {@code title_player}：expiration_time 在 SQLite 下为 epoch 毫秒、
 *       在 MySQL/MariaDB 下为真正的 DATETIME，统一解析为毫秒（见 {@link #toEpochMillis}）；
 *       PlayerTitle 的 9999 年"永久"映射为 0；is_use=1 映射为佩戴；</li>
 *   <li>幂等合并：已存在的称号定义与玩家持有记录不覆盖，在线玩家记录跳过
 *       （其数据在缓存中，直接写库会被写回队列覆盖）。</li>
 * </ul>
 * 所有方法均为阻塞 IO，必须在异步线程调用。
 */
public final class PlayerTitleImporter {

    /**
     * "永久"判定阈值。PlayerTitle 用 9999-12-31 表示永久，但该值经 DATETIME 与时区往返后
     * 可能有偏移，故不按精确等值判定，而是取年份 3000 作为阈值——超过即视为永久。
     */
    private static final long PERMANENT_THRESHOLD = 32_503_680_000_000L; // 3000-01-01T00:00:00Z

    /** PlayerTitle 在 MySQL/MariaDB 下 DATETIME 的文本形态。 */
    private static final DateTimeFormatter DATETIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private PlayerTitleImporter() {
    }

    /** 导入失败（配置/数据源问题），消息面向指令发送者。 */
    public static final class ImportException extends Exception {

        private static final long serialVersionUID = 1L;

        public ImportException(String message) {
            super(message);
        }
    }

    public record Result(int titlesImported, int titlesSkipped, int grantsImported,
                         int grantsSkipped, List<String> warnings) {
    }

    /**
     * 执行导入。阻塞 IO，必须在异步线程调用。
     * 在线玩家（数据已在 {@code PlayerTagStore} 缓存中）的持有记录会被跳过——
     * 直接写库会被其缓存的写回队列覆盖；离线后重新执行导入即可补齐。
     */
    public static Result run(MikuTagPlugin plugin) throws ImportException {
        File ptFolder = new File(plugin.getDataFolder().getParentFile(), "PlayerTitle");
        File storageCfg = new File(ptFolder, "storage.yml");
        if (!storageCfg.isFile()) {
            throw new ImportException("未找到 plugins/PlayerTitle/storage.yml，请确认 PlayerTitle 已安装并启动过。");
        }
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(storageCfg);
        String method = cfg.getString("storage-method", "SQLite").trim();

        Connection source;
        if ("SQLite".equalsIgnoreCase(method)) {
            File db = new File(ptFolder, "PlayerTitle.db");
            if (!db.isFile()) {
                throw new ImportException("未找到 PlayerTitle 的 SQLite 数据文件 " + db.getPath());
            }
            source = openSqlite(db, plugin);
        } else if ("MySQL".equalsIgnoreCase(method)) {
            source = openMysql(cfg, plugin);
        } else {
            throw new ImportException("无法识别的 PlayerTitle storage-method：" + method);
        }

        try {
            return doImport(plugin, source);
        } catch (SQLException e) {
            throw new ImportException("读取 PlayerTitle 数据失败：" + e.getMessage());
        } finally {
            try {
                source.close();
            } catch (SQLException ignored) {
            }
        }
    }

    /**
     * SQLite 驱动按 MikuTag 自身的 storage.type 由 MikuTagLoader 注入 classpath；
     * PlayerTitle 自带驱动在其插件 classloader 中，本插件不可见，故跨类型时明确报错。
     */
    private static Connection openSqlite(File db, MikuTagPlugin plugin) throws ImportException {
        loadDriver("org.sqlite.JDBC", plugin, "SQLite");
        try {
            return DriverManager.getConnection("jdbc:sqlite:" + db.getAbsolutePath());
        } catch (SQLException e) {
            throw new ImportException("连接 PlayerTitle 的 SQLite 库失败：" + e.getMessage());
        }
    }

    private static Connection openMysql(YamlConfiguration cfg, MikuTagPlugin plugin) throws ImportException {
        loadDriver("com.mysql.cj.jdbc.Driver", plugin, "MySQL");
        String host = cfg.getString("MySQL.Host", "127.0.0.1");
        int port = cfg.getInt("MySQL.Port", 3306);
        String database = cfg.getString("MySQL.Database", "");
        String user = cfg.getString("MySQL.User", "root");
        String password = cfg.getString("MySQL.Password", "");
        String param = cfg.getString("MySQL.param", "");
        StringBuilder url = new StringBuilder("jdbc:mysql://").append(host).append(':').append(port)
                .append('/').append(database);
        // PlayerTitle 示例值形如 "&allowPublicKeyRetrieval=true"（自带前导 &），统一剥掉前导 ?/&
        String query = param.trim();
        while (query.startsWith("?") || query.startsWith("&")) {
            query = query.substring(1);
        }
        if (!query.isBlank()) {
            url.append('?').append(query);
        }
        if (cfg.getBoolean("MySQL.UseSSL", false)) {
            url.append(url.indexOf("?") >= 0 ? "&" : "?").append("useSSL=true");
        }
        try {
            return DriverManager.getConnection(url.toString(), user, password);
        } catch (SQLException e) {
            throw new ImportException("连接 PlayerTitle 的 MySQL 库失败：" + e.getMessage());
        }
    }

    private static void loadDriver(String className, MikuTagPlugin plugin, String label)
            throws ImportException {
        try {
            Class.forName(className, true, plugin.getClass().getClassLoader());
        } catch (ClassNotFoundException e) {
            throw new ImportException("当前 classpath 缺少 " + label + " JDBC 驱动。"
                    + "请把 MikuTag config.yml 的 storage.type 临时改为 " + label.toLowerCase(Locale.ROOT)
                    + " 并重启服务器后再执行导入（导入完成后可以改回）。");
        }
    }

    private static Result doImport(MikuTagPlugin plugin, Connection source) throws SQLException {
        List<String> warnings = new ArrayList<>();
        Storage storage = plugin.storage();

        // ---- 1. 称号定义 ----
        Map<Integer, String> idMapping = new LinkedHashMap<>(); // PlayerTitle int id -> MikuTag id
        Map<Integer, List<Requirement>> reqsByTitle = new HashMap<>();
        Map<Integer, Boolean> activityByTitle = new HashMap<>();
        loadRequirements(source, reqsByTitle, activityByTitle, warnings);

        int titlesImported = 0;
        int titlesSkipped = 0;
        List<Title> toSave = new ArrayList<>();
        try (PreparedStatement ps = source.prepareStatement(
                "SELECT id, title_name, description, day, is_hide, position, buy_type, amount"
                        + " FROM title_list ORDER BY id");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                int ptId = rs.getInt("id");
                String mikuId = "pt_" + ptId;
                idMapping.put(ptId, mikuId);
                if (plugin.titleManager().get(mikuId) != null) {
                    titlesSkipped++; // 幂等：已导入过（或 ID 冲突）不覆盖
                    continue;
                }
                String name = convertLegacyColor(rs.getString("title_name"));
                String description = convertLegacyColor(rs.getString("description"));
                List<Requirement> reqs = reqsByTitle.getOrDefault(ptId, List.of());
                boolean activity = Boolean.TRUE.equals(activityByTitle.get(ptId));
                if (reqs.isEmpty() && !activity) {
                    // 无 require 行：回退旧单类型列
                    reqs = legacyFallbackRequirements(rs.getString("buy_type"), rs.getInt("amount"),
                            name, warnings);
                    activity = "activity".equalsIgnoreCase(rs.getString("buy_type"));
                }
                boolean sellable = rs.getInt("is_hide") == 0 && !activity;
                toSave.add(new Title(mikuId, name.isEmpty() ? mikuId : name,
                        Title.splitDescription(description), Material.NAME_TAG,
                        reqs, rs.getInt("day"), sellable, rs.getInt("position"),
                        System.currentTimeMillis()));
            }
        }
        for (Title title : toSave) {
            storage.upsertTitle(title);
            titlesImported++;
        }
        // 快照同步更新，使后续玩家数据校验与菜单立即可见新称号
        plugin.titleManager().reloadFromStorage();

        // ---- 2. 玩家持有与佩戴 ----
        // 在线判定用 tagStore 缓存命中（进服放入、退服移除）：缓存命中意味着该玩家
        // 的数据正由写回队列管理，直接写库会被覆盖，故跳过；离线玩家无写回风险，可安全写。
        Storage.PlayerSnapshot snapshot = storage.loadPlayerSnapshot();
        Map<UUID, Map<String, Long>> existingOwned = snapshot.owned();
        Map<UUID, String> existingEquipped = snapshot.equipped();

        List<WriteOp> ops = new ArrayList<>();
        Map<UUID, String> pendingEquip = new HashMap<>();
        Set<UUID> onlineSkipped = new HashSet<>();
        int grantsImported = 0;
        int grantsSkipped = 0;
        try (PreparedStatement ps = source.prepareStatement(
                "SELECT player_uuid, title_id, expiration_time, is_use FROM title_player");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String uuidRaw = rs.getString("player_uuid");
                int ptId = rs.getInt("title_id");
                String mikuId = idMapping.get(ptId);
                if (uuidRaw == null || mikuId == null) {
                    grantsSkipped++;
                    continue;
                }
                UUID uuid;
                try {
                    uuid = UUID.fromString(uuidRaw);
                } catch (IllegalArgumentException ex) {
                    grantsSkipped++;
                    continue;
                }
                if (plugin.tagStore().get(uuid) != null) {
                    onlineSkipped.add(uuid);
                    grantsSkipped++;
                    continue;
                }
                long expires = normalizeExpiry(readExpiry(rs, "expiration_time"));
                Map<String, Long> owned = existingOwned.get(uuid);
                if (owned != null && owned.containsKey(mikuId)) {
                    grantsSkipped++; // 幂等：已有记录不覆盖（可能已在 MikuTag 续费）
                    continue;
                }
                ops.add(new WriteOp.Grant(uuid, mikuId, expires));
                grantsImported++;
                if (rs.getInt("is_use") == 1
                        && existingEquipped.getOrDefault(uuid, "").isEmpty()
                        && !pendingEquip.containsKey(uuid)) {
                    pendingEquip.put(uuid, mikuId);
                }
            }
        }
        if (!onlineSkipped.isEmpty()) {
            warnings.add(onlineSkipped.size() + " 名在线玩家的称号记录已跳过，请其离线后重新执行导入");
        }
        for (Map.Entry<UUID, String> e : pendingEquip.entrySet()) {
            ops.add(new WriteOp.State(e.getKey(), e.getValue()));
        }
        if (!ops.isEmpty()) {
            storage.writeBatch(ops);
        }
        return new Result(titlesImported, titlesSkipped, grantsImported, grantsSkipped, warnings);
    }

    /** 读取 title_require；activity 类型记录标记（转成免费+不进商城）。 */
    private static void loadRequirements(Connection source, Map<Integer, List<Requirement>> reqsByTitle,
                                         Map<Integer, Boolean> activityByTitle, List<String> warnings)
            throws SQLException {
        try (Statement st = source.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT title_id, buy_type, amount, buy_data FROM title_require ORDER BY title_id, id")) {
            while (rs.next()) {
                int titleId = rs.getInt("title_id");
                String type = rs.getString("buy_type");
                if (type == null) {
                    continue;
                }
                switch (type.toLowerCase(Locale.ROOT)) {
                    case "vault" -> reqsByTitle.computeIfAbsent(titleId, k -> new ArrayList<>())
                            .add(Requirement.vault(rs.getDouble("amount")));
                    case "permission" -> {
                        String node = rs.getString("buy_data");
                        if (node == null || node.isBlank()) {
                            warnings.add("称号 #" + titleId + " 的 permission 条件缺少权限节点，已忽略该条件");
                        } else {
                            reqsByTitle.computeIfAbsent(titleId, k -> new ArrayList<>())
                                    .add(Requirement.permission(node.trim()));
                        }
                    }
                    case "not" -> {
                        // 免费：不产生条件行
                    }
                    case "activity" -> activityByTitle.put(titleId, true);
                    default -> warnings.add("称号 #" + titleId + " 的条件类型 '" + type
                            + "' MikuTag 不支持，已丢弃该条件");
                }
            }
        }
    }

    /** 旧版单类型列（title_list.buy_type/amount）的回退转换；permission 旧列无法还原节点，丢弃。 */
    private static List<Requirement> legacyFallbackRequirements(String buyType, int amount,
                                                                String titleName, List<String> warnings) {
        if (buyType == null || buyType.isBlank() || "not".equalsIgnoreCase(buyType)) {
            return List.of();
        }
        if ("vault".equalsIgnoreCase(buyType)) {
            return List.of(Requirement.vault(amount));
        }
        if (!"activity".equalsIgnoreCase(buyType)) {
            warnings.add("称号 '" + titleName + "' 的旧条件类型 '" + buyType + "' 无法完整迁移，已按免费处理");
        }
        return List.of();
    }

    /** 阈值之上（PlayerTitle 的 9999 年"永久"）与 0 均视为永久（0）；其余保留原毫秒。 */
    private static long normalizeExpiry(long raw) {
        if (raw <= 0 || raw >= PERMANENT_THRESHOLD) {
            return 0L;
        }
        return raw;
    }

    /**
     * 读取 {@code expiration_time} 并返回 epoch 毫秒。
     * <p>
     * 该列声明类型为 DATETIME，但实际回传类型随数据库/驱动而异：SQLite 存的是 epoch 毫秒
     * 整数（动态类型原样保留），MySQL/MariaDB 存的是真正的 DATETIME（驱动回传 Timestamp 或
     * LocalDateTime）。直接 {@code getLong} 在 MySQL 上会抛
     * "Unsupported conversion from DATETIME to java.lang.Long"，故按实际类型分派。
     */
    private static long readExpiry(ResultSet rs, String column) throws SQLException {
        return toEpochMillis(rs.getObject(column));
    }

    /**
     * 把 PlayerTitle 的到期时间原始值统一为 epoch 毫秒。
     * 无法识别或为空时返回 0（按永久处理），避免把未知值误判成已过期而丢失玩家称号。
     */
    static long toEpochMillis(Object raw) {
        if (raw == null) {
            return 0L;
        }
        if (raw instanceof Number number) {
            return number.longValue();
        }
        // Timestamp 是 java.util.Date 子类，必须先于 Date 判断
        if (raw instanceof Timestamp timestamp) {
            return timestamp.getTime();
        }
        if (raw instanceof java.util.Date date) {
            return date.getTime();
        }
        if (raw instanceof LocalDateTime dateTime) {
            return dateTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        }
        if (raw instanceof LocalDate date) {
            return date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
        }
        String text = raw.toString().trim();
        if (text.isEmpty()) {
            return 0L;
        }
        try {
            return Long.parseLong(text); // 部分驱动会把整数以文本形式回传
        } catch (NumberFormatException ignored) {
        }
        try {
            return LocalDateTime.parse(text, DATETIME_FORMAT)
                    .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        } catch (DateTimeParseException ignored) {
        }
        try {
            return LocalDateTime.parse(text, DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                    .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        } catch (DateTimeParseException ignored) {
        }
        try {
            return LocalDate.parse(text, DateTimeFormatter.ISO_LOCAL_DATE)
                    .atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
        } catch (DateTimeParseException ignored) {
        }
        return 0L;
    }

    /**
     * {@code &}/{@code §} 旧颜色码 → MiniMessage 标签。
     * 未知码原样保留；颜色码互斥覆盖（与 Minecraft 行为一致，直接顺序追加标签即可，
     * MiniMessage 后出现的样式同样生效）。
     */
    private static String convertLegacyColor(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        if (raw.indexOf('&') < 0 && raw.indexOf('\u00A7') < 0) {
            return raw;
        }
        StringBuilder out = new StringBuilder(raw.length() + 16);
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if ((c == '&' || c == '\u00A7') && i + 1 < raw.length()) {
                String tag = legacyTag(Character.toLowerCase(raw.charAt(i + 1)));
                if (tag != null) {
                    out.append(tag);
                    i++;
                    continue;
                }
            }
            out.append(c);
        }
        return out.toString();
    }

    private static String legacyTag(char code) {
        return switch (code) {
            case '0' -> "<black>";
            case '1' -> "<dark_blue>";
            case '2' -> "<dark_green>";
            case '3' -> "<dark_aqua>";
            case '4' -> "<dark_red>";
            case '5' -> "<dark_purple>";
            case '6' -> "<gold>";
            case '7' -> "<gray>";
            case '8' -> "<dark_gray>";
            case '9' -> "<blue>";
            case 'a' -> "<green>";
            case 'b' -> "<aqua>";
            case 'c' -> "<red>";
            case 'd' -> "<light_purple>";
            case 'e' -> "<yellow>";
            case 'f' -> "<white>";
            case 'k' -> "<obfuscated>";
            case 'l' -> "<bold>";
            case 'm' -> "<strikethrough>";
            case 'n' -> "<underlined>";
            case 'o' -> "<italic>";
            case 'r' -> "<reset>";
            default -> null;
        };
    }
}
