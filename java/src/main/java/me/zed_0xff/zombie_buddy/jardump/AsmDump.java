package me.zed_0xff.zombie_buddy.jardump;

import net.bytebuddy.jar.asm.AnnotationVisitor;
import net.bytebuddy.jar.asm.ClassReader;
import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.jar.asm.FieldVisitor;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;
import net.bytebuddy.jar.asm.Type;

import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

public class AsmDump extends CLIUtil {
    // "Ljava/util/regex/Pattern;" -> "java.util.regex.Pattern"
    static String typeName(String desc) {
        return Type.getType(desc).getClassName();
    }

    static String annotationName(String desc) {
        return "@" + simpleName(desc);
    }

    // "Ljava/util/regex/Pattern;" -> "Pattern"
    static String simpleName(String desc) {
        String name = Type.getType(desc).getClassName();

        int idx = name.lastIndexOf('.');
        return idx >= 0 ? name.substring(idx + 1) : name;
    }

    private static void addModifier(List<String> mods, int access, int flag, String label) {
        if ((access & flag) != 0) {
            mods.add(label);
        }
    }

    static String classModifiers(int access) {
        List<String> mods = new ArrayList<>();
        addModifier(mods, access, Opcodes.ACC_PUBLIC, "public");
        addModifier(mods, access, Opcodes.ACC_FINAL, "final");
        addModifier(mods, access, Opcodes.ACC_ABSTRACT, "abstract");
        addModifier(mods, access, Opcodes.ACC_INTERFACE, "interface");
        addModifier(mods, access, Opcodes.ACC_ENUM, "enum");

        return String.join(" ", colorizeModifiers(mods));
    }

    static String methodModifiers(int access) {
        List<String> mods = new ArrayList<>();
        addModifier(mods, access, Opcodes.ACC_PUBLIC, "public");
        addModifier(mods, access, Opcodes.ACC_PRIVATE, "private");
        addModifier(mods, access, Opcodes.ACC_PROTECTED, "protected");
        addModifier(mods, access, Opcodes.ACC_STATIC, "static");
        addModifier(mods, access, Opcodes.ACC_FINAL, "final");
        addModifier(mods, access, Opcodes.ACC_SYNCHRONIZED, "synchronized");
        addModifier(mods, access, Opcodes.ACC_ABSTRACT, "abstract");

        return String.join(" ", colorizeModifiers(mods));
    }

    static String fieldModifiers(int access) {
        List<String> mods = new ArrayList<>();
        addModifier(mods, access, Opcodes.ACC_PUBLIC, "public");
        addModifier(mods, access, Opcodes.ACC_PRIVATE, "private");
        addModifier(mods, access, Opcodes.ACC_PROTECTED, "protected");

        addModifier(mods, access, Opcodes.ACC_STATIC, "static");
        addModifier(mods, access, Opcodes.ACC_FINAL, "final");

        addModifier(mods, access, Opcodes.ACC_VOLATILE, "volatile");
        addModifier(mods, access, Opcodes.ACC_TRANSIENT, "transient");

        addModifier(mods, access, Opcodes.ACC_SYNTHETIC, "synthetic");
        addModifier(mods, access, Opcodes.ACC_ENUM, "enum");

        return String.join(" ", colorizeModifiers(mods));
    }

    static ArrayList<String> colorizeModifiers(List<String> mods) {
        ArrayList<String> colored = new ArrayList<>(mods);
        for (int i = 0; i < mods.size(); i++) {
            String mod = mods.get(i);
            if (_modifierColors.containsKey(mod)) {
                colored.set(i, colorize(mod, _modifierColors.get(mod)));
            }
        }
        return colored;
    }

    static String formatAnnotation(String desc, Map<String, Object> values) {
        StringBuilder sb = new StringBuilder();
        sb.append(annotationName(desc));

        if (!values.isEmpty()) {
            sb.append("(");

            boolean first = true;
            for (var e : values.entrySet()) {
                if (!first) {
                    sb.append(", ");
                }
                first = false;

                sb.append(e.getKey()).append("=").append(formatValue(e.getValue()));
            }
            sb.append(")");
        }
        return colorize(sb.toString(), desc.contains("bytebuddy") ? BB_ANN_COLOR : ANN_COLOR);
    }

    static String formatValue(Object v) {
        if (v instanceof String s) {
            return "\"" + s + "\"";
        }
        if (v instanceof Type t) {
            return simpleName(t.getDescriptor()) + ".class";
        }
        if (v instanceof List<?> list) {
            StringBuilder sb = new StringBuilder();
            sb.append("{");
            boolean first = true;
            for (Object o : list) {
                if (!first) {
                    sb.append(", ");
                }
                first = false;
                sb.append(formatValue(o));
            }
            sb.append("}");
            return sb.toString();
        }
        return String.valueOf(v);
    }

    /** ASM uses {@code null} for the default member name {@code value}. */
    private static String annMemberName(String name) {
        return name != null ? name : "value";
    }

