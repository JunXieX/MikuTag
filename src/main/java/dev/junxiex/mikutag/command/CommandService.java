package dev.junxiex.mikutag.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.junxiex.mikutag.MikuTagPlugin;
import dev.junxiex.mikutag.config.ConfigManager;
import dev.junxiex.mikutag.model.Requirement;
import dev.junxiex.mikutag.model.Title;
import dev.junxiex.mikutag.migration.PlayerTitleImporter;
import dev.junxiex.mikutag.util.Permissions;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.command.brigadier.argument.ArgumentTypes;
import io.papermc.paper.command.brigadier.argument.resolvers.selector.PlayerSelectorArgumentResolver;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * /mikutag 指令（Paper Brigadier）。在 onEnable 通过 LifecycleEvents.COMMANDS 注册。
 * <p>
 * 根命令与 shop/open 直接打开菜单；equip/unequip 为玩家自助；其余管理指令需要 mikutag.admin。
 * 涉及玩家数据修改的操作都回投到目标玩家的区域/实体线程执行。
 */
public final class CommandService {

    private final MikuTagPlugin plugin;

    public CommandService(MikuTagPlugin plugin) {
        this.plugin = plugin;
    }

    public void register() {
        plugin.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            var registrar = event.registrar();
            registrar.register(Commands.literal("mikutag")
                    .executes(this::openSelf)
                    .then(Commands.literal("shop").executes(this::openShop))
                    .then(Commands.literal("open")
                            .requires(this::isAdmin)
                            .then(Commands.argument("player", ArgumentTypes.player())
                                    .executes(this::openOther)))
                    .then(Commands.literal("equip")
                            .then(Commands.argument("title", StringArgumentType.string())
                                    .suggests((ctx, builder) -> {
                                        String remaining = builder.getRemainingLowerCase();
                                        for (Title title : plugin.titleManager().ordered()) {
                                            if (title.id().startsWith(remaining)) {
                                                builder.suggest(StringArgumentType.escapeIfRequired(title.id()));
                                            }
                                        }
                                        return builder.buildFuture();
                                    })
                                    .executes(this::equip)))
                    .then(Commands.literal("unequip").executes(this::unequip))
                    .then(Commands.literal("admin")
                            .requires(this::isAdmin)
                            .executes(this::openAdmin))
                    .then(Commands.literal("give")
                            .requires(this::isAdmin)
                            .then(Commands.argument("player", ArgumentTypes.player())
                                    .then(Commands.argument("title", StringArgumentType.string())
                                            .suggests((ctx, builder) -> {
                                                String remaining = builder.getRemainingLowerCase();
                                                for (Title title : plugin.titleManager().ordered()) {
                                                    if (title.id().startsWith(remaining)) {
                                                        builder.suggest(StringArgumentType.escapeIfRequired(title.id()));
                                                    }
                                                }
                                                return builder.buildFuture();
                                            })
                                            .executes(ctx -> give(ctx, 0))
                                            .then(Commands.argument("days", IntegerArgumentType.integer(0))
                                                    .executes(ctx -> give(ctx,
                                                            IntegerArgumentType.getInteger(ctx, "days")))))))
                    .then(Commands.literal("take")
                            .requires(this::isAdmin)
                            .then(Commands.argument("player", ArgumentTypes.player())
                                    .then(Commands.argument("title", StringArgumentType.string())
                                            .executes(this::take))))
                    .then(Commands.literal("create")
                            .requires(this::isAdmin)
                            .then(Commands.argument("id", StringArgumentType.word())
                                    .then(Commands.argument("requirements", StringArgumentType.string())
                                            .suggests((ctx, builder) -> {
                                                String remaining = builder.getRemainingLowerCase();
                                                for (String suggestion : List.of(
                                                        "free", "VAULT:", "PERMISSION:")) {
                                                    if (suggestion.toLowerCase(Locale.ROOT)
                                                            .startsWith(remaining)) {
                                                        builder.suggest(suggestion);
                                                    }
                                                }
                                                return builder.buildFuture();
                                            })
                                            .then(Commands.argument("name", StringArgumentType.string())
                                                    .executes(this::create)
                                                    .then(Commands.argument("days",
                                                                    IntegerArgumentType.integer(0))
                                                            .executes(this::create))))))
                    .then(Commands.literal("import")
                            .requires(this::isAdmin)
                            .executes(this::importFromPlayerTitle))
                    .then(Commands.literal("reload")
                            .requires(this::isAdmin)
                            .executes(this::reload))
                    .then(Commands.literal("info")
                            .requires(this::isAdmin)
                            .executes(this::info))
                    .build(),
                    "MikuTag 称号插件指令", List.of("mt", "mtag", "title", "titles"));
        });
    }

    private boolean isAdmin(CommandSourceStack source) {
        return source.getSender().hasPermission(Permissions.ADMIN);
    }

    /**
     * 基础功能权限校验，无权限时回复消息并返回 false。
     * 与 Dialog 点击路径（{@code CustomClickListener} 的 {@code Permissions.canUse}）保持一致，
     * 否则把 {@code mikutag.use} 设为 false 的玩家仍能通过指令打开菜单、佩戴/卸下。
     */
    private boolean requireUse(Player player) {
        if (Permissions.canUse(player)) {
            return true;
        }
        player.sendMessage(plugin.configManager().message("error.no-permission"));
        return false;
    }

    private int openSelf(CommandContext<CommandSourceStack> ctx) {
        if (ctx.getSource().getSender() instanceof Player player) {
            if (requireUse(player)) {
                plugin.menuService().openWarehouse(player, 0);
            }
        } else {
            ctx.getSource().getSender().sendMessage(plugin.configManager().message("command.console-no-menu"));
        }
        return 1;
    }

    private int openShop(CommandContext<CommandSourceStack> ctx) {
        if (ctx.getSource().getSender() instanceof Player player) {
            if (requireUse(player)) {
                plugin.menuService().openShop(player, 0);
            }
        } else {
            ctx.getSource().getSender().sendMessage(plugin.configManager().message("command.console-no-menu"));
        }
        return 1;
    }

    private int openOther(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        CommandSender sender = ctx.getSource().getSender();
        Player target = resolvePlayer(ctx, "player");
        if (target == null) {
            sender.sendMessage(plugin.configManager().message("command.player-not-found",
                    Map.of("player", "?")));
            return 0;
        }
        plugin.menuService().openWarehouse(target, 0);
        return 1;
    }

    private int openAdmin(CommandContext<CommandSourceStack> ctx) {
        if (ctx.getSource().getSender() instanceof Player player) {
            plugin.adminMenuService().openAdmin(player, 0);
        } else {
            ctx.getSource().getSender().sendMessage(plugin.configManager().message("command.console-no-menu"));
        }
        return 1;
    }

    private int equip(CommandContext<CommandSourceStack> ctx) {
        if (!(ctx.getSource().getSender() instanceof Player player)) {
            ctx.getSource().getSender().sendMessage(plugin.configManager().message("error.player-only"));
            return 0;
        }
        if (!requireUse(player)) {
            return 0;
        }
        Title title = plugin.titleManager().get(StringArgumentType.getString(ctx, "title"));
        if (title == null) {
            player.sendMessage(plugin.configManager().message("command.title-not-found",
                    Map.of("title", StringArgumentType.getString(ctx, "title"))));
            return 0;
        }
        player.getScheduler().run(plugin, task -> {
            var result = plugin.tagService().equip(player, title);
            player.sendMessage(plugin.menuService().equipMessage(result, title));
        }, null);
        return 1;
    }

    private int unequip(CommandContext<CommandSourceStack> ctx) {
        if (!(ctx.getSource().getSender() instanceof Player player)) {
            ctx.getSource().getSender().sendMessage(plugin.configManager().message("error.player-only"));
            return 0;
        }
        if (!requireUse(player)) {
            return 0;
        }
        player.getScheduler().run(plugin, task -> {
            if (plugin.tagService().equippedId(player).isEmpty()) {
                player.sendMessage(plugin.configManager().message("command.unequip-none"));
            } else {
                plugin.tagService().unequip(player);
                player.sendMessage(plugin.configManager().message("command.unequip-ok"));
            }
        }, null);
        return 1;
    }

    private int give(CommandContext<CommandSourceStack> ctx, int days) throws CommandSyntaxException {
        CommandSender sender = ctx.getSource().getSender();
        String rawId = StringArgumentType.getString(ctx, "title");
        Title title = plugin.titleManager().get(rawId);
        if (title == null) {
            sender.sendMessage(plugin.configManager().message("command.title-not-found",
                    Map.of("title", rawId)));
            return 0;
        }
        Player target = resolvePlayer(ctx, "player");
        if (target == null) {
            sender.sendMessage(plugin.configManager().message("command.player-not-found",
                    Map.of("player", "?")));
            return 0;
        }
        // grant 会改玩家缓存并可能触发自动佩戴，必须在目标玩家属主线程执行；
        // 结果按真实成败回发，避免"数据未加载导致发放被跳过、管理员却收到成功提示"。
        target.getScheduler().run(plugin, task -> {
            boolean ok = plugin.tagService().grant(target, title.id(), days);
            reply(sender, plugin.configManager().message(ok ? "command.give-ok" : "command.data-loading",
                    Map.of("player", target.getName(),
                            "title", ConfigManager.plain(title.displayName()),
                            "duration", ConfigManager.plain(plugin.configManager().durationLabel(days)))));
        }, null);
        return 1;
    }

    private int take(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        CommandSender sender = ctx.getSource().getSender();
        String rawId = StringArgumentType.getString(ctx, "title");
        Title title = plugin.titleManager().get(rawId);
        if (title == null) {
            sender.sendMessage(plugin.configManager().message("command.title-not-found",
                    Map.of("title", rawId)));
            return 0;
        }
        Player target = resolvePlayer(ctx, "player");
        if (target == null) {
            sender.sendMessage(plugin.configManager().message("command.player-not-found",
                    Map.of("player", "?")));
            return 0;
        }
        // revoke 改玩家缓存，必须在目标玩家属主线程执行；结果按真实成败回发。
        target.getScheduler().run(plugin, task -> {
            boolean ok = plugin.tagService().revoke(target, title.id());
            reply(sender, plugin.configManager().message(ok ? "command.take-ok" : "command.take-not-owned",
                    Map.of("player", target.getName(),
                            "title", ConfigManager.plain(title.displayName()))));
        }, null);
        return 1;
    }

    /**
     * 从任意线程安全地向 sender 回发消息。Folia 下向玩家发送消息必须回到该玩家的属主线程，
     * 控制台发送者则直接发送。
     */
    private void reply(CommandSender sender, Component message) {
        if (sender instanceof Player player) {
            player.getScheduler().run(plugin, task -> player.sendMessage(message), null);
        } else {
            sender.sendMessage(message);
        }
    }

    /**
     * 命令行新建称号：{@code /mikutag create <id> <requirements> <name> [days]}。
     * <p>
     * requirements 为条件串（含空格时用引号包裹）：{@code free}，或
     * {@code "VAULT:1000 | PERMISSION:mikutag.vip"}（多项用 | 分隔，须全部满足）。
     * name 含空格时用引号包裹；days 省略或 0 表示永久。图标默认 NAME_TAG、描述为空；
     * 更细的属性（图标/描述/出售开关/排序）可随后用 /mikutag admin 的表单继续编辑。
     */
    private int create(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        ConfigManager cfg = plugin.configManager();

        String id = StringArgumentType.getString(ctx, "id").toLowerCase(Locale.ROOT);
        if (!Title.isValidId(id)) {
            sender.sendMessage(cfg.message("admin.invalid-id"));
            return 0;
        }
        if (plugin.titleManager().get(id) != null) {
            sender.sendMessage(cfg.message("admin.exists", Map.of("id", id)));
            return 0;
        }
        List<Requirement> requirements;
        try {
            requirements = Requirement.parseList(StringArgumentType.getString(ctx, "requirements"));
        } catch (IllegalArgumentException ex) {
            sender.sendMessage(cfg.message("admin.invalid-requirement", Map.of("value", ex.getMessage())));
            return 0;
        }
        String name = StringArgumentType.getString(ctx, "name").trim();
        if (name.isEmpty()) {
            sender.sendMessage(cfg.message("admin.invalid-name"));
            return 0;
        }
        int days = optionalInt(ctx, "days");
        // 默认进商城出售，与 /mikutag admin 表单的"新建"默认值保持一致。
        // （曾按"有无条件"决定：免费称号会被置为不出售，导致其在商城中永远无法领取。）
        boolean sellable = true;

        Title title = new Title(id, name, List.of(), Material.NAME_TAG,
                requirements, days, sellable, 0, System.currentTimeMillis());
        plugin.titleManager().save(title);
        // 新建后立即按条件注册权限节点（PERMISSION 型），无需等待 reload
        plugin.refreshTitlePermissions();

        Component message = cfg.message("command.create-ok",
                Map.of("id", id,
                        "duration", ConfigManager.plain(cfg.durationLabel(days))),
                "title", title.displayName());
        message = message.append(cfg.message("command.create-req", null,
                "requirements", cfg.requirementsSummary(title)));
        sender.sendMessage(message);
        return 1;
    }

    /** 读取可选数值参数：参数未提供时 Brigadier 会抛出 IllegalArgumentException。 */
    private static int optionalInt(CommandContext<CommandSourceStack> ctx, String name) {
        try {
            return IntegerArgumentType.getInteger(ctx, name);
        } catch (IllegalArgumentException ex) {
            return 0;
        }
    }

    /**
     * 从 PlayerTitle 迁移称号数据（一次性，幂等）。阻塞 IO 全部在异步线程执行，
     * 结果通过消息回发；发送者可能是控制台或玩家，两者都支持。
     */
    private int importFromPlayerTitle(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        ConfigManager cfg = plugin.configManager();
        sender.sendMessage(cfg.message("command.import-start"));
        plugin.getServer().getAsyncScheduler().runNow(plugin, task -> {
            PlayerTitleImporter.Result result = null;
            String failure = null;
            try {
                result = PlayerTitleImporter.run(plugin);
            } catch (PlayerTitleImporter.ImportException ex) {
                failure = ex.getMessage();
            } catch (RuntimeException ex) {
                plugin.getLogger().severe("PlayerTitle 导入失败: " + ex);
                failure = String.valueOf(ex.getMessage());
            }
            // 回发消息：玩家发送者须回其属主线程（Folia），控制台直接发
            if (sender instanceof Player player) {
                final PlayerTitleImporter.Result r = result;
                final String reason = failure;
                player.getScheduler().run(plugin, t -> sendImportResult(player, r, reason), null);
            } else {
                sendImportResult(sender, result, failure);
            }
            if (result != null) {
                plugin.refreshTitlePermissions();
            }
        });
        return 1;
    }

    private void sendImportResult(CommandSender sender, PlayerTitleImporter.Result result, String failure) {
        ConfigManager cfg = plugin.configManager();
        if (failure != null) {
            sender.sendMessage(cfg.message("command.import-failed", Map.of("reason", failure)));
            return;
        }
        sender.sendMessage(cfg.message("command.import-done", Map.of(
                "titles", String.valueOf(result.titlesImported()),
                "skippedTitles", String.valueOf(result.titlesSkipped()),
                "grants", String.valueOf(result.grantsImported()),
                "skippedGrants", String.valueOf(result.grantsSkipped()))));
        for (String warning : result.warnings()) {
            sender.sendMessage(cfg.message("command.import-warning", Map.of("text", warning)));
        }
    }

    private int reload(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        // reloadConfigs 的称号重读是异步的：立即回复会报出重读前的旧数量。
        // 先回"已触发"，重读完成后再回报真实数量（在重读任务的回调里回投主/区域线程）。
        sender.sendMessage(plugin.configManager().message("command.reload-start"));
        plugin.reloadConfigs(() -> reply(sender, plugin.configManager().message("command.reload",
                Map.of("count", String.valueOf(plugin.titleManager().size())))));
        return 1;
    }

    private int info(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        ConfigManager cfg = plugin.configManager();
        sender.sendMessage(cfg.message("command.info-header", Map.of(
                "version", plugin.getPluginMeta().getVersion(),
                "storage", cfg.dbSummary(),
                "titles", String.valueOf(plugin.titleManager().size()),
                "vault", ConfigManager.plain(cfg.message(
                        plugin.economyHook().available() ? "command.enabled" : "command.disabled")))));
        sender.sendMessage(cfg.message("command.info-body", Map.of(
                "cached", String.valueOf(plugin.tagStore().cachedCount()),
                "pending", String.valueOf(plugin.tagStore().pendingCount()),
                "papi", ConfigManager.plain(cfg.message(
                        plugin.placeholderHook().registered() ? "command.enabled" : "command.disabled")))));
        return 1;
    }

    private Player resolvePlayer(CommandContext<CommandSourceStack> ctx, String name)
            throws CommandSyntaxException {
        PlayerSelectorArgumentResolver resolver = ctx.getArgument(name, PlayerSelectorArgumentResolver.class);
        var list = resolver.resolve(ctx.getSource());
        return list.isEmpty() ? null : list.get(0);
    }
}
