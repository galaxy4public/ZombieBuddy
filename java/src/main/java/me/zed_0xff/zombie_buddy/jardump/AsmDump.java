package me.zed_0xff.zombie_buddy.jardump;

import me.zed_0xff.zombie_buddy.transformers.*;

import me.zed_0xff.zombie_buddy.Logger;
import me.zed_0xff.zombie_buddy.Utils;

import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.jar.asm.*; // shaded org.objectweb.asm
import net.bytebuddy.pool.TypePool;

import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

public class AsmDump extends CLIUtil {
    private static final int ASM_API = Opcodes.ASM9;
    private final ClassContext m_ctx;

    public AsmDump(ClassContext ctx) {
        m_ctx = ctx;
    }

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

    private record Mod(int flag, String label) {}

    private static final Mod[] CLASS_MODS  = { new Mod(Opcodes.ACC_PUBLIC, "public"), new Mod(Opcodes.ACC_FINAL, "final"), new Mod(Opcodes.ACC_ABSTRACT, "abstract"), new Mod(Opcodes.ACC_INTERFACE, "interface"), new Mod(Opcodes.ACC_ENUM, "enum") };
    private static final Mod[] METHOD_MODS = { new Mod(Opcodes.ACC_PUBLIC, "public"), new Mod(Opcodes.ACC_PRIVATE, "private"), new Mod(Opcodes.ACC_PROTECTED, "protected"), new Mod(Opcodes.ACC_STATIC, "static"), new Mod(Opcodes.ACC_FINAL, "final"), new Mod(Opcodes.ACC_SYNCHRONIZED, "synchronized"), new Mod(Opcodes.ACC_ABSTRACT, "abstract") };
    private static final Mod[] FIELD_MODS  = { new Mod(Opcodes.ACC_PUBLIC, "public"), new Mod(Opcodes.ACC_PRIVATE, "private"), new Mod(Opcodes.ACC_PROTECTED, "protected"), new Mod(Opcodes.ACC_STATIC, "static"), new Mod(Opcodes.ACC_FINAL, "final"), new Mod(Opcodes.ACC_VOLATILE, "volatile"), new Mod(Opcodes.ACC_TRANSIENT, "transient"), new Mod(Opcodes.ACC_SYNTHETIC, "synthetic"), new Mod(Opcodes.ACC_ENUM, "enum") };

    private static String formatMods(int access, Mod[] mods) {
        List<String> found = new ArrayList<>();
        for (Mod m : mods) if ((access & m.flag()) != 0) found.add(m.label());
        return String.join(" ", colorizeModifiers(found));
    }

    static String classModifiers(int access)  { return formatMods(access, CLASS_MODS); }
    static String methodModifiers(int access) { return formatMods(access, METHOD_MODS); }
    static String fieldModifiers(int access)  { return formatMods(access, FIELD_MODS); }

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

    static final HashMap<String, Map<String, MethodDescription>> _annMemberCache = new HashMap<>();

    Map<String, MethodDescription> getAnnotationMembers(TypeDescription td) {
        Map<String, MethodDescription> members = new LinkedHashMap<>();
        for (var m : td.getDeclaredMethods()) {
            members.put(m.getName(), m);
        }
        return members;
    }

    static TypeDescription box(TypeDescription t) {
        if (!t.isPrimitive()) return t;

        if (t.represents(boolean.class)) return TypeDescription.ForLoadedType.of(Boolean.class);
        if (t.represents(int.class))     return TypeDescription.ForLoadedType.of(Integer.class);
        if (t.represents(long.class))    return TypeDescription.ForLoadedType.of(Long.class);
        if (t.represents(double.class))  return TypeDescription.ForLoadedType.of(Double.class);
        if (t.represents(float.class))   return TypeDescription.ForLoadedType.of(Float.class);
        if (t.represents(short.class))   return TypeDescription.ForLoadedType.of(Short.class);
        if (t.represents(byte.class))    return TypeDescription.ForLoadedType.of(Byte.class);
        if (t.represents(char.class))    return TypeDescription.ForLoadedType.of(Character.class);

        return t;
    }

    private boolean isAssignable(TypeDescription target, TypeDescription value) {
        if (target.isPrimitive()) {
            // allow unboxing conversions for primitives (e.g. int can be assigned from Integer)
            return box(target).isAssignableFrom(value);
        } else {
            if (target.isAssignableFrom(value)) return true;
            if (target.represents(Class.class) && value.represents(Type.class)) {
                // allow Type to be assigned to Class for annotation members of type Class<?>
                return true;
            }
        }
        return false;
    }

    private record Rule(boolean allowBlank, boolean allowDefault) {}

    private static final Map<String, Map<String, Rule>> _annotationRules = Map.of(
        Type.getDescriptor(Advice.FieldValue.class), Map.of(
            "value", new Rule(false, false) // value member of @FieldValue cannot be empty string
        )
    );

