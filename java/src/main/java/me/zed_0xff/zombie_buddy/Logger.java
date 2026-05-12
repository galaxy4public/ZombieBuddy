package me.zed_0xff.zombie_buddy;

import java.io.PrintStream;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

public class Logger {
    private static final String ZB                = "[ZB]";
    private static final int MAX_ARG_LEN          = 128;
    private static final int DEFAULT_MAX_LINE_LEN = 256;

    private enum Channel { OUT, ERR }
    private static final Channel STDOUT = Channel.OUT;
    private static final Channel STDERR = Channel.ERR;

    public static class Instance {
        private final String[] m_tags;
        private final String m_tagStr; // preformatted tag string for efficiency
        private int m_level;
        private int m_maxLineLen;

        Instance(int level, int maxLineLen, String... tags) {
            m_level  = level;
            m_maxLineLen = maxLineLen;
            m_tags   = tags;
            m_tagStr = Arrays.stream(tags).map(t -> "[" + t + "]").collect(Collectors.joining(" ", "", " "));
        }

        public void setLevel(int level)  { this.m_level = level; }
        public void setMaxLineLen(int l) { this.m_maxLineLen = l; }

        public Instance withTag(String tag) {
            if (Utils.isBlank(tag)) return this;

            String[] newTags = Arrays.copyOf(m_tags, m_tags.length + 1);
            newTags[m_tags.length] = tag;
            return new Instance(m_level, m_maxLineLen, newTags);
        }

        public void log(int level, String message, Object... args) {
            if (m_level < level) return;

            if (message.length() > m_maxLineLen) {
                message = message.substring(0, m_maxLineLen - 3) + "...";
            } else if (args != null && args.length > 0) {
                message += " " + formatArgs(args, 0, m_maxLineLen - message.length() - 1);
            }

            String lp = switch (level) {
                case TRACE -> "[t] ";
                case DEBUG -> "[d] ";
                case WARN  -> "[?] ";
                case ERROR -> "[!] ";
                default    -> "";
            };
            msg(level <= WARN ? STDERR : STDOUT, lp + m_tagStr + message);
        }

        public void printStackTrace(Throwable t) {
            Logger.printStackTrace(t);
        }

        public void trace(String message, Object... args) { log(TRACE, message, args); }
        public void debug(String message, Object... args) { log(DEBUG, message, args); }
        public void info( String message, Object... args) { log(INFO,  message, args); }
        public void warn( String message, Object... args) { log(WARN,  message, args); }
        public void error(String message, Object... args) { log(ERROR, message, args); }
    }

    // save before PZ overrides them, so we can still log to console even if PZ's logger is messed up
    private static final PrintStream _out = System.out;
    private static final PrintStream _err = System.err;

    public static final int TRACE =  2;
    public static final int DEBUG =  1;
    public static final int INFO  =  0;
    public static final int WARN  = -1;
    public static final int ERROR = -2;
    public static final int DEFAULT_LEVEL = INFO;

    public static final Instance DEFAULT = new Instance(DEFAULT_LEVEL, DEFAULT_MAX_LINE_LEN);

    private static String add_prefix(String prefix, String msg) {
        if (msg == null)    return prefix != null ? prefix : "";
        if (prefix == null) return msg;
        boolean needSpace = prefix.endsWith("]") && !msg.startsWith("[") && !msg.startsWith(" ");
        return needSpace ? prefix + " " + msg : prefix + msg;
    }

    private static void msg(Channel ch, String msg) {
        final PrintStream priStream = (ch == STDOUT) ? System.out : System.err;
        final PrintStream secStream = (ch == STDOUT) ? _out : _err;

        msg = add_prefix(ZB, msg);
        try {
            priStream.println(msg);
        } catch (Throwable t) { // might fail on game boot
            secStream.println(msg);
        }
    }

    private static final Map<String, Instance> _instances = new HashMap<>();
    public static Instance get(String tag) {
        if (tag == null) return DEFAULT;
        return _instances.computeIfAbsent(tag, t -> new Instance(DEFAULT.m_level, DEFAULT.m_maxLineLen, t));
    }

    public static void log(int lv, String m, Object... args) { DEFAULT.log(lv,    m,       args); }
    public static void trace(String message, Object... args) { DEFAULT.log(TRACE, message, args); }
    public static void debug(String message, Object... args) { DEFAULT.log(DEBUG, message, args); }
    public static void info( String message, Object... args) { DEFAULT.log(INFO,  message, args); }
    public static void warn( String message, Object... args) { DEFAULT.log(WARN,  message, args); }
    public static void error(String message, Object... args) { DEFAULT.log(ERROR, message, args); }

    // intentionally package-private; mods shouldl use Instance's public setLevel
    static void setLevel(int level) { DEFAULT.setLevel(level); }

    public static void printStackTrace(Throwable t) {
        if (!Agent.arguments.containsKey("filter_stacktrace")) { // undocumented; intentionally omitted from CommandLine.md
            t.printStackTrace();
            return;
        }
        while (t != null) {
            msg(STDERR, t.toString());
            for (StackTraceElement f : t.getStackTrace()) {
                if (f.getClassName().startsWith("me.zed_0xff")) msg(STDERR, "    at " + f);
            }
            t = t.getCause();
            if (t != null) msg(STDERR, "  Caused by:");
        }
    }

    private static String formatRecord(Object record) {
        Class<?> cls = record.getClass();
        return Arrays.stream(cls.getRecordComponents())
            .map(rc -> {
                try {
                    return rc.getName() + "=" + formatArg(rc.getAccessor().invoke(record));
                } catch (ReflectiveOperationException e) {
                    return rc.getName() + "=<error>";
                }
            })
            .collect(Collectors.joining(", ", "<record " + cls.getSimpleName() + " ", ">"));
    }

    public static String formatArray(Object[] arr) {
        return Arrays.stream(arr).map(Logger::formatArg).collect(Collectors.joining(", ", "[", "]"));
    }

    /** Format an object for logging: strings quoted, arrays expanded, length capped. */
    private static final LuaJSON _luaJSON = new LuaJSON(5, MAX_ARG_LEN, LuaJSON.Flags.STRIP_QUOTES, LuaJSON.Flags.ADD_SPACE_AFTER_COMMA);
    public static String formatArg(Object o) {
        if (o == null) return "null";

        if (o instanceof Object[] arr)   return formatArray(arr);
        if (o.getClass().isRecord())     return formatRecord(o);
        if (LuaJSON.canSerialize(o))     return _luaJSON.toJson(o);

        String s = o.toString();
        if (s.length() > MAX_ARG_LEN) s = s.substring(0, MAX_ARG_LEN - 3) + "...";
        if (o instanceof String) return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
        return s;
    }

    public static String formatArgs(Object[] args)        { return formatArgs(args, 0, DEFAULT_MAX_LINE_LEN); }
    public static String formatArgs(Object[] args, int f) { return formatArgs(args, f, DEFAULT_MAX_LINE_LEN); }

    public static String formatArgs(Object[] args, int fromIndex, int maxLineLen) {
        if (args == null || fromIndex >= args.length || maxLineLen <= 0) return "";

        StringBuilder sb = new StringBuilder();
        for (int i = fromIndex; i < args.length; i++) {
            if (i > fromIndex) sb.append(", ");
            sb.append(formatArg(args[i]));
            if (sb.length() > maxLineLen) {
                sb.setLength(maxLineLen - 3);
                sb.append("...");
                break;
            }
        }
        return sb.toString();
    }
}
