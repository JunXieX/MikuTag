package dev.junxiex.mikutag;

import io.papermc.paper.plugin.loader.PluginClasspathBuilder;
import io.papermc.paper.plugin.loader.PluginLoader;
import io.papermc.paper.plugin.loader.library.impl.MavenLibraryResolver;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.repository.RemoteRepository;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;

/**
 * 运行期依赖加载：在开服阶段读取 config.yml 的 storage.type，
 * 仅解析并下载对应的 JDBC 驱动（SQLite / MySQL / MariaDB）。
 * <p>
 * 下载与缓存完全交给 Paper 的 {@link MavenLibraryResolver}（基于 Aether）：
 * 驱动会被缓存到服务端的 libraries 目录，已存在则直接复用，插件 JAR 内不内置任何驱动。
 * 仓库顺序为 papermc 镜像（代理 Central，规避直连限速）→ Central 官方镜像。
 * <p>
 * 本类在插件类加载阶段执行，此时无法使用 Bukkit API，故对 config.yml 采用极简文本解析，
 * 只读取 {@code storage.type} 一项。
 * <p>
 * 配置路径按"服务端工作目录/plugins/MikuTag/config.yml"定位（Paper 以服务端根目录为工作目录）。
 * 文件不存在或读取失败时按 sqlite 处理；若实际配置为 mysql/mariadb，插件随后会在初始化存储时
 * 因缺少驱动而明确报错并禁用，不会静默连到错误的数据库。
 */
@SuppressWarnings("unused") // 由服务端在类加载阶段实例化
public final class MikuTagLoader implements PluginLoader {

    private static final String SQLITE_JDBC = "org.xerial:sqlite-jdbc:3.53.4.0";
    private static final String MYSQL_JDBC = "com.mysql:mysql-connector-j:9.7.0";
    private static final String MARIADB_JDBC = "org.mariadb.jdbc:mariadb-java-client:3.5.10";

    @Override
    public void classloader(PluginClasspathBuilder builder) {
        String coordinate = switch (readStorageType()) {
            case "mysql" -> MYSQL_JDBC;
            case "mariadb" -> MARIADB_JDBC;
            default -> SQLITE_JDBC;
        };
        MavenLibraryResolver resolver = new MavenLibraryResolver();
        resolver.addDependency(new Dependency(new DefaultArtifact(coordinate), null));
        resolver.addRepository(new RemoteRepository.Builder(
                "papermc", "default", "https://repo.papermc.io/repository/maven-public/").build());
        resolver.addRepository(new RemoteRepository.Builder(
                "central", "default", MavenLibraryResolver.MAVEN_CENTRAL_DEFAULT_MIRROR).build());
        builder.addLibrary(resolver);
    }

    private static String readStorageType() {
        Path config = Paths.get("plugins", "MikuTag", "config.yml");
        if (!Files.isRegularFile(config)) {
            return "sqlite";
        }
        try {
            boolean inStorage = false;
            for (String rawLine : Files.readAllLines(config, StandardCharsets.UTF_8)) {
                String line = stripComment(rawLine);
                if (line.isBlank()) {
                    continue;
                }
                String trimmed = line.trim();
                if (leadingSpaces(line) == 0) {
                    inStorage = trimmed.equals("storage:");
                    continue;
                }
                if (inStorage && trimmed.startsWith("type:")) {
                    return normalize(trimmed.substring("type:".length()).trim());
                }
            }
        } catch (IOException ignored) {
            // 读取失败时回退默认值，插件随后仍会在启用阶段给出明确日志
        }
        return "sqlite";
    }

    private static String normalize(String value) {
        String result = value;
        if (result.length() >= 2
                && (result.charAt(0) == '"' || result.charAt(0) == '\'')
                && result.charAt(result.length() - 1) == result.charAt(0)) {
            result = result.substring(1, result.length() - 1);
        }
        result = result.trim().toLowerCase(Locale.ROOT);
        return result.isEmpty() ? "sqlite" : result;
    }

    private static String stripComment(String line) {
        boolean single = false;
        boolean dbl = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '\'' && !dbl) {
                single = !single;
            } else if (c == '"' && !single) {
                dbl = !dbl;
            } else if (c == '#' && !single && !dbl) {
                return line.substring(0, i);
            }
        }
        return line;
    }

    private static int leadingSpaces(String line) {
        int i = 0;
        while (i < line.length() && line.charAt(i) == ' ') {
            i++;
        }
        return i;
    }
}
