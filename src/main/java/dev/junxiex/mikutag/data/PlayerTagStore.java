package dev.junxiex.mikutag.data;

import dev.junxiex.mikutag.model.PlayerTagData;
import dev.junxiex.mikutag.storage.Storage;
import dev.junxiex.mikutag.storage.WriteOp;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * 玩家称号数据的内存缓存 + write-behind 写回队列。
 * <p>
 * 单个玩家的 {@link PlayerTagData} 只在拥有该玩家的区域/实体线程修改；
 * 修改后由该线程生成不可变 {@link WriteOp} 快照入队，异步线程仅从队列 drain
 * 纯数据行写库，全程不触碰 Bukkit API，也无需跨线程读取内部 Map。
 * <p>
 * 约定：对 {@link PlayerTagData} 的任何修改都必须在同一线程内立刻调用对应的 enqueueXxx，
 * 该队列是持久化的唯一来源，因此玩家退出时无需再全量入队。
 */
public final class PlayerTagStore {

    /**
     * 串行化"出队+写库"与"加载"：
     * 1) 防止两个 flush 线程拿到同一玩家的新旧快照后乱序写库（旧覆盖新）；
     * 2) 玩家快速重连时，join 的 load 先 flush 掉上一会话的待写行，保证读到最新数据。
     */
    private final Object ioLock = new Object();

    private final Map<UUID, PlayerTagData> cache = new ConcurrentHashMap<>();
    private final ConcurrentLinkedDeque<WriteOp> pending = new ConcurrentLinkedDeque<>();
    private final Storage storage;

    public PlayerTagStore(Storage storage) {
        this.storage = storage;
    }

    public PlayerTagData get(UUID uuid) {
        return cache.get(uuid);
    }

    public void put(PlayerTagData data) {
        cache.put(data.uuid(), data);
    }

    public void remove(UUID uuid) {
        cache.remove(uuid);
    }

    /** 异步线程直接加载（阻塞 IO）。加载前先 flush 待写队列，保证读到的包含上一会话的最新数据。 */
    public PlayerTagData loadFromStorage(UUID uuid) {
        synchronized (ioLock) {
            flushPendingLocked();
            return storage.loadPlayer(uuid);
        }
    }

    public void enqueueGrant(UUID uuid, String titleId, long expiresAt) {
        pending.add(new WriteOp.Grant(uuid, titleId, expiresAt));
    }

    public void enqueueRevoke(UUID uuid, String titleId) {
        pending.add(new WriteOp.Revoke(uuid, titleId));
    }

    public void enqueueState(PlayerTagData data) {
        pending.add(new WriteOp.State(data.uuid(), data.equipped()));
    }

    /** 异步线程：drain 队列并批量写库。 */
    public void drainAndFlush() {
        synchronized (ioLock) {
            flushPendingLocked();
        }
    }

    /** 调用方必须持有 ioLock。写库失败时把已出队的行按原顺序放回队首，避免丢数据且保持 FIFO。 */
    private void flushPendingLocked() {
        if (pending.isEmpty()) {
            return;
        }
        List<WriteOp> batch = new ArrayList<>();
        WriteOp op;
        while ((op = pending.pollFirst()) != null) {
            batch.add(op);
        }
        try {
            storage.writeBatch(batch);
        } catch (RuntimeException ex) {
            // 必须放回队首（逆序 addFirst 还原原顺序）：若追加到队尾，写库失败期间新入队的
            // 更新快照会排在旧快照之前，下一轮 flush 会把旧数据写在后面，导致状态倒退。
            for (int i = batch.size() - 1; i >= 0; i--) {
                pending.addFirst(batch.get(i));
            }
            throw ex;
        }
    }

    public int pendingCount() {
        return pending.size();
    }

    public int cachedCount() {
        return cache.size();
    }

    public void close() {
        storage.close();
    }
}
