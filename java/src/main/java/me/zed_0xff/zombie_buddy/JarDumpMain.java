package me.zed_0xff.zombie_buddy;

import me.zed_0xff.zombie_buddy.transformers.*;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;

import net.bytebuddy.asm.AsmVisitorWrapper;
import net.bytebuddy.description.annotation.AnnotationSource;
import net.bytebuddy.description.annotation.AnnotationValue;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.method.MethodList;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.jar.asm.Type;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;

public class JarDumpMain {

    static boolean _showHelp  = false;
    static boolean _publicize = false;
    static boolean _color     = true;

    static StringBuilder indent(StringBuilder src) {
        return indent(src, 4);
    }

    static final int RESET   = 0;
    static final int BOLD    = 1;
    static final int DIM     = 2;
    static final int RED     = 31;
    static final int GREEN   = 32;
    static final int YELLOW  = 33;
    static final int BLUE    = 34;
    static final int MAGENTA = 35;
    static final int CYAN    = 36;
    static final int WHITE   = 37;

    static final int BRIGHT = 60; // ADD to base color code for bright variants

    static String colorize(String s, int color) {
        if (!_color || Utils.isBlank(s)) return s;
        return "\u001B[" + color + "m" + s + "\u001B[0m";
    }

    static String highlight(String s, String word, int color) {
        if (!_color || Utils.isBlank(s)) return s;
        return s.replace(word, colorize(word, color));
    }

    static String highlight(String s, Map<String, Integer> colorMap) {
        if (!_color || Utils.isBlank(s)) return s;
        String result = s;
        for (Map.Entry<String, Integer> entry : colorMap.entrySet()) {
            result = highlight(result, entry.getKey(), entry.getValue());
        }        
        return result;
    }

    static final String PKG_PRIVATE = "pkgPrivate";
    static final Map<String, Integer> modifierColors = Map.of(
        PKG_PRIVATE, RED,
        "private",   RED,
        "protected", RED,
        "public",    GREEN
        // "final",     YELLOW
    );

    static String formatModifiers(net.bytebuddy.description.ModifierReviewable mr) {
        int mod = mr.getModifiers();
        boolean isPackagePrivate = !Modifier.isPublic(mod) && !Modifier.isProtected(mod) && !Modifier.isPrivate(mod);
        String mods = Modifier.toString(mod);
        if (isPackagePrivate) mods = PKG_PRIVATE + " " + mods;
        if (Utils.isBlank(mods)) return "";
        return highlight( mods, modifierColors ) + " ";
    }

    static String formatAnnotations(AnnotationSource as) {
        final boolean wasParams[] = {false}; // hack to modify from lambda
        ArrayList<String> anns = new ArrayList<>();
        as.getDeclaredAnnotations().forEach(a -> {
            StringBuilder sb = new StringBuilder();
            sb.append("@").append(a.getAnnotationType().getTypeName());
            int color = MAGENTA;
            if (a.getAnnotationType().getName().startsWith("net.bytebuddy.")) {
                color = CYAN;
            }
            MethodList<MethodDescription.InDefinedShape> params = a.getAnnotationType().getDeclaredMethods();
            if (!params.isEmpty()) {
                StringJoiner sj = new StringJoiner(", ");
                params.forEach(m -> {
                    AnnotationValue<?, ?> val = a.getValue(m);
                    AnnotationValue<?, ?> def = m.getDefaultValue();
                    if (def == null || !def.equals(val)) {
                        sj.add(m.getName() + "=" + val);
                    }
                });
                if (sj.length() > 0) {
                    wasParams[0] = true;
                    sb.append('(');
                    sb.append(sj);
                    sb.append(')');
                }
            }
            if (sb.length() > 0) {
                anns.add(colorize(sb.toString(), color));
            }
        });
        return anns.size() == 0 ? "" : (String.join(wasParams[0] ? "\n" : " ", anns) + "\n");
    }

