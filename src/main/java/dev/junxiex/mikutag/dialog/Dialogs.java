package dev.junxiex.mikutag.dialog;

import dev.junxiex.mikutag.MikuTagPlugin;
import io.papermc.paper.dialog.Dialog;
import org.bukkit.entity.Player;

import java.util.function.Supplier;

/**
 * Dialog 显示工具。
 * <p>
 * Dialog 的构建与下发完全面向客户端，任何一处参数不合法都会在运行时抛出，
 * 若不捕获会静默地让玩家"点不动"且控制台无线索。这里统一兜底：
 * 记录完整堆栈并给玩家一条可读的失败提示。
 */
public final class Dialogs {

    private Dialogs() {
    }

    public static void show(MikuTagPlugin plugin, Player player, Supplier<Dialog> supplier) {
        try {
            player.showDialog(supplier.get());
        } catch (RuntimeException ex) {
            plugin.getLogger().severe("为玩家 " + player.getName() + " 构建/显示 Dialog 失败: " + ex);
            player.sendMessage(plugin.configManager().message("menu.failed"));
        }
    }
}
