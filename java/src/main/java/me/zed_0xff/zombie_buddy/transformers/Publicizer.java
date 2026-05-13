package me.zed_0xff.zombie_buddy.transformers;

import net.bytebuddy.jar.asm.*; // shaded org.objectweb.asm

/*
 * makes all fields and methods public
 */
public class Publicizer implements Transformer {
    static public int forcePublic(int access) {
        access &= ~(Opcodes.ACC_PRIVATE | Opcodes.ACC_PROTECTED);
        access |= Opcodes.ACC_PUBLIC;
        return access;
    }

    public byte[] transform(byte[] classBytes) {
        ClassReader cr = new ClassReader(classBytes);
        ClassWriter cw = new ClassWriter(0);  // no COMPUTE_FRAMES, no COMPUTE_MAXS

        cr.accept(new ClassVisitor(Opcodes.ASM9, cw) {
            @Override
            public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
                return super.visitField(forcePublic(access), name, descriptor, signature, value);
            }

            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                return super.visitMethod(forcePublic(access), name, descriptor, signature, exceptions);
            }
        }, 0);

        return cw.toByteArray();
    }
}
