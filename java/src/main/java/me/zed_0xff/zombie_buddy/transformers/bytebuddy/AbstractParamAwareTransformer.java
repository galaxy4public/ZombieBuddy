package me.zed_0xff.zombie_buddy.transformers.bytebuddy;

import me.zed_0xff.zombie_buddy.Patch;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.function.BiFunction;

import net.bytebuddy.jar.asm.*;

/*
 * base class for transformers that need access to parameter _names_ when processing annotations (e.g. AnnotationConverter)
 */
public abstract class AbstractParamAwareTransformer extends AbstractTransformer {
    /** Set for the duration of {@link #createVisitor}; used by subclasses (e.g. annotation translation) to share one scope stack with {@link TrackingClassVisitor}. */
    protected ScopeTracker<Object> m_scopeTracker;

    @FunctionalInterface
    public interface TriFunction<A, B, C, R> {
        R apply(A a, B b, C c);
    }

    @Override
    protected ClassVisitor createVisitor(ClassWriter cw, byte[] classBytes) {
        m_scopeTracker = new ScopeTracker<>();
        ScopeTracker<Object> tracker = m_scopeTracker;
        Map<String, String[]> allParams = AbstractTransformer.collectParamNames(classBytes);
        return new TrackingClassVisitor(ASM_API, cw, tracker) {
            @Override
            public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
                return ScopeVisitor.wrapAnn(ASM_API, tracker, desc, processClassAnnotation(desc, visible, super::visitAnnotation));
            }

            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                String[] paramNames = allParams.getOrDefault(name + descriptor, new String[0]);
                MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
                return new MethodVisitor(ASM_API, mv) {
                    @Override
                    public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
                        return ScopeVisitor.wrapAnn(ASM_API, tracker, desc, processMethodAnnotation(desc, visible, super::visitAnnotation));
                    }

                    @Override
                    public AnnotationVisitor visitParameterAnnotation(int index, String desc, boolean visible) {
                        String paramName = (index < paramNames.length) ? paramNames[index] : ("arg" + index);

                        return ScopeVisitor.wrapArg(ASM_API, tracker, index, desc,
                            processParameterAnnotation(index, desc, visible, super::visitParameterAnnotation, paramName));
                    }
                };
            }
        };
    }

    // Default: pass through. Override to intercept class-level annotations.
    protected AnnotationVisitor processClassAnnotation(String desc, boolean visible, BiFunction<String, Boolean, AnnotationVisitor> visitor) {
        return visitor.apply(desc, visible);
    }

    // Default: pass through. Override to intercept method-level annotations.
    protected AnnotationVisitor processMethodAnnotation(String desc, boolean visible, BiFunction<String, Boolean, AnnotationVisitor> visitor) {
        return visitor.apply(desc, visible);
    }

    // Default: pass through. Override to intercept parameter annotations.
    protected AnnotationVisitor processParameterAnnotation(int index, String desc, boolean visible, TriFunction<Integer, String, Boolean, AnnotationVisitor> visitor, String paramName) {
        return visitor.apply(index, desc, visible);
    }
}
