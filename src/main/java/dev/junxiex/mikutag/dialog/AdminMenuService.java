package dev.junxiex.mikutag.dialog;

import dev.junxiex.mikutag.MikuTagPlugin;
import dev.junxiex.mikutag.config.ConfigManager;
import dev.junxiex.mikutag.model.Requirement;
import dev.junxiex.mikutag.model.Title;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.dialog.DialogResponseView;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 管理员称号管理菜单：列表 / 新建与编辑（使用 DialogInput 表单）/ 删除确认。
 * <p>
 * 表单值在点击保存时通过 {@link DialogResponseView} 读取（Dialog 的每个 input key 都会随点击事件回传），
 * 因此无需服务端保存草稿。
 */
public final class AdminMenuService {

    private final MikuTagPlugin plugin;

    public AdminMenuService(MikuTagPlugin plugin) {
        this.plugin = plugin;
    }

    public void openAdmin(Player player, int page) {
        player.getScheduler().execute(plugin, () -> doOpenAdmin(player, page), null, 0L);
    }

    private void doOpenAdmin(Player player, int page) {
        ConfigManager cfg = plugin.configManager();
        List<Title> titles = plugin.titleManager().ordered();
        int pageSize = cfg.pageSize();
        int totalPages = Math.max(1, (titles.size() + pageSize - 1) / pageSize);
        page = clamp(page, totalPages);
        int from = page * pageSize;
        int to = Math.min(titles.size(), from + pageSize);

        List<ActionButton> buttons = new ArrayList<>();
        buttons.add(ActionButton.builder(cfg.message("admin.new-button"))
                .width(150)
                .action(DialogAction.customClick(MenuService.CLICK_KEY,
                        ClickPayload.tag(ClickPayload.ADMIN_NEW, "", page)))
                .build());
        for (int i = from; i < to; i++) {
            Title title = titles.get(i);
            buttons.add(ActionButton.builder(title.displayName())
                    .tooltip(adminTooltip(title))
                    .width(150)
                    .action(DialogAction.customClick(MenuService.CLICK_KEY,
                            ClickPayload.tag(ClickPayload.ADMIN_EDIT, title.id(), page)))
                    .build());
        }
        if (totalPages > 1) {
            if (page > 0) {
                buttons.add(navButton(cfg.message("menu.prev-page"), ClickPayload.ADMIN_PREV, page - 1));
            }
            if (page < totalPages - 1) {
                buttons.add(navButton(cfg.message("menu.next-page"), ClickPayload.ADMIN_NEXT, page + 1));
            }
        }
        buttons.add(navButton(cfg.message("menu.warehouse-title"), ClickPayload.WAREHOUSE, 0));

        Component info = cfg.message("menu.page-info", Map.of(
                "page", String.valueOf(page + 1),
                "total", String.valueOf(totalPages),
                "count", String.valueOf(titles.size())));

        Dialogs.show(plugin, player, () -> Dialog.create(b -> b.empty()
                .base(DialogBase.builder(cfg.message("menu.admin-title"))
                        .body(List.of(DialogBody.plainMessage(info)))
                        .canCloseWithEscape(true)
                        .build())
                .type(DialogType.multiAction(buttons).columns(2).build())));
    }

    /** 新建（titleId 为空）或编辑称号。 */
    public void openEditor(Player player, String titleId, int page) {
        player.getScheduler().execute(plugin, () -> doOpenEditor(player, titleId, page), null, 0L);
    }

