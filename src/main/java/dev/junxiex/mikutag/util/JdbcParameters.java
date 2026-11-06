package dev.junxiex.mikutag.util;

/**
 * 远端 JDBC 查询串的默认值：驱动默认 connectTimeout/socketTimeout 为 0（无限等待），
 * 网络黑洞时会把持有 dbLock 的调用或导入线程拖住数分钟。这里统一补默认值，
 * 管理员在 parameters 里写了同名参数则保留其值。
 */
public final class JdbcParameters {

    private JdbcParameters() {
    }

    /** 返回补好超时的查询串（不含前导 ?）。 */
    public static String withDefaults(String parameters) {
        String configured = parameters == null ? "" : parameters.trim();
        StringBuilder query = new StringBuilder(configured);
        appendDefault(query, configured, "connectTimeout", "5000");
        appendDefault(query, configured, "socketTimeout", "15000");
        return query.toString();
    }

    private static void appendDefault(StringBuilder query, String configured, String key, String value) {
        if (hasParam(configured, key)) {
            return;
        }
        if (!query.isEmpty()) {
            query.append('&');
        }
        query.append(key).append('=').append(value);
    }

    private static boolean hasParam(String parameters, String key) {
        if (parameters.isEmpty()) {
            return false;
        }
        for (String part : parameters.split("&")) {
            int equal = part.indexOf('=');
            String name = equal < 0 ? part : part.substring(0, equal);
            if (name.trim().equalsIgnoreCase(key)) {
                return true;
            }
        }
        return false;
    }
}
