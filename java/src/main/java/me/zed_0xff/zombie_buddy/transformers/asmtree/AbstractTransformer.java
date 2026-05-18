package me.zed_0xff.zombie_buddy.transformers.asmtree;

import me.zed_0xff.zombie_buddy.Patch;
import me.zed_0xff.zombie_buddy.transformers.ClassContext;
import me.zed_0xff.zombie_buddy.transformers.Transformer;

import java.util.*;

import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

abstract class AbstractTransformer extends Transformer {

    /** Lazily built from {@link Patch} nested annotations + {@link Patch.Internal.Meta}; shared by all asm-tree transformers. */
    private static volatile Map<String, PatchAnnMeta[]> PATCH_ANN_META_BY_DESC;

    /** Descriptor {@code Lme/zed_0xff/zombie_buddy/Patch$…;} → BB/advice target annotation (+ advice/delegation choice). */
    protected record PatchAnnMeta(String targetDesc, boolean advice, String[] targetParamNames, String[] targetParamValues) {}

    /** Reflection scan once per JVM; thread-safe published map. */
    protected static Map<String, PatchAnnMeta[]> patchAnnMetaByDesc() {
        Map<String, PatchAnnMeta[]> v = PATCH_ANN_META_BY_DESC;

        if (v != null) return v;

        synchronized (AbstractTransformer.class) {
            if (PATCH_ANN_META_BY_DESC != null) return PATCH_ANN_META_BY_DESC;

            PATCH_ANN_META_BY_DESC = buildPatchAnnMetaByDesc();

            return PATCH_ANN_META_BY_DESC;
        }
    }

    private static Map<String, PatchAnnMeta[]> buildPatchAnnMetaByDesc() {
        Map<String, List<PatchAnnMeta>> tmp = new HashMap<>();

        for (Class<?> c : Patch.class.getDeclaredClasses()) {
            if (!c.isAnnotation()) continue;

            Patch.Internal.Meta[] metas = c.getAnnotationsByType(Patch.Internal.Meta.class);
            if (metas.length == 0) continue;

            List<PatchAnnMeta> row = new ArrayList<>(metas.length);
            for (Patch.Internal.Meta m : metas) {
                Class<?> ta = m.targetAnnotation();
                if (ta == null || ta == void.class) continue;

                row.add(new PatchAnnMeta(Type.getDescriptor(ta), m.isAdvice(), m.targetParamNames(), m.targetParamValues()));
            }

            if (!row.isEmpty()) tmp.put(Type.getDescriptor(c), row);
        }

        Map<String, PatchAnnMeta[]> out = new HashMap<>(tmp.size());
        for (Map.Entry<String, List<PatchAnnMeta>> e : tmp.entrySet()) {
            out.put(e.getKey(), e.getValue().toArray(PatchAnnMeta[]::new));
        }

        return Collections.unmodifiableMap(out);
    }

    protected abstract void transformNode(ClassNode cn);

    @Override
    public Result transform(byte[] classBytes, ClassContext ctx) {
        try {
            m_ctx = ctx;
            ClassReader cr = new ClassReader(classBytes);

            ClassNode cn = new ClassNode();
            cr.accept(cn, 0);

            transformNode(cn);

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
