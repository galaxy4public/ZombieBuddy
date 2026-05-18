package me.zed_0xff.zombie_buddy.transformers.asmtree;

import me.zed_0xff.zombie_buddy.Logger;

// XXX bytebuddy shaded asm does not have asm.tree, so using the original asm here
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/*
 * resolves alternative names like Field("CHUNKS_PER_WIDTH", "ChunksPerWidth") to the first one that exists in the target class
 */
public class Resolver extends AbstractTransformer {
    @Override
    protected void transformNode(ClassNode cn) {
        for (MethodNode mn : cn.methods) {
            if (mn.visibleAnnotations != null) {
                Logger.debug("MethodNode", mn);
            }
        }
    }
}
