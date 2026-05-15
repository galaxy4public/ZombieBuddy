package me.zed_0xff.zombie_buddy.transformers;

import me.zed_0xff.zombie_buddy.Logger;
import me.zed_0xff.zombie_buddy.Patch;

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
    private record AnnInfo(String targetDesc, Map<String, MapBoolInfo> mapBools, boolean nameToValue) {}

    static final Map<String, AnnInfo> MAPPINGS;
    static {
        MAPPINGS = new HashMap<>();
        for (Class<?> inner : Patch.class.getDeclaredClasses()) {
            if (!inner.isAnnotation()) continue;
            Patch.Internal.Meta[] metas = inner.getAnnotationsByType(Patch.Internal.Meta.class);
            if (metas.length == 0 || metas[0].requireType().length > 0) continue;

            Map<String, MapBoolInfo> mapBools = new HashMap<>();
            boolean nameToValue = false;
            for (Method m : inner.getDeclaredMethods()) {
                Patch.Internal.MapBool mb = m.getAnnotation(Patch.Internal.MapBool.class);
                if (mb != null) {
                    mapBools.put(m.getName(), new MapBoolInfo(resolveType(mb.onTrue()), resolveType(mb.onFalse())));
                }
                Patch.Internal.Flags flags = m.getAnnotation(Patch.Internal.Flags.class);
                if (flags != null && flags.inferFromTargetName()) nameToValue = true;
            }

            MAPPINGS.put(Type.getDescriptor(inner), new AnnInfo(Type.getDescriptor(metas[0].targetClass()), mapBools, nameToValue));
        }
    }

    private static Type resolveType(Class<?> cls) {
        if (cls == Patch.Internal.DropAnnParam.class) return null;
        return Type.getType(cls);
    }

    public static String mapDescriptor(String descriptor) {
        AnnInfo info = MAPPINGS.get(descriptor);
        return info != null ? info.targetDesc() : null;
    }

    /** Forwards every callback to two delegates so ASM element parsing fills both annotations (keep-original + dst). */
    private static class DualAnnotationVisitor extends AnnotationVisitor {
        private final AnnotationVisitor src;
        private final AnnotationVisitor dst;
        private final HashMap<String, Object> params = new HashMap<>();
        private final String paramName;
        private final AnnInfo annInfo;

        private static final Object ARRAY_SENTINEL = new Object();

        DualAnnotationVisitor(AnnotationVisitor src, AnnotationVisitor dst) { this(src, dst, null, null); }
        DualAnnotationVisitor(AnnotationVisitor src, AnnotationVisitor dst, AnnInfo annInfo, String paramName) {
            super(ASM_API);
            this.src       = src;
            this.dst       = dst;
            this.annInfo   = annInfo;
            this.paramName = paramName;
        }

        @Override
        public void visit(String name, Object value) {
            if (name != null) params.put(name, value);
            if (src != null) src.visit(name, value);
            if (dst != null) {
                if (annInfo != null && name != null) {
                    MapBoolInfo mb = annInfo.mapBools().get(name);
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
                dst.visit(name, value);
            }
        }

        @Override
        public void visitEnum(String name, String descriptor, String value) {
            Logger.debug("Visiting enum", name, descriptor, value);
            if (src != null) src.visitEnum(name, descriptor, value);
            if (dst != null) dst.visitEnum(name, descriptor, value);
        }

        @Override
        public AnnotationVisitor visitAnnotation(String name, String descriptor) {
            if (src != null && dst != null) return new DualAnnotationVisitor(src.visitAnnotation(name, descriptor), dst.visitAnnotation(name, descriptor));
            if (src != null) return src.visitAnnotation(name, descriptor);
            return dst.visitAnnotation(name, descriptor);
        }

        @Override
        public AnnotationVisitor visitArray(String name) {
            params.put(name, ARRAY_SENTINEL);
            if (src != null && dst != null) return new DualAnnotationVisitor(src.visitArray(name), dst.visitArray(name));
            if (src != null) return src.visitArray(name);
            return dst.visitArray(name);
        }

        @Override
        public void visitEnd() {
            if (src != null) src.visitEnd();
            if (dst != null) {
                if (annInfo != null && annInfo.nameToValue() && paramName != null && !params.containsKey("value")) {
                    dst.visit("value", paramName);
                }
                dst.visitEnd();
            }
        }
    }

    private AnnotationVisitor visitMappedParamAnnotation(int pidx, String descriptor, boolean visible, TriFunction<Integer, String, Boolean, AnnotationVisitor> visitor, String paramName) {
        AnnInfo info = MAPPINGS.get(descriptor);
        if (info == null) return visitor.apply(pidx, descriptor, visible);

        m_changed = true;
        m_ctx.setAnnChanged();
        AnnotationVisitor src = bKeepOriginalAnnotations ? visitor.apply(pidx, descriptor, visible) : null;
        AnnotationVisitor dst = visitor.apply(pidx, info.targetDesc(), visible);
        return new DualAnnotationVisitor(src, dst, info, paramName);
    }

    private AnnotationVisitor visitMappedAnnotation(String descriptor, boolean visible, BiFunction<String, Boolean, AnnotationVisitor> visitor) {
        AnnInfo info = MAPPINGS.get(descriptor);
        if (info == null) return visitor.apply(descriptor, visible);

        m_changed = true;
        m_ctx.setAnnChanged();
        AnnotationVisitor src = bKeepOriginalAnnotations ? visitor.apply(descriptor, visible) : null;
        AnnotationVisitor dst = visitor.apply(info.targetDesc(), visible);
        return new DualAnnotationVisitor(src, dst, info, null);
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
