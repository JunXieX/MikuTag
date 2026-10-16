package dev.junxiex.mikutag.data;

import dev.junxiex.mikutag.MikuTagPlugin;
import dev.junxiex.mikutag.model.Requirement;
import dev.junxiex.mikutag.model.Title;
import dev.junxiex.mikutag.storage.Storage;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

    /**
     * 尚未确认落库的本地改动明细，由 {@link #writeLock} 保护。
     * <p>
     * 保存/删除是"先改内存快照、后异步落库"，存在两个窗口需要兜底：
     * <ol>
     *   <li>重读（定时同步 / {@code /mikutag reload}）在写库完成前发生：若直接以库内容整体覆盖，
     *       刚保存的称号会被库中的旧版本打回原样。故重读时把这些改动叠加到库内容之上；</li>
     *   <li>关服时异步写库任务可能还没执行就被取消：{@link #flushPendingMutations()} 同步补写。</li>
     * </ol>
     * 值为 {@link Title} 实例本身，落库回调通过引用相等判断"是否仍是最新版本"。
     */
    private final Map<String, Title> dirtyUpserts = new HashMap<>();
    private final Set<String> dirtyDeletes = new HashSet<>();

    /**
     * 本地改动版本号，任何一次 save/delete 使其自增，由 {@link #writeLock} 保护。
     * <p>
     * 重读需要先读库（阻塞 IO，在锁外）再拿锁替换；若期间发生本地增删改，
     * 读到的库内容就是"旧于此刻快照"的，直接替换会让刚保存的称号回退。
     * 故在读取前记录版本号、拿锁后比对，不一致则跳过本轮（下一轮自然一致）。
     */
    private long mutationVersion;

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
        long versionBefore;
        synchronized (writeLock) {
            versionBefore = mutationVersion;
        }
        List<Title> loaded = new ArrayList<>(storage.loadTitles()); // 阻塞 IO 在锁外；拷贝一份以便就地叠加改动
        synchronized (writeLock) {
            // 读库期间发生了本地增删改：本次读到的内容可能未包含这些改动，跳过本轮，
            // 否则刚保存的称号会被库中的旧版本覆盖（下一轮重读即可拿到一致内容）。
            if (mutationVersion != versionBefore) {
                return false;
            }
            // 叠加更早的、尚未落库的本地改动：库内容可能还没包含刚保存的称号 / 仍含刚删除的称号。
            applyDirtyLocked(loaded);
            if (signatureOf(prepared(loaded)) == snapshot.signature()) {
                return false;
            }
            replaceAll(loaded);
            return true;
        }
    }

    /**
     * 把未落库的本地改动叠加到从库读到的内容之上。调用方必须持有 {@link #writeLock}。
     */
    private void applyDirtyLocked(List<Title> loaded) {
        if (dirtyUpserts.isEmpty() && dirtyDeletes.isEmpty()) {
            return;
        }
        loaded.removeIf(t -> dirtyDeletes.contains(t.id()));
        for (Title title : dirtyUpserts.values()) {
            loaded.removeIf(t -> t.id().equals(title.id()));
            loaded.add(title);
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
            dirtyUpserts.put(title.id(), title);
            dirtyDeletes.remove(title.id());
            mutationVersion++;
        }
        plugin.getServer().getAsyncScheduler().runNow(plugin, task -> {
            try {
                storage.upsertTitle(title);
                // 仅在确实写入成功后才清除脏标记：写库失败时保留，使重读继续以内存版本为准，
                // 并在关服时由 flushPendingMutations 再补写一次，避免管理员的改动被旧库内容覆盖。
                markPersisted(title.id(), title, null);
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
            dirtyDeletes.add(id);
            dirtyUpserts.remove(id);
            mutationVersion++;
        }
        plugin.getServer().getAsyncScheduler().runNow(plugin, task -> {
            try {
                storage.deleteTitle(id);
                // 同上：仅成功才清除脏标记
                markPersisted(id, null, id);
            } catch (RuntimeException ex) {
                plugin.getLogger().severe("删除称号 " + id + " 失败: " + ex.getMessage());
            }
        });
    }

    /**
     * 一次本地改动已成功落库，清除其脏标记。
     * <p>
     * 只清除"仍是本次任务对应版本"的标记：若期间同一 ID 又被保存（快照中是新的 {@link Title} 实例），
     * 则保留标记，避免旧任务的完成回调把后续改动误标为已落库。
     */
    private void markPersisted(String id, Title saved, String deletedId) {
        synchronized (writeLock) {
            if (saved != null) {
                if (dirtyUpserts.get(id) == saved) {
                    dirtyUpserts.remove(id);
                }
            } else if (dirtyDeletes.contains(deletedId)) {
                dirtyDeletes.remove(deletedId);
            }
        }
    }

    /**
     * 停用时同步补写尚未落库的称号改动（保存/删除）。
     * <p>
     * 正常的保存/删除任务由异步调度器执行，关服时可能还未运行就被取消；此处兜底一次，
     * 保证管理员在关服前刚做的改动不丢。调用方须处于关闭阶段（此后不再接受新改动）。
     */
    public void flushPendingMutations() {
        Map<String, Title> upserts;
        Set<String> deletes;
        synchronized (writeLock) {
            if (dirtyUpserts.isEmpty() && dirtyDeletes.isEmpty()) {
                return;
            }
            upserts = new HashMap<>(dirtyUpserts);
            deletes = new HashSet<>(dirtyDeletes);
            dirtyUpserts.clear();
            dirtyDeletes.clear();
        }
        for (Title title : upserts.values()) {
            try {
                storage.upsertTitle(title);
            } catch (RuntimeException ex) {
                plugin.getLogger().severe("关服补写称号 " + title.id() + " 失败: " + ex.getMessage());
            }
        }
        for (String id : deletes) {
            try {
                storage.deleteTitle(id);
            } catch (RuntimeException ex) {
                plugin.getLogger().severe("关服补删称号 " + id + " 失败: " + ex.getMessage());
            }
        }
    }
}
