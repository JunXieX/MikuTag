package dev.junxiex.mikutag;

import dev.junxiex.mikutag.api.MikuTagAPI;
import dev.junxiex.mikutag.command.CommandService;
import dev.junxiex.mikutag.config.ConfigManager;
import dev.junxiex.mikutag.data.PlayerTagStore;
import dev.junxiex.mikutag.data.TagService;
import dev.junxiex.mikutag.data.TitleManager;
import dev.junxiex.mikutag.dialog.AdminMenuService;
import dev.junxiex.mikutag.dialog.MenuService;
import dev.junxiex.mikutag.economy.EconomyHook;
import dev.junxiex.mikutag.listener.CustomClickListener;
import dev.junxiex.mikutag.listener.PlayerConnectionListener;
import dev.junxiex.mikutag.model.Requirement;
import dev.junxiex.mikutag.model.Title;
import dev.junxiex.mikutag.placeholder.PlaceholderHook;
import dev.junxiex.mikutag.storage.DatabaseType;
import dev.junxiex.mikutag.storage.SqlStorage;
import dev.junxiex.mikutag.storage.Storage;
import dev.junxiex.mikutag.util.Permissions;
import io.papermc.paper.ServerBuildInfo;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.kyori.adventure.key.Key;
import org.bukkit.Bukkit;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * MikuTag 主类 —— 基于 Dialog 菜单的称号插件。
 * <p>
 * 单代码库同时支持 Paper 与 Folia：所有涉及游戏世界的操作都通过
 * 实体/区域/异步/全局调度器执行，不使用 BukkitScheduler。
 */
public final class MikuTagPlugin extends JavaPlugin {

    private ConfigManager configManager;
    private DatabaseType activeStorageType;
    private Storage storage;
    private TitleManager titleManager;
    private PlayerTagStore tagStore;
    private TagService tagService;
    private EconomyHook economyHook;
    private MenuService menuService;
    private AdminMenuService adminMenuService;
    private PlaceholderHook placeholderHook;

    private volatile ScheduledTask flushTask;
    private volatile int scheduledFlushSeconds;

    /** 远端库（mysql/mariadb）称号定时重读任务；多服共享同一库，库即唯一数据源。 */
    private volatile ScheduledTask titleSyncTask;
    private volatile int scheduledSyncSeconds;

