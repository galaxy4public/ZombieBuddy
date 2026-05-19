package me.zed_0xff.zombie_buddy.transformers.asmtree;

import me.zed_0xff.zombie_buddy.Logger;

// XXX bytebuddy shaded asm does not have asm.tree, so using the original asm here
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

public class Publicizer extends AbstractTransformer {
    public static int forcePublic(int access) {
        access &= ~(Opcodes.ACC_PRIVATE | Opcodes.ACC_PROTECTED);
        access |= Opcodes.ACC_PUBLIC;
        return access;
    }

    @Override
    protected boolean transformNode(ClassNode cn) {
        boolean changed = false;
        int newClsAccess = forcePublic(cn.access);
        if (newClsAccess != cn.access) {
            cn.access = newClsAccess;
            changed = true;
        }

        for (FieldNode fn : cn.fields) {
            int newAccess = forcePublic(fn.access);

            if (newAccess != fn.access) {
                fn.access = newAccess;
                changed = true;
            }
        }

        for (MethodNode mn : cn.methods) {
            if (!mn.name.equals("<clinit>")) {
                int newAccess = forcePublic(mn.access);

                if (newAccess != mn.access) {
                    mn.access = newAccess;
                    changed = true;
                }
            }
        }
        return changed;
    }
}
