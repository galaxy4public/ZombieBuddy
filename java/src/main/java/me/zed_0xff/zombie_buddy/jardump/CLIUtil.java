package me.zed_0xff.zombie_buddy.jardump;

import me.zed_0xff.zombie_buddy.Utils;

import java.util.ArrayList;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

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
        if (!_color || Utils.isBlank(s)) return s;
        return "\u001B[" + color + "m" + s + "\u001B[0m";
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

    public static record Table2( ArrayList<Row> rows, int minDelta ) {
        public Table2() {
            this(new ArrayList<>(), 0);
        }

        public Table2(int minDelta) {
            this(new ArrayList<>(), minDelta);
        }

        static record Row(String col1, int len1, String col2) {
            public String toString(int col1Width) {
                // can't use String.format here because of the color codes, which mess up the width calculation
                return col1 + " ".repeat(Math.max(0, col1Width - len1)) + " " + col2;
            }

            public String toString() {
                return Stream.of(col1, col2)
                    .filter(s -> !Utils.isBlank(s))
                    .collect(Collectors.joining(" "));
            }
        }

        public void addRow(String col1, String col2) {
            int maxlen = uncolorize(col1).lines().mapToInt(String::length).max().orElse(0);
            rows().add(new Row(col1, maxlen, col2));
        }

        public String toString() {
            if (rows().isEmpty()) return "";

            int maxLen = 0;
            if (minDelta == 0 || rows().size() == 1) {
                maxLen = rows().stream().mapToInt(Row::len1).max().orElse(0);
            } else {
                // 2+ rows
                int lens[] = rows().stream().mapToInt(Row::len1).sorted().toArray();
                int delta = lens[lens.length - 1] - lens[lens.length - 2];
                maxLen = delta < minDelta ? lens[lens.length - 1] : lens[lens.length - 2];
            }

            final int finalMaxLen = maxLen;
            return rows().stream().map(r -> r.toString(finalMaxLen)).collect(Collectors.joining("\n")) + "\n";
        }
    }
}
