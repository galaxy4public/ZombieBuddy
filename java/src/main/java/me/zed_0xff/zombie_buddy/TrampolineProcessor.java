package me.zed_0xff.zombie_buddy;

import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;
import net.bytebuddy.jar.asm.Type;

final class TrampolineProcessor {
    private TrampolineProcessor() {}

    /** Resolved trampoline target: enough info to emit the INVOKE instruction without a Class object. */
    record ResolvedMethod(String owner, String name, String desc, boolean isStatic, boolean isIface) {}

    /** Wraps {@code mv} with a MethodVisitor that replaces the method body with a direct call to {@code target}. */
    static MethodVisitor makeBodyRewriter(MethodVisitor mv, String trampolineDesc, ResolvedMethod target) {
        Type[] params   = Type.getArgumentTypes(trampolineDesc);
        int    invokeOp = target.isStatic() ? Opcodes.INVOKESTATIC : (target.isIface() ? Opcodes.INVOKEINTERFACE : Opcodes.INVOKEVIRTUAL);
        int    returnOp = Type.getReturnType(target.desc()).getOpcode(Opcodes.IRETURN);

        return new MethodVisitor(Opcodes.ASM9, mv) {
            // Every MethodVisitor.visitX default does `if (mv != null) mv.visitX(...)`.
            // Nulling this.mv after emitting the body suppresses all original instructions without explicit overrides.
            private MethodVisitor realMv = mv;

            @Override
            public void visitCode() {
                mv.visitCode();
                int slot = 0;
                if (!target.isStatic()) {
                    mv.visitVarInsn(Opcodes.ALOAD, slot);
                    // If the declared receiver type differs from the target class (e.g. Object),
                    // emit CHECKCAST so the verifier accepts the INVOKEVIRTUAL/INVOKEINTERFACE.
                    if (!params[0].getInternalName().equals(target.owner())) {
                        mv.visitTypeInsn(Opcodes.CHECKCAST, target.owner());
                    }
                    slot += params[0].getSize();
                    for (int i = 1; i < params.length; i++) { mv.visitVarInsn(params[i].getOpcode(Opcodes.ILOAD), slot); slot += params[i].getSize(); }
                } else {
                    for (int i = 0; i < params.length; i++) { mv.visitVarInsn(params[i].getOpcode(Opcodes.ILOAD), slot); slot += params[i].getSize(); }
                }
                mv.visitMethodInsn(invokeOp, target.owner(), target.name(), target.desc(), target.isIface());
                mv.visitInsn(returnOp);
                this.mv = null; // suppress all original body instructions
            }

            @Override public void visitMaxs(int s, int l) { realMv.visitMaxs(s, l); }
            @Override public void visitEnd()               { realMv.visitEnd(); }
        };
    }
}
