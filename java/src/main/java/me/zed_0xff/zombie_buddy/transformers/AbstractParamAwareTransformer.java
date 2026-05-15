package me.zed_0xff.zombie_buddy.transformers;

import java.util.Map;
import java.util.function.BiFunction;

import net.bytebuddy.jar.asm.*;

public abstract class AbstractParamAwareTransformer extends Transformer {

    @FunctionalInterface
    public interface TriFunction<A, B, C, R> {
        R apply(A a, B b, C c);
    }

    @Override
    protected ClassVisitor createVisitor(ClassWriter cw, byte[] classBytes) {
        Map<String, String[]> allParams = Transformer.collectParamNames(classBytes);
        return new ClassVisitor(ASM_API, cw) {
            @Override
            public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
                return processClassAnnotation(desc, visible, super::visitAnnotation);
            }

            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                String[] paramNames = allParams.getOrDefault(name + descriptor, new String[0]);
                MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
                return new MethodVisitor(ASM_API, mv) {
                    @Override
                    public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
                        return processMethodAnnotation(desc, visible, super::visitAnnotation);
                    }

                    @Override
                    public AnnotationVisitor visitParameterAnnotation(int index, String desc, boolean visible) {
                        String paramName = (index < paramNames.length) ? paramNames[index] : ("arg" + index);
                        return processParameterAnnotation(index, desc, visible, super::visitParameterAnnotation, paramName);
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
