package dev.junxiex.mikutag.placeholder;

import dev.junxiex.mikutag.MikuTagPlugin;

/**
 * PlaceholderAPI 挂钩。
 * <p>
 * 与 Vault 相同的隔离策略：本类不引用任何 PAPI 类型，真正引用 PAPI 的
 * {@link PapiExpansion} 只在确认已安装 PlaceholderAPI 后才加载，
 * 未安装时仅触发一次加载失败并静默降级，不会影响插件其他功能。
 */
public final class PlaceholderHook {

    private final MikuTagPlugin plugin;
    private volatile boolean registered;
    /** 保留实例用于停用时注销；persist=true 时 PAPI 不会自动清理。 */
    private PapiExpansion expansion;

    public PlaceholderHook(MikuTagPlugin plugin) {
        this.plugin = plugin;
    }

    public void register() {
        if (plugin.getServer().getPluginManager().getPlugin("PlaceholderAPI") == null) {
            return;
        }
        try {
            PapiExpansion candidate = new PapiExpansion(plugin);
            if (candidate.register()) {
                expansion = candidate;
                registered = true;
                plugin.getLogger().info("已注册 PlaceholderAPI 变量 %mikutag_title% 等。");
            }
        } catch (Throwable t) {
            plugin.getLogger().warning("PlaceholderAPI 变量注册失败: " + t.getMessage());
        }
    }

    /** 停用时注销扩展，避免插件热重载后旧实例占着 identifier 且绑定已停用的插件。 */
    public void unregister() {
        PapiExpansion current = expansion;
        expansion = null;
        registered = false;
        if (current == null) {
            return;
        }
        try {
            current.unregister();
        } catch (Throwable t) {
            plugin.getLogger().warning("PlaceholderAPI 变量注销失败: " + t.getMessage());
        }
    }

    public boolean registered() {
        return registered;
    }
}
