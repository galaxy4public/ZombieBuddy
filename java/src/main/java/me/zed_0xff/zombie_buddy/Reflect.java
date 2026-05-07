package me.zed_0xff.zombie_buddy;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Optional;

/**
 * Fluent reflection chain. Entry point is {@link #on(String)} (class-name lookup) or
 * {@link #on(Object)} (wrap an instance or Class). Every step returns a new {@code Reflect};
 * a failed step (null value, missing field/method) returns a null-valued chain that silently
 * propagates — call {@link #isPresent()} or {@link #as} at the end to detect failure.
 *
 * <pre>{@code
 * String dir = Reflect.on("zombie.ZomboidFileSystem")
 *     .getInstance()
 *     .call("getCacheDir")
 *     .as(String.class)
 *     .orElse(null);
 * }</pre>
 */
public final class Reflect {
    private final Object value;

    private Reflect(Object value) {
        this.value = value;
    }

    /** Looks up {@code className} and wraps the result (null-valued chain if not found). */
    public static Reflect on(String className) {
        return new Reflect(Accessor.findClass(className));
    }

    /** Wraps {@code obj} directly; accepts instances and Class objects alike. */
    public static Reflect on(Object obj) {
        return new Reflect(obj);
    }

    public Reflect field(String fieldName) {
        if (value == null || Utils.isBlank(fieldName)) return new Reflect(null);
        return new Reflect(Accessor.tryGet(value, fieldName, null));
    }

    public Reflect staticField(String fieldName) {
        if (value == null || Utils.isBlank(fieldName)) return new Reflect(null);
        if (value instanceof Class<?> cls) return new Reflect(Accessor.tryGet(cls, fieldName, null));
        return new Reflect(Accessor.tryGet(value.getClass(), fieldName, null));
    }

    /** Tries {@code getInstance()} static method, then falls back to a static {@code instance} field. */
    public Reflect getInstance() {
        if (value == null) return new Reflect(null);
        Class<?> cls = value instanceof Class<?> c ? c : value.getClass();
        Method m = Accessor.findExactMethod(cls, "getInstance");
        if (m != null && Modifier.isStatic(m.getModifiers())) {
            try {
                m.setAccessible(true);
                Object instance = m.invoke(null);
                if (instance != null) return new Reflect(instance);
            } catch (ReflectiveOperationException | RuntimeException ignored) {}
        }
        return new Reflect(Accessor.tryGet(cls, "instance", null));
    }

    public Reflect call(String methodName, Object... args) {
        if (value == null || Utils.isBlank(methodName)) return new Reflect(null);
        try {
            return new Reflect(Accessor.callByName(value, methodName, args));
        } catch (ReflectiveOperationException | IllegalArgumentException e) {
            return new Reflect(null);
        }
    }

    /** Shorthand for {@code field(name).as(type)}. */
    public <T> Optional<T> field(String fieldName, Class<T> type) {
        return field(fieldName).as(type);
    }

    public <T> Optional<T> as(Class<T> type) {
        if (value == null || type == null || !type.isInstance(value)) return Optional.empty();
        return Optional.of(type.cast(value));
    }

    public Optional<Object> asObject() {
        return Optional.ofNullable(value);
    }

    public Object orElse(Object defaultValue) {
        return value != null ? value : defaultValue;
    }

    public boolean isPresent() {
        return value != null;
    }
}
