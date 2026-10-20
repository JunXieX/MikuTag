package dev.junxiex.mikutag.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 统一的文本解析入口：同时兼容 MiniMessage 与旧式 {@code &}/{@code §} 颜色码。
 * <p>
 * 服务端插件与玩家习惯里的颜色码大多是 {@code &} 形式，而 MiniMessage 不识别它，
 * 会把 {@code &r&7} 原样输出成可见字符。这里在交给 MiniMessage 解析前，先把旧式
 * 颜色码转成语义等价的标签，因此两种写法可以混用：
 * <ul>
 *   <li>颜色码 {@code &0-&9} / {@code &a-&f}、十六进制 {@code &#RRGGBB} 与
 *       {@code &x&R&R&R&R&R&R} 会设色并清掉加粗/斜体等格式——与游戏内
 *       “颜色码会顺带清掉格式”的行为一致；</li>
 *   <li>格式码 {@code &k}&nbsp;{@code &l}&nbsp;{@code &m}&nbsp;{@code &n}&nbsp;{@code &o}
 *       追加对应样式，{@code &r} 等价于恢复默认（白色、无格式）；</li>
 *   <li>{@code &&} 输出字面 {@code &}，正文出现 “M&M” 这类文本时可避免被误判为颜色码。</li>
 * </ul>
 * 清理格式用的是作用域内的否定标签（{@code <!bold>} 等），而不是 {@code <reset>}：
 * {@code <reset>} 会把 MiniMessage 解析节点直接拨回根节点，等于关闭当前所有未闭合标签，
 * 让 {@code <gradient>…&7…</gradient>} 这类混用写法把 {@code </gradient>} 当普通文本吐出来，
 * 且 {@code <reset>} 在 strict 模式下会被拒绝；否定标签只改当前作用域的格式位，不会破坏外层标签。
 * <p>
 * MiniMessage 标签内部（{@code <...>}，例如 click 的 URL 或 hover 的文本）不参与颜色码转换，
 * 以免 URL 里的 {@code &b=2} 之类被改写。
 * 不含颜色码的字符串原样返回（不新建字符串），纯 MiniMessage 文本没有额外开销。
 */
public final class Text {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private static final char AMPERSAND = '&';
    private static final char SECTION = '\u00A7';

    /** 颜色码与 {@code &r} 需要一并清掉的格式：作用域内显式置 false，不弹出外层标签。 */
    private static final String RESET_DECORATIONS =
            "<!bold><!italic><!underlined><!strikethrough><!obfuscated>";

    /**
     * 合法标签名：与 MiniMessage 的 {@code TagInternals} 保持一致（小写化后匹配）。
     * 用来区分“真的是 MiniMessage 标签”与正文里的 {@code "<3"}、{@code "<=10"} 这类普通文本——
     * 只看到 {@code <} 与后面的 {@code >} 就当作标签，会把后面的颜色码整段漏转。
     */
    private static final Pattern TAG_NAME_PATTERN = Pattern.compile("[!?#]?[a-z0-9_-]*");

    /** 是否把旧式 {@code &}/{@code §} 颜色码当作颜色码解析（关闭后完全按 MiniMessage 处理）。 */
    private static volatile boolean legacyColorsEnabled = true;

    private Text() {
    }

    /** 由配置在加载时调用；切换后新建的 {@link Component} 才会使用新行为。 */
    public static void setLegacyColorsEnabled(boolean enabled) {
        legacyColorsEnabled = enabled;
    }

    public static boolean legacyColorsEnabled() {
        return legacyColorsEnabled;
    }

    /** 解析为 Component：MiniMessage 标签与旧式颜色码都可直接使用。 */
    public static Component parse(String text) {
        return MM.deserialize(toMiniMessage(text));
    }

    /** 解析已规范化为 MiniMessage 的字符串，跳过颜色码转换（供内部复用，避免重复扫描）。 */
    public static Component parseMiniMessage(String miniMessage) {
        return MM.deserialize(miniMessage == null ? "" : miniMessage);
    }

    /**
     * 旧式颜色码 → MiniMessage 标签。
     *
     * @param text 原始文本，可为 {@code null}
     * @return 转换后的文本；不含颜色码时返回同一实例
     */
    public static String toMiniMessage(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        if (!legacyColorsEnabled) {
            return text;
        }
        if (indexOfLegacy(text) < 0) {
            return text;
        }
        int length = text.length();
        StringBuilder out = new StringBuilder(length + 32);
        for (int i = 0; i < length; i++) {
            char c = text.charAt(i);
            // 整段 MiniMessage 标签（含参数、引号内的 >）原样跳过：
            // <click:/cmd?x=1&b=2> 这类参数必须保持原样，正文里的 "<3"、"<=10" 则不算标签
            if (c == '<') {
                int end = tagEnd(text, i);
                if (end >= 0) {
                    out.append(text, i, end + 1);
                    i = end;
                    continue;
                }
            }
            if (c != AMPERSAND && c != SECTION) {
                out.append(c);
                continue;
            }
            if (i + 1 >= length) {
                out.append(c);
                break;
            }
            char code = text.charAt(i + 1);
            // && 表示字面 &，避免正文里的 “M&M” 之类被当成颜色码
            if (c == AMPERSAND && code == AMPERSAND) {
                out.append(AMPERSAND);
                i++;
                continue;
            }
            if (code == '#') {
                if (isHex(text, i + 2, 6)) {
                    out.append("<#").append(text, i + 2, i + 8).append('>').append(RESET_DECORATIONS);
                    i += 7;
                    continue;
                }
                out.append(c);
                continue;
            }
            if ((code == 'x' || code == 'X') && isBungeeHex(text, i)) {
                out.append("<#");
                for (int k = 0; k < 6; k++) {
                    out.append(text.charAt(i + 3 + k * 2));
                }
                out.append('>').append(RESET_DECORATIONS);
                i += 13;
                continue;
            }
            String tag = tagFor(code);
            if (tag == null) {
                out.append(c);
                continue;
            }
            out.append(tag);
            i++;
        }
        return out.toString();
    }

    /**
     * 找出从 {@code start}（指向 {@code <}）开始的 MiniMessage 标签的结尾 {@code >} 下标；不是标签返回 -1。
     * <p>
     * 规则对齐 MiniMessage 的 TokenParser：引号内的 {@code >} 不算结束；遇到嵌套的 {@code <} 视为
     * 重新开始（外层不是标签）；标签名必须匹配 {@link #TAG_NAME_PATTERN}，否则 {@code <3}、{@code <=10}
     * 这类正文会被误当成标签、导致后面的颜色码整段漏转。
     */
    private static int tagEnd(String text, int start) {
        int length = text.length();
        char quote = 0;
        for (int i = start + 1; i < length; i++) {
            char c = text.charAt(i);
            if (quote != 0) {
                if (c == '\\' && i + 1 < length) {
                    i++; // 引号内的转义：跳过下一个字符
                } else if (c == quote) {
                    quote = 0;
                }
                continue;
            }
            if (c == '\\' && i + 1 < length && text.charAt(i + 1) == '<') {
                return -1; // 标签内的转义 < 会让 MiniMessage 退回普通文本，这里不再视为标签
            }
            if (c == '\'' || c == '"') {
                if (text.indexOf(c, i + 1) >= 0) {
                    quote = c; // 仅当后面还存在同种引号时才进入引号状态（与 MiniMessage 一致）
                }
                continue;
            }
            if (c == '<') {
                return -1; // 嵌套 <：按 MiniMessage 规则从新的 < 重新判定
            }
            if (c == '>') {
                return isValidTagName(text, start, i) ? i : -1;
            }
        }
        return -1;
    }

    /** 校验标签名是否合法（{@code /} 表示闭合标签，末尾 {@code /} 表示自闭合）。 */
    private static boolean isValidTagName(String text, int start, int end) {
        int nameStart = start + 1;
        if (nameStart < end && text.charAt(nameStart) == '/') {
            nameStart++;
        }
        int nameEnd = nameStart;
        while (nameEnd < end && text.charAt(nameEnd) != ':') {
            nameEnd++;
        }
        if (nameEnd > nameStart && text.charAt(nameEnd - 1) == '/') {
            nameEnd--; // 自闭合标签 <name/>
        }
        if (nameEnd <= nameStart) {
            return false;
        }
        String name = text.substring(nameStart, nameEnd).toLowerCase(Locale.ROOT);
        return TAG_NAME_PATTERN.matcher(name).matches();
    }

    private static int indexOfLegacy(String text) {
        for (int i = 0, n = text.length(); i < n; i++) {
            char c = text.charAt(i);
            if (c == AMPERSAND || c == SECTION) {
                return i;
            }
        }
        return -1;
    }

    /** 从 from 起连续 count 个字符是否都是 ASCII 十六进制数字。 */
    private static boolean isHex(String text, int from, int count) {
        if (from < 0 || from + count > text.length()) {
            return false;
        }
        for (int i = from; i < from + count; i++) {
            if (!isAsciiHex(text.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    /** 只接受 ASCII 十六进制：Character.digit 会把全角数字等 Unicode 数字也算进来。 */
    private static boolean isAsciiHex(char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }

    /** start 指向 {@code &}，判断其后是否为 Bungee 十六进制写法 {@code x&R&R&R&R&R&R}。 */
    private static boolean isBungeeHex(String text, int start) {
        if (start + 13 >= text.length()) {
            return false;
        }
        for (int k = 0; k < 6; k++) {
            char separator = text.charAt(start + 2 + k * 2);
            if (separator != AMPERSAND && separator != SECTION) {
                return false;
            }
            if (!isAsciiHex(text.charAt(start + 3 + k * 2))) {
                return false;
            }
        }
        return true;
    }

    private static String tagFor(char code) {
        return switch (Character.toLowerCase(code)) {
            case '0' -> color("black");
            case '1' -> color("dark_blue");
            case '2' -> color("dark_green");
            case '3' -> color("dark_aqua");
            case '4' -> color("dark_red");
            case '5' -> color("dark_purple");
            case '6' -> color("gold");
            case '7' -> color("gray");
            case '8' -> color("dark_gray");
            case '9' -> color("blue");
            case 'a' -> color("green");
            case 'b' -> color("aqua");
            case 'c' -> color("red");
            case 'd' -> color("light_purple");
            case 'e' -> color("yellow");
            case 'f' -> color("white");
            case 'k' -> "<obfuscated>";
            case 'l' -> "<bold>";
            case 'm' -> "<strikethrough>";
            case 'n' -> "<underlined>";
            case 'o' -> "<italic>";
            case 'r' -> color("white");
            default -> null;
        };
    }

    /** 颜色标签 + 清空格式位；{@code &r} 等价于重置为默认白色。 */
    private static String color(String name) {
        return "<" + name + ">" + RESET_DECORATIONS;
    }
}
