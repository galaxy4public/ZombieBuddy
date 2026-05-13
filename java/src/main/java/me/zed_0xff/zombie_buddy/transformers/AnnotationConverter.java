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

    public static String mapDescriptor(String descriptor) {
        return ANN_MAP.get(descriptor);
    }

    public byte[] transform(byte[] classBytes) {
        ClassReader cr = new ClassReader(classBytes);
        ClassWriter cw = new ClassWriter(0);  // no COMPUTE_FRAMES, no COMPUTE_MAXS

        cr.accept(new ClassVisitor(Opcodes.ASM9, cw) {
            @Override
            public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                if (bKeepOriginalAnnotations) {
                    super.visitAnnotation(descriptor, visible);
                }
                String newDescriptor = mapDescriptor(descriptor);
                if (newDescriptor == null) return null;

                return new AnnotationVisitor(Opcodes.ASM9, super.visitAnnotation(newDescriptor, visible)) {
                    @Override
                    public void visit(String name, Object value) {
                        Logger.debug("AnnotationVisitor", name, value);
                        super.visit(name, value);
                    }
                };
            }
// 
            // @Override
            // public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
                // FieldVisitor fv = super.visitField(forcePublic(access), name, descriptor, signature, value);
                // return fv;
            // }
// 
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
                return new MethodVisitor(Opcodes.ASM9, mv) {
                    @Override
                    public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                        if (bKeepOriginalAnnotations) super.visitAnnotation(descriptor, visible);
                        String newDescriptor = mapDescriptor(descriptor);
                        return newDescriptor == null ? null : super.visitAnnotation(newDescriptor, visible);
                    }
                };
            }
        }, 0);

        return cw.toByteArray();
    }
}
