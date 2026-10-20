package dev.junxiex.mikutag.model;

import dev.junxiex.mikutag.util.Text;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 称号定义（不可变）。名称与描述在构造时即解析为 {@link Component}，
 * 菜单渲染时直接复用，避免每次打开菜单都重新走一遍 MiniMessage。
 * <p>
 * 获取条件为 {@link Requirement} 列表，购买时全部满足（AND）；
 * 空列表 = 免费领取；{@code sellable=false} = 不在商城出售（活动/发放限定）。
 */
public final class Title {

    /** 合法的称号 ID：字母/数字/下划线/短横线，1-64 位。 */
    private static final Pattern ID_PATTERN = Pattern.compile("[a-zA-Z0-9_\\-]{1,64}");

    /** 数据库列宽：mikutag_titles.name VARCHAR(255)。超长会在 MySQL 严格模式下直接写入失败。 */
    private static final int NAME_MAX_LENGTH = 255;

    /**
     * 可执行/可交互的 MiniMessage 事件标签。称号名称与描述允许使用样式标签（颜色/加粗等），
     * 但不允许携带 {@code <click>}/{@code <hover>}/{@code <insertion>}——
     * 这些标签能让文本附带"点击执行命令/打开链接"等能力，属越权面。
     * 渲染前统一剔除（含带参数形式），只保留纯样式。
     */
    private static final Pattern EVENT_TAG =
            Pattern.compile("<(/?)(click|hover|insertion)\\b[^>]*>", Pattern.CASE_INSENSITIVE);

    /** 校验称号 ID（指令与菜单表单共用同一规则）。 */
    public static boolean isValidId(String id) {
        return id != null && ID_PATTERN.matcher(id).matches();
    }

    /** 剔除 MiniMessage 事件标签，仅保留样式标签与普通文本。 */
    private static String sanitize(String text) {
        return text == null ? "" : EVENT_TAG.matcher(text).replaceAll("");
    }

    private final String id;
    private final String name;
    private final List<String> description;
    private final Material icon;
    private final List<Requirement> requirements;
    private final int durationDays;
    private final boolean sellable;
    private final int sortOrder;
    private final long createdAt;

    private final Component displayName;
    private final List<Component> descriptionLines;
    /** 规范化后的 MiniMessage 源串（旧式颜色码已转换），供占位符等直接取用。 */
    private final String miniMessageName;

    public Title(String id, String name, List<String> description, Material icon,
                 List<Requirement> requirements, int durationDays, boolean sellable,
                 int sortOrder, long createdAt) {
        this.id = id;
        // 名称与描述在入库/渲染前统一剔除事件标签，避免称号文本携带命令执行等能力
        String safeName = sanitize(name);
        if (safeName.length() > NAME_MAX_LENGTH) {
            // 与列宽对齐，避免 MySQL 严格模式下整条 upsert 失败（导入/管理保存都会受影响）
            safeName = safeName.substring(0, NAME_MAX_LENGTH);
        }
        this.name = safeName.isBlank() ? id : safeName;
        if (description == null || description.isEmpty()) {
            this.description = List.of();
        } else {
            List<String> safeDesc = new ArrayList<>(description.size());
            for (String line : description) {
                safeDesc.add(sanitize(line));
            }
            this.description = List.copyOf(safeDesc);
        }
        // 图标必须是真实物品：非物品材质（如 WATER）在 new ItemStack(...) 时会抛异常，
        // 而材质名可能来自手工改库或旧数据，故在唯一入口处兜底为 NAME_TAG
        this.icon = (icon == null || !icon.isItem()) ? Material.NAME_TAG : icon;
        this.requirements = requirements == null ? List.of() : List.copyOf(requirements);
        this.durationDays = Math.max(0, durationDays);
        this.sellable = sellable;
        this.sortOrder = sortOrder;
        this.createdAt = createdAt;

        // 名称与描述既接受 MiniMessage，也接受 &/§ 旧式颜色码；先统一转换为 MiniMessage 再解析，
        // 菜单、聊天与占位符因此共用同一份渲染结果，不会出现"某处生效、某处显示原始颜色码"。
        this.miniMessageName = Text.toMiniMessage(this.name);
        this.displayName = Text.parseMiniMessage(this.miniMessageName);
        List<Component> lines = new ArrayList<>(this.description.size());
        for (String line : this.description) {
            lines.add(Text.parse(line));
        }
        this.descriptionLines = List.copyOf(lines);
    }

    public String id() {
        return id;
    }

    /**
     * 名称的原始源串（可能是 MiniMessage，也可能含 {@code &}/{@code §} 旧式颜色码）。
     * 展示与对外渲染请使用 {@link #displayName()}，本方法仅用于存储与管理表单回填。
     */
    public String name() {
        return name;
    }

    /** 规范化后的 MiniMessage 名称（旧式颜色码已转换，不含显示前后缀）。 */
    public String miniMessageName() {
        return miniMessageName;
    }

    /** 描述的原始源串（每行一条，可能含 {@code &}/{@code §} 旧式颜色码）；展示请用 {@link #descriptionLines()}。 */
    public List<String> description() {
        return description;
    }

    public Material icon() {
        return icon;
    }

    public List<Requirement> requirements() {
        return requirements;
    }

    public int durationDays() {
        return durationDays;
    }

    public boolean sellable() {
        return sellable;
    }

    public int sortOrder() {
        return sortOrder;
    }

    public long createdAt() {
        return createdAt;
    }

    public Component displayName() {
        return displayName;
    }

    public List<Component> descriptionLines() {
        return descriptionLines;
    }

    public boolean permanent() {
        return durationDays <= 0;
    }

    /** 有效时长（毫秒），0 表示永久。 */
    public long durationMillis() {
        return permanent() ? 0L : durationDays * 86_400_000L;
    }

    /** 该称号需要的 Vault 金币总额（无金币条件时为 0）。 */
    public double totalVaultPrice() {
        double sum = 0;
        for (Requirement r : requirements) {
            if (r.type() == Requirement.ReqType.VAULT) {
                sum += r.amount();
            }
        }
        return sum;
    }

    /** 描述以换行符存入单列，读取时按行拆分。 */
    public String descriptionRaw() {
        return String.join("\n", description);
    }

    public static List<String> splitDescription(String raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        List<String> lines = new ArrayList<>();
        for (String line : raw.split("\n")) {
            // 兼容手工改库留下的 CRLF：只按 \n 拆分时行尾会残留 \r，渲染时会多出异常字符
            String trimmed = line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
            if (!trimmed.isBlank()) {
                lines.add(trimmed);
            }
        }
        return lines;
    }
}
