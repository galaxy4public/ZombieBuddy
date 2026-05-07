package me.zed_0xff.zombie_buddy;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public final class Accessor {

    // Class-name -> Class<?> lives separately: we need the Class object before we can create a ClassInfo.
    private static final ConcurrentHashMap<String, Optional<Class<?>>> classNameCache = new ConcurrentHashMap<>();

    // Per-class cache. On second access the class is fully prewarmed (all fields and declared methods scanned).
    private static final ConcurrentHashMap<Class<?>, ClassInfo> cache = new ConcurrentHashMap<>();

    private static final class ClassInfo {
        final AtomicInteger accessCount = new AtomicInteger(0);
        // Set to true only after prewarm() fully completes (volatile for happens-before).
        volatile boolean prewarmed = false;
        final ConcurrentHashMap<String, Optional<Field>>    fields        = new ConcurrentHashMap<>();
        final ConcurrentHashMap<String, List<Method>>       methodsByName = new ConcurrentHashMap<>();
        // key: methodName + '\0' + param1Name + '\0' + param2Name ...
        final ConcurrentHashMap<String, Optional<Method>>   exactMethods  = new ConcurrentHashMap<>();
        // populated lazily before prewarm; replaced by publicMethodNames after
        final ConcurrentHashMap<String, Boolean>            publicMethods     = new ConcurrentHashMap<>();
        volatile Set<String>  publicMethodNames = null;
        volatile List<Method> publicMethodList  = null;
    }

    private Accessor() {}

    private static ClassInfo getClassInfo(Class<?> cls) {
        ClassInfo info = cache.computeIfAbsent(cls, k -> new ClassInfo());
        // incrementAndGet is atomic: exactly one thread observes count==2, so no CAS needed.
        if (info.accessCount.incrementAndGet() == 2) {
            prewarm(cls, info);
        }
        return info;
    }

    private static void prewarm(Class<?> cls, ClassInfo info) {
        Logger.debug("Prewarming Accessor cache for " + cls.getName());
        for (Class<?> c = cls; c != null; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                info.fields.putIfAbsent(f.getName(), Optional.of(f));
            }
        }
        Map<String, List<Method>> byName = new HashMap<>();
        for (Class<?> c = cls; c != null; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                byName.computeIfAbsent(m.getName(), k -> new ArrayList<>()).add(m);
                info.exactMethods.putIfAbsent(exactMethodKey(m.getName(), m.getParameterTypes()), Optional.of(m));
            }
        }
        for (Map.Entry<String, List<Method>> e : byName.entrySet()) {
            info.methodsByName.putIfAbsent(e.getKey(), Collections.unmodifiableList(e.getValue()));
        }
        Set<String> names = new HashSet<>();
        List<Method> pubList = new ArrayList<>();
        for (Method m : cls.getMethods()) {
            names.add(m.getName());
            pubList.add(m);
        }
        info.publicMethodNames = Collections.unmodifiableSet(names);
        info.publicMethodList  = Collections.unmodifiableList(pubList);
        info.prewarmed = true;
    }

    private static String exactMethodKey(String methodName, Class<?>[] parameterTypes) {
        StringBuilder sb = new StringBuilder(methodName).append('\0');
        if (parameterTypes != null) {
            for (int i = 0; i < parameterTypes.length; i++) {
                if (i > 0) sb.append('\0');
                sb.append(parameterTypes[i].getName());
            }
        }
        return sb.toString();
    }

    public static void clearCaches() {
        Logger.debug("Clearing Accessor caches");
        classNameCache.clear();
        cache.clear();
    }

    /**
     * Returns all declared fields in {@code cls}'s hierarchy, one per name (most-derived wins).
     * Uses the prewarmed cache when available; otherwise scans and warms the per-name entries.
     */
    public static List<Field> allFields(Class<?> cls) {
        if (cls == null) return Collections.emptyList();
        ClassInfo info = getClassInfo(cls);
        if (info.prewarmed) {
            List<Field> out = new ArrayList<>(info.fields.size());
            for (Optional<Field> opt : info.fields.values()) {
                opt.ifPresent(out::add);
            }
            return out;
        }
        List<Field> out = new ArrayList<>();
        for (Class<?> c = cls; c != null; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (info.fields.putIfAbsent(f.getName(), Optional.of(f)) == null) {
                    out.add(f);
                }
            }
        }
        return out;
    }

    /**
     * Returns all public methods of {@code cls} (equivalent to {@link Class#getMethods()}, cached).
     */
    public static List<Method> publicMethods(Class<?> cls) {
        if (cls == null) return Collections.emptyList();
        ClassInfo info = getClassInfo(cls);
        List<Method> list = info.publicMethodList;
        if (list != null) return list;
        list = List.copyOf(Arrays.asList(cls.getMethods()));
        info.publicMethodList = list;
        return list;
    }

    /**
     * Returns all declared methods in {@code cls}'s hierarchy (all names, all overloads).
     * Uses the prewarmed cache when available; otherwise scans directly.
     */
    public static List<Method> allMethods(Class<?> cls) {
        if (cls == null) return Collections.emptyList();
        ClassInfo info = getClassInfo(cls);
        if (info.prewarmed) {
            List<Method> out = new ArrayList<>();
            for (List<Method> ml : info.methodsByName.values()) {
                out.addAll(ml);
            }
            return out;
        }
        List<Method> out = new ArrayList<>();
        for (Class<?> c = cls; c != null; c = c.getSuperclass()) {
            Collections.addAll(out, c.getDeclaredMethods());
        }
        return out;
    }

    /**
     * Gets the value of the named field on {@code obj}, or {@code defaultValue} if
     * the object is null, the field does not exist, or it cannot be read.
     * If {@code obj} is a {@link Class}, the field is looked up on that class and
     * read as a static field (instance argument is ignored for static fields).
     */
    public static <T> T tryGet(Object obj, String fieldName, T defaultValue) {
        if (obj == null || Utils.isBlank(fieldName)) {
            return defaultValue;
        }
        Class<?> cls = obj instanceof Class ? (Class<?>) obj : obj.getClass();
        Field field = findField(cls, fieldName);
        Object instance = obj instanceof Class ? null : obj;
        return tryGet(instance, field, defaultValue);
    }

    /**
     * Gets the value of {@code field} on {@code obj}, or {@code defaultValue} if
     * the field is null or it cannot be read. For static fields, {@code obj} may be null.
     */
    @SuppressWarnings("unchecked")
    public static <T> T tryGet(Object obj, Field field, T defaultValue) {
        if (field == null) {
            return defaultValue;
        }
        if (obj == null && !Modifier.isStatic(field.getModifiers())) {
            return defaultValue;
        }
        try {
            field.setAccessible(true);
            return (T) field.get(obj);
        } catch (Throwable t) {
            return defaultValue;
        }
    }

    /**
     * Sets the named field on {@code obj} to {@code value}.
     * If {@code obj} is a {@link Class}, the field is looked up on that class and set as a static field.
     */
    public static <T> boolean trySet(Object obj, String fieldName, T value) {
        if (obj == null || Utils.isBlank(fieldName)) {
            return false;
        }
        Class<?> cls = obj instanceof Class ? (Class<?>) obj : obj.getClass();
        Field field = findField(cls, fieldName);
        Object instance = obj instanceof Class ? null : obj;
        return trySet(instance, field, value);
    }

    /**
     * Sets {@code field} on {@code obj} to {@code value}. For static fields, {@code obj} may be null.
     */
    public static <T> boolean trySet(Object obj, Field field, T value) {
        if (field == null) {
            return false;
        }
        if (obj == null && !Modifier.isStatic(field.getModifiers())) {
            return false;
        }
        try {
            field.setAccessible(true);
            field.set(obj, value);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Returns the first loadable class from the given names, or null. Never throws. */
    public static Class<?> findClass(String... classNames) {
        if (classNames == null || classNames.length == 0) {
            return null;
        }
        for (String className : classNames) {
            if (!Utils.isBlank(className)) {
                String normalized = className.replace('/', '.');
                Class<?> cls = classNameCache
                    .computeIfAbsent(normalized, k -> Optional.ofNullable(findClassUncached(k)))
                    .orElse(null);
                if (cls != null) {
                    return cls;
                }
            }
        }
        return null;
    }

    private static Class<?> findClassUncached(String className) {
        try {
            return Class.forName(className);
        } catch (ClassNotFoundException | LinkageError e) {
            return null;
        }
    }

    /**
     * Finds a field by name in {@code cls} or any superclass. Accepts multiple candidate
     * names and returns the first found. Results are cached per class.
     */
    public static Field findField(Class<?> cls, String... fieldNames) {
        if (cls == null || fieldNames == null || fieldNames.length == 0) {
            return null;
        }
        ClassInfo info = getClassInfo(cls);
        for (String fieldName : fieldNames) {
            if (!Utils.isBlank(fieldName)) {
                Field f = info.fields
                    .computeIfAbsent(fieldName, k -> Optional.ofNullable(findFieldUncached(cls, k)))
                    .orElse(null);
                if (f != null) {
                    return f;
                }
            }
        }
        return null;
    }

    /**
     * Finds a field by name in the class named {@code className} or any superclass.
     */
    public static Field findField(String className, String... fieldNames) {
        if (Utils.isBlank(className) || fieldNames == null || fieldNames.length == 0) {
            return null;
        }
        return findField(findClass(className), fieldNames);
    }

    private static Field findFieldUncached(Class<?> cls, String fieldName) {
        for (Class<?> c = cls; c != null; c = c.getSuperclass()) {
            try {
                return c.getDeclaredField(fieldName);
            } catch (NoSuchFieldException e) {
                // continue to superclass
            }
        }
        return null;
    }

    /**
     * Finds all methods with the given name in {@code cls} and its superclasses.
     * Includes overloads and overrides. Order: current class first, then superclass.
     */
    public static List<Method> findMethodsByName(Class<?> cls, String methodName) {
        if (cls == null || Utils.isBlank(methodName)) {
            return Collections.emptyList();
        }
        return getClassInfo(cls).methodsByName
            .computeIfAbsent(methodName, k -> findMethodsByNameUncached(cls, k));
    }

    private static List<Method> findMethodsByNameUncached(Class<?> cls, String methodName) {
        List<Method> out = new ArrayList<>();
        for (Class<?> c = cls; c != null; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (methodName.equals(m.getName())) {
                    out.add(m);
                }
            }
        }
        return out.isEmpty() ? Collections.emptyList() : Collections.unmodifiableList(out);
    }

    /** Finds a no-arg method by name in {@code cls} or any superclass. Returns null if not found. */
    public static Method findNoArgMethod(Class<?> cls, String methodName) {
        return findExactMethod(cls, methodName, (Class<?>[]) null);
    }

    /**
     * Finds a method by name and parameter types in {@code cls} or any superclass.
     * Pass empty array or null for no-arg method. Results are cached per class.
     */
    public static Method findExactMethod(Class<?> cls, String methodName, Class<?>... parameterTypes) {
        String key = exactMethodKey(methodName, parameterTypes);
        return getClassInfo(cls).exactMethods
            .computeIfAbsent(key, k -> Optional.ofNullable(findExactMethodUncached(cls, methodName, parameterTypes)))
            .orElse(null);
    }

    private static Method findExactMethodUncached(Class<?> cls, String methodName, Class<?>[] parameterTypes) {
        for (Class<?> c = cls; c != null; c = c.getSuperclass()) {
            try {
                if (parameterTypes == null || parameterTypes.length == 0) {
                    return c.getDeclaredMethod(methodName);
                }
                return c.getDeclaredMethod(methodName, parameterTypes);
            } catch (NoSuchMethodException e) {
                // continue to superclass
            }
        }
        return null;
    }

    /**
     * Returns true if {@code cls} has a public method with the given name (any overload).
     *
     * @throws IllegalArgumentException if cls or methodName is null/empty
     */
    public static boolean hasPublicMethod(Class<?> cls, String methodName) {
        if (cls == null || Utils.isBlank(methodName)) {
            throw new IllegalArgumentException("cls and methodName must be non-null and non-empty");
        }
        ClassInfo info = getClassInfo(cls);
        Set<String> names = info.publicMethodNames;
        if (names != null) {
            return names.contains(methodName);
        }
        return info.publicMethods.computeIfAbsent(methodName, k -> {
            for (Method m : cls.getMethods()) {
                if (k.equals(m.getName())) return true;
            }
            return false;
        });
    }

    /**
     * Returns true if {@code obj}'s class has a public method with the given name (any overload).
     *
     * @throws IllegalArgumentException if obj or methodName is null/empty
     */
    public static boolean hasPublicMethod(Object obj, String methodName) {
        if (obj == null || Utils.isBlank(methodName)) {
            throw new IllegalArgumentException("obj and methodName must be non-null and non-empty");
        }
        return hasPublicMethod(obj.getClass(), methodName);
    }

    /**
     * Invokes the named no-arg method on {@code obj}. Does not catch exceptions.
     *
     * @throws NoSuchMethodException        if the method is not found
     * @throws ReflectiveOperationException if setAccessible or invoke fails
     */
    public static Object callNoArg(Object obj, String methodName) throws ReflectiveOperationException {
        return callExact(obj, methodName, (Class<?>[]) null);
    }

    /**
     * Invokes the named method on {@code obj} with the given parameter types and arguments.
     * Does not catch exceptions.
     *
     * @throws NoSuchMethodException        if the method is not found
     * @throws ReflectiveOperationException if setAccessible or invoke fails
     */
    public static Object callExact(Object obj, String methodName, Class<?>[] parameterTypes, Object... args) throws ReflectiveOperationException {
        if (obj == null || Utils.isBlank(methodName)) {
            throw new IllegalArgumentException("obj and methodName must be non-null and non-empty");
        }
        Method m = findExactMethod(obj.getClass(), methodName, parameterTypes);
        if (m == null) {
            throw new NoSuchMethodException(methodName);
        }
        m.setAccessible(true);
        return m.invoke(obj, args);
    }

    /**
     * Invokes a method with the given name on {@code obj}, choosing an overload by argument count
     * and compatibility. If {@code obj} is a String class name or a Class, calls a static method.
     * Does not catch exceptions.
     *
     * @throws NoSuchMethodException        if no compatible overload is found
     * @throws ReflectiveOperationException if setAccessible or invoke fails
     */
    public static Object callByName(Object obj, String methodName, Object... args) throws ReflectiveOperationException {
        if (obj == null || Utils.isBlank(methodName)) {
            throw new IllegalArgumentException("obj and methodName must be non-null and non-empty");
        }
        boolean staticCall = false;
        Class<?> targetClass;
        if (obj instanceof String className) {
            targetClass = findClass(className);
            if (targetClass == null) {
                throw new ClassNotFoundException("class not found: " + className);
            }
            staticCall = true;
        } else if (obj instanceof Class<?> c) {
            targetClass = c;
            staticCall = true;
        } else {
            targetClass = obj.getClass();
        }
        int nArgs = args == null ? 0 : args.length;
        Object[] invokeArgs = args == null ? new Object[0] : args;
        for (Method m : findMethodsByName(targetClass, methodName)) {
            if (m.getParameterCount() != nArgs) {
                continue;
            }
            if (staticCall && !Modifier.isStatic(m.getModifiers())) {
                continue;
            }
            try {
                m.setAccessible(true);
                Object receiver = Modifier.isStatic(m.getModifiers()) ? null : obj;
                return m.invoke(receiver, invokeArgs);
            } catch (IllegalArgumentException e) {
                // argument types don't match this overload, try next
                continue;
            }
        }
        throw new NoSuchMethodException("no compatible overload for " + methodName + " with " + nArgs + " argument(s)");
    }
}