    boolean validateAnnotation(TypeDescription td, Map<String, MethodDescription> methods, Map<String, Object> values, Map<String, Rule> rules) {
        boolean valid = true;
        for (MethodDescription m : methods.values()) {
            String name = m.getName();
            Rule rule = rules.get(name);
            Object value = values.get(name);
            if (value == null) {
                if (rule != null && !rule.allowDefault()) {
                    Logger.debug("missing required annotation member -", name);
                    valid = false;
                    continue;
                }
                value = m.getDefaultValue();
            } else {
                TypeDescription returnType = m.getReturnType().asErasure();
                TypeDescription valueType  = TypeDescription.ForLoadedType.of(value.getClass());
                if (!isAssignable(returnType, valueType)) {
                    Logger.debug(m.getReturnType() + " " + name + " is not assignable from " + valueType, value);
                    valid = false;
                }
            }
            if (value == null) {
                Logger.debug("missing required annotation member", name);
                valid = false;
                continue;
            }
            if (rule != null && !rule.allowBlank() && Utils.isBlank(value)) {
                Logger.debug("annotation " + name + " value cannot be blank", name);
                valid = false;
            }
        }
        for (var name : values.keySet()) {
            if (!methods.containsKey(name)) {
                Logger.debug("unknown annotation member", name);
                valid = false;
            }
        }
        return valid;
    }

    boolean validateAnnotation(String desc, Map<String, Object> values) {
        try {
            TypeDescription td =
                m_ctx.getTypePool().describe(Type.getType(desc).getClassName())
                .resolve();

            Map<String, MethodDescription> methods = _annMemberCache.computeIfAbsent(td.getName(), k -> getAnnotationMembers(td));
            Map<String, Rule> rules = _annotationRules.getOrDefault(desc, Map.of());
            return validateAnnotation(td, methods, values, rules);
        } catch (Exception e) {
            Logger.error("Failed to resolve annotation type: " + desc + ": " + e);
            return false;
        }
    }

    String formatAnnotation(String desc, Map<String, Object> values) {
        StringBuilder sb = new StringBuilder();
        sb.append(annotationName(desc));

        if (!values.isEmpty()) {
            sb.append("(");

            if (values.size() == 1 && values.containsKey("value")) {
                // special case for single "value" member to allow @Anno("foo") instead of @Anno(value="foo")
                sb.append(formatValue(values.get("value")));
            } else {
                boolean first = true;
                for (var e : values.entrySet()) {
                    if (!first) {
                        sb.append(", ");
                    }
                    first = false;

                    sb.append(e.getKey()).append("=").append(formatValue(e.getValue()));
                }
            }
            sb.append(")");
        }

        boolean valid = desc.startsWith("Lme/zed_0xff/zombie_buddy/Patch") || validateAnnotation(desc, values);
        return colorize(sb.toString(), valid ? (desc.contains("bytebuddy") ? BB_ANN_COLOR : ANN_COLOR) : RED);
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
    AnnotationVisitor annotationPrinter(String desc, Consumer<String> emitLine) {
        Map<String, Object> values = new LinkedHashMap<>();
        return annotationPrinterBody(desc, values, () -> emitLine.accept(formatAnnotation(desc, values)));
    }

    private AnnotationVisitor annotationPrinterBody(String desc, Map<String, Object> values, Runnable onEnd) {
        return new AnnotationVisitor(ASM_API) {
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

    private AnnotationVisitor annotationArrayElements(List<Object> arr) {
        return new AnnotationVisitor(ASM_API) {
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

    public String dump(byte[] classBytes) {
        var allParams = Transformer.collectParamNames(classBytes);

        StringBuilder sb = new StringBuilder();
        ClassReader   cr = new ClassReader(classBytes);
        ClassVisitor  cv = new ClassVisitor(ASM_API) {
            @Override
            public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
                return annotationPrinter(desc, line -> sb.insert(0, line + "\n"));
            }

            @Override
            public void visit(int version, int access, String className, String signature, String superName, String[] interfaces) {
                StringBuilder csb = new StringBuilder();
                csb.append(classModifiers(access)).append(" class ").append(className.replace('/', '.'));
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

                return new FieldVisitor(ASM_API) {
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
                String[] paramNames = allParams.getOrDefault(name + descriptor, new String[0]);
                StringBuilder msb = new StringBuilder();
                Type mt = Type.getMethodType(descriptor);
                Type[] args = mt.getArgumentTypes();
                List<List<String>> paramAnnotations = new ArrayList<>();

                for (int i = 0; i < args.length; i++) {
                    paramAnnotations.add(new ArrayList<>());
                }

                //MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
                return new MethodVisitor(ASM_API /*, mv*/) {
                    // available only if compiled with -parameters
                    // @Override
                    // public void visitParameter(String name, int access) {
                    //     Logger.debug("parameter", name, access);
                    // }

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

                        int nAnns = 0;
                        int nAnnotatedParams = 0;
                        Table2 tbl = new Table2(4);
                        for (int i = 0; i < args.length; i++) {
                            List<String> anns = paramAnnotations.get(i);
                            if (!anns.isEmpty()) {
                                nAnns += anns.size();
                                nAnnotatedParams++;
                            } 
                            String paramName = (i >= paramNames.length) ? ("arg" + i ) : paramNames[i];
                            tbl.addRow(
                                    String.join(" ", anns),
                                    simpleName(args[i].getDescriptor()) + " " + paramName + ((i < args.length - 1) ? "," : "")
                                    );
                        }

                        if (nAnns > 4 && nAnnotatedParams < nAnns){
                            // multi-line if there are many annotations but not all params are annotated (to avoid too much clutter)
                            msb.append("\n");
                            msb.append(indent(tbl.toString()));
                        } else {
                            // one-line
                            msb.append(String.join(" ", tbl.rows().stream().map(r -> r.toString()).toArray(String[]::new)) );
                        }

                        msb.append(")\n");
                        sb.append(indent(msb.toString()));
                    }
                };
            }
        };
        // SKIP_CODE or SKIP_DEBUG hides method parameter names
        cr.accept(cv, ClassReader.SKIP_FRAMES);
        return sb.toString();
    }
}
