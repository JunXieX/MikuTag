package dev.junxiex.mikutag.economy;

import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * Vault 经济挂钩。
 * <p>
 * 关键设计：本类不直接引用 {@code net.milkbowl.vault.economy.Economy} 类型，
 * 而是通过 {@link EconomyBridge} 间接访问。这样当服务器未安装 Vault 时，
 * 对 EconomyHook 的调用只会触发桥接类的加载失败并被捕获，
 * 不会污染调用方（如购买逻辑）的类校验，避免运行期 NoClassDefFoundError。
 * <p>
 * 缺失时优雅降级：VAULT 类型的称号在商城中不展示，也无法购买。
 */
public final class EconomyHook {

    private final Plugin plugin;
    private volatile boolean available;
    private volatile boolean resolved;
    private volatile boolean warned;

    public EconomyHook(Plugin plugin) {
        this.plugin = plugin;
    }

    /** 探测 Vault + Economy 是否可用（幂等，线程安全）。 */
    public boolean available() {
        if (!resolved) {
            synchronized (this) {
                if (!resolved) {
                    boolean ok = false;
                    try {
                        if (plugin.getServer().getPluginManager().getPlugin("Vault") != null) {
                            ok = EconomyBridge.hasEconomy();
                        }
                    } catch (Throwable ignored) {
                        ok = false;
                    }
                    available = ok;
                    resolved = true;
                }
            }
        }
        return available;
    }

    /**
     * 重新探测经济可用性（配置重载时调用）。
     * <p>
     * 探测结果一旦确定就会被缓存。若 Vault 已存在、但经济插件的 provider 在本插件首次探测之后
     * 才注册到 ServicesManager（启动顺序不同），此前会永久停留在"不可用"，只能重启服务器。
     * 重载配置时重新判定一次即可恢复。
     */
    public void recheck() {
        synchronized (this) {
            resolved = false;
        }
        available();
    }

    /** 余额是否足够。Vault 不可用时视为不足。必须在玩家线程调用。 */
    public boolean has(Player player, double amount) {
        if (!available()) {
            return false;
        }
        try {
            return EconomyBridge.has(player, amount);
        } catch (Throwable t) {
            warnOnce(t);
            return false;
        }
    }

    /** 扣款。返回是否成功。必须在玩家线程调用。 */
    public boolean withdraw(Player player, double amount) {
        if (!available()) {
            return false;
        }
        try {
            return EconomyBridge.withdraw(player, amount);
        } catch (Throwable t) {
            warnOnce(t);
            return false;
        }
    }

    /** 存款（用于多条件扣款失败后的回滚）。返回是否成功。必须在玩家线程调用。 */
    public boolean deposit(Player player, double amount) {
        if (!available()) {
            return false;
        }
        try {
            return EconomyBridge.deposit(player, amount);
        } catch (Throwable t) {
            warnOnce(t);
            return false;
        }
    }

    /** 金额展示（交由经济插件格式化）。 */
    public String format(double amount) {
        if (!available()) {
            return String.valueOf(amount);
        }
        try {
            return EconomyBridge.format(amount);
        } catch (Throwable t) {
            return String.valueOf(amount);
        }
    }

    private void warnOnce(Throwable t) {
        if (!warned) {
            warned = true;
            plugin.getLogger().warning("Vault 经济操作失败，将降级为不可购买: " + t.getMessage());
        }
    }
}
