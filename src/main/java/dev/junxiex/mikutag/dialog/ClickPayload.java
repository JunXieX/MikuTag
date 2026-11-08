package dev.junxiex.mikutag.dialog;

import net.kyori.adventure.nbt.api.BinaryTagHolder;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Dialog 按钮负载编码：{@code action|id|page}。
 * <p>
 * 以 SNBT 字符串 {@code {d:"..."}} 形式放入 BinaryTagHolder（Dialog 的 payload 只接受 SNBT）。
 * 注意：带 {@code DialogInput} 的对话框会由 Paper 把每个输入键并入同一个复合，回传的
 * tag 变成 {@code {d:"...", name:"...", ...}}，因此解析时必须只取顶层 {@code d} 字段，
 * 不能取整串首尾引号之间的内容。菜单因此完全无状态：页码与称号 ID 随按钮走，
 * 交互时重新渲染，无需服务端维护"打开中的菜单"集合。
 */
public final class ClickPayload {

    public static final String SHOP = "shop";
    public static final String SHOP_SELECT = "shop_select";
    public static final String SHOP_BUY = "shop_buy";
    public static final String SHOP_PREV = "shop_prev";
    public static final String SHOP_NEXT = "shop_next";

    public static final String WAREHOUSE = "warehouse";
    public static final String WH_EQUIP = "wh_equip";
    public static final String WH_UNEQUIP = "wh_unequip";
    public static final String WH_PREV = "wh_prev";
    public static final String WH_NEXT = "wh_next";

    public static final String ADMIN = "admin";
    public static final String ADMIN_NEW = "admin_new";
    public static final String ADMIN_EDIT = "admin_edit";
    public static final String ADMIN_SAVE = "admin_save";
    public static final String ADMIN_DEL = "admin_del";
    public static final String ADMIN_DEL_OK = "admin_del_ok";
    public static final String ADMIN_PREV = "admin_prev";
    public static final String ADMIN_NEXT = "admin_next";

    public static final String BACK = "back";

    /**
     * 顶层 d 字段；允许键名带引号、冒号与值前后有空白。
     * 约束：对话框的 {@code DialogInput.key()} 不得命名为 {@code d}，否则会与本负载键冲突。
     */
    private static final Pattern PAYLOAD_FIELD =
            Pattern.compile("[{,]\\s*\"?d\"?\\s*:\\s*\"([^\"]*)\"");

    private ClickPayload() {
    }

    /** 编码为可直接交给 {@code DialogAction.customClick} 的 SNBT 负载；id 为空串表示无目标称号。 */
    public static BinaryTagHolder tag(String action, String id, int page) {
        return BinaryTagHolder.binaryTagHolder("{d:\"" + action + '|' + id + '|' + page + "\"}");
    }

    /** 从 {@code BinaryTagHolder.string()}（形如 {d:"wh_equip|vip|0", name:"..."}）解析出编码串。 */
    public static String decode(String snbt) {
        if (snbt == null) {
            return "";
        }
        Matcher matcher = PAYLOAD_FIELD.matcher(snbt);
        return matcher.find() ? matcher.group(1) : "";
    }

    public record Parsed(String action, String id, int page) {
    }

    public static Parsed parse(String encoded) {
        String[] parts = encoded.split("\\|", 3);
        String action = parts[0];
        String id = parts.length > 1 ? parts[1] : "";
        int page = 0;
        if (parts.length > 2) {
            try {
                page = Integer.parseInt(parts[2]);
            } catch (NumberFormatException ignored) {
            }
        }
        return new Parsed(action, id, page);
    }
}
