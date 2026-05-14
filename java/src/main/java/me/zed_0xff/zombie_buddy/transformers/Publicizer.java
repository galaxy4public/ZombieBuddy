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

    public Result transform(byte[] classBytes) {
        ClassReader cr = new ClassReader(classBytes);
        ClassWriter cw = new ClassWriter(0);          // no COMPUTE_FRAMES, no COMPUTE_MAXS
        boolean[] modified = {false};                 // use array to modify inside lambda

        cr.accept(new ClassVisitor(ASM_API, cw) {
            @Override
            public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
                int newAccess = forcePublic(access);
                if (newAccess != access) modified[0] = true;
                return super.visitField(newAccess, name, descriptor, signature, value);
            }

            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                // static class initializer should not be public
                if (!name.equals("<clinit>")){
                    int newAccess = forcePublic(access);
                    if (newAccess != access) {
                        modified[0] = true;
                        access = newAccess;
                    }
                }
                return super.visitMethod(access, name, descriptor, signature, exceptions);
            }
        }, 0);

        return new Result(cw.toByteArray(), modified[0]);
    }
}
