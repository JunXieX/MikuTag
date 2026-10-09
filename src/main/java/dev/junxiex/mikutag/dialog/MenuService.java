package dev.junxiex.mikutag.dialog;

import dev.junxiex.mikutag.MikuTagPlugin;
import dev.junxiex.mikutag.config.ConfigManager;
import dev.junxiex.mikutag.data.TagService;
import dev.junxiex.mikutag.model.PlayerTagData;
import dev.junxiex.mikutag.model.Title;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 玩家侧称号菜单（商城 / 详情 / 仓库 / 结果反馈），基于 Paper Dialog API。
 * <p>
 * 采用无状态设计：页码与称号 ID 编码进按钮 payload，交互时重新渲染，
 * 服务端不维护"打开中的菜单"集合。Dialog 在每次点击后都会关闭，
 * 因此上一页/下一页/购买结果等都通过"重新打开一个新 Dialog"来实现。
 * <p>
 * 所有玩家可见文本均来自 messages.yml。
 */
public final class MenuService {

    public static final Key CLICK_KEY = Key.key("mikutag", "menu_click");

    private final MikuTagPlugin plugin;

    public MenuService(MikuTagPlugin plugin) {
        this.plugin = plugin;
    }

    /** 打开称号商城。可从任意线程调用，内部投递到玩家实体线程。 */
    public void openShop(Player player, int page) {
        player.getScheduler().execute(plugin, () -> doOpenShop(player, page), null, 0L);
    }

    private void doOpenShop(Player player, int page) {
        ConfigManager cfg = plugin.configManager();
        PlayerTagData data = plugin.tagService().data(player);
        if (data == null || !data.loaded()) {
            player.sendMessage(cfg.message("menu.loading"));
            return;
        }
        List<Title> titles = shopTitles(data);
        int pageSize = cfg.pageSize();
        int totalPages = Math.max(1, (titles.size() + pageSize - 1) / pageSize);
        page = clamp(page, totalPages);
        int from = page * pageSize;
        int to = Math.min(titles.size(), from + pageSize);

        List<ActionButton> buttons = new ArrayList<>();
        for (int i = from; i < to; i++) {
            Title title = titles.get(i);
            buttons.add(ActionButton.builder(cfg.displayedName(title))
                    .tooltip(shopTooltip(title))
                    .width(150)
                    .action(DialogAction.customClick(CLICK_KEY,
                            ClickPayload.tag(ClickPayload.SHOP_SELECT, title.id(), page)))
                    .build());
        }
        if (buttons.isEmpty()) {
            // 空列表时占位按钮本身就是"去仓库"的入口，不再重复添加切换按钮
            buttons.add(ActionButton.builder(cfg.message("menu.empty-shop"))
                    .action(DialogAction.customClick(CLICK_KEY,
                            ClickPayload.tag(ClickPayload.WAREHOUSE, "", 0)))
                    .build());
        } else {
            buttons.add(ActionButton.builder(cfg.message("menu.warehouse-title"))
                    .width(120)
                    .action(DialogAction.customClick(CLICK_KEY,
                            ClickPayload.tag(ClickPayload.WAREHOUSE, "", 0)))
                    .build());
        }
        if (totalPages > 1) {
            if (page > 0) {
                buttons.add(navButton(cfg.message("menu.prev-page"), ClickPayload.SHOP_PREV, page - 1));
            }
            if (page < totalPages - 1) {
                buttons.add(navButton(cfg.message("menu.next-page"), ClickPayload.SHOP_NEXT, page + 1));
            }
        }

        Component info = cfg.message("menu.page-info", Map.of(
                "page", String.valueOf(page + 1),
                "total", String.valueOf(totalPages),
                "count", String.valueOf(titles.size())));

        Dialogs.show(plugin, player, () -> Dialog.create(b -> b.empty()
                .base(DialogBase.builder(cfg.message("menu.shop-title"))
                        .body(List.of(DialogBody.plainMessage(info)))
                        .canCloseWithEscape(true)
                        .build())
                .type(DialogType.multiAction(buttons).columns(2).build())));
    }

    /** 打开某个称号的详情/确认购买页。page 为来源商城页码。 */
    public void openDetail(Player player, String titleId, int page) {
        player.getScheduler().execute(plugin, () -> doOpenDetail(player, titleId, page), null, 0L);
    }

