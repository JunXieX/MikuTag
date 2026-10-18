package dev.junxiex.mikutag.economy;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * Vault Economy 桥接。所有对 {@code net.milkbowl.vault.economy.Economy} 的引用
 * 都集中在这个类中，仅当 Vault 确实存在时才会被 {@link EconomyHook} 调用。
 * <p>
 * 若未安装 Vault，本类的首次加载/校验会抛 NoClassDefFoundError，
 * 由 EconomyHook 的 try/catch(Throwable) 捕获并永久禁用经济功能。
 */
final class EconomyBridge {

    private EconomyBridge() {
    }

    static boolean hasEconomy() {
        return Bukkit.getServicesManager()
                .getRegistration(net.milkbowl.vault.economy.Economy.class) != null;
    }

    static boolean has(Player player, double amount) {
        var rsp = Bukkit.getServicesManager()
                .getRegistration(net.milkbowl.vault.economy.Economy.class);
        return rsp != null && rsp.getProvider().has(player, amount);
    }

    static boolean withdraw(Player player, double amount) {
        var rsp = Bukkit.getServicesManager()
                .getRegistration(net.milkbowl.vault.economy.Economy.class);
        if (rsp == null) {
            return false;
        }
        var response = rsp.getProvider().withdrawPlayer(player, amount);
        return response.transactionSuccess();
    }

    static String format(double amount) {
        var rsp = Bukkit.getServicesManager()
                .getRegistration(net.milkbowl.vault.economy.Economy.class);
        if (rsp == null) {
            return String.valueOf(amount);
        }
        return rsp.getProvider().format(amount);
    }
}
