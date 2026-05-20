package me.zed_0xff.zombie_buddy.transformers.asmtree;

import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.LocalVariableNode;
import org.objectweb.asm.tree.MethodNode;

import me.zed_0xff.zombie_buddy.Utils;
import me.zed_0xff.zombie_buddy.transformers.ClassContext;
import me.zed_0xff.zombie_buddy.transformers.Transformer;

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

    static class AnnElements extends HashMap<String, Object> {
        public static AnnElements fromValues(List<Object> values) {
            if (Utils.isBlank(values)) return new AnnElements();

            AnnElements map = new AnnElements();
            for (int i = 0; i < values.size(); i += 2) {
                map.put((String)values.get(i), values.get(i + 1));
            }
            return map;
        }

        /** ASM runtime may store annotation booleans as {@link Integer} ({@code 0}/{@code 1}) instead of {@link Boolean}. */
        public Boolean getBoolean(String name) {
            Object val = get(name);
            if (val instanceof Boolean b) return b;
            if (val instanceof Integer j) return j != 0;

            return null;
        }

        public List<Object> toValues() {
            if (Utils.isBlank(this)) return List.of();

            return this.entrySet().stream()
                .flatMap(e -> Stream.of(e.getKey(), e.getValue()))
                .toList();
        }
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
                setModified();
            }

            if (!isModified()) {
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
