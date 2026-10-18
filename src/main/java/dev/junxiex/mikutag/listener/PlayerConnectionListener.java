package dev.junxiex.mikutag.listener;

import dev.junxiex.mikutag.MikuTagPlugin;
import dev.junxiex.mikutag.model.PlayerTagData;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 玩家进出处理：进服时异步加载称号数据并回投玩家线程放入缓存，离线时移出缓存。
 * <p>
 * 退出时无需写回：所有修改在发生时已即时入队
 * （见 {@link dev.junxiex.mikutag.data.PlayerTagStore}）。
 */
public final class PlayerConnectionListener implements Listener {

    private final MikuTagPlugin plugin;

    /** 单调递增的加载序号，配合 {@link #loadSeq} 丢弃快速重连时上一会话迟到的加载结果。 */
    private final AtomicLong sequence = new AtomicLong();
    /** 玩家 UUID → 本会话的加载序号。仅最新序号的加载结果允许写入缓存。 */
    private final Map<UUID, Long> loadSeq = new ConcurrentHashMap<>();

    public PlayerConnectionListener(MikuTagPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        var uuid = player.getUniqueId();
        long token = sequence.incrementAndGet();
        loadSeq.put(uuid, token);
        plugin.getServer().getAsyncScheduler().runNow(plugin, task -> {
            PlayerTagData data;
            try {
                data = plugin.tagStore().loadFromStorage(uuid);
            } catch (Exception ex) {
                // 加载失败必须在此兜住：否则异常会终止整个任务，数据永不入缓存，
                // 玩家将永久停留在"数据加载中"且控制台毫无线索
                plugin.getLogger().severe("加载玩家 " + uuid + " 的称号数据失败: " + ex.getMessage()
                        + "（该玩家本次会话无法使用称号，请检查数据库）");
                // 清掉本次会话的加载序号：否则后续重试（如数据库恢复后重新进服）会被
                // 迟到判定拦下，玩家在该会话里将永远无法加载成功。
                loadSeq.remove(uuid, token);
                player.getScheduler().run(plugin, t -> {
                    if (player.isOnline()) {
                        player.sendMessage(plugin.configManager().message("menu.load-failed"));
                    }
                }, null);
                return;
            }
            // 回投到玩家线程：放入缓存。仅当本次加载的序号仍是最新时才写入——
            // 快速重连时上一会话的加载可能晚于本会话返回，若无条件覆盖，
            // 会把本会话已修改的缓存打回旧快照（写回队列仍在，但内存状态会倒退）。
            player.getScheduler().run(plugin, t -> {
                Long latest = loadSeq.get(uuid);
                if (!player.isOnline() || latest == null || latest != token) {
                    return;
                }
                plugin.tagStore().put(data);
            }, null);
        });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        // 所有修改在发生时已即时入队，退出只需移出缓存，
        // 避免每次退出都按"称号数量"全量重写该玩家的所有记录
        var uuid = event.getPlayer().getUniqueId();
        plugin.tagStore().remove(uuid);
        // 计数缓存以玩家 uuid 为键，离线后不会再被求值，必须同步清理，否则随历史玩家无限增长
        plugin.tagService().invalidateCountCache(uuid);
        loadSeq.remove(uuid); // 会话结束，迟到加载的序号随之失效，避免表无限增长
    }
}
