package me.zed_0xff.zombie_buddy.transformers;

import net.bytebuddy.jar.asm.Type;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.StringJoiner;

final class ScopeTracker<T> {
    sealed interface Node permits Ann, Arg, Arr, Cls, Elm, Fld, Mth {}
    record Ann(String desc)              implements Node { @Override public String toString() { return "Ann{desc=" + simpleName(desc) + "}"; } }
    record Arg(int idx, String desc)     implements Node { @Override public String toString() { return String.format("Arg{idx=%d desc=%s}", idx, simpleName(desc)); } }
    record Arr(String name)              implements Node { @Override public String toString() { return "Arr{name=" + name + "}"; } }
    record Cls(String name)              implements Node { @Override public String toString() { return "Cls{name=" + simpleName(name) + "}"; } }
    record Elm(String name)              implements Node { @Override public String toString() { return "Elm{name=" + name + "}"; } }
    record Fld(String name, String desc) implements Node { @Override public String toString() { return "Fld{name=" + name + ", desc=" + simpleName(desc) + "}"; } }
    record Mth(String name, String desc) implements Node { @Override public String toString() { return "Mth{name=" + name + ", desc=" + displayMethodDesc(desc) + "}"; } }

    private final Deque<T> stack = new ArrayDeque<>();

    interface Scope extends AutoCloseable {
        @Override void close();
    }

    public Scope enter(T value) {
        stack.addLast(value);
        return () -> stack.removeLast();
    }

    /** Human path for logs; {@link Node} records still hold full JVM descriptors internally. */
    public String path() {
        StringJoiner joiner = new StringJoiner(" -> ", "{", "}");
        for (T value : stack) {
            joiner.add(String.valueOf(value));
        }
        return joiner.toString();
    }

    /** Single type or array descriptor ({@code Lfoo/Bar;}, {@code I}, {@code [Ljava/lang/String;}). */
    private static String simpleName(String desc) {
        if (desc == null || desc.isEmpty()) {
            return desc;
        }

        try {
            return simpleBinaryName(Type.getType(desc).getClassName());
        } catch (IllegalArgumentException e) {
            return simpleBinaryName(desc);
        }
    }

    /** Method descriptor {@code (II)Lfoo/Bar;} → {@code (int,int)->Bar} style using simple names. */
    private static String displayMethodDesc(String desc) {
        if (desc == null || desc.isEmpty() || desc.charAt(0) != '(') {
            return desc;
        }

        try {
            Type ret = Type.getReturnType(desc);
            return simpleBinaryName(Type.getReturnType(desc).getClassName());
        } catch (IllegalArgumentException e) {
            return simpleBinaryName(desc);
        }
    }

    /** {@code me.pkg.Outer$Inner} → {@code Outer$Inner} (same rule as jardump {@code AsmDump#simpleName} for class names). */
    private static String simpleBinaryName(String className) {
        className = className
            .replace('$', '.')
            .replace('/', '.');
        int idx = className.lastIndexOf('.');
        if (idx > 0){
            if (className.length() - idx < 4) {
                // find previous separator
                idx = className.lastIndexOf('.', idx - 1);
            }
        }
        return idx >= 0 ? className.substring(idx + 1) : className;
    }
}