    /**
     * Collects annotation member values (non-default elements visited by ASM) and passes the formatted line to {@code emitLine} on {@link AnnotationVisitor#visitEnd()}.
     */
    static AnnotationVisitor annotationPrinter(String desc, Consumer<String> emitLine) {
        Map<String, Object> values = new LinkedHashMap<>();
        return annotationPrinterBody(desc, values, () -> emitLine.accept(formatAnnotation(desc, values)));
    }

    private static AnnotationVisitor annotationPrinterBody(String desc, Map<String, Object> values, Runnable onEnd) {
        return new AnnotationVisitor(Opcodes.ASM9) {
            @Override
            public void visit(String name, Object value) {
                values.put(annMemberName(name), value);
            }

            @Override
            public void visitEnum(String name, String descriptor, String value) {
                values.put(annMemberName(name), simpleName(descriptor) + "." + value);
            }

            @Override
            public AnnotationVisitor visitAnnotation(String name, String descriptor) {
                Map<String, Object> nested = new LinkedHashMap<>();
                String key = annMemberName(name);
                return annotationPrinterBody(descriptor, nested, () -> values.put(key, formatAnnotation(descriptor, nested)));
            }

            @Override
            public AnnotationVisitor visitArray(String name) {
                List<Object> arr = new ArrayList<>();
                values.put(annMemberName(name), arr);
                return annotationArrayElements(arr);
            }

            @Override
            public void visitEnd() {
                onEnd.run();
            }
        };
    }

    private static AnnotationVisitor annotationArrayElements(List<Object> arr) {
        return new AnnotationVisitor(Opcodes.ASM9) {
            @Override
            public void visit(String name, Object value) {
                arr.add(value);
            }

            @Override
            public void visitEnum(String name, String descriptor, String value) {
                arr.add(simpleName(descriptor) + "." + value);
            }

            @Override
            public AnnotationVisitor visitAnnotation(String name, String descriptor) {
                Map<String, Object> nested = new LinkedHashMap<>();
                return annotationPrinterBody(descriptor, nested, () -> arr.add(formatAnnotation(descriptor, nested)));
            }

            @Override
            public AnnotationVisitor visitArray(String name) {
                List<Object> inner = new ArrayList<>();
                arr.add(inner);
                return annotationArrayElements(inner);
            }
        };
    }

    public static String dump(byte[] classBytes) {
        StringBuilder sb = new StringBuilder();
        ClassReader cr = new ClassReader(classBytes);
        cr.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
                return annotationPrinter(desc, line -> sb.insert(0, line + "\n"));
            }

            @Override
            public void visit(int version, int access, String className, String signature, String superName, String[] interfaces) {
                StringBuilder csb = new StringBuilder();
                csb.append(classModifiers(access)).append(" class ").append(className);
                if (!Type.getInternalName(Object.class).equals(superName)) {
                    csb.append(" extends ").append(superName);
                }

                if (interfaces != null && interfaces.length > 0) {
                    csb.append(" implements");
                    for (String ifname : interfaces) {
                        csb.append(" ").append(ifname);
                    }
                }
                sb.append(csb).append("\n");
            }

            @Override
            public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
                StringBuilder fsb = new StringBuilder();

                return new FieldVisitor(Opcodes.ASM9) {
                    @Override
                    public AnnotationVisitor visitAnnotation(String adesc, boolean visible) {
                        return annotationPrinter(adesc, line -> fsb.append(line).append("\n"));
                    }

                    @Override
                    public void visitEnd() {
                        fsb.append(fieldModifiers(access)).append(" ")
                            .append(simpleName(descriptor)).append(" ")
                            .append(name).append("\n");
                        sb.append(indent(fsb.toString()));
                    }
                };
            }

            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                StringBuilder msb = new StringBuilder();
                Type mt = Type.getMethodType(descriptor);
                Type[] args = mt.getArgumentTypes();
                List<List<String>> paramAnnotations = new ArrayList<>();

                for (int i = 0; i < args.length; i++) {
                    paramAnnotations.add(new ArrayList<>());
                }

                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public AnnotationVisitor visitAnnotation(String adesc, boolean visible) {
                        return annotationPrinter(adesc, line -> msb.append(line).append("\n"));
                    }

                    @Override
                    public AnnotationVisitor visitParameterAnnotation(int parameter, String adescriptor, boolean visible) {
                        return annotationPrinter(adescriptor, line -> paramAnnotations.get(parameter).add(line));
                    }

                    @Override
                    public void visitEnd() {
                        msb.append(methodModifiers(access)).append(" ").append(name).append("(");

                        for (int i = 0; i < args.length; i++) {
                            if (i != 0) {
                                msb.append(", ");
                            }

                            List<String> anns = paramAnnotations.get(i);
                            if (!anns.isEmpty()) {
                                msb.append(String.join(" ", anns)).append(" ");
                            }
                            msb.append(simpleName(args[i].getDescriptor()));
                        }

                        msb.append(")\n");
                        sb.append(indent(msb.toString()));
                    }
                };
            }

        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return sb.toString();
    }
}
