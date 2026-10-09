package dev.junxiex.mikutag.storage;

import dev.junxiex.mikutag.model.PlayerTagData;
import dev.junxiex.mikutag.model.Title;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 存储抽象。称号定义为低频写、读取多；玩家数据为高频读写，
 * 通过 {@link WriteOp} 批量写回降低数据库压力。
 * <p>
 * 多服共享同一数据库时，数据库即唯一数据源，各服无需额外同步协议，
 * 只需定时重读称号表（见 {@code MikuTagPlugin} 的称号定时重读）。
 */
public interface Storage {

    List<Title> loadTitles();

    void upsertTitle(Title title);

    void deleteTitle(String id);

    /** 加载玩家数据（阻塞 IO，必须在异步线程调用）。 */
    PlayerTagData loadPlayer(UUID uuid);

    /**
     * 全量玩家数据快照（阻塞 IO，仅迁移导入时调用）。
     * 迁移需要"读取-合并-整批写回"，逐玩家读太慢，整体快照一次到位。
     */
    PlayerSnapshot loadPlayerSnapshot();

    /** 玩家数据全量快照：持有记录与佩戴状态。 */
    record PlayerSnapshot(Map<UUID, Map<String, Long>> owned, Map<UUID, String> equipped) {
    }

    /** 批量写回玩家数据（阻塞 IO，必须在异步线程调用）。 */
    void writeBatch(List<WriteOp> ops);

    void close();
}
