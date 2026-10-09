package dev.junxiex.mikutag.api;

import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * 玩家佩戴的称号发生变化时触发（佩戴 / 切换 / 卸下）。
 * 供其他插件监听并同步显示（聊天、Tab、头顶等）。
 */
public final class TitleEquipEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private final String previousTitleId;
    private final String newTitleId;

    public TitleEquipEvent(Player player, String previousTitleId, String newTitleId) {
        this.player = player;
        this.previousTitleId = previousTitleId == null ? "" : previousTitleId;
        this.newTitleId = newTitleId == null ? "" : newTitleId;
    }

    public Player getPlayer() {
        return player;
    }

    /** 变化前的称号 ID，空串表示之前未佩戴。 */
    public String getPreviousTitleId() {
        return previousTitleId;
    }

    /** 变化后的称号 ID，空串表示已卸下。 */
    public String getNewTitleId() {
        return newTitleId;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