    @Override
    public void onEnable() {
        boolean folia = ServerBuildInfo.buildInfo().isBrandCompatible(Key.key("papermc", "folia"));
        getLogger().info("运行于 " + (folia ? "Folia" : "Paper") + "，Minecraft "
                + ServerBuildInfo.buildInfo().minecraftVersionName());

        configManager = new ConfigManager(this);
        configManager.loadAll();
        registerBasePermissions();

        DatabaseType type = configManager.storageType();
        try {
            // 驱动 jar 由 MikuTagLoader 注入插件 classpath；DriverManager 的 ServiceLoader
            // 自动发现使用系统 classloader，看不到插件类路径，必须用插件 classloader 显式加载
            Class.forName(type.driverClass(), true, getClassLoader());
            storage = new SqlStorage(type, new File(getDataFolder(), "data.db"),
                    configManager.dbHost(), configManager.dbPort(), configManager.dbName(),
                    configManager.dbUser(), configManager.dbPassword(), configManager.dbParameters(),
                    getLogger());
        } catch (Throwable e) {
            getLogger().severe("无法初始化 " + type.id() + " 存储，插件禁用。请检查："
                    + "1) config.yml 的 storage.type 是否正确；2) 数据库是否可连接；"
                    + "3) 对应 JDBC 驱动是否已由 MikuTagLoader 注入（首次启动需能访问 Maven 仓库）。原因："
                    + e);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        this.activeStorageType = type;

        titleManager = new TitleManager(this, storage);
        titleManager.reloadFromStorage();
        tagStore = new PlayerTagStore(storage);
        tagService = new TagService(this, tagStore);
        economyHook = new EconomyHook(this);
        menuService = new MenuService(this);
        adminMenuService = new AdminMenuService(this);
        placeholderHook = new PlaceholderHook(this);

        getServer().getPluginManager().registerEvents(new PlayerConnectionListener(this), this);
        getServer().getPluginManager().registerEvents(new CustomClickListener(this), this);
        new CommandService(this).register();
        placeholderHook.register();
        MikuTagAPI.init(this);

        applyGlobalState();

        getLogger().info("已加载 " + titleManager.size() + " 个称号，存储：" + configManager.dbSummary());
    }

    @Override
    public void onDisable() {
        MikuTagAPI.shutdown();
        // 存储初始化失败时 tagStore 为 null，需判空避免 NPE
        if (tagStore == null) {
            return;
        }
        // 所有修改都已在发生时即时入队，故关闭只需：停掉异步任务 → drain 队列 → 关连接。
        // 不遍历在线玩家读取数据 —— 那些数据归属各自区域线程，主线程读取是数据竞争。
        getServer().getAsyncScheduler().cancelTasks(this);
        // 称号定义的保存/删除走异步任务，可能刚被 cancelTasks 取消：先同步补写，避免刚保存的称号丢失
        try {
            titleManager.flushPendingMutations();
        } catch (RuntimeException ex) {
            getLogger().warning("关闭时补写称号定义失败: " + ex.getMessage());
        }
        try {
            tagStore.drainAndFlush();
        } catch (Exception ex) {
            getLogger().warning("关闭时写回失败: " + ex.getMessage());
        }
        tagStore.close();
    }

    /** 重新加载 config.yml 与 messages.yml，并重新读取数据库中的称号定义。 */
    public void reloadConfigs() {
        configManager.loadAll();
        if (configManager.storageType() != activeStorageType) {
            getLogger().warning("config.yml 的 storage.type 已改为 "
                    + configManager.storageType().id() + "，但驱动在启动时已按 "
                    + activeStorageType.id() + " 加载，切换数据库需重启服务器后才会生效。");
        }
        // 称号定义存于数据库，异步重读以支持手工改库或多服共享（定义数据量极小）。
        // 手动热重载不阻塞区域线程。
        getServer().getAsyncScheduler().runNow(this, task -> {
            try {
                titleManager.reloadFromStorage();
            } catch (RuntimeException ex) {
                getLogger().warning("重读称号列表失败: " + ex.getMessage());
            }
            applyGlobalState();
        });
    }

    /**
     * 权限表与调度器属全局状态：Folia 下玩家执行 /mikutag reload 时当前是区域线程，
     * 而 addPermission/removePermission 会遍历并重算所有在线玩家权限，必须回到全局区域线程。
     */
    private void applyGlobalState() {
        if (Bukkit.isGlobalTickThread()) {
            registerTitlePermissions();
            scheduleFlush();
            scheduleTitleSync();
        } else {
            getServer().getGlobalRegionScheduler().run(this, task -> {
                registerTitlePermissions();
                scheduleFlush();
                scheduleTitleSync();
            });
        }
    }

    /**
     * 多服称号同步（仅 mysql / mariadb）。
     * <p>
     * 多服连的是同一个数据库，库本身就是唯一数据源，不存在"同步协议"；
     * 各服内存中的称号表只是它的缓存。因此同步 = 定时重读：按固定间隔把称号表
     * 重新加载进内存快照，其他服做的增删改自然生效。称号表极小（一条 SELECT），
     * 定时重读的代价可忽略，且不引入任何跨服前置依赖（插件消息/binlog/Redis 都不需要）。
     * <p>
     * 重载后补一次权限节点注册，使其他服新建的 PERMISSION 型称号在本服即时可用。
     */
    private void pollTitleReload() {
        try {
            // 内容未变时跳过权限重注册：定时重读每轮都会跑，无变化还去重算权限纯属浪费
            if (titleManager.reloadFromStorage()) {
                refreshTitlePermissions();
            }
        } catch (RuntimeException ex) {
            getLogger().warning("称号定时重读失败，将在下一轮重试: " + ex.getMessage());
        }
    }

    /** 启动/调整称号定时重读任务；SQLite 不启用（本地单文件库，只有本服读写）。 */
    private synchronized void scheduleTitleSync() {
        if (!configManager.storageType().isRemote()) {
            if (titleSyncTask != null) {
                titleSyncTask.cancel();
                titleSyncTask = null;
                scheduledSyncSeconds = -1;
            }
            return;
        }
        int interval = configManager.titleSyncIntervalSeconds();
        if (titleSyncTask != null && !titleSyncTask.isCancelled() && interval == scheduledSyncSeconds) {
            return;
        }
        if (titleSyncTask != null) {
            titleSyncTask.cancel();
        }
        scheduledSyncSeconds = interval;
        // 首次延迟一个周期再重读，避免与启动阶段的加载重复
        titleSyncTask = getServer().getAsyncScheduler().runAtFixedRate(this,
                task -> pollTitleReload(), interval, interval, TimeUnit.SECONDS);
    }

    /**
     * 称号定义变化后刷新其权限节点。可从任意线程调用：
     * 非全局区域线程时会自动投递到全局区域线程（权限重算会遍历所有在线玩家）。
     */
    public void refreshTitlePermissions() {
        if (Bukkit.isGlobalTickThread()) {
            registerTitlePermissions();
        } else {
            getServer().getGlobalRegionScheduler().run(this, task -> registerTitlePermissions());
        }
    }

    /**
     * 注册基础权限节点。paper-plugin.yml 的 permissions 字段格式与 plugin.yml 不同且文档不明确，
     * 故统一在启用时注册，保证权限描述与默认值生效。
     */
    private void registerBasePermissions() {
        PluginManager pm = getServer().getPluginManager();
        if (pm.getPermission(Permissions.USE) == null) {
            pm.addPermission(new Permission(Permissions.USE, "使用称号功能（打开菜单、佩戴/卸下）",
                    PermissionDefault.TRUE));
        }
        registerPermissionWithChildren(pm, Permissions.ADMIN, "称号插件管理权限");
    }

    /** 为称号的 PERMISSION 型获取条件注册权限节点，使节点拥有明确描述与默认值（OP）。 */
    private void registerTitlePermissions() {
        PluginManager pm = getServer().getPluginManager();
        // 同一节点可能被多个称号共用：去重后每个节点只处理一次
        Set<String> nodes = new HashSet<>();
        for (Title title : titleManager.ordered()) {
            for (Requirement r : title.requirements()) {
                if (r.type() == Requirement.ReqType.PERMISSION && !r.permission().isEmpty()) {
                    nodes.add(r.permission());
                }
            }
        }
        for (String node : nodes) {
            Permission existing = pm.getPermission(node);
            if (existing != null && existing.getDefault() == PermissionDefault.OP) {
                continue;
            }
            if (existing != null) {
                pm.removePermission(node);
            }
            try {
                pm.addPermission(new Permission(node, "称号获取条件权限", PermissionDefault.OP));
            } catch (IllegalArgumentException ex) {
                getLogger().warning("称号权限节点 '" + node + "' 非法，已忽略");
            }
        }
    }

    private void registerPermissionWithChildren(PluginManager pm, String name, String description) {
        if (pm.getPermission(name) != null) {
            return;
        }
        Permission admin = new Permission(name, description, PermissionDefault.OP);
        admin.getChildren().put(Permissions.TITLE_WILDCARD, true);
        pm.addPermission(admin);
    }

    /** 定时批量写回（异步线程，仅 drain 纯数据队列）。周期随 reload 生效。 */
    private synchronized void scheduleFlush() {
        int interval = configManager.flushIntervalSeconds();
        if (flushTask != null && !flushTask.isCancelled() && interval == scheduledFlushSeconds) {
            return;
        }
        if (flushTask != null) {
            flushTask.cancel();
        }
        scheduledFlushSeconds = interval;
        flushTask = getServer().getAsyncScheduler().runAtFixedRate(this, task -> {
            try {
                tagStore.drainAndFlush();
            } catch (Exception ex) {
                getLogger().warning("写回称号数据失败: " + ex.getMessage());
            }
        }, interval, interval, TimeUnit.SECONDS);
    }

    public ConfigManager configManager() {
        return configManager;
    }

    /** 存储层（迁移导入等一次性批量操作直接使用，须处于异步线程）。 */
    public Storage storage() {
        return storage;
    }

    public TitleManager titleManager() {
        return titleManager;
    }

    public PlayerTagStore tagStore() {
        return tagStore;
    }

    public TagService tagService() {
        return tagService;
    }

    public EconomyHook economyHook() {
        return economyHook;
    }

    public MenuService menuService() {
        return menuService;
    }

    public AdminMenuService adminMenuService() {
        return adminMenuService;
    }

    public PlaceholderHook placeholderHook() {
        return placeholderHook;
    }
}
