package me.zed_0xff.zombie_buddy.transformers;

import me.zed_0xff.zombie_buddy.Logger;
import me.zed_0xff.zombie_buddy.Patch;

import java.util.HashMap;
import java.util.Map;

import net.bytebuddy.asm.Advice;
import net.bytebuddy.jar.asm.*;

/*
 * converts ZombieBuddy annotations to ByteBuddy's
 */
public class AnnotationConverter implements Transformer {
    public static final Map<String, String> ANN_MAP;
    static {
        ANN_MAP = new HashMap<>();
        ANN_MAP.put(Type.getDescriptor(Patch.This.class),         Type.getDescriptor(Advice.This.class));
        ANN_MAP.put(Type.getDescriptor(Patch.Adapter.class),      Type.getDescriptor(Advice.Local.class));
        ANN_MAP.put(Type.getDescriptor(Patch.Argument.class),     Type.getDescriptor(Advice.Argument.class));
        ANN_MAP.put(Type.getDescriptor(Patch.AllArguments.class), Type.getDescriptor(Advice.AllArguments.class));
        ANN_MAP.put(Type.getDescriptor(Patch.Field.class),        Type.getDescriptor(Advice.FieldValue.class));
        ANN_MAP.put(Type.getDescriptor(Patch.FieldRW.class),      Type.getDescriptor(Advice.FieldValue.class));

        ANN_MAP.put(Type.getDescriptor(Patch.Local.class),        Type.getDescriptor(Advice.Local.class));
        ANN_MAP.put(Type.getDescriptor(Patch.OnEnter.class),      Type.getDescriptor(Advice.OnMethodEnter.class));
        ANN_MAP.put(Type.getDescriptor(Patch.OnExit.class),       Type.getDescriptor(Advice.OnMethodExit.class));
        ANN_MAP.put(Type.getDescriptor(Patch.Return.class),       Type.getDescriptor(Advice.Return.class));
        ANN_MAP.put(Type.getDescriptor(Patch.Thrown.class),       Type.getDescriptor(Advice.Thrown.class));
    }

    boolean bKeepOriginalAnnotations = true;
    boolean bChanged = false;

    /** Forwards every callback to two delegates so ASM element parsing fills both annotations (keep-original + mapped). */
    private static AnnotationVisitor duplicateAnnotations(AnnotationVisitor first, AnnotationVisitor second) {
        if (first == null) {
            return second;
        }
        if (second == null) {
            return first;
        }
        return new AnnotationVisitor(ASM_API, null) {
            @Override
            public void visit(String name, Object value) {
                first.visit(name, value);
                second.visit(name, value);
            }

            @Override
            public void visitEnum(String name, String descriptor, String value) {
                first.visitEnum(name, descriptor, value);
                second.visitEnum(name, descriptor, value);
            }

            @Override
            public AnnotationVisitor visitAnnotation(String name, String descriptor) {
                return duplicateAnnotations(first.visitAnnotation(name, descriptor), second.visitAnnotation(name, descriptor));
            }

            @Override
            public AnnotationVisitor visitArray(String name) {
                return duplicateAnnotations(first.visitArray(name), second.visitArray(name));
            }

            @Override
            public void visitEnd() {
                first.visitEnd();
                second.visitEnd();
            }
        };
    }

    private AnnotationVisitor visitMappedAnnotation( String descriptor, boolean visible, java.util.function.BiFunction<String, Boolean, AnnotationVisitor> visitor) {
        String newDescriptor = mapDescriptor(descriptor);
        if (newDescriptor == null) return visitor.apply(descriptor, visible);

        bChanged = true;
        AnnotationVisitor mapped = visitor.apply(newDescriptor, visible);
        if (!bKeepOriginalAnnotations) {
            return mapped;
        }

        AnnotationVisitor kept = visitor.apply(descriptor, visible);
        return duplicateAnnotations(kept, mapped);
    }

    public static String mapDescriptor(String descriptor) {
        return ANN_MAP.get(descriptor);
    }

    public Result transform(byte[] classBytes) {
        bChanged = false;

        ClassReader cr = new ClassReader(classBytes);
        ClassWriter cw = new ClassWriter(0);  // no COMPUTE_FRAMES, no COMPUTE_MAXS

        cr.accept(new ClassVisitor(ASM_API, cw) {
            @Override
            public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                return visitMappedAnnotation(descriptor, visible, super::visitAnnotation);
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
                        return visitMappedAnnotation(descriptor, visible, super::visitAnnotation);
                    }

                    @Override
                    public AnnotationVisitor visitParameterAnnotation( int parameter, String descriptor, boolean visible) {
                        return visitMappedAnnotation( descriptor, visible, (d, v) -> super.visitParameterAnnotation(parameter, d, v));
                    }
                };
            }
        }, 0);

        return new Result(cw.toByteArray(), bChanged);
    }
}
