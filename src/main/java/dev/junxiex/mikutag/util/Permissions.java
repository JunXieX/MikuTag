package dev.junxiex.mikutag.util;

import org.bukkit.entity.Player;

/**
 * 权限节点统一入口，避免字符串散落各处导致不一致。
 * <p>
 * Bukkit 原生权限不做通配符继承，{@code mikutag.title.*} 只是字面节点，
 * 不会自动授予 {@code mikutag.title.vip}，因此显式检查通配节点，
 * 使纯 Bukkit 环境（以及 LuckPerms 等）下授予通配符也能生效。
 */
public final class Permissions {

    public static final String USE = "mikutag.use";
    public static final String ADMIN = "mikutag.admin";
    public static final String TITLE_WILDCARD = "mikutag.title.*";

    private Permissions() {
    }

    /** 打开菜单 / 佩戴卸下等基础功能。 */
    public static boolean canUse(Player player) {
        return player.hasPermission(USE) || player.hasPermission(ADMIN);
    }

    public static boolean isAdmin(Player player) {
        return player.hasPermission(ADMIN);
    }

    /** 是否满足某称号的权限获取条件。 */
    public static boolean hasTitlePermission(Player player, String node) {
        return node == null || node.isEmpty()
                || player.hasPermission(node)
                || player.hasPermission(TITLE_WILDCARD)
                || player.hasPermission(ADMIN);
    }
}
