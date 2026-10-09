package dev.junxiex.mikutag.model;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 单个玩家的称号数据：已拥有的称号（titleId → 到期时间，0 表示永久）与当前佩戴称号。
 * <p>
 * 写操作只发生在拥有该玩家的区域/实体线程；读取可能来自其他线程（如 PlaceholderAPI），
 * 因此 {@link #owned} 使用并发容器、{@link #equipped} 使用 volatile。
 */
public final class PlayerTagData {

    private final UUID uuid;
    private final Map<String, Long> owned = new ConcurrentHashMap<>();
    private volatile String equipped = "";
    private volatile boolean loaded;

    public PlayerTagData(UUID uuid) {
        this.uuid = uuid;
    }

    public UUID uuid() {
        return uuid;
    }

    public boolean loaded() {
        return loaded;
    }

    public void markLoaded() {
        this.loaded = true;
    }

    /** 只读视图（非拷贝，反映最新状态），供遍历与计数使用。 */
    public Map<String, Long> owned() {
        return java.util.Collections.unmodifiableMap(owned);
    }

    /** 当前是否有效拥有某称号（永久或未过期）。 */
    public boolean owns(String titleId, long now) {
        Long expires = owned.get(titleId);
        return expires != null && (expires == 0L || expires > now);
    }

    /** 是否曾经获得过该称号（含已过期），用于商城隐藏已购买称号。 */
    public boolean everOwned(String titleId) {
        return owned.containsKey(titleId);
    }

    public Long expiresAt(String titleId) {
        return owned.get(titleId);
    }

    public void grant(String titleId, long expiresAt) {
        owned.put(titleId, expiresAt);
    }

    public void revoke(String titleId) {
        owned.remove(titleId);
        if (titleId.equals(equipped)) {
            equipped = "";
        }
    }

    public String equipped() {
        return equipped;
    }

    public void setEquipped(String titleId) {
        this.equipped = titleId == null ? "" : titleId;
    }
}
