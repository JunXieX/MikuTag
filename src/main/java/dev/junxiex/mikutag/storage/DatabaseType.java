package dev.junxiex.mikutag.storage;

import java.util.Locale;

/**
 * 支持的数据库类型及其 JDBC 驱动类。
 * <p>
 * 驱动坐标由 {@code MikuTagLoader} 在开服时按需解析下载（见该类注释），
 * 因此本枚举只需登记驱动类名，用于 {@code Class.forName} 显式加载。
 */
public enum DatabaseType {

    SQLITE("sqlite", "org.sqlite.JDBC"),
    MYSQL("mysql", "com.mysql.cj.jdbc.Driver"),
    MARIADB("mariadb", "org.mariadb.jdbc.Driver");

    private final String id;
    private final String driverClass;

    DatabaseType(String id, String driverClass) {
        this.id = id;
        this.driverClass = driverClass;
    }

    public String id() {
        return id;
    }

    public String driverClass() {
        return driverClass;
    }

    public boolean isRemote() {
        return this != SQLITE;
    }

    public static DatabaseType parse(String raw) {
        if (raw != null) {
            String value = raw.trim().toLowerCase(Locale.ROOT);
            for (DatabaseType type : values()) {
                if (type.id.equals(value)) {
                    return type;
                }
            }
        }
        return SQLITE;
    }
}
