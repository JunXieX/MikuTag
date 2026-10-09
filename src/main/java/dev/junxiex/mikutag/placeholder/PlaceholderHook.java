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

    public PlaceholderHook(MikuTagPlugin plugin) {
        this.plugin = plugin;
    }

    public void register() {
        if (plugin.getServer().getPluginManager().getPlugin("PlaceholderAPI") == null) {
            return;
        }
        try {
            if (new PapiExpansion(plugin).register()) {
                registered = true;
                plugin.getLogger().info("已注册 PlaceholderAPI 变量 %mikutag_title% 等。");
            }
        } catch (Throwable t) {
            plugin.getLogger().warning("PlaceholderAPI 变量注册失败: " + t.getMessage());
        }
    }

    public boolean registered() {
        return registered;
    }
}
