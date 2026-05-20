package me.zed_0xff.zombie_buddy.transformers.bytebuddy;

import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.jar.asm.ClassWriter;
import net.bytebuddy.jar.asm.FieldVisitor;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;

/*
 * makes all fields and methods public
 */
public class Publicizer extends AbstractTransformer {
    static public int forcePublic(int access) {
        access &= ~(Opcodes.ACC_PRIVATE | Opcodes.ACC_PROTECTED);
        access |= Opcodes.ACC_PUBLIC;
        return access;
    }

    class ClsVisitor extends TrackingClassVisitor {
        public ClsVisitor(int api, ClassVisitor cv, ScopeTracker<Object> tracker) {
            super(api, cv, tracker);
        }

        @Override
        public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
            int newAccess = forcePublic(access);
            if (newAccess != access) setModified();
            return super.visitField(newAccess, name, descriptor, signature, value);
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
            if (!name.equals("<clinit>")) {
                int newAccess = forcePublic(access);
                if (newAccess != access) {
                    setModified();
                    access = newAccess;
                }
            }
            return super.visitMethod(access, name, descriptor, signature, exceptions);
        }
    }

    @Override
    protected ClassVisitor createVisitor(ClassWriter cw, byte[] classBytes) {
        ScopeTracker<Object> tracker = new ScopeTracker<>();
        return new ClsVisitor(ASM_API, cw, tracker);
    }
}
