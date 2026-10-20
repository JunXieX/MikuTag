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
 *   <li>{@code %mikutag_title_raw%} — 规范化后的 MiniMessage 模板（旧式 {@code &} 颜色码已转换），
 *       <b>不含</b>显示前后缀，交由消费插件自行解析样式</li>
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
            case "title_id" -> equippedId(data);
            case "count" -> String.valueOf(plugin.tagService().ownedCount(player.getUniqueId()));
            default -> key.startsWith("has_")
                    ? hasTitle(data, params.substring(4).toLowerCase(Locale.ROOT))
                    : null;
        };
    }

    /**
     * 当前佩戴的称号 ID；已过期的佩戴视为未佩戴。
     * <p>
     * 佩戴状态不会因称号到期自动清除（清除需回到玩家线程），因此这里按"是否仍有效拥有"过滤，
     * 否则聊天/Tab 会一直显示一个早已过期的称号。离线玩家同样适用（纯内存判断）。
     */
    private String equippedId(PlayerTagData data) {
        if (data == null) {
            return "";
        }
        String id = data.equipped();
        return id.isEmpty() || !data.owns(id, System.currentTimeMillis()) ? "" : id;
    }

    private enum Form {
        LEGACY,
        STRIPPED,
        RAW
    }

    private String equipped(PlayerTagData data, Form form) {
        String id = equippedId(data);
        if (id.isEmpty()) {
            return plugin.configManager().placeholderEmpty();
        }
        // 一次取整体快照：title 与其 generation 成对读取，不会跨快照撕裂
        TitleManager.Snapshot snap = plugin.titleManager().current();
        Title title = snap.byId().get(id);
        if (title == null) {
            return plugin.configManager().placeholderEmpty();
        }
        // 原始模板：不套显示前后缀，返回规范化后的 MiniMessage 源串（旧式 & 颜色码已转换）
        if (form == Form.RAW) {
            return title.miniMessageName();
        }
        // 显示装饰与代号成对取自同一快照，渲染与缓存标记不会跨 loadAll 撕裂
        ConfigManager.Display disp = plugin.configManager().currentDisplay();
        Cached cached = renderCache.get(title.id());
        if (cached == null || cached.titleGeneration() != snap.generation()
                || cached.displayGeneration() != disp.generation()) {
            cached = render(title, disp, snap.generation(), disp.generation());
            if (renderCache.size() >= RENDER_CACHE_MAX) {
                // 缓存以称号 ID 为键，正常情况下上限即称号总数；一旦超限说明有陈旧条目
                // （称号被删/改名），此时清掉与本轮快照或显示配置不符的条目。
                // 不能只按 generation 判断：同一代号内也可能因改名产生旧键残留。
                long generation = snap.generation();
                long displayGeneration = disp.generation();
                renderCache.values().removeIf(entry ->
                        entry.titleGeneration() != generation
                                || entry.displayGeneration() != displayGeneration);
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
