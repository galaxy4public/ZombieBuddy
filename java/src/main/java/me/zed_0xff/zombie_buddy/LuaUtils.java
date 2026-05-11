package me.zed_0xff.zombie_buddy;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import se.krka.kahlua.integration.expose.caller.Caller;
import se.krka.kahlua.integration.expose.MethodDebugInformation;
import se.krka.kahlua.vm.KahluaTable;
import zombie.Lua.LuaManager;

public final class LuaUtils {
    private LuaUtils() {}

    /**
     * If {@code obj} is a kahlua-exposed Java invoker (MultiLuaJavaInvoker / LuaJavaInvoker),
     * fills {@code out.invokers} with per-overload metadata.
     */
    public static void addInvokersInfo(KahluaTable out, Object obj) {
        if (out == null || obj == null) return;
        try {
            Class<?> c = obj.getClass();
            List<?> invokersList;
            if ("se.krka.kahlua.integration.expose.MultiLuaJavaInvoker".equals(c.getName())) {
                Object invokers = c.getMethod("getInvokers").invoke(obj);
                invokersList = (invokers instanceof List<?> l && !l.isEmpty()) ? l : null;
            } else if ("se.krka.kahlua.integration.expose.LuaJavaInvoker".equals(c.getName())) {
                invokersList = Collections.singletonList(obj);
            } else {
                invokersList = null;
            }
            if (Utils.isBlank(invokersList)) return;

            Class<?> invokerClass = invokersList.get(0).getClass();
            if (!"se.krka.kahlua.integration.expose.LuaJavaInvoker".equals(invokerClass.getName())) return;

            var invokersTbl = LuaManager.platform.newTable();
            for (int i = 0; i < invokersList.size(); i++) {
                Object inv = invokersList.get(i);
                var invR = Reflect.on(inv);
                Class<?> targetClass = invR.get("clazz", Class.class);
                String methodName    = invR.get("name", String.class);
                if (targetClass == null || methodName == null) continue;

                Caller caller = invR.get("caller", Caller.class);
                var invTbl = LuaManager.platform.newTable();
                invTbl.rawset("targetClass",       targetClass.getName());
                invTbl.rawset("targetSimpleClass", targetClass.getSimpleName());
                invTbl.rawset("methodName",        methodName);
                invTbl.rawset("hasSelf",           invR.get("hasSelf", boolean.class));
                if (caller != null && "se.krka.kahlua.integration.expose.caller.MethodCaller".equals(caller.getClass().getName())) {
                    Method m = Reflect.on(caller).get("method", Method.class);
                    if (m != null) invTbl.rawset("declaringClass", m.getDeclaringClass().getName());
                }
                Object debugData = invokerClass.getMethod("getMethodDebugData").invoke(inv);
                invTbl.rawset("methodDebugData", debugData != null ? debugData.toString() : "");
                invokersTbl.rawset(Double.valueOf(i + 1), invTbl);
            }
            out.rawset("invokers", invokersTbl);
        } catch (Exception e) {
            Logger.printStackTrace(e);
            out.rawset("unwrapError", e.getMessage());
        }
    }

    static KahluaTable mapToLuaTable(Map<?, ?> map) {
        if (map == null) return null;

        var tbl = LuaManager.platform.newTable();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            Object key = entry.getKey();
            Object value = entry.getValue();
            if (key != null) {
                tbl.rawset(key.toString(), value);
            }
        }
        return tbl;
    }
}
