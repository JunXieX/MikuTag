package dev.junxiex.mikutag.storage;

import dev.junxiex.mikutag.model.PlayerTagData;
import dev.junxiex.mikutag.model.Requirement;
import dev.junxiex.mikutag.model.Title;
import org.bukkit.Material;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * 基于 JDBC 的统一存储实现，同一套代码支持 SQLite / MySQL / MariaDB。
 * <p>
 * 单连接 + {@link #dbLock} 串行化所有操作：
 * <ul>
 *   <li>SQLite 的单连接本就只能串行访问；</li>
 *   <li>MySQL/MariaDB 采用单长连接而非连接池——称号插件的数据量极小（每个玩家仅几行），
 *       且所有写操作都经 write-behind 合并提交，单连接的吞吐远高于实际负载，
 *       换取的是零额外依赖与确定的线程安全。</li>
 * </ul>
 * 远端连接掉线（数据库重启、网络抖动）时会在失败路径上自动重建连接：本次操作照常失败并由
 * 上层重新入队，下一轮写回即可恢复，无需重启服务器。
 * <p>
 * 所有方法均为阻塞 IO，必须只在异步线程调用（加载/写回）或启动阶段调用。
 */
public final class SqlStorage implements Storage {

    private final Object dbLock = new Object();
    private final boolean sqlite;
    private final String url;
    private final String user;
    private final String password;
    private final Logger logger;

    /** 非 final：远端连接掉线后会在持锁状态下整体替换，所有访问都发生在 dbLock 内。 */
    private Connection connection;
    private volatile boolean closed;

    private final String titleUpsertSql;
    private final String reqDeleteSql;
    private final String reqInsertSql;
    private final String grantUpsertSql;
    private final String revokeSql;
    private final String stateUpsertSql;

    public SqlStorage(DatabaseType type, File sqliteFile, String host, int port, String database,
                      String user, String password, String parameters, Logger logger) throws SQLException {
        this.sqlite = !type.isRemote();
        this.url = buildUrl(type, sqliteFile, host, port, database, parameters);
        this.user = user;
        this.password = password;
        this.logger = logger;

        String titleColumns = "(id, name, description, icon, duration_days, sellable, sort_order, created_at)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
        this.titleUpsertSql = sqlite
                ? "INSERT INTO mikutag_titles " + titleColumns
                + " ON CONFLICT(id) DO UPDATE SET name=excluded.name, description=excluded.description,"
                + " icon=excluded.icon, duration_days=excluded.duration_days,"
                + " sellable=excluded.sellable, sort_order=excluded.sort_order"
                : "REPLACE INTO mikutag_titles " + titleColumns;

        this.reqDeleteSql = "DELETE FROM mikutag_title_requirements WHERE title_id = ?";
        this.reqInsertSql = "INSERT INTO mikutag_title_requirements"
                + " (title_id, req_index, req_type, amount, permission_node) VALUES (?, ?, ?, ?, ?)";

        this.grantUpsertSql = sqlite
                ? "INSERT INTO mikutag_player_titles (uuid, title_id, expires_at) VALUES (?, ?, ?)"
                + " ON CONFLICT(uuid, title_id) DO UPDATE SET expires_at=excluded.expires_at"
                : "REPLACE INTO mikutag_player_titles (uuid, title_id, expires_at) VALUES (?, ?, ?)";

        this.revokeSql = "DELETE FROM mikutag_player_titles WHERE uuid = ? AND title_id = ?";

        this.stateUpsertSql = sqlite
                ? "INSERT INTO mikutag_player_state (uuid, equipped_title) VALUES (?, ?)"
                + " ON CONFLICT(uuid) DO UPDATE SET equipped_title=excluded.equipped_title"
                : "REPLACE INTO mikutag_player_state (uuid, equipped_title) VALUES (?, ?)";

        synchronized (dbLock) {
            this.connection = openConnection();
            try {
                createTables(this.connection, sqlite);
                migrateLegacyRequirements(this.connection);
            } catch (SQLException | RuntimeException e) {
                // 初始化失败时插件会被禁用，必须关闭已打开的连接，否则句柄泄漏
                try {
                    this.connection.close();
                } catch (SQLException ignored) {
                }
                throw e;
            }
        }
    }

    private static String buildUrl(DatabaseType type, File sqliteFile, String host, int port,
                                   String database, String parameters) {
        if (type == DatabaseType.SQLITE) {
            return "jdbc:sqlite:" + sqliteFile.getAbsolutePath();
        }
        String scheme = type == DatabaseType.MYSQL ? "jdbc:mysql://" : "jdbc:mariadb://";
        StringBuilder sb = new StringBuilder(scheme)
                .append(host).append(':').append(port).append('/').append(database);
        if (parameters != null && !parameters.isBlank()) {
            sb.append('?').append(parameters.trim());
        }
        return sb.toString();
    }

    private Connection openConnection() throws SQLException {
        Connection conn = sqlite
                ? DriverManager.getConnection(url)
                : DriverManager.getConnection(url, user, password);
        if (sqlite) {
            try (Statement st = conn.createStatement()) {
                st.execute("PRAGMA journal_mode=WAL");
                st.execute("PRAGMA synchronous=NORMAL");
            }
        }
        return conn;
    }

    private static void createTables(Connection conn, boolean sqlite) throws SQLException {
        // 远端库固定 utf8mb4：称号名/描述含中文，若目标库默认 latin1，
        // 不指定表字符集会导致中文乱码。SQLite 无此语法，故仅远端追加。
        String charset = sqlite ? "" : " DEFAULT CHARSET=utf8mb4";
        try (Statement st = conn.createStatement()) {
            st.execute("""
                    CREATE TABLE IF NOT EXISTS mikutag_titles (
                      id VARCHAR(64) NOT NULL,
                      name VARCHAR(255) NOT NULL,
                      description TEXT NOT NULL,
                      icon VARCHAR(64) NOT NULL,
                      duration_days INT NOT NULL DEFAULT 0,
                      sellable TINYINT NOT NULL DEFAULT 1,
                      sort_order INT NOT NULL DEFAULT 0,
                      created_at BIGINT NOT NULL DEFAULT 0,
                      PRIMARY KEY (id)
                    )""" + charset);
            st.execute("""
                    CREATE TABLE IF NOT EXISTS mikutag_title_requirements (
                      title_id VARCHAR(64) NOT NULL,
                      req_index INT NOT NULL,
                      req_type VARCHAR(32) NOT NULL,
                      amount DOUBLE NOT NULL DEFAULT 0,
                      permission_node VARCHAR(255) NOT NULL DEFAULT '',
                      PRIMARY KEY (title_id, req_index)
                    )""" + charset);
            st.execute("""
                    CREATE TABLE IF NOT EXISTS mikutag_player_titles (
                      uuid VARCHAR(36) NOT NULL,
                      title_id VARCHAR(64) NOT NULL,
                      expires_at BIGINT NOT NULL DEFAULT 0,
                      PRIMARY KEY (uuid, title_id)
                    )""" + charset);
            st.execute("""
                    CREATE TABLE IF NOT EXISTS mikutag_player_state (
                      uuid VARCHAR(36) NOT NULL,
                      equipped_title VARCHAR(64) NOT NULL DEFAULT '',
                      PRIMARY KEY (uuid)
                    )""" + charset);
        }
    }

    /**
     * 旧版单类型模型（purchase_type/price/permission_node 列）→ 多条件模型的一次性迁移：
     * 把每个称号的旧列转成条件行，再删除旧列（三种库均支持 DROP COLUMN：SQLite 3.35+/MySQL/MariaDB）。
     * 各步骤以"列是否存在"为幂等守卫，中途失败下次启动自动续完。调用方必须持有 dbLock。
     */
    private void migrateLegacyRequirements(Connection conn) {
        try {
            // 中间版本遗留的 updated_at 列（同步签名机制已移除）：无条件幂等清理
            dropColumn(conn, "updated_at");
            if (!hasColumn(conn, "mikutag_titles", "purchase_type")) {
                return; // 已是新结构（或空库）
            }
            List<String> migrated = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT id, purchase_type, price, permission_node FROM mikutag_titles");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String id = rs.getString("id");
                    String type = rs.getString("purchase_type");
                    double price = rs.getDouble("price");
                    String permission = rs.getString("permission_node");
                    List<Requirement> reqs = new ArrayList<>(2);
                    boolean activity = "ACTIVITY".equalsIgnoreCase(type);
                    if ("VAULT".equalsIgnoreCase(type)) {
                        reqs.add(Requirement.vault(price));
                    } else if ("PERMISSION".equalsIgnoreCase(type)
                            && permission != null && !permission.isBlank()) {
                        reqs.add(Requirement.permission(permission));
                    }
                    if (!reqs.isEmpty() || activity) {
                        // 条件行与 sellable 覆盖（ACTIVITY 原本就不在商城出售）
                        writeRequirements(conn, id, reqs);
                        if (activity) {
                            try (PreparedStatement up = conn.prepareStatement(
                                    "UPDATE mikutag_titles SET sellable = 0 WHERE id = ?")) {
                                up.setString(1, id);
                                up.executeUpdate();
                            }
                        }
                        migrated.add(id);
                    }
                }
            }
            dropColumn(conn, "purchase_type");
            dropColumn(conn, "price");
            dropColumn(conn, "permission_node");
            logger.info("已将旧版单类型称号数据迁移为多购买条件模型（迁移 " + migrated.size() + " 个称号）。");
        } catch (SQLException e) {
            logger.warning("旧版称号数据迁移失败（不影响新数据读写）: " + e.getMessage());
        }
    }

    private void dropColumn(Connection conn, String column) {
        try {
            if (hasColumn(conn, "mikutag_titles", column)) {
                try (Statement st = conn.createStatement()) {
                    st.execute("ALTER TABLE mikutag_titles DROP COLUMN " + column);
                }
            }
        } catch (SQLException e) {
            logger.warning("删除旧列 mikutag_titles." + column + " 失败，将保留为无害冗余列: " + e.getMessage());
        }
    }

    private static boolean hasColumn(Connection conn, String table, String column) throws SQLException {
        try (ResultSet rs = conn.getMetaData().getColumns(null, null, table, column)) {
            return rs.next();
        }
    }

    /**
     * 连接异常时的兜底重建。调用方必须持有 {@link #dbLock}，且本次操作仍会照常失败
     * （由上层把数据重新入队），下一轮写回即可在新连接上成功。
     * <p>
     * 重开失败时字段保留指向已关闭的旧连接：后续操作会立即失败并再次触发本方法重试，
     * 数据库恢复后即可自动重建，无需重启服务器。
     */
    private void reconnectLocked(SQLException cause) {
        try {
            connection.close();
        } catch (SQLException ignored) {
        }
        try {
            connection = openConnection();
            createTables(connection, sqlite);
            logger.warning("数据库连接异常，已重建连接（本次操作失败，数据将在下一轮写回重试）: "
                    + cause.getMessage());
        } catch (SQLException ex) {
            logger.severe("数据库重连失败，后续读写将持续失败直到数据库恢复: " + ex.getMessage());
        }
    }

    @Override
    public List<Title> loadTitles() {
        List<Title> titles = new ArrayList<>();
        synchronized (dbLock) {
            if (closed) {
                return titles;
            }
            Connection conn = connection;
            try {
                Map<String, List<Requirement>> reqsByTitle = loadRequirements(conn);
                String sql = "SELECT id, name, description, icon, duration_days, sellable, sort_order,"
                        + " created_at FROM mikutag_titles ORDER BY sort_order ASC, id ASC";
                try (PreparedStatement ps = conn.prepareStatement(sql);
                     ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String id = rs.getString("id");
                        String iconName = rs.getString("icon");
                        Material icon = Material.matchMaterial(iconName == null ? "" : iconName);
                        titles.add(new Title(
                                id,
                                rs.getString("name"),
                                Title.splitDescription(rs.getString("description")),
                                icon,
                                reqsByTitle.getOrDefault(id, List.of()),
                                rs.getInt("duration_days"),
                                rs.getInt("sellable") != 0,
                                rs.getInt("sort_order"),
                                rs.getLong("created_at")));
                    }
                }
            } catch (SQLException e) {
                reconnectLocked(e);
                throw new StorageException("加载称号列表失败", e);
            }
        }
        return titles;
    }

    private static Map<String, List<Requirement>> loadRequirements(Connection conn) throws SQLException {
        Map<String, List<Requirement>> map = new HashMap<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT title_id, req_type, amount, permission_node FROM mikutag_title_requirements"
                        + " ORDER BY title_id, req_index");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                Requirement.ReqType type = Requirement.ReqType.parse(rs.getString("req_type"));
                if (type == null) {
                    continue; // 未知类型（手工改库/未来版本）：跳过该行而非放弃整表
                }
                map.computeIfAbsent(rs.getString("title_id"), k -> new ArrayList<>())
                        .add(new Requirement(type, rs.getDouble("amount"), rs.getString("permission_node")));
            }
        }
        return map;
    }

    /** 条件行全量替换：先删后插。调用方必须持有 dbLock（迁移路径除外，其在构造期单线程执行）。 */
    private void writeRequirements(Connection conn, String titleId, List<Requirement> requirements)
            throws SQLException {
        try (PreparedStatement del = conn.prepareStatement(reqDeleteSql)) {
            del.setString(1, titleId);
            del.executeUpdate();
        }
        try (PreparedStatement ins = conn.prepareStatement(reqInsertSql)) {
            for (int i = 0; i < requirements.size(); i++) {
                Requirement r = requirements.get(i);
                ins.setString(1, titleId);
                ins.setInt(2, i);
                ins.setString(3, r.type().name());
                ins.setDouble(4, r.amount());
                ins.setString(5, r.permission());
                ins.addBatch();
            }
            ins.executeBatch();
        }
    }

    /** 保存称号与其条件行（同一事务，避免"称号已存、条件未存"的半写状态）。 */
    @Override
    public void upsertTitle(Title title) {
        synchronized (dbLock) {
            if (closed) {
                return;
            }
            Connection conn = connection;
            boolean prevAuto = true;
            try {
                prevAuto = conn.getAutoCommit();
                conn.setAutoCommit(false);
                try (PreparedStatement ps = conn.prepareStatement(titleUpsertSql)) {
                    ps.setString(1, title.id());
                    ps.setString(2, title.name());
                    ps.setString(3, title.descriptionRaw());
                    ps.setString(4, title.icon().name());
                    ps.setInt(5, title.durationDays());
                    ps.setInt(6, title.sellable() ? 1 : 0);
                    ps.setInt(7, title.sortOrder());
                    ps.setLong(8, title.createdAt());
                    ps.executeUpdate();
                }
                writeRequirements(conn, title.id(), title.requirements());
                conn.commit();
            } catch (SQLException e) {
                try {
                    conn.rollback();
                } catch (SQLException ignored) {
                }
                reconnectLocked(e);
                throw new StorageException("保存称号失败: " + title.id(), e);
            } finally {
                try {
                    conn.setAutoCommit(prevAuto);
                } catch (SQLException ignored) {
                }
            }
        }
    }

    /** 删除称号并级联清理条件行、玩家持有记录与佩戴状态；全部语句在同一事务内，避免半删状态。 */
    @Override
    public void deleteTitle(String id) {
        synchronized (dbLock) {
            if (closed) {
                return;
            }
            Connection conn = connection;
            boolean prevAuto = true;
            try {
                prevAuto = conn.getAutoCommit();
                conn.setAutoCommit(false);
                try (PreparedStatement delTitle = conn.prepareStatement(
                        "DELETE FROM mikutag_titles WHERE id = ?");
                     PreparedStatement delReqs = conn.prepareStatement(reqDeleteSql);
                     PreparedStatement delOwned = conn.prepareStatement(
                             "DELETE FROM mikutag_player_titles WHERE title_id = ?");
                     PreparedStatement resetState = conn.prepareStatement(
                             "UPDATE mikutag_player_state SET equipped_title = '' WHERE equipped_title = ?")) {
                    delTitle.setString(1, id);
                    delTitle.executeUpdate();
                    delReqs.setString(1, id);
                    delReqs.executeUpdate();
                    delOwned.setString(1, id);
                    delOwned.executeUpdate();
                    resetState.setString(1, id);
                    resetState.executeUpdate();
                }
                conn.commit();
            } catch (SQLException e) {
                try {
                    conn.rollback();
                } catch (SQLException ignored) {
                }
                reconnectLocked(e);
                throw new StorageException("删除称号失败: " + id, e);
            } finally {
                try {
                    conn.setAutoCommit(prevAuto);
                } catch (SQLException ignored) {
                }
            }
        }
    }

    /**
     * 全量玩家数据快照（uuid → 持有记录、uuid → 佩戴），仅迁移导入时调用一次。
     * 数据量为玩家数 × 人均称号数，称号插件规模下可安全整体载入内存。
     */
    @Override
    public PlayerSnapshot loadPlayerSnapshot() {
        Map<UUID, Map<String, Long>> owned = new HashMap<>();
        Map<UUID, String> equipped = new HashMap<>();
        synchronized (dbLock) {
            if (closed) {
                return new PlayerSnapshot(owned, equipped);
            }
            Connection conn = connection;
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT uuid, title_id, expires_at FROM mikutag_player_titles");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String raw = rs.getString("uuid");
                    UUID uuid;
                    try {
                        uuid = raw == null ? null : UUID.fromString(raw);
                    } catch (IllegalArgumentException ex) {
                        uuid = null;
                    }
                    if (uuid == null) {
                        // 手工改库产生的非法 UUID 行：跳过该行而非让整次快照失败
                        logger.warning("玩家称号表存在非法 UUID 行（title_id=" + rs.getString("title_id")
                                + "），已跳过");
                        continue;
                    }
                    owned.computeIfAbsent(uuid, k -> new HashMap<>())
                            .put(rs.getString("title_id"), rs.getLong("expires_at"));
                }
            } catch (SQLException e) {
                reconnectLocked(e);
                throw new StorageException("加载玩家称号快照失败", e);
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT uuid, equipped_title FROM mikutag_player_state");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    try {
                        equipped.put(UUID.fromString(rs.getString("uuid")), rs.getString("equipped_title"));
                    } catch (IllegalArgumentException ex) {
                        logger.warning("玩家佩戴表存在非法 UUID 行，已跳过: " + ex.getMessage());
                    }
                }
            } catch (SQLException e) {
                reconnectLocked(e);
                throw new StorageException("加载玩家佩戴快照失败", e);
            }
        }
        return new PlayerSnapshot(owned, equipped);
    }

    @Override
    public PlayerTagData loadPlayer(UUID uuid) {
        PlayerTagData data = new PlayerTagData(uuid);
        synchronized (dbLock) {
            // 关服竞态：cancelTasks 不保证已在运行的异步加载立即结束，close() 之后它才回来。
            if (!closed) {
                Connection conn = connection;
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT title_id, expires_at FROM mikutag_player_titles WHERE uuid = ?")) {
                    ps.setString(1, uuid.toString());
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            data.grant(rs.getString("title_id"), rs.getLong("expires_at"));
                        }
                    }
                } catch (SQLException e) {
                    reconnectLocked(e);
                    throw new StorageException("加载玩家称号失败: " + uuid, e);
                }
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT equipped_title FROM mikutag_player_state WHERE uuid = ?")) {
                    ps.setString(1, uuid.toString());
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            data.setEquipped(rs.getString("equipped_title"));
                        }
                    }
                } catch (SQLException e) {
                    reconnectLocked(e);
                    throw new StorageException("加载玩家佩戴状态失败: " + uuid, e);
                }
            }
        }
        data.markLoaded();
        return data;
    }

    @Override
    public void writeBatch(List<WriteOp> ops) {
        if (ops.isEmpty()) {
            return;
        }
        synchronized (dbLock) {
            if (closed) {
                // 仅可能出现在关服竞态：此时已无法写库，但必须让问题可见而非静默丢数据
                logger.warning("存储已关闭，放弃写入 " + ops.size() + " 条待写回数据。");
                return;
            }
            Connection conn = connection;
            boolean prevAuto = true;
            try {
                prevAuto = conn.getAutoCommit();
                conn.setAutoCommit(false);
                try (PreparedStatement grant = conn.prepareStatement(grantUpsertSql);
                     PreparedStatement revoke = conn.prepareStatement(revokeSql);
                     PreparedStatement state = conn.prepareStatement(stateUpsertSql)) {
                    // 按"同类连续段"分批提交：既保留批量吞吐，又严格保持入队 FIFO。
                    // 若按类型各自整批执行（先全部授予、再全部回收），同一称号"先收回再授予"
                    // 会被写成相反结果。
                    int i = 0;
                    while (i < ops.size()) {
                        WriteOp first = ops.get(i);
                        int end = i + 1;
                        while (end < ops.size() && ops.get(end).getClass() == first.getClass()) {
                            end++;
                        }
                        if (first instanceof WriteOp.Grant) {
                            for (int k = i; k < end; k++) {
                                WriteOp.Grant g = (WriteOp.Grant) ops.get(k);
                                grant.setString(1, g.uuid().toString());
                                grant.setString(2, g.titleId());
                                grant.setLong(3, g.expiresAt());
                                grant.addBatch();
                            }
                            grant.executeBatch();
                        } else if (first instanceof WriteOp.Revoke) {
                            for (int k = i; k < end; k++) {
                                WriteOp.Revoke r = (WriteOp.Revoke) ops.get(k);
                                revoke.setString(1, r.uuid().toString());
                                revoke.setString(2, r.titleId());
                                revoke.addBatch();
                            }
                            revoke.executeBatch();
                        } else {
                            for (int k = i; k < end; k++) {
                                WriteOp.State s = (WriteOp.State) ops.get(k);
                                state.setString(1, s.uuid().toString());
                                state.setString(2, s.equipped());
                                state.addBatch();
                            }
                            state.executeBatch();
                        }
                        i = end;
                    }
                }
                conn.commit();
            } catch (SQLException e) {
                try {
                    conn.rollback();
                } catch (SQLException ignored) {
                }
                reconnectLocked(e);
                throw new StorageException("批量写回玩家称号数据失败", e);
            } finally {
                try {
                    conn.setAutoCommit(prevAuto);
                } catch (SQLException ignored) {
                }
            }
        }
    }

    @Override
    public void close() {
        synchronized (dbLock) {
            closed = true;
            try {
                connection.close();
            } catch (SQLException ignored) {
            }
        }
    }

    public static final class StorageException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        public StorageException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
