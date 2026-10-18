package dev.junxiex.mikutag.listener;

import dev.junxiex.mikutag.MikuTagPlugin;
import dev.junxiex.mikutag.data.TagService;
import dev.junxiex.mikutag.dialog.AdminMenuService;
import dev.junxiex.mikutag.dialog.ClickPayload;
import dev.junxiex.mikutag.dialog.MenuService;
import dev.junxiex.mikutag.model.Title;
import dev.junxiex.mikutag.util.Permissions;
import io.papermc.paper.connection.PlayerGameConnection;
import io.papermc.paper.dialog.DialogResponseView;
import io.papermc.paper.event.player.PlayerCustomClickEvent;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 处理 Dialog 菜单的 customClick 事件。事件在玩家连接线程触发，可直接操作玩家。
 * <p>
 * 该事件由客户端触发，可被伪造并高频重放，因此：
 * <ul>
 *   <li>按玩家做最小点击间隔节流；</li>
 *   <li>每个分支都重新校验权限与数据有效性，不信任 payload。</li>
 * </ul>
 */
public final class CustomClickListener implements Listener {

    private final MikuTagPlugin plugin;
    /** 玩家 UUID → 上次处理点击的时间戳（毫秒）。 */
    private final Map<UUID, Long> lastClickAt = new ConcurrentHashMap<>();

    public CustomClickListener(MikuTagPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onClick(PlayerCustomClickEvent event) {
        if (!MenuService.CLICK_KEY.equals(event.getIdentifier())) {
            return;
        }
        if (!(event.getCommonConnection() instanceof PlayerGameConnection game)) {
            return; // 配置阶段连接，忽略
        }
        Player player = game.getPlayer();
        // 先判权限再判节流：无权限者不应污染节流表
        if (player == null || !Permissions.canUse(player) || isThrottled(player)) {
            return;
        }

        ClickPayload.Parsed click = ClickPayload.parse(
                ClickPayload.decode(event.getTag() == null ? null : event.getTag().string()));

        MenuService menu = plugin.menuService();
        AdminMenuService admin = plugin.adminMenuService();
        TagService service = plugin.tagService();

        switch (click.action()) {
            case ClickPayload.SHOP -> menu.openShop(player, 0);
            case ClickPayload.SHOP_PREV, ClickPayload.SHOP_NEXT, ClickPayload.BACK ->
                    menu.openShop(player, click.page());
            case ClickPayload.SHOP_SELECT -> menu.openDetail(player, click.id(), click.page());
            case ClickPayload.SHOP_BUY -> {
                Title title = plugin.titleManager().get(click.id());
                if (title == null) {
                    menu.openShop(player, click.page());
                    return;
                }
                TagService.BuyResult result = service.buy(player, title);
                menu.feedback(player, menu.buyMessage(result, title), ClickPayload.SHOP, click.page());
            }
            case ClickPayload.WAREHOUSE, ClickPayload.WH_PREV, ClickPayload.WH_NEXT ->
                    menu.openWarehouse(player, click.page());
            case ClickPayload.WH_EQUIP -> {
                Title title = plugin.titleManager().get(click.id());
                if (title == null) {
                    menu.openWarehouse(player, click.page());
                    return;
                }
                TagService.EquipResult result = service.equip(player, title);
                menu.feedback(player, menu.equipMessage(result, title),
                        ClickPayload.WAREHOUSE, click.page());
            }
            case ClickPayload.WH_UNEQUIP -> {
                // 卸下按钮只在"仓库中确有有效佩戴"时渲染；但按钮可被重放，
                // 若此时已无有效佩戴（例如称号刚到期），必须回"未佩戴"而非谎报成功。
                if (service.equippedId(player).isEmpty()) {
                    menu.feedback(player, plugin.configManager().message("command.unequip-none"),
                            ClickPayload.WAREHOUSE, click.page());
                } else {
                    service.unequip(player);
                    menu.feedback(player, plugin.configManager().message("warehouse.unequip-ok"),
                            ClickPayload.WAREHOUSE, click.page());
                }
            }
            case ClickPayload.ADMIN, ClickPayload.ADMIN_PREV, ClickPayload.ADMIN_NEXT -> {
                if (requireAdmin(player)) {
                    admin.openAdmin(player, click.page());
                }
            }
            case ClickPayload.ADMIN_NEW -> {
                if (requireAdmin(player)) {
                    admin.openEditor(player, "", click.page());
                }
            }
            case ClickPayload.ADMIN_EDIT -> {
                if (requireAdmin(player)) {
                    admin.openEditor(player, click.id(), click.page());
                }
            }
            case ClickPayload.ADMIN_SAVE -> {
                if (requireAdmin(player)) {
                    DialogResponseView view = event.getDialogResponseView();
                    if (view == null) {
                        admin.openEditor(player, click.id(), click.page());
                    } else {
                        admin.handleSave(player, click.id(), view, click.page());
                    }
                }
            }
            case ClickPayload.ADMIN_DEL -> {
                if (requireAdmin(player)) {
                    admin.openDeleteConfirm(player, click.id(), click.page());
                }
            }
            case ClickPayload.ADMIN_DEL_OK -> {
                if (requireAdmin(player)) {
                    admin.handleDelete(player, click.id(), click.page());
                }
            }
            default -> {
                // 未知动作直接忽略
            }
        }
    }

    private boolean requireAdmin(Player player) {
        if (Permissions.isAdmin(player)) {
            return true;
        }
        plugin.menuService().openWarehouse(player, 0);
        return false;
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        lastClickAt.remove(event.getPlayer().getUniqueId());
    }

    /**
     * 点击节流：与上次"被接受"的点击间隔不足 {@code menu.click-throttle-ms} 时丢弃本次点击。
     * <p>
     * 被丢弃的点击<b>不</b>刷新时间戳——否则持续快速点击（每次都在阈值内）会让时间戳不断前移，
     * 玩家一旦开始连点就永远无法再触发菜单。同一玩家的点击由属主线程串行处理，无需额外同步。
     */
    private boolean isThrottled(Player player) {
        int throttle = plugin.configManager().clickThrottleMillis();
        if (throttle <= 0) {
            return false;
        }
        UUID uuid = player.getUniqueId();
        long now = System.currentTimeMillis();
        Long previous = lastClickAt.get(uuid);
        if (previous != null && now - previous < throttle) {
            return true;
        }
        lastClickAt.put(uuid, now);
        return false;
    }
}
