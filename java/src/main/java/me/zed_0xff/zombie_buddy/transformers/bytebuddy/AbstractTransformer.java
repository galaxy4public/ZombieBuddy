package me.zed_0xff.zombie_buddy.transformers.bytebuddy;

import me.zed_0xff.zombie_buddy.transformers.*;

import java.util.HashMap;
import java.util.Map;

import net.bytebuddy.jar.asm.*;

public abstract class AbstractTransformer extends Transformer {
    public static final int ASM_API = Opcodes.ASM9;

    @Override
    public Result transform(byte[] classBytes, ClassContext ctx) {
        try {
            m_ctx = ctx;
            ClassWriter cw = new ClassWriter(0);
            new ClassReader(classBytes).accept(createVisitor(cw, classBytes), 0);
            if (m_ctx.isChanged()) {
                byte[] newBytes = cw.toByteArray();
                ctx.setClassBytes(newBytes);
                return new Result(newBytes, true);
            } else {
                return NOOP_RESULT;
            }
        } finally {
            m_ctx = null;
        }
    }

    protected abstract ClassVisitor createVisitor(ClassWriter cw, byte[] classBytes);

    /** Reads the LocalVariableTable of each method to extract parameter names. Key = methodName + descriptor. */
    public static Map<String, String[]> collectParamNames(byte[] classBytes) {
        Map<String, String[]> result = new HashMap<>();
        new ClassReader(classBytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String mName, String mDesc, String sig, String[] ex) {
                boolean isStatic = (access & Opcodes.ACC_STATIC) != 0;
                Type[] argTypes  = Type.getArgumentTypes(mDesc);
                int paramCount   = argTypes.length;
                String[] names   = new String[paramCount];
                result.put(mName + mDesc, names);
                // Build slot→paramIdx map; long/double occupy 2 slots, so slot ≠ paramIdx for wide types.
                Map<Integer, Integer> slotToParam = new HashMap<>();
                int slot = isStatic ? 0 : 1;
                for (int i = 0; i < paramCount; i++) {
                    slotToParam.put(slot, i);
                    slot += argTypes[i].getSize();
                }
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitLocalVariable(String varName, String varDesc, String varSig, Label start, Label end, int index) {
                        Integer paramIdx = slotToParam.get(index);
                        if (paramIdx != null) names[paramIdx] = varName;
                    }
                };
            }
        }, ClassReader.SKIP_FRAMES);
        return result;
    }
}
