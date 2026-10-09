package dev.junxiex.mikutag.dialog;

import net.kyori.adventure.nbt.api.BinaryTagHolder;

/**
 * Dialog 按钮负载编码：{@code action|id|page}。
 * <p>
 * 以 SNBT 字符串 {@code {d:"..."}} 形式放入 BinaryTagHolder（Dialog 的 payload 只接受 SNBT），
 * 解析时取引号内文本。菜单因此完全无状态：页码与称号 ID 随按钮走，交互时重新渲染，
 * 无需服务端维护"打开中的菜单"集合。
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

    private ClickPayload() {
    }

    public static String encode(String action, String id, int page) {
        return action + "|" + (id == null ? "" : id) + "|" + page;
    }

    /** 编码为可直接交给 {@code DialogAction.customClick} 的 SNBT 负载。 */
    public static BinaryTagHolder tag(String action, String id, int page) {
        return BinaryTagHolder.binaryTagHolder("{d:\"" + encode(action, id, page) + "\"}");
    }

    /** 从 {@code BinaryTagHolder.string()}（形如 {d:"wh_equip|vip|0"}）解析出编码串。 */
    public static String decode(String snbt) {
        if (snbt == null) {
            return "";
        }
        int first = snbt.indexOf('"');
        int last = snbt.lastIndexOf('"');
        if (first >= 0 && last > first) {
            return snbt.substring(first + 1, last);
        }
        return "";
    }

    public record Parsed(String action, String id, int page) {
    }

    public static Parsed parse(String encoded) {
        String[] parts = encoded.split("\\|", 3);
        String action = parts.length > 0 ? parts[0] : "";
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
