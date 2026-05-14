package me.zed_0xff.zombie_buddy.transformers;

import me.zed_0xff.zombie_buddy.Logger;
import me.zed_0xff.zombie_buddy.Patch;

import java.util.HashMap;
import java.util.Map;

import net.bytebuddy.asm.Advice;
import net.bytebuddy.jar.asm.*;

/*
 * resolves alternative names like Field("CHUNKS_PER_WIDTH", "ChunksPerWidth") to the first one that exists in the target class
 */
public class AlternativeResolver implements Transformer {
    boolean bChanged = false;

    private AnnotationVisitor processAnnotation(String descriptor, boolean visible, java.util.function.BiFunction<String, Boolean, AnnotationVisitor> visitor) {
        return visitor.apply(descriptor, visible);
    }

    public Result transform(byte[] classBytes) {
        bChanged = false;

        ClassReader cr = new ClassReader(classBytes);
        ClassWriter cw = new ClassWriter(0);  // no COMPUTE_FRAMES, no COMPUTE_MAXS

        cr.accept(new ClassVisitor(ASM_API, cw) {
            @Override
            public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                return processAnnotation(descriptor, visible, super::visitAnnotation);
            }

            // TODO: field annotations
            // @Override
            // public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
                // FieldVisitor fv = super.visitField(forcePublic(access), name, descriptor, signature, value);
                // return fv;
            // }

            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
                return new MethodVisitor(ASM_API, mv) {
                    @Override
                    public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                        return processAnnotation(descriptor, visible, super::visitAnnotation);
                    }

                    @Override
                    public AnnotationVisitor visitParameterAnnotation( int parameter, String descriptor, boolean visible) {
                        return processAnnotation( descriptor, visible, (d, v) -> super.visitParameterAnnotation(parameter, d, v));
                    }
                };
            }
        }, 0);

        return new Result(cw.toByteArray(), bChanged);
    }
}