    private void doOpenEditor(Player player, String titleId, int page) {
        ConfigManager cfg = plugin.configManager();
        Title title = titleId == null || titleId.isEmpty() ? null : plugin.titleManager().get(titleId);
        if (titleId != null && !titleId.isEmpty() && title == null) {
            doOpenAdmin(player, page);
            return;
        }
        boolean creating = title == null;

        List<DialogInput> inputs = new ArrayList<>();
        if (creating) {
            inputs.add(text("id", "admin.input.id", "", 64));
        }
        inputs.add(text("name", "admin.input.name", creating ? "" : title.name(), 128));
        inputs.add(text("icon", "admin.input.icon",
                creating ? Material.NAME_TAG.name() : title.icon().name(), 64));
        inputs.add(text("requirements", "admin.input.requirements",
                creating ? "" : Requirement.toText(title.requirements()), 256));
        inputs.add(text("days", "admin.input.days",
                creating ? "0" : String.valueOf(title.durationDays()), 8));
        inputs.add(DialogInput.bool("sellable", cfg.message("admin.input.sellable"))
                .initial(creating || title.sellable())
                .onTrue("true")
                .onFalse("false")
                .build());
        inputs.add(text("description", "admin.input.description",
                creating ? "" : joinDescription(title.description()), 256));

        List<DialogBody> bodies = new ArrayList<>();
        bodies.add(DialogBody.plainMessage(cfg.message("admin.hint.requirements")));
        bodies.add(DialogBody.plainMessage(cfg.message("admin.hint.description")));

        List<ActionButton> buttons = new ArrayList<>();
        buttons.add(ActionButton.builder(cfg.message("admin.save-button"))
                .width(100)
                .action(DialogAction.customClick(MenuService.CLICK_KEY,
                        ClickPayload.tag(ClickPayload.ADMIN_SAVE, creating ? "" : title.id(), page)))
                .build());
        if (!creating) {
            buttons.add(ActionButton.builder(cfg.message("admin.delete-button"))
                    .width(100)
                    .action(DialogAction.customClick(MenuService.CLICK_KEY,
                            ClickPayload.tag(ClickPayload.ADMIN_DEL, title.id(), page)))
                    .build());
        }
        buttons.add(ActionButton.builder(cfg.message("menu.back-button"))
                .width(100)
                .action(DialogAction.customClick(MenuService.CLICK_KEY,
                        ClickPayload.tag(ClickPayload.ADMIN, "", page)))
                .build());

        Component titleText = creating
                ? cfg.message("admin.editor-title-new")
                : cfg.message("admin.editor-title-edit", Map.of("id", title.id()));

        Dialogs.show(plugin, player, () -> Dialog.create(b -> b.empty()
                .base(DialogBase.builder(titleText)
                        .body(bodies)
                        .inputs(inputs)
                        .canCloseWithEscape(true)
                        .build())
                .type(DialogType.multiAction(buttons).columns(3).build())));
    }

    /** 处理保存：读取表单值并校验。由点击监听器在玩家线程调用。 */
    public void handleSave(Player player, String titleId, DialogResponseView view, int page) {
        ConfigManager cfg = plugin.configManager();
        boolean creating = titleId == null || titleId.isEmpty();
        String id = creating ? trim(view.getText("id")) : titleId;
        if (!Title.isValidId(id)) {
            fail(player, cfg.message("admin.invalid-id"), titleId, page);
            return;
        }
        id = id.toLowerCase(Locale.ROOT);
        if (creating && plugin.titleManager().get(id) != null) {
            fail(player, cfg.message("admin.exists", Map.of("id", id)), titleId, page);
            return;
        }
        String name = trim(view.getText("name"));
        if (name.isEmpty()) {
            fail(player, cfg.message("admin.invalid-name"), titleId, page);
            return;
        }
        String iconRaw = trim(view.getText("icon"));
        Material icon = Material.matchMaterial(iconRaw);
        if (icon == null || !icon.isItem()) {
            fail(player, cfg.message("admin.invalid-icon", Map.of("value", iconRaw)), titleId, page);
            return;
        }

        // 解析获取条件（每行/每项 "VAULT:金额" 或 "PERMISSION:节点"，空 = 免费）。
        // 任一非法项都会拒绝整份表单，避免静默丢弃管理员输入的条件。
        List<Requirement> requirements;
        try {
            requirements = Requirement.parseList(view.getText("requirements"));
        } catch (IllegalArgumentException ex) {
            fail(player, cfg.message("admin.invalid-requirement", Map.of("value", ex.getMessage())), titleId, page);
            return;
        }

        int days;
        try {
            days = Integer.parseInt(trim(view.getText("days")));
        } catch (NumberFormatException ex) {
            fail(player, cfg.message("admin.invalid-days", Map.of("value", trim(view.getText("days")))), titleId, page);
            return;
        }
        if (days < 0) {
            days = 0;
        }
        Boolean sellable = view.getBoolean("sellable");
        boolean sell = sellable == null || sellable;

        List<String> description = parseDescription(view.getText("description"));

        Title existing = plugin.titleManager().get(id);
        long createdAt = existing != null ? existing.createdAt() : System.currentTimeMillis();
        int sortOrder = existing != null ? existing.sortOrder() : 0;

        plugin.titleManager().save(new Title(id, name, description, icon,
                requirements, days, sell, sortOrder, createdAt));
        // 新建/修改后按最新条件注册权限节点（PERMISSION 型条件），无需等待 reload
        plugin.refreshTitlePermissions();
        player.sendMessage(cfg.message("admin.saved", Map.of("id", id)));
        doOpenAdmin(player, page);
    }

