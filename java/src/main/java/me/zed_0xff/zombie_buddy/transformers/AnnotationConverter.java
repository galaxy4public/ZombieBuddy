package me.zed_0xff.zombie_buddy.transformers;

import me.zed_0xff.zombie_buddy.Logger;
import me.zed_0xff.zombie_buddy.Patch;
import me.zed_0xff.zombie_buddy.Utils;

import java.lang.reflect.Method;
import java.util.function.BiFunction;
import java.util.HashMap;
import java.util.Map;

import net.bytebuddy.jar.asm.*;

/*
 * converts ZombieBuddy annotations to ByteBuddy's, driven by @Patch.Internal.Meta metadata
 */
public class AnnotationConverter extends AbstractParamAwareTransformer {

    private record MapBoolInfo(Type onTrue, Type onFalse) {} // onFalse==null means drop on false
    private record AnnInfo(
            Patch.Internal.Meta[]             metas,
            Map<String, MapBoolInfo>          mapBools,
            Map<String, Patch.Internal.Flags> mapFlags
    ) {}

    static final Map<String, AnnInfo> MAPPINGS;
    static {
        MAPPINGS = new HashMap<>();
        for (Class<?> inner : Patch.class.getDeclaredClasses()) {
            if (!inner.isAnnotation()) continue;
            Patch.Internal.Meta[] metas = inner.getAnnotationsByType(Patch.Internal.Meta.class);
            if (metas.length == 0) continue;

            Map<String, MapBoolInfo> mapBools          = new HashMap<>();
            Map<String, Patch.Internal.Flags> mapFlags = new HashMap<>();

            for (Method m : inner.getDeclaredMethods()) {
                Patch.Internal.MapBool mb = m.getAnnotation(Patch.Internal.MapBool.class);
                if (mb != null) mapBools.put(m.getName(), new MapBoolInfo(resolveType(mb.onTrue()), resolveType(mb.onFalse())));

                Patch.Internal.Flags flags = m.getAnnotation(Patch.Internal.Flags.class);
                if (flags != null) mapFlags.put(m.getName(), flags);
            }

            MAPPINGS.put(Type.getDescriptor(inner), new AnnInfo(metas, mapBools, mapFlags));
        }
    }

    private static Type resolveType(Class<?> cls) {
        if (cls == Patch.Internal.DropAnnParam.class) return null;
        return Type.getType(cls);
    }

    public static String mapDescriptor(String descriptor) {
        AnnInfo info = MAPPINGS.get(descriptor);
        if (info == null) return null;

        var meta = info.metas()[0]; // FIXME: handle isAdvice flag and multiple metas
        return meta.targetClass() == void.class ? null : Type.getDescriptor(meta.targetClass());
    }

    /** Forwards every callback to two delegates so ASM element parsing fills both annotations (keep-original + dst). */
    private static class TranslateAnnotationVisitor extends AnnotationVisitor {
        private final AnnotationVisitor       m_src;
        private final AnnotationVisitor       m_dst;
        private final HashMap<String, Object> m_params = new HashMap<>();
        private final String                  m_paramName;
        private final AnnInfo                 m_annInfo;

        private static final Object ARRAY_SENTINEL = new Object();

        TranslateAnnotationVisitor(AnnotationVisitor src, AnnotationVisitor dst) { this(src, dst, null, null); }
        TranslateAnnotationVisitor(AnnotationVisitor src, AnnotationVisitor dst, AnnInfo annInfo, String paramName) {
            super(ASM_API);
            this.m_src       = src;
            this.m_dst       = dst;
            this.m_annInfo   = annInfo;
            this.m_paramName = paramName;
        }

        @Override
        public void visit(String name, Object value) {
            if (name != null) m_params.put(name, value);
            if (m_src != null) m_src.visit(name, value);
            if (m_dst != null) {
                if (m_annInfo != null && name != null) {
                    MapBoolInfo mb = m_annInfo.mapBools().get(name);
                    if (mb != null && value instanceof Boolean b) {
                        if (b) {
                            value = mb.onTrue();
                        } else if (mb.onFalse() == null) {
                            return; // drop annotation parameter (DropAnnParam)
                        } else {
                            value = mb.onFalse();
                        }
                    }
                }
                m_dst.visit(name, value);
            }
        }

        @Override
        public void visitEnum(String name, String descriptor, String value) {
            Logger.debug("Visiting enum", name, descriptor, value);
            if (m_src != null) m_src.visitEnum(name, descriptor, value);
            if (m_dst != null) m_dst.visitEnum(name, descriptor, value);
        }

