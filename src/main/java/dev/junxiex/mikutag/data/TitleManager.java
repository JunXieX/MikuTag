package dev.junxiex.mikutag.data;

import dev.junxiex.mikutag.MikuTagPlugin;
import dev.junxiex.mikutag.model.Requirement;
import dev.junxiex.mikutag.model.Title;
import dev.junxiex.mikutag.storage.Storage;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 称号定义的内存快照。
 * <p>
 * 快照（byId / ordered / generation）打包为不可变 {@link Snapshot}，用单个 volatile 字段整体替换：
 * 任意线程读取 {@code get}/{@code ordered}/{@code generation} 都取自同一快照，三者始终自洽，
 * 不会出现"读到新称号却配旧代号"的撕裂。管理员增删改立即更新快照并异步落库，菜单渲染无需访问数据库。
 * <p>
 * 所有会替换快照的操作（reload / save / delete）都在 {@link #writeLock} 下串行完成，
 * 因此 generation 单调递增、快照不会因并发替换而回退或丢更新；阻塞 IO 一律在锁外执行，
 * 持锁区间仅为微秒级的内存重建，绝不卡住调用 save 的区域/玩家线程。
 * <p>
 * 多服共享同一数据库时，数据库即唯一数据源：各服的快照只是它的缓存，
 * 由插件的定时重读任务（远端库）或 {@code /mikutag reload}（任意库）刷新，不存在跨服同步协议。
 */
public final class TitleManager {

    /**
     * 不可变快照：定义集合 + 排序视图 + 代号 + 内容签名。
     * 代号随每次整体替换递增，供渲染缓存失效判断；签名用于判断重读后的内容是否真的变化。
     */
    public record Snapshot(Map<String, Title> byId, List<Title> ordered, long generation, long signature) {
        static final Snapshot EMPTY = new Snapshot(Map.of(), List.of(), 0, 0);
    }

    private final MikuTagPlugin plugin;
    private final Storage storage;

    private volatile Snapshot snapshot = Snapshot.EMPTY;

    /** 串行化所有快照替换（reload/save/delete）；持锁区间不含任何阻塞 IO。 */
    private final Object writeLock = new Object();

    public TitleManager(MikuTagPlugin plugin, Storage storage) {
        this.plugin = plugin;
        this.storage = storage;
    }

    /**
     * 从库中整表重载并替换快照（调用方须处于异步线程或启动阶段）。
     * <p>
     * 重读前先比对内容签名：完全相同则直接返回 {@code false}，不替换快照、不递增代号。
     * 这样多服定时重读（每 {@code sync.title-interval-seconds} 秒一次）在库中无变化时零副作用——
     * 既不会连累占位符渲染缓存全量失效，也不会触发每轮的权限节点重注册。
     *
     * @return 内容确有变化并已替换快照返回 {@code true}；无变化返回 {@code false}
     */
    public boolean reloadFromStorage() {
        List<Title> loaded = storage.loadTitles(); // 阻塞 IO 在锁外
        long signature = signatureOf(prepared(loaded));
        synchronized (writeLock) {
            if (signature == snapshot.signature()) {
                return false;
            }
            replaceAll(loaded);
            return true;
        }
    }

    /**
     * 整体替换快照并递增代号。调用方必须持有 {@link #writeLock}。
     */
    private void replaceAll(Collection<Title> titles) {
        List<Title> list = prepared(titles);
        Map<String, Title> map = new HashMap<>();
        for (Title title : list) {
            map.put(title.id(), title);
        }
        snapshot = new Snapshot(Map.copyOf(map), List.copyOf(list),
                snapshot.generation() + 1, signatureOf(list));
    }

    /** 去重（按 id）并按展示顺序排序；重载签名比对与实际替换共用同一排序规则。 */
    private static List<Title> prepared(Collection<Title> titles) {
        Map<String, Title> map = new HashMap<>();
        for (Title title : titles) {
            map.put(title.id(), title);
        }
        List<Title> list = new ArrayList<>(map.values());
        list.sort(Comparator.comparingInt(Title::sortOrder).thenComparing(Title::id));
        return list;
    }

    /** 称号定义的内容签名（覆盖所有会影响展示与购买的字段），用于判断重读结果是否变化。 */
    private static long signatureOf(List<Title> ordered) {
        long h = 1125899906842597L;
        for (Title t : ordered) {
            h = h * 31 + t.id().hashCode();
            h = h * 31 + t.name().hashCode();
            h = h * 31 + t.descriptionRaw().hashCode();
            h = h * 31 + t.icon().hashCode();
            h = h * 31 + t.durationDays();
            h = h * 31 + (t.sellable() ? 1 : 0);
            h = h * 31 + t.sortOrder();
            h = h * 31 + t.createdAt();
            for (Requirement r : t.requirements()) {
                h = h * 31 + r.type().hashCode();
                h = h * 31 + Double.hashCode(r.amount());
                h = h * 31 + r.permission().hashCode();
            }
        }
        return h;
    }

    /** 当前整体快照。需要成对读取 title 与 generation 时使用，避免两次 volatile 读之间的撕裂。 */
    public Snapshot current() {
        return snapshot;
    }

    public Title get(String id) {
        return id == null ? null : snapshot.byId().get(id);
    }

    public List<Title> ordered() {
        return snapshot.ordered();
    }

    public int size() {
        return snapshot.byId().size();
    }

    /** 新增或更新：立即更新内存快照（锁内重建），异步落库（锁外）。 */
    public void save(Title title) {
        synchronized (writeLock) {
            Map<String, Title> map = new HashMap<>(snapshot.byId());
            map.put(title.id(), title);
            replaceAll(map.values());
        }
        plugin.getServer().getAsyncScheduler().runNow(plugin, task -> {
            try {
                storage.upsertTitle(title);
            } catch (RuntimeException ex) {
                plugin.getLogger().severe("保存称号 " + title.id() + " 失败: " + ex.getMessage());
            }
        });
    }

    /** 删除称号：立即更新快照（锁内重建），异步级联删除（锁外，含玩家持有记录）。 */
    public void delete(String id) {
        synchronized (writeLock) {
            Map<String, Title> map = new HashMap<>(snapshot.byId());
            if (map.remove(id) == null) {
                return;
            }
            replaceAll(map.values());
        }
        plugin.getServer().getAsyncScheduler().runNow(plugin, task -> {
            try {
                storage.deleteTitle(id);
            } catch (RuntimeException ex) {
                plugin.getLogger().severe("删除称号 " + id + " 失败: " + ex.getMessage());
            }
        });
    }
}
