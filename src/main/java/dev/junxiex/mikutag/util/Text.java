package dev.junxiex.mikutag.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;

/**
 * 统一的文本解析入口：同时兼容 MiniMessage 与旧式 {@code &}/{@code §} 颜色码。
 * <p>
 * 服务端插件与玩家习惯里的颜色码大多是 {@code &} 形式，而 MiniMessage 不识别它，
 * 会把 {@code &r&7} 原样输出成可见字符。这里在交给 MiniMessage 解析前，先把旧式
 * 颜色码转成语义等价的标签，因此两种写法可以混用（MiniMessage 标签仍原样保留）：
 * <ul>
 *   <li>颜色码 {@code &0-&9} / {@code &a-&f}、十六进制 {@code &#RRGGBB} 与
 *       {@code &x&R&R&R&R&R&R} 会先 {@code <reset>} 再设色——与游戏内
 *       “颜色码会顺带清掉加粗/斜体等格式”的行为一致；</li>
 *   <li>格式码 {@code &k}&nbsp;{@code &l}&nbsp;{@code &m}&nbsp;{@code &n}&nbsp;{@code &o}
 *       追加对应样式，{@code &r} 等价于 {@code <reset>}；</li>
 *   <li>{@code &&} 输出字面 {@code &}，正文出现 “M&M” 这类文本时可避免被误判为颜色码。</li>
 * </ul>
 * 不含颜色码的字符串原样返回（不新建字符串），纯 MiniMessage 文本没有额外开销。
 */
public final class Text {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private static final char AMPERSAND = '&';
    private static final char SECTION = '\u00A7';

    private Text() {
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
        int start = firstLegacyIndex(text);
        if (start < 0) {
            return text;
        }
        int length = text.length();
        StringBuilder out = new StringBuilder(length + 16);
        out.append(text, 0, start);
        for (int i = start; i < length; i++) {
            char c = text.charAt(i);
            if (c != AMPERSAND && c != SECTION) {
                out.append(c);
                continue;
            }
            if (i + 1 >= length) {
                out.append(c);
                break;
            }
            char code = text.charAt(i + 1);
            // && 表示字面 &，避免正文里的 “&D” 之类被当成颜色码
            if (c == AMPERSAND && code == AMPERSAND) {
                out.append(AMPERSAND);
                i++;
                continue;
            }
            if (code == '#') {
                if (isHex(text, i + 2, 6)) {
                    out.append("<reset><#").append(text, i + 2, i + 8).append('>');
                    i += 7;
                    continue;
                }
                out.append(c);
                continue;
            }
            if ((code == 'x' || code == 'X') && isBungeeHex(text, i)) {
                out.append("<reset><#");
                for (int k = 0; k < 6; k++) {
                    out.append(text.charAt(i + 3 + k * 2));
                }
                out.append('>');
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

    private static int firstLegacyIndex(String text) {
        for (int i = 0, n = text.length(); i < n; i++) {
            char c = text.charAt(i);
            if (c == AMPERSAND || c == SECTION) {
                return i;
            }
        }
        return -1;
    }

    /** 从 from 起连续 count 个字符是否都是十六进制数字。 */
    private static boolean isHex(String text, int from, int count) {
        if (from < 0 || from + count > text.length()) {
            return false;
        }
        for (int i = from; i < from + count; i++) {
            if (Character.digit(text.charAt(i), 16) < 0) {
                return false;
            }
        }
        return true;
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
            if (Character.digit(text.charAt(start + 3 + k * 2), 16) < 0) {
                return false;
            }
        }
        return true;
    }

    private static String tagFor(char code) {
        return switch (Character.toLowerCase(code)) {
            case '0' -> "<reset><black>";
            case '1' -> "<reset><dark_blue>";
            case '2' -> "<reset><dark_green>";
            case '3' -> "<reset><dark_aqua>";
            case '4' -> "<reset><dark_red>";
            case '5' -> "<reset><dark_purple>";
            case '6' -> "<reset><gold>";
            case '7' -> "<reset><gray>";
            case '8' -> "<reset><dark_gray>";
            case '9' -> "<reset><blue>";
            case 'a' -> "<reset><green>";
            case 'b' -> "<reset><aqua>";
            case 'c' -> "<reset><red>";
            case 'd' -> "<reset><light_purple>";
            case 'e' -> "<reset><yellow>";
            case 'f' -> "<reset><white>";
            case 'k' -> "<obfuscated>";
            case 'l' -> "<bold>";
            case 'm' -> "<strikethrough>";
            case 'n' -> "<underlined>";
            case 'o' -> "<italic>";
            case 'r' -> "<reset>";
            default -> null;
        };
    }
}
