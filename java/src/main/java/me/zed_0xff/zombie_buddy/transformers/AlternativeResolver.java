package me.zed_0xff.zombie_buddy.transformers;

import me.zed_0xff.zombie_buddy.Logger;
import me.zed_0xff.zombie_buddy.LuaJSON;

import java.util.ArrayList;
import java.util.function.BiFunction;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.bytebuddy.asm.Advice;
import net.bytebuddy.jar.asm.*;

/*
 * resolves alternative names like Field("CHUNKS_PER_WIDTH", "ChunksPerWidth") to the first one that exists in the target class
 */
public class AlternativeResolver implements Transformer {

    boolean bChanged = false;

    private static final Object SKIP = new Object();

    public Result transform(byte[] classBytes) {
        bChanged = false;

        Map<String, String[]> allParams = Transformer.collectParamNames(classBytes);

        ClassReader cr = new ClassReader(classBytes);
        ClassWriter cw = new ClassWriter(0);

        cr.accept(new ClassVisitor(ASM_API, cw) {
            @Override
            public MethodVisitor visitMethod( int access, String name, String descriptor, String signature, String[] exceptions) {
                String[] names = allParams.getOrDefault(name + descriptor, new String[0]);
                MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);

                String methodKey = name + descriptor;
                String[] paramNames = allParams.getOrDefault(methodKey, new String[0]);
                boolean isStatic = (access & Opcodes.ACC_STATIC) != 0;

                return new MethodVisitor(ASM_API, mv) {

                    @Override
                    public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
                        return wrapAnnotation(desc, super.visitAnnotation(desc, visible));
                    }

                    @Override
                    public AnnotationVisitor visitParameterAnnotation(int index, String desc, boolean visible) {
                        String paramName = (index < names.length) ? names[index] : ("arg" + index);
                        AnnotationVisitor av = super.visitParameterAnnotation(index, desc, visible);
                        return wrapAnnotation(desc, new NamedAnnotationVisitor(av, paramName));
                    }
                };
            }

            // -------------------------
            // ANNOTATION WRAPPING
            // -------------------------
            private AnnotationVisitor wrapAnnotation(String desc, AnnotationVisitor out) {
                if (out == null) return null;

                return new AnnotationVisitor(ASM_API, out) {

                    @Override
                    public void visit(String name, Object value) {
                        Object rewritten = rewriteValue(name, value);
                        if (rewritten != SKIP) {
                            bChanged = true;
                            super.visit(name, rewritten);
                        }
                    }

                    @Override
                    public void visitEnum(String name, String desc, String value) {
                        Object rewritten = rewriteEnum(name, desc, value);
                        if (rewritten != SKIP) {
                            bChanged = true;
                            super.visitEnum(name, desc, value);
                        }
                    }

                    @Override
                    public AnnotationVisitor visitAnnotation(String name, String desc) {
                        return wrapAnnotation(desc, super.visitAnnotation(name, desc));
                    }

                    @Override
                    public AnnotationVisitor visitArray(String name) {
                        List<Object> values = new ArrayList<>();

                        return new AnnotationVisitor(ASM_API) {

                            @Override
                            public void visit(String n, Object value) {
                                values.add(value);
                            }

                            @Override
                            public void visitEnum(String n, String d, String v) {
                                values.add(v);
                            }

                            @Override
                            public void visitEnd() {
                                Object rewritten = flattenArray(name, values);

                                if (rewritten != SKIP) {
                                    bChanged = true;
                                    out.visit(name, rewritten);
                                }
                            }
                        };
                    }
                };
            }

            // -------------------------
            // PARAMETER NAME WRAPPER
            // -------------------------
            private class NamedAnnotationVisitor extends AnnotationVisitor {
                private final String paramName;

                NamedAnnotationVisitor(AnnotationVisitor av, String paramName) {
                    super(ASM_API, av);
                    this.paramName = paramName;
                }

                @Override
                public void visit(String name, Object value) {
                    if (name == null && paramName != null) {
                        super.visit(paramName, value);
                    } else {
                        super.visit(name, value);
                    }
                }
            }

            // -------------------------
            // HOOKS
            // -------------------------
            private Object rewriteValue(String name, Object value) {
                return value;
            }

            private Object rewriteEnum(String name, String desc, String value) {
                return value;
            }

            private Object flattenArray(String name, List<Object> values) {
                return new LuaJSON().toJson(values);
            }

        }, 0);

        return new Result(cw.toByteArray(), bChanged);
    }
}
