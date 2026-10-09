package dev.junxiex.mikutag.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 称号购买条件。一个称号可叠加多个条件，购买时须全部满足（AND 语义）。
 * <p>
 * 空条件列表表示免费领取。
 */
public record Requirement(ReqType type, double amount, String permission) {

    /** 条件类型。FREE 由空列表表达、活动限定由 sellable=false 表达，均不占用本枚举。 */
    public enum ReqType {
        VAULT,
        PERMISSION;

        public static ReqType parse(String raw) {
            if (raw != null) {
                try {
                    return valueOf(raw.trim().toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException ignored) {
                }
            }
            return null;
        }
    }

    public static Requirement vault(double amount) {
        // NaN/Infinity 会污染后续的 >0 比较（NaN 与任何值比较均为 false），
        // 使金币条件被误判为"无需扣款"，故非有限值一律按 0 处理；负数额按 0 处理。
        double safe = Double.isFinite(amount) ? Math.max(0, amount) : 0;
        return new Requirement(ReqType.VAULT, safe, "");
    }

    public static Requirement permission(String node) {
        return new Requirement(ReqType.PERMISSION, 0, node == null ? "" : node);
    }

    /**
     * 解析条件串：{@code | } 分隔，每项 {@code VAULT:金额} 或 {@code PERMISSION:权限节点}；
     * 空白或 {@code free} 表示无条件（免费）。
     *
     * @throws IllegalArgumentException 任一项非法（消息为该项原文），调用方据此拒绝整份输入
     */
    public static List<Requirement> parseList(String raw) {
        List<Requirement> result = new ArrayList<>();
        if (raw == null || raw.isBlank() || raw.equalsIgnoreCase("free")) {
            return result;
        }
        for (String line : raw.split("\\|")) {
            String entry = line.trim();
            if (entry.isEmpty()) {
                continue;
            }
            int colon = entry.indexOf(':');
            if (colon <= 0 || colon == entry.length() - 1) {
                throw new IllegalArgumentException(entry);
            }
            ReqType type = ReqType.parse(entry.substring(0, colon));
            String value = entry.substring(colon + 1).trim();
            if (type == null || value.isEmpty()) {
                throw new IllegalArgumentException(entry);
            }
            if (type == ReqType.VAULT) {
                double amount;
                try {
                    amount = Double.parseDouble(value);
                } catch (NumberFormatException ex) {
                    throw new IllegalArgumentException(entry);
                }
                // Double.parseDouble 接受 "NaN"/"Infinity"，负数额也会被 vault() 钳成 0；
                // 二者都会让金币条件静默失效（变成免费），故在此直接拒绝整份输入。
                if (!Double.isFinite(amount) || amount < 0) {
                    throw new IllegalArgumentException(entry);
                }
                result.add(vault(amount));
            } else {
                result.add(permission(value));
            }
        }
        return result;
    }

    /** 渲染回 {@link #parseList} 可解析的串（用于表单回填与指令展示）。 */
    public static String toText(List<Requirement> requirements) {
        List<String> parts = new ArrayList<>(requirements.size());
        for (Requirement r : requirements) {
            parts.add(switch (r.type()) {
                case VAULT -> "VAULT:" + trimNumber(r.amount());
                case PERMISSION -> "PERMISSION:" + r.permission();
            });
        }
        return String.join(" | ", parts);
    }

    private static String trimNumber(double value) {
        if (value == Math.rint(value) && !Double.isInfinite(value)) {
            return String.valueOf((long) value);
        }
        return String.valueOf(value);
    }
}
