package me.zed_0xff.zombie_buddy.transformers.bytebuddy;

import me.zed_0xff.zombie_buddy.Logger;
import me.zed_0xff.zombie_buddy.Utils;

import java.util.function.BiFunction;
import java.util.HashMap;
import java.util.List;

import net.bytebuddy.jar.asm.*;

/*
 * converts ZombieBuddy annotations to ByteBuddy's, driven by @Patch.Internal.Meta metadata
 */
public class AnnotationConverter extends AbstractPatchAnnotationTransformer {
    boolean bKeepOriginalAnnotations = true;

    /** Forwards every callback to two delegates so ASM element parsing fills both annotations (keep-original + dst). */
    private static class TranslateAnnotationVisitor extends TrackingAnnotationVisitor {
        private final AnnotationVisitor       m_src;
        private final AnnotationVisitor       m_dst;
        private final HashMap<String, Object> m_params = new HashMap<>();
        private final String                  m_paramName;
        private final AnnInfo                 m_annInfo;

        TranslateAnnotationVisitor(AnnotationVisitor src, AnnotationVisitor dst) {
            this(src, dst, null, null, new ScopeTracker<>());
        }
        TranslateAnnotationVisitor(AnnotationVisitor src, AnnotationVisitor dst, ScopeTracker<Object> tracker) {
            this(src, dst, null, null, tracker);
        }
        TranslateAnnotationVisitor(AnnotationVisitor src, AnnotationVisitor dst, AnnInfo annInfo, String paramName) {
            this(src, dst, annInfo, paramName, new ScopeTracker<>());
        }
        TranslateAnnotationVisitor(AnnotationVisitor src, AnnotationVisitor dst, AnnInfo annInfo, String paramName, ScopeTracker<Object> tracker) {
            super(ASM_API, tracker);
            this.m_src       = src;
            this.m_dst       = dst;
            this.m_annInfo   = annInfo;
            this.m_paramName = paramName;
        }

        @Override
        public void visit(String name, Object value) {
            try (var elmScope = tracker.enter(new ScopeTracker.Elm(name != null ? name : "value"))) {
                // Logger.debug("visit", m_paramName, name, value);
                if (name == null) {
                    // array value
                } else {
                    // param
                    m_params.put(name, value);
                }

                if (m_src != null) m_src.visit(name, value);
                if (m_dst != null) {
                    Object v = value;
                    if (m_annInfo != null && name != null) {
                        MapBoolInfo mb = m_annInfo.mapBools().get(name);
                        if (mb != null && v instanceof Boolean b) {
                            if (b) {
                                v = mb.onTrue();
                            } else if (mb.onFalse() == null) {
                                return; // drop annotation parameter (DropAnnParam)
                            } else {
                                v = mb.onFalse();
                            }
                        }
                    }
                    m_dst.visit(name, v);
                }
            }
        }

        @Override
        public void visitEnum(String name, String descriptor, String value) {
            if (m_src != null) m_src.visitEnum(name, descriptor, value);
            if (m_dst != null) m_dst.visitEnum(name, descriptor, value);
        }

        @Override
        public AnnotationVisitor visitAnnotation(String name, String descriptor) {
            ScopeTracker.Scope s = tracker.enter(new ScopeTracker.Ann(descriptor));
            AnnotationVisitor child;
            if (m_src != null && m_dst != null) {
                child = new TranslateAnnotationVisitor(m_src.visitAnnotation(name, descriptor), m_dst.visitAnnotation(name, descriptor), tracker);
            } else if (m_src != null) {
                child = m_src.visitAnnotation(name, descriptor);
            } else {
                child = m_dst.visitAnnotation(name, descriptor);
            }

            return ScopeVisitor.closingDelegate(ASM_API, child, s);
        }

        @Override
        public AnnotationVisitor visitArray(String name) {
            ScopeTracker.Scope s = tracker.enter(new ScopeTracker.Arr(name));
            AnnotationVisitor child;
            if (m_src != null && m_dst != null) {
                child = new TranslateAnnotationVisitor(m_src.visitArray(name), m_dst.visitArray(name), tracker);
            } else if (m_src != null) {
                child = m_src.visitArray(name);
            } else {
                child = m_dst.visitArray(name);
            }

            return ScopeVisitor.closingDelegate(ASM_API, child, s);
        }

        @Override
        public void visitEnd() {
            // Logger.debug("visitEnd", m_paramName);
            if (m_src != null) m_src.visitEnd();
            if (m_dst != null) {
                if (m_annInfo != null && m_paramName != null) {
                    // FIXME: handle isAdvice flag and multiple metas
                    if (!Utils.isBlank(m_annInfo.metas())) {
                        var meta = m_annInfo.metas()[0];
                        // paramNames = ["value"], paramValues = ["zb.nameMap"]
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

                    // FIXME: handle isAdvice flag and multiple metas
                    // for (var entry : m_annInfo.mapFlags().entrySet()) {
                    //     String pname = entry.getKey();
                    //     var flags = entry.getValue();
                    //     if (flags.inferFromTargetName() && !m_params.containsKey(pname) && m_paramName != null) {
                    //         // Logger.debug("Inferring annotation parameter", pname, m_paramName, m_params);
                    //         m_dst.visit(pname, m_paramName);
                    //         m_params.put(pname, m_paramName);
                    //     }
                    // }
                }
                m_dst.visitEnd();
            }
        }
    }

    @Override
    protected AnnotationVisitor visitMappedParamAnnotation(AnnInfo ai, boolean visible, TriFunction<Integer, String, Boolean, AnnotationVisitor> visitor, int pidx, String paramName) {
        setChanged();
        AnnotationVisitor src = bKeepOriginalAnnotations ? visitor.apply(pidx, ai.descriptor(), visible) : null;

        var meta = ai.metas()[0]; // FIXME: handle isAdvice flag and multiple metas
        String targetDesc = Type.getDescriptor(meta.targetAnnotation());
        AnnotationVisitor dst = visitor.apply(pidx, targetDesc, visible);
        return new TranslateAnnotationVisitor(src, dst, ai, paramName, m_scopeTracker);
    }

    @Override
    protected AnnotationVisitor visitMappedAnnotation(AnnInfo ai, boolean visible, BiFunction<String, Boolean, AnnotationVisitor> visitor) {
        setChanged();
        AnnotationVisitor src = bKeepOriginalAnnotations ? visitor.apply(ai.descriptor(), visible) : null;

        var meta = ai.metas()[0]; // FIXME: handle isAdvice flag and multiple metas
        String targetDesc = Type.getDescriptor(meta.targetAnnotation());
        AnnotationVisitor dst = visitor.apply(targetDesc, visible);
        return new TranslateAnnotationVisitor(src, dst, ai, null, m_scopeTracker);
    }
}
