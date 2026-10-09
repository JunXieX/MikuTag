package dev.junxiex.mikutag.placeholder;

import dev.junxiex.mikutag.MikuTagPlugin;
import dev.junxiex.mikutag.config.ConfigManager;
import dev.junxiex.mikutag.data.TitleManager;
import dev.junxiex.mikutag.model.PlayerTagData;
import dev.junxiex.mikutag.model.Title;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * PlaceholderAPI 变量扩展。当前佩戴称号提供三种形态，供不同显示插件按需取用：
 * <ul>
 *   <li>{@code %mikutag_title%} — 带 § 颜色码、含配置显示前后缀（多数聊天/Tab 插件直接可用）</li>
 *   <li>{@code %mikutag_title_strip%} — 剥离颜色的纯文本，含显示前后缀</li>
 *   <li>{@code %mikutag_title_raw%} — 原始 MiniMessage 模板，<b>不含</b>显示前后缀，
 *       交由消费插件自行解析样式</li>
 *   <li>{@code %mikutag_title_id%} — 当前佩戴称号 ID</li>
 *   <li>{@code %mikutag_count%} — 当前有效拥有的称号数量</li>
 *   <li>{@code %mikutag_has_<称号ID>%} — 是否有效持有（true/false）</li>
 * </ul>
 * 数据取自内存缓存（仅在线玩家）；对已离线且不在缓存中的玩家返回空值。
 * <p>
 * 占位符在 Tab/聊天插件中按 观看者×目标 高频求值（多人服每秒可达数千次），
 * 因此对带色/去色两种渲染形态按称号 ID 缓存字符串。缓存条目记录其渲染所依据的
 * 称号快照代号与显示配置代号，命中时逐一比对，任一不符即重算——条目级校验无全局
 * 清空，不存在"清空与写入交错导致脏缓存"的竞态。
 */
public final class PapiExpansion extends PlaceholderExpansion {

    /** 单个称号的两种渲染形态缓存，随称号快照与显示配置代号失效。 */
    private record Cached(long titleGeneration, long displayGeneration, String legacy, String stripped) {
    }

    /** 渲染缓存条目上限；超过后按称号代号清理陈旧条目，防止已删除称号的条目长期驻留。 */
    private static final int RENDER_CACHE_MAX = 512;

    private final MikuTagPlugin plugin;
    private final Map<String, Cached> renderCache = new ConcurrentHashMap<>();
    /** 上次执行陈旧条目清理时的称号快照代号，保证每个代号只清理一次。 */
    private volatile long prunedGeneration = -1;

    public PapiExpansion(MikuTagPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public @NotNull String getIdentifier() {
        return "mikutag";
    }

    @Override
    public @NotNull String getAuthor() {
        return "JunXieX";
    }

    @Override
    public @NotNull String getVersion() {
        return plugin.getPluginMeta().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String onRequest(OfflinePlayer player, @NotNull String params) {
        if (player == null) {
            return "";
        }
        PlayerTagData data = plugin.tagStore().get(player.getUniqueId());
        String key = params.toLowerCase(Locale.ROOT);
        return switch (key) {
            case "title" -> equipped(data, Form.LEGACY);
            case "title_strip" -> equipped(data, Form.STRIPPED);
            case "title_raw" -> equipped(data, Form.RAW);
            case "title_id" -> data == null ? "" : data.equipped();
            case "count" -> String.valueOf(plugin.tagService().ownedCount(player.getUniqueId()));
            default -> key.startsWith("has_")
                    ? hasTitle(data, params.substring(4).toLowerCase(Locale.ROOT))
                    : null;
        };
    }

    private enum Form {
        LEGACY,
        STRIPPED,
        RAW
    }

    private String equipped(PlayerTagData data, Form form) {
        if (data == null || data.equipped().isEmpty()) {
            return plugin.configManager().placeholderEmpty();
        }
        // 一次取整体快照：title 与其 generation 成对读取，不会跨快照撕裂
        TitleManager.Snapshot snap = plugin.titleManager().current();
        Title title = snap.byId().get(data.equipped());
        if (title == null) {
            return plugin.configManager().placeholderEmpty();
        }
        // 原始模板：不套显示前后缀，保留 MiniMessage 源串（字段直读，无需缓存）
        if (form == Form.RAW) {
            return title.name();
        }
        // 显示装饰与代号成对取自同一快照，渲染与缓存标记不会跨 loadAll 撕裂
        ConfigManager.Display disp = plugin.configManager().currentDisplay();
        Cached cached = renderCache.get(title.id());
        if (cached == null || cached.titleGeneration() != snap.generation()
                || cached.displayGeneration() != disp.generation()) {
            cached = render(title, disp, snap.generation(), disp.generation());
            if (renderCache.size() >= RENDER_CACHE_MAX && prunedGeneration != snap.generation()) {
                long generation = snap.generation();
                // 清理基于旧称号快照的条目（含已删除称号）；每个代号只清一次，避免高频全表扫描
                renderCache.values().removeIf(entry -> entry.titleGeneration() != generation);
                prunedGeneration = generation;
            }
            renderCache.put(title.id(), cached);
        }
        return form == Form.LEGACY ? cached.legacy() : cached.stripped();
    }

    private Cached render(Title title, ConfigManager.Display disp, long titleGeneration, long displayGeneration) {
        Component displayed = disp.displayedName(title);
        return new Cached(titleGeneration, displayGeneration,
                LegacyComponentSerializer.legacySection().serialize(displayed),
                ConfigManager.plain(displayed));
    }

    private String hasTitle(PlayerTagData data, String titleId) {
        return data != null && data.owns(titleId, System.currentTimeMillis()) ? "true" : "false";
    }
}
