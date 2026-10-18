package dev.junxiex.mikutag.data;

import dev.junxiex.mikutag.MikuTagPlugin;
import dev.junxiex.mikutag.api.TitleEquipEvent;
import dev.junxiex.mikutag.model.PlayerTagData;
import dev.junxiex.mikutag.model.Requirement;
import dev.junxiex.mikutag.model.Title;
import dev.junxiex.mikutag.util.Permissions;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 称号业务逻辑：购买 / 佩戴 / 卸下 / 发放 / 收回。
 * <p>
 * 会修改数据的方法都必须在"拥有该玩家的区域/实体线程"上调用（菜单点击事件与指令回投均满足），
 * 因为其中会调用 Vault 经济与触发事件，二者都不是线程安全的；
 * 只读方法（{@link #ownedCount(UUID)}、{@link #equippedId(Player)} 等）线程安全，可从任意线程调用。
 */
public final class TagService {

    public enum BuyResult {
        OK, ALREADY_OWNED, NO_MONEY, NO_PERMISSION, NOT_SELLABLE, FAILED
    }

    public enum EquipResult {
        OK, NOT_OWNED, EXPIRED, FAILED
    }

    private static final long DAY_MILLIS = 86_400_000L;

    /** "有效拥有数量"缓存的存活时长：到期类称号随时间失效，靠 TTL 兜底刷新。 */
    private static final long COUNT_TTL_MILLIS = 2000L;

    private final MikuTagPlugin plugin;
    private final PlayerTagStore store;

    /**
     * 拥有数量缓存：uuid →（称号快照代号、计算时刻、数量）。
     * 占位符 {@code %mikutag_count%} 按 观看者×目标 高频求值，缓存把每次 O(称号数) 的遍历降为常数；
     * 授予/回收时主动失效，过期变化由 TTL 覆盖。
     */
    private record CountCache(long titleGeneration, long computedAt, int count) {
    }

    private final Map<UUID, CountCache> countCache = new ConcurrentHashMap<>();

    /** 正在补拉数据的玩家，避免连点菜单时重复发起数据库查询。 */
    private final Map<UUID, Boolean> reloading = new ConcurrentHashMap<>();

    public TagService(MikuTagPlugin plugin, PlayerTagStore store) {
        this.plugin = plugin;
        this.store = store;
    }

    public PlayerTagData data(Player player) {
        return store.get(player.getUniqueId());
    }

    private boolean ready(PlayerTagData data) {
        return data != null && data.loaded();
    }

    /**
     * 购买称号：全部条件（AND）校验通过后才扣款授予。
     * <p>
     * 金币条件按总额<b>一次性</b>扣款：多条 VAULT 条件逐条扣款会让按笔计费的经济插件
     * 重复收取手续费，且中途失败需要回滚，玩家账面会短暂出现多笔流水。先按总额校验余额，
     * 再单次扣款即可，失败即未扣款，无需回滚。
     */
    public BuyResult buy(Player player, Title title) {
        PlayerTagData data = data(player);
        if (!ready(data)) {
            return BuyResult.FAILED;
        }
        long now = System.currentTimeMillis();
        if (data.owns(title.id(), now)) {
            return BuyResult.ALREADY_OWNED;
        }
        if (!title.sellable()) {
            return BuyResult.NOT_SELLABLE;
        }
        double vaultTotal = 0;
        for (Requirement r : title.requirements()) {
            switch (r.type()) {
                case PERMISSION -> {
                    if (!Permissions.hasTitlePermission(player, r.permission())) {
                        return BuyResult.NO_PERMISSION;
                    }
                }
                case VAULT -> {
                    if (!plugin.economyHook().available()) {
                        return BuyResult.NOT_SELLABLE; // 无经济插件，金币条件无法满足
                    }
                    vaultTotal += r.amount();
                }
            }
        }
        if (vaultTotal > 0) {
            if (!plugin.economyHook().has(player, vaultTotal)) {
                return BuyResult.NO_MONEY;
            }
            if (!plugin.economyHook().withdraw(player, vaultTotal)) {
                // 已经过余额校验；仍失败说明余额被并发变动或经济插件拒绝，此时尚未产生任何扣款
                return BuyResult.NO_MONEY;
            }
        }
        grantInternal(player, data, title.id(), title.durationMillis(), now);
        return BuyResult.OK;
    }

    /** 佩戴称号。 */
    public EquipResult equip(Player player, Title title) {
        PlayerTagData data = data(player);
        if (!ready(data)) {
            return EquipResult.FAILED;
        }
        long now = System.currentTimeMillis();
        if (!data.owns(title.id(), now)) {
            return data.everOwned(title.id()) ? EquipResult.EXPIRED : EquipResult.NOT_OWNED;
        }
        setEquipped(player, data, title.id());
        return EquipResult.OK;
    }

    public void unequip(Player player) {
        PlayerTagData data = data(player);
        if (!ready(data) || data.equipped().isEmpty()) {
            return;
        }
        setEquipped(player, data, "");
    }

    private void setEquipped(Player player, PlayerTagData data, String titleId) {
        String previous = data.equipped();
        if (previous.equals(titleId)) {
            return;
        }
        data.setEquipped(titleId);
        store.enqueueState(data);
        plugin.getServer().getPluginManager().callEvent(
                new TitleEquipEvent(player, previous, titleId));
    }

    /**
     * 直接发放称号（管理员指令 / 对外 API）。已永久持有则保持永久，否则取较晚的到期时间。
     * days &lt;= 0 表示永久。
     *
     * @return 数据已加载并完成发放返回 {@code true}；玩家数据尚未加载完成返回 {@code false}
     */
    public boolean grant(Player player, String titleId, int days) {
        PlayerTagData data = data(player);
        if (!ready(data)) {
            return false;
        }
        long now = System.currentTimeMillis();
        long newExpiry = days <= 0 ? 0L : now + days * DAY_MILLIS;
        Long existing = data.expiresAt(titleId);
        long finalExpiry;
        if (newExpiry == 0L || (existing != null && existing == 0L)) {
            finalExpiry = 0L;
        } else {
            finalExpiry = Math.max(existing == null ? 0L : existing, newExpiry);
        }
        data.grant(titleId, finalExpiry);
        store.enqueueGrant(data.uuid(), titleId, finalExpiry);
        countCache.remove(data.uuid());
        maybeAutoEquip(player, data, titleId);
        return true;
    }

    /**
     * 授予称号（购买路径）：永久或按时长到期，并触发自动佩戴。
     * durationMillis 为 0 表示永久。
     */
    private void grantInternal(Player player, PlayerTagData data, String titleId,
                               long durationMillis, long now) {
        long expiresAt = durationMillis <= 0 ? 0L : now + durationMillis;
        data.grant(titleId, expiresAt);
        store.enqueueGrant(data.uuid(), titleId, expiresAt);
        countCache.remove(data.uuid());
        maybeAutoEquip(player, data, titleId);
    }

    /** 配置开启自动佩戴、且玩家当前未佩戴有效称号时，自动佩戴刚获得的称号。 */
    private void maybeAutoEquip(Player player, PlayerTagData data, String titleId) {
        if (!plugin.configManager().autoEquip()) {
            return;
        }
        long now = System.currentTimeMillis();
        String current = data.equipped();
        // 佩戴项已过期时按"未佩戴"处理，否则玩家拿到新称号也不会自动佩戴
        if (current.isEmpty() || !data.owns(current, now)) {
            setEquipped(player, data, titleId);
        }
    }

    /** 收回称号。返回是否确实持有过。 */
    public boolean revoke(Player player, String titleId) {
        PlayerTagData data = data(player);
        if (!ready(data) || !data.everOwned(titleId)) {
            return false;
        }
        String previous = data.equipped();
        data.revoke(titleId);
        store.enqueueRevoke(data.uuid(), titleId);
        countCache.remove(data.uuid());
        if (previous.equals(titleId)) {
            store.enqueueState(data);
            // 被收回的正是当前佩戴的称号：必须广播变化，否则聊天/Tab 等监听方会继续显示已不存在的称号
            plugin.getServer().getPluginManager().callEvent(
                    new TitleEquipEvent(player, previous, ""));
        }
        return true;
    }

    /**
     * 称号被删除后，清除所有在线玩家缓存中该称号的持有与佩戴记录。
     * <p>
     * 库中该称号的记录已随删除级联清理，但在线玩家的内存快照仍保留持有条目；
     * 若不清理，管理员随后用同一 ID 新建称号时，这些玩家会被误判为"已拥有"而无法购买，
     * 仓库中也会出现一条来自旧称号的残留记录。清理必须回到各玩家的属主线程执行。
     */
    public void purgeDeletedTitle(String titleId) {
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            player.getScheduler().run(plugin, task -> {
                PlayerTagData data = data(player);
                if (data != null && data.everOwned(titleId)) {
                    revoke(player, titleId);
                }
            }, null);
        }
    }

    /** 当前佩戴的称号 ID（未佩戴为空串）。已过期的佩戴视为未佩戴。 */
    public String equippedId(Player player) {
        PlayerTagData data = data(player);
        if (data == null) {
            return "";
        }
        String id = data.equipped();
        // 佩戴的称号自然到期后缓存中不会自动清除，此处按"是否仍有效拥有"过滤，
        // 避免占位符/API 继续把已过期的称号当成在佩戴。
        if (!id.isEmpty() && !data.owns(id, System.currentTimeMillis())) {
            return "";
        }
        return id;
    }

    /** 玩家离线时清理其计数缓存，避免该表随历史玩家无限增长。 */
    public void invalidateCountCache(UUID uuid) {
        countCache.remove(uuid);
        reloading.remove(uuid);
    }

    /**
     * 玩家数据缺失（首次进服加载失败、或加载仍未完成）时触发一次重新加载。
     * <p>
     * 进服加载失败后缓存中不会留下任何数据，若玩家不重连就永远无法使用称号。
     * 这里让菜单/指令入口可以主动补拉一次，数据库恢复后无需玩家重连即可恢复。
     * <p>
     * 同一玩家同时只允许一次补拉：菜单按钮可被连点，若不做去重，每次点击都会各自
     * 发起一次数据库查询（数据库异常时更会堆积）。
     *
     * @return 是否确实发起了一次加载
     */
    public boolean requestReload(Player player) {
        if (data(player) != null) {
            return false;
        }
        UUID uuid = player.getUniqueId();
        // 玩家名在进入异步任务前取好：Bukkit 实体 API 不应在异步线程读取
        String name = player.getName();
        if (reloading.putIfAbsent(uuid, Boolean.TRUE) != null) {
            return true; // 已有一次补拉在途，本次不重复发起
        }
        plugin.getServer().getAsyncScheduler().runNow(plugin, task -> {
            try {
                PlayerTagData loaded = store.loadFromStorage(uuid);
                player.getScheduler().run(plugin, t -> {
                    // 补拉期间可能已由正常进服流程写入缓存（且之后又发生过修改）：
                    // 只在仍无数据时写入，避免用较旧的库快照覆盖更新的内存状态。
                    if (player.isOnline() && store.get(uuid) == null) {
                        store.put(loaded);
                    }
                }, null);
            } catch (RuntimeException ex) {
                plugin.getLogger().warning("重新加载玩家 " + name + " 的称号数据失败: "
                        + ex.getMessage());
            } finally {
                reloading.remove(uuid);
            }
        });
        return true;
    }

    /** 当前佩戴的称号定义（未佩戴、已过期或已删除为 null）。 */
    public Title equippedTitle(Player player) {
        String id = equippedId(player);
        return id.isEmpty() ? null : plugin.titleManager().get(id);
    }

    /**
     * 若玩家当前佩戴的称号已过期，则就地卸下并落库。
     * <p>
     * 佩戴状态不会因称号到期而自动消失，若不清理，玩家的"仓库"里会一直留着一条
     * 指向已过期称号的佩戴记录（既无法通过卸下按钮之外的路径消除，也会随重连反复出现）。
     * 必须在玩家属主线程调用。
     */
    public void clearExpiredEquipped(Player player) {
        PlayerTagData data = data(player);
        if (!ready(data)) {
            return;
        }
        String id = data.equipped();
        if (!id.isEmpty() && !data.owns(id, System.currentTimeMillis())) {
            setEquipped(player, data, "");
        }
    }

    /**
     * 当前有效拥有的称号数量：只统计仍存在（未被删除）且未过期的称号。
     * 只读方法，线程安全，可从任意线程调用（如 PlaceholderAPI）。
     */
    public int ownedCount(UUID uuid) {
        PlayerTagData data = store.get(uuid);
        if (data == null) {
            // 玩家已离线移出缓存：一并清掉计数缓存，避免表随历史玩家无限增长
            countCache.remove(uuid);
            return 0;
        }
        long generation = plugin.titleManager().current().generation();
        long now = System.currentTimeMillis();
        CountCache cached = countCache.get(uuid);
        if (cached != null && cached.titleGeneration() == generation
                && now - cached.computedAt() < COUNT_TTL_MILLIS) {
            return cached.count();
        }
        int count = 0;
        for (String id : data.owned().keySet()) {
            if (plugin.titleManager().get(id) != null && data.owns(id, now)) {
                count++;
            }
        }
        countCache.put(uuid, new CountCache(generation, now, count));
        return count;
    }
}
