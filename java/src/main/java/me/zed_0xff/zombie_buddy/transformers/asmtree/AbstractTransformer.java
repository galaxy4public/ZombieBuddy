package me.zed_0xff.zombie_buddy.transformers.asmtree;

import me.zed_0xff.zombie_buddy.transformers.ClassContext;
import me.zed_0xff.zombie_buddy.transformers.Transformer;
import me.zed_0xff.zombie_buddy.Utils;

import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

abstract class AbstractTransformer extends Transformer {
    private static final HashMap<String, Map<Integer, String>> _methodArgNamesCache = new HashMap<>();

    protected static Map<Integer, String> getArgNames(MethodNode mn) {
        Type[] argTypes = Type.getArgumentTypes(mn.desc);
        if (argTypes.length == 0 || Utils.isBlank(mn.localVariables))
            return Map.of();

        Map<Integer, LocalVariableNode> varsBySlot = mn.localVariables.stream().collect(Collectors.toMap(v -> v.index, v -> v, (a, b) -> a));
        Map<Integer, String> result = new HashMap<>();
        int slot = Modifier.isStatic(mn.access) ? 0 : 1;
        for (int i = 0; i < argTypes.length; slot += argTypes[i++].getSize()) {
            LocalVariableNode lv = varsBySlot.get(slot);
            result.put(i, lv != null ? lv.name : "arg" + i);
        }

        return result;
    }

    protected String getArgName(MethodNode mn, int pidx) {
        if (mn.parameters != null && pidx < mn.parameters.size()) {
            // if compiled with -parameters
            return mn.parameters.get(pidx).name;
        }

        String cacheKey = mn.name + "|" + mn.desc;
        _methodArgNamesCache.computeIfAbsent(cacheKey, k -> getArgNames(mn));
        return _methodArgNamesCache.get(cacheKey).get(pidx);
    }

    // overridden by subclasses
    protected abstract boolean transformNode(ClassNode cn);

    @Override
    public Result transform(byte[] classBytes, ClassContext ctx) {
        try {
            m_ctx = ctx;
            ClassReader cr = new ClassReader(classBytes);

            ClassNode cn = new ClassNode();
            cr.accept(cn, 0);

            if (transformNode(cn)) {
                m_ctx.setChanged();
            }

            if (!m_ctx.isChanged()) {
                return NOOP_RESULT;
            }

            ClassWriter cw = new ClassWriter(0);
            cn.accept(cw);
            byte[] newBytes = cw.toByteArray();
            ctx.setClassBytes(newBytes);

            return new Result(newBytes, true);
        } finally {
            m_ctx = null;
        }
    }
}