        @Override
        public AnnotationVisitor visitAnnotation(String name, String descriptor) {
            if (m_src != null && m_dst != null) return new TranslateAnnotationVisitor(m_src.visitAnnotation(name, descriptor), m_dst.visitAnnotation(name, descriptor));
            if (m_src != null) return m_src.visitAnnotation(name, descriptor);
            return m_dst.visitAnnotation(name, descriptor);
        }

        @Override
        public AnnotationVisitor visitArray(String name) {
            m_params.put(name, ARRAY_SENTINEL);
            if (m_src != null && m_dst != null) return new TranslateAnnotationVisitor(m_src.visitArray(name), m_dst.visitArray(name));
            if (m_src != null) return m_src.visitArray(name);
            return m_dst.visitArray(name);
        }

        @Override
        public void visitEnd() {
            if (m_src != null) m_src.visitEnd();
            if (m_dst != null) {
                if (m_annInfo != null && m_paramName != null) {
                    // FIXME: handle isAdvice flag and multiple metas
                    if (!Utils.isBlank(m_annInfo.metas())) {
                        var meta = m_annInfo.metas()[0];
                        if (!Utils.isBlank(meta.targetParamNames()) && !Utils.isBlank(meta.targetParamValues())) {
                            for (int i = 0; i < meta.targetParamNames().length && i < meta.targetParamValues().length; i++) {
                                String pname = meta.targetParamNames()[i];
                                String pvalue = meta.targetParamValues()[i];
                                if (!m_params.containsKey(pname)) {
                                    m_dst.visit(pname, pvalue);
                                }
                            }
                        }
                    }

                    for (var entry : m_annInfo.mapFlags().entrySet()) {
                        String pname = entry.getKey();
                        var flags = entry.getValue();
                        if (flags.inferFromTargetName() && !m_params.containsKey(pname) && m_paramName != null) {
                            m_dst.visit(pname, m_paramName);
                        }
                    }
                }
                m_dst.visitEnd();
            }
        }
    }

    private AnnotationVisitor visitMappedParamAnnotation(int pidx, String descriptor, boolean visible, TriFunction<Integer, String, Boolean, AnnotationVisitor> visitor, String paramName) {
        AnnInfo info = MAPPINGS.get(descriptor);
        if (info == null) return visitor.apply(pidx, descriptor, visible);

        m_changed = true;
        m_ctx.setAnnChanged();
        AnnotationVisitor src = bKeepOriginalAnnotations ? visitor.apply(pidx, descriptor, visible) : null;

        var meta = info.metas()[0]; // FIXME: handle isAdvice flag and multiple metas
        String targetDesc = Type.getDescriptor(meta.targetClass());
        AnnotationVisitor dst = visitor.apply(pidx, targetDesc, visible);
        return new TranslateAnnotationVisitor(src, dst, info, paramName);
    }

    private AnnotationVisitor visitMappedAnnotation(String descriptor, boolean visible, BiFunction<String, Boolean, AnnotationVisitor> visitor) {
        AnnInfo info = MAPPINGS.get(descriptor);
        if (info == null) return visitor.apply(descriptor, visible);

        m_changed = true;
        m_ctx.setAnnChanged();
        AnnotationVisitor src = bKeepOriginalAnnotations ? visitor.apply(descriptor, visible) : null;

        var meta = info.metas()[0]; // FIXME: handle isAdvice flag and multiple metas
        String targetDesc = Type.getDescriptor(meta.targetClass());
        AnnotationVisitor dst = visitor.apply(targetDesc, visible);
        return new TranslateAnnotationVisitor(src, dst, info, null);
    }

    boolean bKeepOriginalAnnotations = true;

    @Override
    protected AnnotationVisitor processClassAnnotation(String desc, boolean visible, BiFunction<String, Boolean, AnnotationVisitor> visitor) {
        return visitMappedAnnotation(desc, visible, visitor);
    }

    @Override
    protected AnnotationVisitor processMethodAnnotation(String desc, boolean visible, BiFunction<String, Boolean, AnnotationVisitor> visitor) {
        return visitMappedAnnotation(desc, visible, visitor);
    }

    @Override
    protected AnnotationVisitor processParameterAnnotation(int index, String desc, boolean visible, TriFunction<Integer, String, Boolean, AnnotationVisitor> visitor, String paramName) {
        return visitMappedParamAnnotation(index, desc, visible, visitor, paramName);
    }
}
