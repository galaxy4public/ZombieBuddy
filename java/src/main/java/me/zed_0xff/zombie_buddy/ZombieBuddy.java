package me.zed_0xff.zombie_buddy;

import static me.zed_0xff.zombie_buddy.ModFlags.*;

import java.io.File;
import java.util.ArrayList;

import se.krka.kahlua.vm.JavaFunction;
import se.krka.kahlua.vm.LuaClosure;
import se.krka.kahlua.vm.KahluaTable;
import zombie.Lua.LuaManager;

//@Exposer.LuaClass doesn't always work here because ZB is loaded in premain
public class ZombieBuddy {
    private static String version = "unknown";
    
    static {
        loadVersionInfo();
    }
    
    private static void loadVersionInfo() {
        try {
            Package pkg = ZombieBuddy.class.getPackage();
            if (pkg != null) {
                String implVersion = pkg.getImplementationVersion();
                if (!Utils.isBlank(implVersion)) {
                    version = implVersion;
                }
            }
        } catch (Exception e) {
            Logger.error("Could not load version info: " + e.getMessage());
        }
    }
    
    public static String getVersion() {
        return version;
    }
    
    public static String getFullVersionString() {
        return "ZombieBuddy v" + version;
    }

    /** Current Java-mod JAR policy: "prompt", "deny-new", or "allow-all". */
    public static String getPolicy() {
        return Loader.getPolicy();
    }

    /**
     * Per-mod status snapshot captured during Loader.loadMods(). Returns null
     * if the given modId has no recorded Java JAR (not a Java mod, or
     * metadata-only mod.info with no javaJarFile).
     *
     * Fields in the returned table:
     *   active    (boolean) — true if the JAR was loaded this run
     *   reason    (string)  — "active" or short skip reason
     *   sha256    (string)  — JAR sha256 (may be absent if hashing failed)
     *   decision  (boolean) — true for allow, false for deny (absent if never decided)
     *   persisted (boolean) — true when decision is present
     *   zbsValid  (boolean) — true if the JAR is signed and signature is valid
     */
    public static KahluaTable getJavaModStatus(String modId) {
        Loader.JavaModLoadState s = Loader.getJarLoadState(modId);
        if (s == null) return null;
        var tbl = LuaManager.platform.newTable();
        tbl.rawset("active", s.flags.has(MF_ACTIVE));
        tbl.rawset("reason", s.reason);
        if (s.sha256 != null) tbl.rawset("sha256", s.sha256);
        if (s.decision != null) {
            tbl.rawset("decision", s.decision);
            tbl.rawset("persisted", s.flags.has(MF_PERSIST));
        }
        tbl.rawset("zbsValid", s.flags.hasAll(MF_SIGNED, MF_VALID));
        return tbl;
    }

    public static ArrayList<String> getActiveJavaMods() {
        return Loader.getActiveJavaMods();
    }

    public static boolean isJavaModSigned(String modId) {
        Loader.JavaModLoadState s = Loader.getJarLoadState(modId);
        return s != null && s.flags.hasAll(MF_SIGNED, MF_VALID);
    }

    public static String getClosureFilename(Object obj) {
        if (obj instanceof LuaClosure closure) {
            if (closure == null || closure.prototype == null)
                return null;

            return closure.prototype.filename;
        }

        return null;
    }

    public static KahluaTable getClosureInfo(Object obj) {
        if (obj instanceof LuaClosure closure) {
            if (closure == null || closure.prototype == null)
                return null;

            var tbl = LuaManager.platform.newTable();
            tbl.rawset("file",      closure.prototype.file);
            tbl.rawset("filename",  closure.prototype.filename);
            tbl.rawset("name",      closure.prototype.name);
            tbl.rawset("numParams", Double.valueOf(closure.prototype.numParams));
            tbl.rawset("isVararg",  closure.prototype.isVararg);

            if (closure.prototype.lines != null && closure.prototype.lines.length > 0) {
                tbl.rawset("line", Double.valueOf(closure.prototype.lines[0]));
            }
            String path = closure.prototype.filename;
            if (!Utils.isBlank(path)) {
                long lastModified = new File(path).lastModified();
                if (lastModified != 0L) {
                    tbl.rawset("fileLastModified", Double.valueOf(lastModified));
                }
            }
            return tbl;
        }

        return null;
    }

    /** Returns metadata for any callable: Lua closure or Java function. */
    public static KahluaTable getCallableInfo(Object obj) {
        if (obj instanceof LuaClosure closure) {
            if (closure == null || closure.prototype == null)
                return null;
            var tbl = getClosureInfo(obj);
            if (tbl != null) tbl.rawset("kind", "lua");
            return tbl;
        }
        if (obj instanceof JavaFunction) {
            var tbl = LuaManager.platform.newTable();
            tbl.rawset("kind", "java");
            Class<?> c = obj.getClass();
            tbl.rawset("className", c.getName());
            tbl.rawset("simpleName", c.getSimpleName());
            tbl.rawset("name", obj.toString());
            Utils.addInvokersInfo(tbl, obj);
            return tbl;
        }
        return null;
    }
}
