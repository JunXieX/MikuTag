package dev.junxiex.mikutag.api;

import dev.junxiex.mikutag.MikuTagPlugin;
import dev.junxiex.mikutag.model.PlayerTagData;
import dev.junxiex.mikutag.model.Title;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Optional;

/**
 * MikuTag 对外 API。其他插件可通过本类读取玩家当前佩戴的称号（聊天/Tab/头顶等显示），
 * 或发放/收回/佩戴称号。
 * <p>
 * 读取方法线程安全（读取的是并发缓存中的不可变/易失状态），可从任意线程调用；
 * 写方法内部会调度到目标玩家的区域/实体线程执行，因此也是线程安全的。
 * 写操作的结果可通过监听 {@link TitleEquipEvent} 获知。
 */
public final class MikuTagAPI {

    private static volatile MikuTagPlugin plugin;

    private MikuTagAPI() {
    }

    /** 由插件在启用时调用。 */
    public static void init(MikuTagPlugin instance) {
        plugin = instance;
    }

    /** 由插件在停用时调用。 */
    public static void shutdown() {
        plugin = null;
    }

    public static boolean available() {
        return plugin != null;
    }

    /** 全部称号定义（按 sortOrder 排序）。 */
    public static List<Title> getTitles() {
        MikuTagPlugin p = plugin;
        return p == null ? List.of() : p.titleManager().ordered();
    }

    public static Optional<Title> getTitle(String titleId) {
        MikuTagPlugin p = plugin;
        if (p == null || titleId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(p.titleManager().get(titleId));
    }

    /** 玩家当前佩戴的称号，未佩戴返回 empty。 */
    public static Optional<Title> getEquippedTitle(Player player) {
        MikuTagPlugin p = plugin;
        if (p == null || player == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(p.tagService().equippedTitle(player));
    }

    /** 玩家当前佩戴的称号 ID，未佩戴返回空串。 */
    public static String getEquippedTitleId(Player player) {
        MikuTagPlugin p = plugin;
        if (p == null || player == null) {
            return "";
        }
        return p.tagService().equippedId(player);
    }

    /** 玩家当前是否有效持有某称号（未过期）。 */
    public static boolean hasTitle(Player player, String titleId) {
        MikuTagPlugin p = plugin;
        if (p == null || player == null || titleId == null) {
            return false;
        }
        PlayerTagData data = p.tagStore().get(player.getUniqueId());
        return data != null && data.owns(titleId, System.currentTimeMillis());
    }

    /** 玩家当前有效拥有的称号数量（不含已删除与已过期）。 */
    public static int getOwnedCount(Player player) {
        MikuTagPlugin p = plugin;
        if (p == null || player == null) {
            return 0;
        }
        return p.tagService().ownedCount(player.getUniqueId());
    }

    /** 发放称号（异步调度到玩家线程）。days &lt;= 0 表示永久。 */
    public static void grantTitle(Player player, String titleId, int days) {
        MikuTagPlugin p = plugin;
        if (p == null || player == null || titleId == null) {
            return;
        }
        player.getScheduler().run(p, task -> p.tagService().grant(player, titleId, days), null);
    }

    /** 收回称号（异步调度到玩家线程）。 */
    public static void revokeTitle(Player player, String titleId) {
        MikuTagPlugin p = plugin;
        if (p == null || player == null || titleId == null) {
            return;
        }
        player.getScheduler().run(p, task -> p.tagService().revoke(player, titleId), null);
    }

    /** 佩戴称号（异步调度到玩家线程）。未持有或已过期时不会生效，结果见 {@link TitleEquipEvent}。 */
    public static void equipTitle(Player player, String titleId) {
        MikuTagPlugin p = plugin;
        if (p == null || player == null || titleId == null) {
            return;
        }
        Title title = p.titleManager().get(titleId);
        if (title == null) {
            return;
        }
        player.getScheduler().run(p, task -> p.tagService().equip(player, title), null);
    }

    /** 卸下当前称号（异步调度到玩家线程）。 */
    public static void unequipTitle(Player player) {
        MikuTagPlugin p = plugin;
        if (p == null || player == null) {
            return;
        }
        player.getScheduler().run(p, task -> p.tagService().unequip(player), null);
    }
}
