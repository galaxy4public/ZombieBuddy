package me.zed_0xff.zombie_buddy;

import java.lang.instrument.Instrumentation;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.invoke.VarHandle;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.concurrent.ConcurrentHashMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Map;

/**
 * Fluent reflection chain. Entry point is {@link #on(Object)}; strings are resolved as class
 * names. Every step returns a new {@code Reflect}; a failed step produces a null-valued chain
 * that silently propagates — call {@link #isPresent()} or {@link #as} at the end to detect
 * failure.
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

    public enum Flag {
        PUBLIC, PROTECTED, PACKAGE_PRIVATE, PRIVATE,
        STATIC, INSTANCE
    }

    public static final Flag PUBLIC          = Flag.PUBLIC;
    public static final Flag PROTECTED       = Flag.PROTECTED;
    public static final Flag PACKAGE_PRIVATE = Flag.PACKAGE_PRIVATE;
    public static final Flag PRIVATE         = Flag.PRIVATE;
    public static final Flag STATIC          = Flag.STATIC;
    public static final Flag INSTANCE        = Flag.INSTANCE;

    private static final Reflect REFLECT_NULL = new Reflect(null);
    private static final Object  MISS         = new Object();

    private final Object value;

    // public final class RField<T> {
    //     private final VarHandle handle;
    // 
    //     public Var(VarHandle handle) {
    //         this.handle = handle;
    //     }
    // 
    //     public Optional<T> get() {
    //         return Optional.ofNullable(value);
    //     }
    //     
    //     public T getOrDefault(T defaultValue) {
    //         return defaultValue;
    //     }
    // 
    //     public boolean set(T newValue) {
    //         return false;
    //     }
    // }

    static void init(Instrumentation inst) {
        // inst.addTransformer(new java.lang.instrument.ClassFileTransformer() {
        //     @Override
        //     public byte[] transform(Module module, ClassLoader loader, String name,
        //             Class<?> cls, java.security.ProtectionDomain domain, byte[] bytes) {
        //         if (name != null) System.out.println("[d] class loaded: " + name);
        //         return null;
        //     }
        // });
    }

    private Reflect(Object value) {
        this.value = value;
    }

    /**
     * Entry point for all chains. Strings are resolved as class names (null-valued chain if
     * not found). Everything else is wrapped directly as the subject.
     */
    public static Reflect on(Object obj) {
        if (obj instanceof String s) {
            return new Reflect(Accessor.findClass(s));
        }
        return new Reflect(obj);
    }

    static final record ClassInfo(
            MethodHandles.Lookup lookup,
            ConcurrentHashMap<String, Object> varCache,
            ConcurrentHashMap<String, Object> methodCache
    ) {
        ClassInfo(Class<?> cls) throws IllegalAccessException {
            this(MethodHandles.privateLookupIn(cls, MethodHandles.lookup()), new ConcurrentHashMap<>(), new ConcurrentHashMap<>());
        }
    }

    private static final ConcurrentHashMap<Class<?>, ClassInfo> _cache = new ConcurrentHashMap<>();

    public static void clearCaches() {
        _cache.clear();
    }

    public Reflect field(String fieldName) {
        if (value == null || Utils.isBlank(fieldName))
            return REFLECT_NULL;
    
        return new Reflect(Accessor.tryGet(value, fieldName, null));
    }

    public Reflect staticField(String fieldName) {
        Class<?> cls = resolveClass();
        if (cls == null || Utils.isBlank(fieldName)) return REFLECT_NULL;
    
        return new Reflect(Accessor.tryGet(cls, fieldName, null));
    }

    /** Tries {@code getInstance()} static method, then falls back to a static {@code instance} field. */
    public Reflect getInstance() {
        Class<?> cls = resolveClass();
        if (cls == null) return REFLECT_NULL;

        Method m = Accessor.findExactMethod(cls, "getInstance");
        if (m != null && Modifier.isStatic(m.getModifiers())) {
            try {
                m.setAccessible(true);
                Object instance = m.invoke(null);
                if (instance != null)
                    return new Reflect(instance);
            } catch (ReflectiveOperationException | RuntimeException ignored) {}
        }
        return new Reflect(Accessor.tryGet(cls, "instance", null));
    }

    public Reflect call(String methodName, Object... args) {
        if (value == null || Utils.isBlank(methodName)) return REFLECT_NULL;
        try {
            return new Reflect(Accessor.callByName(value, methodName, args));
        } catch (ReflectiveOperationException | IllegalArgumentException e) {
            return REFLECT_NULL;
        }
    }

    /**
     * Returns declared methods of the subject's class hierarchy, excluding synthetic, bridge, and
     * Object-declared methods. Flags further filter by access/static; empty flags = no extra filtering.
     * Access flags (PUBLIC/PROTECTED/PACKAGE_PRIVATE/PRIVATE) are OR-combined within the group.
     * Static flags (STATIC/INSTANCE) are OR-combined within the group.
     * Both groups must match when both are present.
     */
    public List<Method> methods(Flag... flags) {
        Class<?> cls = resolveClass();
        if (cls == null) return Collections.emptyList();

        EnumSet<Flag> flagSet = toFlagSet(flags);
        List<Method> out = new ArrayList<>();
        for (Method m : Accessor.allMethods(cls)) {
            if (m.isSynthetic() || m.isBridge() || m.getDeclaringClass() == Object.class) continue;

            if (flagSet != null && !matchesMod(m.getModifiers(), flagSet)) continue;

            out.add(m);
        }
        return out;
    }

    /**
     * Returns all declared fields of the subject's class hierarchy (one per name, most-derived
     * wins) matching {@code flags}. Same flag semantics as {@link #methods(Flag...)}.
     */
    public List<Field> fields(Flag... flags) {
        Class<?> cls = resolveClass();
        if (cls == null) return Collections.emptyList();

        EnumSet<Flag> flagSet = toFlagSet(flags);
        List<Field> out = new ArrayList<>();
        for (Field f : Accessor.allFields(cls)) {
            if (f.isSynthetic()) continue;

            if (flagSet != null && !matchesMod(f.getModifiers(), flagSet)) continue;

            out.add(f);
        }
        return out;
    }

    private static EnumSet<Flag> toFlagSet(Flag[] flags) {
        if (flags == null || flags.length == 0)
            return null;

        EnumSet<Flag> set = EnumSet.noneOf(Flag.class);
        for (Flag f : flags) {
            if (f != null) set.add(f);
        }
        return set.isEmpty() ? null : set;
    }

    private static boolean matchesMod(int mod, EnumSet<Flag> flags) {
        boolean hasAccess = flags.contains(Flag.PUBLIC) || flags.contains(Flag.PROTECTED) || flags.contains(Flag.PACKAGE_PRIVATE) || flags.contains(Flag.PRIVATE);
        if (hasAccess) {
            boolean ok = (flags.contains(Flag.PUBLIC)          &&  Modifier.isPublic(mod))
                      || (flags.contains(Flag.PROTECTED)       &&  Modifier.isProtected(mod))
                      || (flags.contains(Flag.PRIVATE)         &&  Modifier.isPrivate(mod))
                      || (flags.contains(Flag.PACKAGE_PRIVATE) && !Modifier.isPublic(mod)
                                                               && !Modifier.isProtected(mod)
                                                               && !Modifier.isPrivate(mod));
            if (!ok) return false;
        }
        boolean hasStatic = flags.contains(Flag.STATIC) || flags.contains(Flag.INSTANCE);
        if (hasStatic) {
            boolean ok = (flags.contains(Flag.STATIC)   &&  Modifier.isStatic(mod))
                      || (flags.contains(Flag.INSTANCE) && !Modifier.isStatic(mod));
            if (!ok) return false;
        }
        return true;
    }

    private Class<?> resolveClass() {
        if (value == null) return null;
        return value instanceof Class<?> c ? c : value.getClass();
    }

    private ClassInfo getClassInfo(Class<?> cls) {
        return _cache.computeIfAbsent(cls, c -> {
                try {
                    return new ClassInfo(c);
                } catch (Exception e) {
                    Logger.error("Failed to create ClassInfo for %s: %s", c, e);
                    return null;
                }
            }
        );
    }

    /** Shorthand for {@code field(name).as(type)}. */
    // public <T> Optional<T> field(String fieldName, Class<T> type) {
    //     return field(fieldName).as(type);
    // }

    public <T> Optional<T> as(Class<T> type) {
        if (value == null || type == null || !type.isInstance(value))
            return Optional.empty();

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

    // call it once and cache the result
    public VarHandle getVarHandle(Class<?> type, String... names) {
        Class<?> cls = resolveClass();
        if (cls == null) return null;
    
        ClassInfo cinfo = getClassInfo(cls);
        if (cinfo == null) return null;

        var varCache = cinfo.varCache();
        for (String fieldName : names) {
            Object v = varCache.get(fieldName);

            if (v == null) {
                try {
                    v = cinfo.lookup().findVarHandle(cls, fieldName, type);
                } catch (NoSuchFieldException | IllegalAccessException e) {
                    try {
                        v = cinfo.lookup().findStaticVarHandle(cls, fieldName, type);
                    } catch (NoSuchFieldException | IllegalAccessException e2) {
                        v = MISS;
                    }
                }

                varCache.put(fieldName, v);
            }

            // v cannot be null here because ConcurrentHashMap doesn't allow null values
            if (v instanceof VarHandle vh) {
                return vh;
            }
        }

        return null;
    }

    // call it once and cache the result
    public MethodHandle getMethodHandle(Class<?> returnType, Class<?>[] parameterTypes, String... names) {
        Class<?> cls = resolveClass();
        if (cls == null) return null;

        ClassInfo cinfo = getClassInfo(cls);
        if (cinfo == null) return null;

        MethodType mt = MethodType.methodType(returnType, parameterTypes);
        String mtKey = mt.toString();
        var methodCache = cinfo.methodCache();
        for (String methodName : names) {
            String cacheKey = methodName + mtKey;
            Object v = methodCache.get(cacheKey);

            if (v == null) {
                try {
                    v = cinfo.lookup().findVirtual(cls, methodName, mt);
                } catch (NoSuchMethodException | IllegalAccessException e) {
                    try {
                        v = cinfo.lookup().findStatic(cls, methodName, mt);
                    } catch (NoSuchMethodException | IllegalAccessException e2) {
                        v = MISS;
                    }
                }

                methodCache.put(cacheKey, v);
            }

            if (v instanceof MethodHandle mh) return mh;
        }

        return null;
    }

    private static final Map<Class<?>, Object> DEFAULTS = Map.of(
            boolean.class, false,
            byte.class, (byte) 0,
            short.class, (short) 0,
            int.class, 0,
            long.class, 0L,
            float.class, 0f,
            double.class, 0d,
            char.class, '\0'
            );

    private static Object defaultValue(Class<?> type) {
        return DEFAULTS.get(type);
    }

    // XXX returns implicit default value for primitives
    @SuppressWarnings("unchecked")
    public <T> T get(String fieldName, Class<T> type) {
        VarHandle vh = getVarHandle(type, fieldName);

        if (vh == null) {
            if (type.isPrimitive()) {
                Logger.warn("using implicit default for " + type.getName() + " " + fieldName);
                return (T) defaultValue(type);
            }
            return null;
        }

        return (T) vh.get(value);
    }

    // safe for primitives
    @SuppressWarnings("unchecked")
    public <T> T getOrDefault(String fieldName, Class<T> type, T defaultValue) {
        VarHandle vh = getVarHandle(type, fieldName);

        if (vh == null)
            return defaultValue;

        return (T) vh.get(value);
    }
}
