package me.zed_0xff.zombie_buddy.transformers;

import net.bytebuddy.jar.asm.*;

/*
 * makes all fields and methods public
 */
public class Publicizer extends Transformer {
    static public int forcePublic(int access) {
        access &= ~(Opcodes.ACC_PRIVATE | Opcodes.ACC_PROTECTED);
        access |= Opcodes.ACC_PUBLIC;
        return access;
    }

    @Override
    protected ClassVisitor createVisitor(ClassWriter cw, byte[] classBytes) {
        return new ClassVisitor(ASM_API, cw) {
            @Override
            public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
                int newAccess = forcePublic(access);
                if (newAccess != access) m_changed = true;
                return super.visitField(newAccess, name, descriptor, signature, value);
            }

            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                if (!name.equals("<clinit>")) {
                    int newAccess = forcePublic(access);
                    if (newAccess != access) {
                        m_changed = true;
                        access = newAccess;
                    }
                }
                return super.visitMethod(access, name, descriptor, signature, exceptions);
            }
        };
    }
}
