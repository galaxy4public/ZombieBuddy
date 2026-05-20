package me.zed_0xff.zombie_buddy.jardump;

import me.zed_0xff.zombie_buddy.Utils;

import java.util.Map;

class CLIUtil {
    static final int RESET   = 0;
    static final int BOLD    = 1;
    static final int DIM     = 2;
    static final int RED     = 31;
    static final int GREEN   = 32;
    static final int YELLOW  = 33;
    static final int BLUE    = 34;
    static final int MAGENTA = 35;
    static final int CYAN    = 36;
    static final int WHITE   = 37;

    static final int BRIGHT  = 60; // ADD to base color code for bright variants

    public static final int ANN_COLOR    = MAGENTA;
    public static final int BB_ANN_COLOR = CYAN; // bytebuddy

    static final String PKG_PRIVATE = "pkgPrivate";
    static final Map<String, Integer> _modifierColors = Map.of(
        PKG_PRIVATE, RED,
        "private",   RED,
        "protected", RED,
        "public",    GREEN
        // "final",     YELLOW
    );

    static boolean _color = true;

    public static String colorize(String s, int color) {
        return colorize(s, color, RESET);
    }

    public static String colorize(String s, int color, int defaultColor) {
        if (!_color || Utils.isBlank(s)) return s;
        return "\u001B[" + color + "m" + s + "\u001B[" + defaultColor + "m";
    }

    public static String uncolorize(String s) {
        return s.replaceAll("\u001B\\[[;\\d]*m", "");
    }

    static String highlight(String s, String word, int color) {
        if (!_color || Utils.isBlank(s)) return s;
        return s.replace(word, colorize(word, color));
    }

    static String highlight(String s, Map<String, Integer> colorMap) {
        if (!_color || Utils.isBlank(s)) return s;
        String result = s;
        for (Map.Entry<String, Integer> entry : colorMap.entrySet()) {
            result = highlight(result, entry.getKey(), entry.getValue());
        }        
        return result;
    }

    public static String indent(String str) {
        if (Utils.isBlank(str)) {
            return "";
        }
        str = "    " + str.replace("\n", "\n    ");
        return str.endsWith("\n    ") ? (str.substring(0, str.length() - 5) + "\n") : str;
    }

    static StringBuilder indent(StringBuilder src) {
        if (src.length() == 0) return src; // avoid adding indent to empty blocks

        String indent = " ".repeat(4);
        String s = src.toString();
        StringBuilder sb = new StringBuilder();
        sb.append(indent);

        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            sb.append(c);

            if (c == '\n' && i + 1 < s.length()) {
                sb.append(indent);
            }
        }
        return sb;
    }
}
