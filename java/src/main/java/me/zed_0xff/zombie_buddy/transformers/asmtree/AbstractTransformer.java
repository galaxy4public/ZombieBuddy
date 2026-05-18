package me.zed_0xff.zombie_buddy.transformers.asmtree;

import me.zed_0xff.zombie_buddy.transformers.*;

// XXX bytebuddy shaded asm does not have asm.tree, so using the original asm here
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

abstract class AbstractTransformer extends Transformer {

    protected abstract void transformNode(ClassNode cn);

    @Override
    public Result transform(byte[] classBytes, ClassContext ctx) {
        m_ctx = ctx;
        ClassReader cr = new ClassReader(classBytes);

        ClassNode cn = new ClassNode();
        cr.accept(cn, 0);

        transformNode(cn);

        if (!m_ctx.isChanged()) {
            return NOOP_RESULT;
        }

        ClassWriter cw = new ClassWriter(0);
        cn.accept(cw);

        return new Result(cw.toByteArray(), true);
    }
}
