package me.zed_0xff.zombie_buddy.transformers;

import me.zed_0xff.zombie_buddy.Logger;
import me.zed_0xff.zombie_buddy.Patch;

import java.util.function.BiFunction;
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
    boolean bChanged = false;

    @FunctionalInterface
    public interface TriFunction<A, B, C, R> {
        R apply(A a, B b, C c);
    }

    /** Forwards every callback to two delegates so ASM element parsing fills both annotations (keep-original + dst). */
    private static class DualAnnotationVisitor extends AnnotationVisitor {
        private final AnnotationVisitor src;
        private final AnnotationVisitor dst;
        private final HashMap<String, Object> params = new HashMap<>();
        private final String newDescriptor;
        private final String paramName;

        DualAnnotationVisitor(AnnotationVisitor src, AnnotationVisitor dst) { this(src, dst, null, null); }
        DualAnnotationVisitor(AnnotationVisitor src, AnnotationVisitor dst, String newDescriptor, String paramName) {
            super(ASM_API);
            this.src = src;
            this.dst = dst;
            this.newDescriptor = newDescriptor;
            this.paramName = paramName;
        }

        @Override
        public void visit(String name, Object value) {
            if (src != null) {
                params.put(name, value);
                src.visit(name, value);
            }
            if (dst != null) dst.visit(name, value);
        }

        @Override
        public void visitEnum(String name, String descriptor, String value) {
            if (src != null) src.visitEnum(name, descriptor, value);
            if (dst != null) dst.visitEnum(name, descriptor, value);
        }

        @Override
        public AnnotationVisitor visitAnnotation(String name, String descriptor) {
            if (src != null && dst != null) {
                return new DualAnnotationVisitor(src.visitAnnotation(name, descriptor), dst.visitAnnotation(name, descriptor));
            } else if (src != null) {
                return src.visitAnnotation(name, descriptor);
            } else { // dst != null
                return dst.visitAnnotation(name, descriptor);
            }
        }

        @Override
        public AnnotationVisitor visitArray(String name) {
            if (src != null && dst != null) {
                return new DualAnnotationVisitor(src.visitArray(name), dst.visitArray(name));
            } else if (src != null) {
                return src.visitArray(name);
            } else { // dst != null
                return dst.visitArray(name);
            }
        }

        @Override
        public void visitEnd() {
            if (src != null) src.visitEnd();
            if (dst != null) {
                if (Type.getDescriptor(Advice.FieldValue.class).equals(newDescriptor) && paramName != null && !params.containsKey("value")) {
                    // for FieldValue annotations on parameters, we need to set the "value" parameter to the parameter name
                    dst.visit("value", paramName);
                }
                dst.visitEnd();
            }
        }
    }

    private AnnotationVisitor visitMappedParamAnnotation(int pidx, String descriptor, boolean visible, TriFunction<Integer, String, Boolean, AnnotationVisitor> visitor, String paramName) {
        String newDescriptor = mapDescriptor(descriptor);
        if (newDescriptor == null) return visitor.apply(pidx, descriptor, visible);

        bChanged = true;
        // visit original annotation first
        AnnotationVisitor src = bKeepOriginalAnnotations ? visitor.apply(pidx, descriptor, visible) : null;
        AnnotationVisitor dst = visitor.apply(pidx, newDescriptor, visible);
        return new DualAnnotationVisitor(src, dst, newDescriptor, paramName);
    }

    private AnnotationVisitor visitMappedAnnotation(String descriptor, boolean visible, BiFunction<String, Boolean, AnnotationVisitor> visitor) {
        String newDescriptor = mapDescriptor(descriptor);
        if (newDescriptor == null) return visitor.apply(descriptor, visible);

        bChanged = true;
        // visit original annotation first
        AnnotationVisitor src = bKeepOriginalAnnotations ? visitor.apply(descriptor, visible) : null;
        AnnotationVisitor dst = visitor.apply(newDescriptor, visible);
        return new DualAnnotationVisitor(src, dst);
    }

    public static String mapDescriptor(String descriptor) {
        return ANN_MAP.get(descriptor);
    }

    /** Reads the LocalVariableTable of each method to extract parameter names. Key = methodName + descriptor. */
    private static Map<String, String[]> collectParamNames(byte[] classBytes) {
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

    public Result transform(byte[] classBytes) {
        bChanged = false;

        var allParams = collectParamNames(classBytes);

        ClassReader cr = new ClassReader(classBytes);
        ClassWriter cw = new ClassWriter(0); // no COMPUTE_FRAMES, no COMPUTE_MAXS

        cr.accept(new ClassVisitor(ASM_API, cw) {
            @Override
            public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                return visitMappedAnnotation(descriptor, visible, super::visitAnnotation);
            }

            // TODO: field annotations
            // @Override
            // public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
                // FieldVisitor fv = super.visitField(forcePublic(access), name, descriptor, signature, value);
                // return fv;
            // }

            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                boolean isStatic = (access & Opcodes.ACC_STATIC) != 0;
                String[] paramNames = allParams.getOrDefault(name + descriptor, null);

                MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
                return new MethodVisitor(ASM_API, mv) {
                    @Override
                    public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                        return visitMappedAnnotation(descriptor, visible, super::visitAnnotation);
                    }

                    @Override
                    public AnnotationVisitor visitParameterAnnotation(int i, String descriptor, boolean visible) {
                        String paramName = (paramNames == null || i >= paramNames.length) ? ("arg" + i ) : paramNames[i];
                        return visitMappedParamAnnotation(i, descriptor, visible, super::visitParameterAnnotation, paramName);
                    }
                };
            }
        }, 0);

        return new Result(cw.toByteArray(), bChanged);
    }
}