    private void doOpenDetail(Player player, String titleId, int page) {
        ConfigManager cfg = plugin.configManager();
        PlayerTagData data = plugin.tagService().data(player);
        if (data == null || !data.loaded()) {
            player.sendMessage(cfg.message("menu.loading"));
            return;
        }
        Title title = plugin.titleManager().get(titleId);
        if (title == null) {
            doOpenShop(player, page);
            return;
        }

        List<DialogBody> bodies = new ArrayList<>();
        bodies.add(DialogBody.item(new ItemStack(title.icon()))
                .showTooltip(true).build());
        for (Component line : title.descriptionLines()) {
            bodies.add(DialogBody.plainMessage(line));
        }
        bodies.add(DialogBody.plainMessage(cfg.message("detail.duration", null,
                "duration", cfg.durationLabel(title.durationDays()))));
        for (Component line : cfg.requirementLines(title)) {
            bodies.add(DialogBody.plainMessage(line));
        }

        boolean owned = data.owns(title.id(), System.currentTimeMillis());
        if (owned) {
            bodies.add(DialogBody.plainMessage(cfg.message("detail.owned")));
        }

        List<ActionButton> buttons = new ArrayList<>();
        if (!owned && title.sellable()) {
            buttons.add(ActionButton.builder(cfg.message("menu.buy-button"))
                    .width(100)
                    .action(DialogAction.customClick(CLICK_KEY,
                            ClickPayload.tag(ClickPayload.SHOP_BUY, title.id(), page)))
                    .build());
        }
        buttons.add(ActionButton.builder(cfg.message("menu.back-button"))
                .width(80)
                .action(DialogAction.customClick(CLICK_KEY,
                        ClickPayload.tag(ClickPayload.BACK, "", page)))
                .build());

        Dialogs.show(plugin, player, () -> Dialog.create(b -> b.empty()
                .base(DialogBase.builder(cfg.displayedName(title))
                        .body(bodies)
                        .canCloseWithEscape(true)
                        .build())
                .type(DialogType.multiAction(buttons).columns(2).build())));
    }

    /** 打开玩家称号仓库。 */
    public void openWarehouse(Player player, int page) {
        player.getScheduler().execute(plugin, () -> doOpenWarehouse(player, page), null, 0L);
    }

    private void doOpenWarehouse(Player player, int page) {
        ConfigManager cfg = plugin.configManager();
        PlayerTagData data = plugin.tagService().data(player);
        if (data == null || !data.loaded()) {
            player.sendMessage(cfg.message("menu.loading"));
            return;
        }
        List<Title> owned = ownedTitles(data);
        int pageSize = cfg.pageSize();
        int totalPages = Math.max(1, (owned.size() + pageSize - 1) / pageSize);
        page = clamp(page, totalPages);
        int from = page * pageSize;
        int to = Math.min(owned.size(), from + pageSize);
        long now = System.currentTimeMillis();
        String equipped = data.equipped();

        List<ActionButton> buttons = new ArrayList<>();
        if (equipped != null && !equipped.isEmpty()) {
            buttons.add(ActionButton.builder(cfg.message("menu.unequip-button"))
                    .width(150)
                    .action(DialogAction.customClick(CLICK_KEY,
                            ClickPayload.tag(ClickPayload.WH_UNEQUIP, "", page)))
                    .build());
        }
        for (int i = from; i < to; i++) {
            Title title = owned.get(i);
            boolean isEquipped = title.id().equals(equipped);
            Component label = isEquipped
                    ? cfg.message("menu.equipped-mark")
                    : cfg.displayedName(title);
            buttons.add(ActionButton.builder(label)
                    .tooltip(warehouseTooltip(title, data, now, isEquipped))
                    .width(150)
                    .action(DialogAction.customClick(CLICK_KEY,
                            ClickPayload.tag(ClickPayload.WH_EQUIP, title.id(), page)))
                    .build());
        }
        if (owned.isEmpty()) {
            // 空列表时占位按钮本身就是"去商城"的入口，不再重复添加切换按钮
            buttons.add(ActionButton.builder(cfg.message("menu.empty-warehouse"))
                    .action(DialogAction.customClick(CLICK_KEY,
                            ClickPayload.tag(ClickPayload.SHOP, "", 0)))
                    .build());
        } else {
            buttons.add(ActionButton.builder(cfg.message("menu.shop-title"))
                    .width(120)
                    .action(DialogAction.customClick(CLICK_KEY,
                            ClickPayload.tag(ClickPayload.SHOP, "", 0)))
                    .build());
        }
        if (totalPages > 1) {
            if (page > 0) {
                buttons.add(navButton(cfg.message("menu.prev-page"), ClickPayload.WH_PREV, page - 1));
            }
            if (page < totalPages - 1) {
                buttons.add(navButton(cfg.message("menu.next-page"), ClickPayload.WH_NEXT, page + 1));
            }
        }

        Component info = cfg.message("menu.page-info", Map.of(
                "page", String.valueOf(page + 1),
                "total", String.valueOf(totalPages),
                "count", String.valueOf(owned.size())));

        Dialogs.show(plugin, player, () -> Dialog.create(b -> b.empty()
                .base(DialogBase.builder(cfg.message("menu.warehouse-title"))
                        .body(List.of(DialogBody.plainMessage(info)))
                        .canCloseWithEscape(true)
                        .build())
                .type(DialogType.multiAction(buttons).columns(2).build())));
    }

