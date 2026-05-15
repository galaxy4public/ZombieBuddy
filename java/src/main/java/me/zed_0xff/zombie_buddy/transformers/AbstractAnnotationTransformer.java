package me.zed_0xff.zombie_buddy.transformers;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiFunction;

import net.bytebuddy.jar.asm.*;

public abstract class AbstractAnnotationTransformer extends AbstractParamAwareTransformer {
    protected static final Object SKIP = new Object();

    public enum AnnSite { CLASS, METHOD, PARAMETER }

    /** Class-level annotation values keyed by annotation descriptor then member name.
     *  Populated during class annotation visiting, which ASM guarantees precedes method/parameter visiting. */
    protected Map<String, Map<String, Object>> classAnnotations;

    // -------------------------
    // HOOKS — override in subclasses
    // -------------------------
    protected Object rewriteValue(AnnSite site, String annDesc, String name, Object value) { return value; }
    protected Object rewriteEnum(AnnSite site, String annDesc, String name, String desc, String value) { return value; }
    protected Object resolveArray(AnnSite site, String annDesc, String name, List<Object> values) { return SKIP; }

    @Override
    protected ClassVisitor createVisitor(ClassWriter cw, byte[] classBytes) {
        classAnnotations = new HashMap<>();
        return super.createVisitor(cw, classBytes);
    }

    @Override
    protected AnnotationVisitor processClassAnnotation(String desc, boolean visible, BiFunction<String, Boolean, AnnotationVisitor> visitor) {
        return wrapAnnotation(AnnSite.CLASS, desc, visitor.apply(desc, visible));
    }

    @Override
    protected AnnotationVisitor processMethodAnnotation(String desc, boolean visible, BiFunction<String, Boolean, AnnotationVisitor> visitor) {
        return wrapAnnotation(AnnSite.METHOD, desc, visitor.apply(desc, visible));
    }

    @Override
    protected AnnotationVisitor processParameterAnnotation(int index, String desc, boolean visible, TriFunction<Integer, String, Boolean, AnnotationVisitor> visitor, String paramName) {
        return wrapAnnotation(AnnSite.PARAMETER, desc, new NamedAnnotationVisitor(visitor.apply(index, desc, visible), paramName));
    }

    // -------------------------
    // ANNOTATION WRAPPING
    // -------------------------
    private AnnotationVisitor wrapAnnotation(AnnSite site, String annDesc, AnnotationVisitor out) {
        if (out == null) return null;
        Map<String, Object> collected = site == AnnSite.CLASS ? classAnnotations.computeIfAbsent(annDesc, k -> new LinkedHashMap<>()) : null;
        return new AnnotationVisitor(ASM_API, out) {
            @Override
            public void visit(String name, Object value) {
                if (collected != null) collected.put(name != null ? name : "value", value);
                Object rewritten = rewriteValue(site, annDesc, name, value);
                if (!Objects.equals(rewritten, value)) {
                    m_changed = true;
                    m_ctx.setAnnChanged();
                }
                if (rewritten != SKIP) {
                    super.visit(name, rewritten);
                }
            }

            @Override
            public void visitEnum(String name, String desc, String value) {
                if (collected != null) collected.put(name != null ? name : "value", value);
                Object rewritten = rewriteEnum(site, annDesc, name, desc, value);
                if (!Objects.equals(rewritten, value)) {
                    m_changed = true;
                    m_ctx.setAnnChanged();
                }
                if (rewritten != SKIP) {
                    super.visitEnum(name, desc, value);
                }
            }

            @Override
            public AnnotationVisitor visitAnnotation(String name, String desc) {
                return wrapAnnotation(site, desc, super.visitAnnotation(name, desc));
            }

            @Override
            public AnnotationVisitor visitArray(String name) {
                List<Object> values = new ArrayList<>();
                return new AnnotationVisitor(ASM_API) {
                    @Override public void visit(String n, Object v) { values.add(v); }
                    @Override public void visitEnum(String n, String d, String v) { values.add(v); }

                    @Override
                    public void visitEnd() {
                        Object rewritten = resolveArray(site, annDesc, name, values);
                        if (rewritten != SKIP) { // SKIP means keep it as it is (array), any other value(i.e. a string) means replace the whole array with that value
                            m_changed = true;
                            m_ctx.setAnnChanged();
                            out.visit(name, rewritten);
                        }
                    }
                };
            }
        };
    }

    // -------------------------
    // PARAMETER NAME WRAPPER
    // -------------------------
    private class NamedAnnotationVisitor extends AnnotationVisitor {
        private final String paramName;

        NamedAnnotationVisitor(AnnotationVisitor av, String paramName) {
            super(ASM_API, av);
            this.paramName = paramName;
        }

        @Override
        public void visit(String name, Object value) {
            super.visit(name == null && paramName != null ? paramName : name, value);
        }
    }
}
