package me.zed_0xff.zombie_buddy.transformers;

import me.zed_0xff.zombie_buddy.Logger;
import me.zed_0xff.zombie_buddy.Utils;

import java.util.function.BiFunction;
import java.util.HashMap;

import net.bytebuddy.jar.asm.*;

/*
 * converts ZombieBuddy annotations to ByteBuddy's, driven by @Patch.Internal.Meta metadata
 */
public class AnnotationConverter extends AbstractPatchAnnotationTransformer {
    boolean bKeepOriginalAnnotations = true;

    /** Forwards every callback to two delegates so ASM element parsing fills both annotations (keep-original + dst). */
    private static class TranslateAnnotationVisitor extends AnnotationVisitor {
        private final AnnotationVisitor       m_src;
        private final AnnotationVisitor       m_dst;
        private final HashMap<String, Object> m_params = new HashMap<>();
        private final String                  m_paramName;
        private final AnnInfo                 m_annInfo;

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
            // Logger.debug("visit", m_paramName, name, value);
            if (name == null) {
                // array value
            } else {
                // param
                m_params.put(name, value);
            }

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
            // Logger.debug("visitEnum", m_paramName, name, descriptor, value);
            if (m_src != null) m_src.visitEnum(name, descriptor, value);
            if (m_dst != null) m_dst.visitEnum(name, descriptor, value);
        }

        @Override
        public AnnotationVisitor visitAnnotation(String name, String descriptor) {
            // Logger.debug("visitAnnotation", m_paramName, name, descriptor);
            if (m_src != null && m_dst != null)
                return new TranslateAnnotationVisitor(m_src.visitAnnotation(name, descriptor), m_dst.visitAnnotation(name, descriptor));
            return (m_src != null) ? m_src.visitAnnotation(name, descriptor) : m_dst.visitAnnotation(name, descriptor);
        }

        @Override
        public AnnotationVisitor visitArray(String name) {
            // Logger.debug("visitArray", m_paramName, name);
            if (m_src != null && m_dst != null)
                return new TranslateAnnotationVisitor(m_src.visitArray(name), m_dst.visitArray(name));
            return (m_src != null) ? m_src.visitArray(name) : m_dst.visitArray(name);
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
                    for (var entry : m_annInfo.mapFlags().entrySet()) {
                        String pname = entry.getKey();
                        var flags = entry.getValue();
                        if (flags.inferFromTargetName() && !m_params.containsKey(pname) && m_paramName != null) {
                            // Logger.debug("Inferring annotation parameter", pname, m_paramName, m_params);
                            m_dst.visit(pname, m_paramName);
                            m_params.put(pname, m_paramName);
                        }
                    }
                }
                m_dst.visitEnd();
            }
        }
    }

    @Override
    protected AnnotationVisitor visitMappedParamAnnotation(AnnInfo ai, boolean visible, TriFunction<Integer, String, Boolean, AnnotationVisitor> visitor, int pidx, String paramName) {
        m_changed = true;
        m_ctx.setAnnChanged();
        AnnotationVisitor src = bKeepOriginalAnnotations ? visitor.apply(pidx, ai.descriptor(), visible) : null;

        var meta = ai.metas()[0]; // FIXME: handle isAdvice flag and multiple metas
        String targetDesc = Type.getDescriptor(meta.targetAnnotation());
        AnnotationVisitor dst = visitor.apply(pidx, targetDesc, visible);
        return new TranslateAnnotationVisitor(src, dst, ai, paramName);
    }

    @Override
    protected AnnotationVisitor visitMappedAnnotation(AnnInfo ai, boolean visible, BiFunction<String, Boolean, AnnotationVisitor> visitor) {
        m_changed = true;
        m_ctx.setAnnChanged();
        AnnotationVisitor src = bKeepOriginalAnnotations ? visitor.apply(ai.descriptor(), visible) : null;

        var meta = ai.metas()[0]; // FIXME: handle isAdvice flag and multiple metas
        String targetDesc = Type.getDescriptor(meta.targetAnnotation());
        AnnotationVisitor dst = visitor.apply(targetDesc, visible);
        return new TranslateAnnotationVisitor(src, dst, ai, null);
    }
}