    static StringBuilder indent(StringBuilder src, int level) {
        if (src.length() == 0) return src; // avoid adding indent to empty blocks

        String indent = " ".repeat(level);
        String s = src.toString();
        StringBuilder sb = new StringBuilder();
        sb.append(indent);

        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            sb.append(c);

            if (c == '\n' && i + 1 < s.length()) {
                sb.append(indent);
            }
        }
        return sb;
    }

    static StringBuilder dumpFields(TypeDescription td) {
        StringBuilder sb = new StringBuilder();
        td.getDeclaredFields().forEach(f -> {
            sb.append(formatAnnotations(f));
            sb.append(formatModifiers(f));
            sb.append(f.getType().getTypeName()).append(" ");
            sb.append(f.getName());
            sb.append('\n');
        });
        return sb;
    }

    static StringBuilder dumpMethods(TypeDescription td) {
        StringBuilder sb = new StringBuilder();

        td.getDeclaredMethods().forEach(m -> {
            sb.append(formatAnnotations(m));
            sb.append(formatModifiers(m));

            // Type mt = Type.getMethodType(m.getDescriptor());
            //
            // if (!m.isConstructor()) {
            //     sb.append(mt.getReturnType().getClassName())
            //         .append(' ');
            // }

            sb.append(m.isConstructor() ? "<init>" : m.getName());
            sb.append('(');

            // Type[] args = mt.getArgumentTypes();
            // for (int i = 0; i < args.length; i++) {
            //     if (i != 0) sb.append(", ");
            //     sb.append(args[i].getClassName());
            // }

            sb.append(')').append('\n');
        });

        return sb;
    }

    static Set<String> seenTypes = new HashSet<>();

    // td.getTypeName(), td.getInterfaces(), td.getDeclaredMethods(), etc.
    // — all without Class.forName / classloader involvement
    static void dumpType(TypeDescription td) {
        if (seenTypes.contains(td.getName())) return;
        seenTypes.add(td.getName());

        StringBuilder sb = new StringBuilder();
        sb.append(formatAnnotations(td));
        sb.append(formatModifiers(td));
        sb.append(td);
        sb.append('\n');

        sb.append(indent(dumpFields(td)));
        sb.append(indent(dumpMethods(td)));

        System.out.println(sb.toString());
    }

    private static ArrayList<Transformer> _transformers = new ArrayList<>(List.of(
        new Publicizer(),
        new AnnotationConverter()
    ));

    static ArrayList<String> parseArgs(String[] args) {
        ArrayList<String> positionalArgs = new ArrayList<>();
        for(int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (!arg.startsWith("-")) {
                positionalArgs.add(arg);
            } else {
                     if (arg.equals("-h") || arg.equals("--help")) _showHelp = true;
                else if (arg.equals("-t") || arg.equals("--transformers")) {
                    if (i + 1 >= args.length) {
                        System.err.println("Error: Missing value for " + arg);
                        System.exit(1);
                    }
                    _transformers.clear();
                    String[] transformers = args[++i].split(",");
                    for (String t : transformers) {
                        switch (t.trim().toLowerCase()) {
                            case "none": break; // no transformers
                            case "ann": _transformers.add(new AnnotationConverter()); break;
                            case "pub": _transformers.add(new Publicizer()); break;
                            default:
                                System.err.println("Unknown translator: " + t);
                                System.exit(1);
                                break;
                        }
                    }
                }
                else {
                    System.err.println("Unknown option: " + arg);
                }
            }
        }
        return positionalArgs;
    }

    public static void main(String[] args) throws IOException {
        Logger.setLevel(Logger.DEBUG);

        ArrayList<String> positionalArgs = parseArgs(args);
        if (positionalArgs.size() == 0 || _showHelp) {
            System.out.println("Usage: java -jar JarDump.jar [options] <path_to_jar>");
            System.out.println("Options:");
            System.out.println("    -h, --help         Show this help message");
            System.out.println("    -t, --transformers Specify which transformers to apply (default:all)");
            System.out.println();
            System.out.println("transformers:");
            System.out.println("    none               Apply no transformations (use original class bytes)");
            System.out.println("    ann                Convert ZombieBuddy annotations to ByteBuddy annotations");
            System.out.println("    pub                Publicize all members (remove non-public modifiers)");
            return;
        }

        String fname = positionalArgs.get(0);
        System.out.println(fname);
        try (
             JarFile jar   = new JarFile(new File(fname), false);
             JarFile zbJar = new JarFile(Utils.getCurrentJarPath().toFile(), false);
             ClassFileLocator locator = new ClassFileLocator.Compound(
                 new ClassFileLocator.ForJarFile(jar),       // target
                 ClassFileLocator.ForClassLoader.of(JarDumpMain.class.getClassLoader()) // ZB + JDK
//                 ClassFileLocator.ForModuleFile.ofBootPath() // JDK
                 );
        ) {
            TypePool pool = TypePool.Default.of(locator);
            // TypePool pool = TypePool.Default.of(locator, TypePool.ClassLoading.ofBootPath());
            jar.stream()
                .map(JarEntry::getName)
                .filter(n -> n.endsWith(".class") && !n.startsWith("META-INF/") && !n.equals("module-info.class"))
                .map(n -> n.substring(0, n.length() - 6).replace('/', '.'))  // → binary class name
                .forEach(className -> {
                    try {
                        byte[] classBytes = locator.locate(className).resolve();
                        byte[] rewritten  = classBytes.clone();

                        for (Transformer t : _transformers) {
                            rewritten = t.transform(rewritten);
                        }

                        TypeDescription td = null;
                        if (Arrays.equals(classBytes, rewritten)) {
                            // no changes, can use original bytes
                            td = pool.describe(className).resolve();
                        } else {
                            ClassFileLocator patchedLocator = new ClassFileLocator.Compound( ClassFileLocator.Simple.of(className, rewritten), locator );
                            td = TypePool.Default.of(patchedLocator).describe(className).resolve();
                        }
                        dumpType(td);
                    } catch (IOException e) {
                        System.err.println("Failed to read class: " + className);
                        e.printStackTrace();
                    }
                });
        }
    }
}
