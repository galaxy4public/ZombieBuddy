package me.zed_0xff.zombie_buddy;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;

import se.krka.kahlua.integration.annotations.LuaMethod;
import se.krka.kahlua.vm.KahluaTable;

import zombie.Lua.LuaManager;

public class Exposer {

    /**
     * Marker annotation for classes that should be exposed to Lua.
     *
     * Usage:
     *   @Exposer.LuaClass
     *   public class MyApi { ... }  // Accessible as MyApi
     *
     *   @Exposer.LuaClass(name = "ZombieBuddy.Utils")
     *   public class Utils { ... }  // Accessible as ZombieBuddy.Utils
     *
     *   @Exposer.LuaClass(name = "ZB.API.Logger")
     *   public class MyLogger { ... }  // Accessible as ZB.API.Logger (nested tables)
     */
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.TYPE)
    public @interface LuaClass {
        /** Optional Lua name. Dots create nested tables. Default: class simple name. */
        String name() default "";
    }

    // Class -> Lua name (may contain dots for nesting, empty = use simple name)
    private static final HashMap<Class<?>, String> g_exposed_classes = new HashMap<>();
    private static final HashMap<Class<?>, HashSet<String>> g_exposed_methods = new HashMap<>();
    private static final HashSet<Class<?>> g_classesWithGlobalLuaMethod = new HashSet<>();

    public static boolean hasGlobalLuaMethod(Class<?> cls) {
        for (Method m : cls.getMethods()) {
            LuaMethod ann = m.getAnnotation(LuaMethod.class);
            if (ann != null && ann.global()) {
                return true;
            }
        }
        return false;
    }

    public static void addClassWithGlobalLuaMethod(Class<?> cls) {
        if (cls != null && hasGlobalLuaMethod(cls)) {
            g_classesWithGlobalLuaMethod.add(cls);
        }
    }

    public static List<Class<?>> getClassesWithGlobalLuaMethod() {
        return new ArrayList<>(g_classesWithGlobalLuaMethod);
    }

    public static void exposeClassToLua(Class<?> cls) {
        Logger.warn("exposeClassToLua() method is deprecated, use exposeClass() or @LuaClass annotation instead");
        exposeClass(cls, null);
    }

    public static boolean exposeClassToLua(String className) {
        Logger.warn("exposeClassToLua() method is deprecated, use exposeClass() or @LuaClass annotation instead");
        return exposeClass(className);
    }

    public static void exposeClass(Class<?> cls) {
        LuaClass ann = cls.getAnnotation(LuaClass.class);
        exposeClass(cls, ann != null ? ann.name() : null);
    }

    public static void exposeClass(Class<?> cls, String name) {
        if (g_exposed_classes.containsKey(cls)) {
            return;
        }
        g_exposed_classes.put(cls, name != null ? name : "");

        // If exposer is already available, expose immediately (for mods loaded after initial exposure)
        if (LuaManager.exposer != null && LuaManager.env != null) {
            exposeClassNow(cls);
        }
    }

    private static void exposeClassNow(Class<?> cls) {
        var exposer = LuaManager.exposer;
        var env = LuaManager.env;
        String name = g_exposed_classes.get(cls);
        String simpleName = cls.getSimpleName();

        if (Utils.isBlank(name)) {
            Logger.info("Exposing class to Lua: " + simpleName);
            exposer.setExposed(cls);
            exposer.exposeLikeJavaRecursively(cls, env);
        } else {
            Logger.info("Exposing class to Lua: " + simpleName + " as " + name);
            var staticBase = getOrCreateParentTable(env, name);
            if (staticBase == null) {
                Logger.error("Failed to create parent table for " + name);
                return;
            }
            exposer.setExposed(cls);
            exposer.exposeLikeJavaRecursively(cls, staticBase);
            if (!name.endsWith("." + simpleName)) {
                String newSimpleName = leafName(name);
                staticBase.rawset(newSimpleName, staticBase.rawget(simpleName));
                staticBase.rawset(simpleName, null);
            }
        }
    }

    public static boolean exposeClass(String className) {
        Class<?> cls = Accessor.findClass(className);
        if (cls == null) {
            Logger.warn("exposeClass(\"" + className + "\"): class not found");
            return false;
        }
        exposeClass(cls);
        return true;
    }

    public static void exposeMethod(String className, String methodName) {
        Class<?> cls = Accessor.findClass(className);
        if (cls == null) {
            Logger.warn("exposeMethod(\"" + className + "\", \"" + methodName + "\"): class not found");
            return;
        }
        g_exposed_methods.computeIfAbsent(cls, k -> new HashSet<>()).add(methodName);
    }

    public static List<Class<?>> getExposedClasses() {
        return new ArrayList<>(g_exposed_classes.keySet());
    }

    public static boolean isClassExposed(Class<?> cls) {
        return g_exposed_classes.containsKey(cls);
    }

    public static void afterExposeAll() {
        var exposer = LuaManager.exposer;
        if (exposer == null) {
            Logger.error("LuaManager.exposer is null!");
            return;
        }
        var env = LuaManager.env;
        if (env == null) {
            Logger.error("LuaManager.env is null!");
            return;
        }

        // expose non-renamed classes first
        for (var entry : g_exposed_classes.entrySet()) {
            Class<?> cls = entry.getKey();
            String name = entry.getValue();
            if (Utils.isBlank(name)) {
                exposeClassNow(cls);
            }
        }

        // expose renamed classes second
        for (var entry : g_exposed_classes.entrySet()) {
            Class<?> cls = entry.getKey();
            String name = entry.getValue();
            if (!Utils.isBlank(name)) {
                exposeClassNow(cls);
            }
        }

        // Expose global functions
        for (Class<?> cls : g_classesWithGlobalLuaMethod) {
            Object instance = newInstance(cls);
            if (instance != null) {
                try {
                    Logger.info("Exposing global functions from class: " + cls.getName());
                    exposer.exposeGlobalFunctions(instance);
                } catch (Exception e) {
                    Logger.error("exposeGlobalFunctions(" + cls.getName() + "): " + e.getMessage());
                }
            }
        }

        // Expose individual methods (for @HiddenFromLua overrides)
        for (var entry : g_exposed_methods.entrySet()) {
            Class<?> cls = entry.getKey();
            for (String methodName : entry.getValue()) {
                Logger.info("Exposing method " + cls.getName() + "." + methodName + "()");
                for (var method : cls.getMethods()) {
                    if (method.getName().equals(methodName)) {
                        try {
                            exposer.exposeMethod(cls, method, method.getName(), env);
                        } catch (Exception e) {
                            Logger.error("exposeMethod(" + cls.getName() + ", " + methodName + "): " + e.getMessage());
                        }
                    }
                }
            }
        }
    }

    private static KahluaTable getOrCreateParentTable(KahluaTable root, String path) {
        if (root == null || path == null) return null;

        String[] parts = path.split("\\.");
        KahluaTable current = root;

        for (int i = 0; i < parts.length - 1; i++) {
            Object next = current.rawget(parts[i]);
            if (next == null) {
                next = LuaManager.platform.newTable();
                current.rawset(parts[i], next);
            }
            if (next instanceof KahluaTable) {
                current = (KahluaTable) next;
            } else {
                Logger.error("Cannot create nested table at " + parts[i] + " - already exists as non-table");
                return null;
            }
        }

        return current;
    }

    private static String leafName(String path) {
        if (path == null) return "";
        int dot = path.lastIndexOf('.');
        return dot >= 0 ? path.substring(dot + 1) : path;
    }

    public static Object newInstance(Class<?> cls) {
        try {
            if (Modifier.isAbstract(cls.getModifiers()) || cls.isInterface()) {
                return null;
            }
            return cls.getDeclaredConstructor().newInstance();
        } catch (Throwable t) {
            return null;
        }
    }

    public static void exposeAnnotatedClasses(String packageName) {
        try (var scanResult = new io.github.classgraph.ClassGraph()
                .acceptPackages(packageName)
                .enableAnnotationInfo()
                .scan()) {
            exposeAnnotatedClasses(scanResult, packageName);
        }
    }

    public static void exposeAnnotatedClasses(io.github.classgraph.ScanResult scanResult, String packageName) {
        for (var classInfo : scanResult.getClassesWithAnnotation(LuaClass.class.getName())) {
            if (Utils.isBlank(packageName) || !classInfo.getPackageName().equals(packageName)) {
                Logger.error("Class " + classInfo.getName() + " is annotated with @LuaClass but is not in the exact package "
                        + packageName + ", skipping exposure");
                continue;
            }
            try {
                Class<?> cls = classInfo.loadClass();
                LuaClass ann = cls.getAnnotation(LuaClass.class);
                if (ann == null) {
                    Logger.error("Class " + classInfo.getName() + " is annotated with @LuaClass but annotation is null, skipping");
                    continue;
                }
                exposeClass(cls, ann.name());
            } catch (Exception e) {
                Logger.error("Error exposing Lua class " + classInfo.getName() + ": " + e.getMessage());
            }
        }
    }
}
