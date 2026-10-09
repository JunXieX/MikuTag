package dev.junxiex.mikutag.storage;

import java.util.UUID;

/**
 * 玩家数据的写回操作。玩家线程只负责生成这些纯数据快照入队，
 * 异步线程按 FIFO 顺序落库，全程不触碰 Bukkit API。
 */
public sealed interface WriteOp {

    /** 授予/续期：写入或更新一条持有记录。 */
    record Grant(UUID uuid, String titleId, long expiresAt) implements WriteOp {
    }

    /** 回收：删除一条持有记录。 */
    record Revoke(UUID uuid, String titleId) implements WriteOp {
    }

    /** 当前佩戴称号。 */
    record State(UUID uuid, String equipped) implements WriteOp {
    }
}