    /** 删除确认页。 */
    public void openDeleteConfirm(Player player, String titleId, int page) {
        player.getScheduler().execute(plugin, () -> doOpenDeleteConfirm(player, titleId, page), null, 0L);
    }

    private void doOpenDeleteConfirm(Player player, String titleId, int page) {
        ConfigManager cfg = plugin.configManager();
        Title title = plugin.titleManager().get(titleId);
        if (title == null) {
            doOpenAdmin(player, page);
            return;
        }
        ActionButton yes = ActionButton.builder(cfg.message("admin.delete-button"))
                .width(100)
                .action(DialogAction.customClick(MenuService.CLICK_KEY,
                        ClickPayload.tag(ClickPayload.ADMIN_DEL_OK, title.id(), page)))
                .build();
        ActionButton no = ActionButton.builder(cfg.message("menu.cancel-button"))
                .width(100)
                .action(DialogAction.customClick(MenuService.CLICK_KEY,
                        ClickPayload.tag(ClickPayload.ADMIN_EDIT, title.id(), page)))
                .build();
        Dialogs.show(plugin, player, () -> Dialog.create(b -> b.empty()
                .base(DialogBase.builder(cfg.message("admin.delete-confirm-title"))
                        .body(List.of(DialogBody.plainMessage(cfg.message("admin.delete-confirm-body", null,
                                "title", title.displayName()))))
                        .canCloseWithEscape(true)
                        .build())
                .type(DialogType.confirmation(yes, no))));
    }

    /** 执行删除。 */
    public void handleDelete(Player player, String titleId, int page) {
        ConfigManager cfg = plugin.configManager();
        Title title = plugin.titleManager().get(titleId);
        if (title == null) {
            doOpenAdmin(player, page);
            return;
        }
        plugin.titleManager().delete(titleId);
        // 同步清理在线玩家缓存中的该称号持有/佩戴记录，避免其残留导致"同 ID 重建后误判已拥有"
        plugin.tagService().purgeDeletedTitle(titleId);
        player.sendMessage(cfg.message("admin.deleted", null, "title", title.displayName()));
        doOpenAdmin(player, page);
    }

    // ---- 内部辅助 ----

    private DialogInput text(String key, String labelKey, String initial, int maxLength) {
        return DialogInput.text(key, plugin.configManager().message(labelKey))
                .initial(truncate(initial, maxLength))
                .maxLength(maxLength)
                .build();
    }

    private Component adminTooltip(Title title) {
        ConfigManager cfg = plugin.configManager();
        List<Component> lines = new ArrayList<>();
        lines.add(cfg.message("admin.tooltip-id", Map.of("id", title.id())));
        lines.add(cfg.requirementsSummary(title));
        lines.add(cfg.message("detail.duration", null, "duration", cfg.durationLabel(title.durationDays())));
        return Component.join(JoinConfiguration.newlines(), lines);
    }

    private void fail(Player player, Component message, String titleId, int page) {
        player.sendMessage(message);
        doOpenEditor(player, titleId, page);
    }

    private ActionButton navButton(Component text, String action, int page) {
        return ActionButton.builder(text)
                .width(80)
                .action(DialogAction.customClick(MenuService.CLICK_KEY, ClickPayload.tag(action, "", page)))
                .build();
    }

    private int clamp(int page, int totalPages) {
        if (page < 0) {
            return 0;
        }
        return Math.min(page, totalPages - 1);
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    /**
     * 描述在表单里是单行文本：多行用未转义的 {@code |} 分隔，行内的字面 {@code |} 写作 {@code \|}。
     * 转义保证"描述行本身含 |"时编辑往返不丢内容。
     */
    private static String joinDescription(List<String> lines) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) {
                sb.append(" | ");
            }
            sb.append(lines.get(i).replace("\\", "\\\\").replace("|", "\\|"));
        }
        return sb.toString();
    }

    /** {@link #joinDescription} 的逆操作：按未转义的 | 拆行并还原转义。 */
    private static List<String> parseDescription(String raw) {
        List<String> lines = new ArrayList<>();
        if (raw == null || raw.isBlank()) {
            return lines;
        }
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '\\' && i + 1 < raw.length()) {
                char next = raw.charAt(i + 1);
                if (next == '\\' || next == '|') {
                    current.append(next);
                    i++;
                    continue;
                }
            }
            if (c == '|') {
                addLine(lines, current);
                current.setLength(0);
                continue;
            }
            current.append(c);
        }
        addLine(lines, current);
        return lines;
    }

    private static void addLine(List<String> lines, StringBuilder current) {
        String line = current.toString().trim();
        if (!line.isEmpty()) {
            lines.add(line);
        }
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
