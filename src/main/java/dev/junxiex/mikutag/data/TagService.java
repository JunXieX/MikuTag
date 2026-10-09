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
     * 金币条件先按总额校验余额，再逐条扣款；某条扣款失败（余额被并发变动等）时
     * 退回已扣部分，避免玩家钱花了称号没到手。
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
        if (vaultTotal > 0 && !plugin.economyHook().has(player, vaultTotal)) {
            return BuyResult.NO_MONEY;
        }
        double spent = 0;
        for (Requirement r : title.requirements()) {
            if (r.type() == Requirement.ReqType.VAULT && r.amount() > 0) {
                if (!plugin.economyHook().withdraw(player, r.amount())) {
                    if (spent > 0 && !plugin.economyHook().deposit(player, spent)) {
                        // 回滚也失败：玩家已扣部分未退回，必须留下可追溯记录（人工补退）
                        plugin.getLogger().severe("玩家 " + player.getName() + " 购买称号 " + title.id()
                                + " 时扣款中断且回滚失败，已扣 " + spent + " 未退回，请人工处理。");
                    }
                    return BuyResult.NO_MONEY;
                }
                spent += r.amount();
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

    /** 配置开启自动佩戴、且玩家当前未佩戴任何称号时，自动佩戴刚获得的称号。 */
    private void maybeAutoEquip(Player player, PlayerTagData data, String titleId) {
        if (plugin.configManager().autoEquip() && data.equipped().isEmpty()) {
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
        }
        return true;
    }

    /** 当前佩戴的称号 ID（未佩戴为空串）。 */
    public String equippedId(Player player) {
        PlayerTagData data = data(player);
        return data == null ? "" : data.equipped();
    }

    /** 当前佩戴的称号定义（未佩戴或已删除为 null）。 */
    public Title equippedTitle(Player player) {
        PlayerTagData data = data(player);
        if (data == null) {
            return null;
        }
        String id = data.equipped();
        return id.isEmpty() ? null : plugin.titleManager().get(id);
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