    /** 通用结果反馈页（notice 类型，单按钮返回）。 */
    public void feedback(Player player, Component message, String backAction, int backPage) {
        player.getScheduler().execute(plugin,
                () -> doFeedback(player, message, backAction, backPage), null, 0L);
    }

    private void doFeedback(Player player, Component message, String backAction, int backPage) {
        ConfigManager cfg = plugin.configManager();
        String action = backAction == null || backAction.isEmpty() ? ClickPayload.BACK : backAction;
        ActionButton ok = ActionButton.builder(cfg.message("menu.back-button"))
                .width(100)
                .action(DialogAction.customClick(CLICK_KEY, ClickPayload.tag(action, "", backPage)))
                .build();
        Dialogs.show(plugin, player, () -> Dialog.create(b -> b.empty()
                .base(DialogBase.builder(cfg.message("menu.feedback-title"))
                        .body(List.of(DialogBody.plainMessage(message)))
                        .canCloseWithEscape(true)
                        .build())
                .type(DialogType.notice(ok))));
    }

    // ---- 文案 ----

    public Component buyMessage(TagService.BuyResult result, Title title) {
        ConfigManager cfg = plugin.configManager();
        return switch (result) {
            case OK -> cfg.message("purchase.ok",
                    Map.of("duration", ConfigManager.plain(cfg.durationLabel(title.durationDays()))),
                    "title", cfg.displayedName(title));
            case ALREADY_OWNED -> cfg.message("purchase.already-owned");
            case NO_MONEY -> cfg.message("purchase.no-money", Map.of(
                    "price", plugin.economyHook().format(title.totalVaultPrice())));
            case NO_PERMISSION -> cfg.message("purchase.no-permission");
            case NOT_SELLABLE -> cfg.message("purchase.not-sellable");
            case FAILED -> cfg.message("purchase.failed");
        };
    }

    public Component equipMessage(TagService.EquipResult result, Title title) {
        ConfigManager cfg = plugin.configManager();
        return switch (result) {
            case OK -> cfg.message("warehouse.equip-ok", null, "title", cfg.displayedName(title));
            case NOT_OWNED -> cfg.message("warehouse.not-owned");
            case EXPIRED -> cfg.message("warehouse.expired");
            case FAILED -> cfg.message("warehouse.failed");
        };
    }

    // ---- 内部构建辅助 ----

    private List<Title> shopTitles(PlayerTagData data) {
        long now = System.currentTimeMillis();
        List<Title> result = new ArrayList<>();
        for (Title title : plugin.titleManager().ordered()) {
            if (!title.sellable()) {
                continue;
            }
            if (data.owns(title.id(), now)) {
                continue;
            }
            // 含金币条件但无经济插件时根本无法购买，直接不展示以免误导
            if (title.totalVaultPrice() > 0 && !plugin.economyHook().available()) {
                continue;
            }
            result.add(title);
        }
        return result;
    }

    private List<Title> ownedTitles(PlayerTagData data) {
        List<Title> result = new ArrayList<>();
        for (Title title : plugin.titleManager().ordered()) {
            if (data.everOwned(title.id())) {
                result.add(title);
            }
        }
        return result;
    }

    private Component shopTooltip(Title title) {
        ConfigManager cfg = plugin.configManager();
        List<Component> lines = new ArrayList<>(title.descriptionLines());
        lines.add(cfg.message("detail.duration", null,
                "duration", cfg.durationLabel(title.durationDays())));
        lines.add(cfg.requirementsSummary(title));
        return Component.join(JoinConfiguration.newlines(), lines);
    }

    private Component warehouseTooltip(Title title, PlayerTagData data, long now, boolean equipped) {
        ConfigManager cfg = plugin.configManager();
        List<Component> lines = new ArrayList<>(title.descriptionLines());
        Long expires = data.expiresAt(title.id());
        if (expires == null || expires == 0L) {
            lines.add(cfg.message("detail.duration", null, "duration",
                    cfg.message("title.duration-permanent")));
        } else {
            lines.add(cfg.message("detail.duration", null, "duration",
                    cfg.duration(Math.max(0L, expires - now))));
        }
        if (!data.owns(title.id(), now)) {
            lines.add(cfg.message("detail.expired"));
        } else if (equipped) {
            lines.add(cfg.message("menu.equipped-mark"));
        }
        return Component.join(JoinConfiguration.newlines(), lines);
    }

    private ActionButton navButton(Component text, String action, int page) {
        return ActionButton.builder(text)
                .width(80)
                .action(DialogAction.customClick(CLICK_KEY, ClickPayload.tag(action, "", page)))
                .build();
    }

    private int clamp(int page, int totalPages) {
        if (page < 0) {
            return 0;
        }
        return Math.min(page, totalPages - 1);
    }
}
