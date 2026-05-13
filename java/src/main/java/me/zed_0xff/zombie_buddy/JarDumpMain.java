package me.zed_0xff.zombie_buddy;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import net.bytebuddy.asm.AsmVisitorWrapper;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.jar.asm.Opcodes;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;

import static net.bytebuddy.matcher.ElementMatchers.*;
import net.bytebuddy.jar.asm.*; // shaded org.objectweb.asm

public class JarDumpMain {

    static boolean _showHelp  = false;
    static boolean _publicize = false;
    static boolean _color     = true;

    static StringBuilder indent(StringBuilder src) {
        return indent(src, 4);
    }

    static final int RESET  = 0;
    static final int BOLD   = 1;
    static final int DIM    = 2;
    static final int RED    = 31;
    static final int GREEN  = 32;
    static final int YELLOW = 33;
    static final int BLUE   = 34;
    static final int CYAN   = 36;
    static final int WHITE  = 37;

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
//        "final",     YELLOW
    );

    static String formatModifiers(net.bytebuddy.description.ModifierReviewable mr) {
        int mod = mr.getModifiers();
        boolean isPackagePrivate = !Modifier.isPublic(mod) && !Modifier.isProtected(mod) && !Modifier.isPrivate(mod);
        String mods = Modifier.toString(mod);
        if (isPackagePrivate) mods = PKG_PRIVATE + " " + mods;
        if (Utils.isBlank(mods)) return "";
        return highlight( mods, modifierColors ) + " ";
    }

    static String formatAnnotations(net.bytebuddy.description.annotation.AnnotationSource as) {
        StringBuilder sb = new StringBuilder();
        as.getDeclaredAnnotations()
            .forEach(a -> sb.append(" @").append(a.getAnnotationType().getSimpleName()));
        return colorize(sb.toString(), BRIGHT+CYAN);
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
            sb.append(formatModifiers(f));
            sb.append(f.getType()).append(' ');
            sb.append(f.getName());
            sb.append(formatAnnotations(f));
            sb.append('\n');
        });
        return sb;
    }

    static StringBuilder dumpMethods(TypeDescription td) {
        StringBuilder sb = new StringBuilder();
        td.getDeclaredMethods().forEach(m -> {
            sb.append(formatModifiers(m));

            String name = m.isConstructor() ? "<init>" : m.getName();
            sb.append(name).append("()");

            if (m.isBridge())    sb.append(" [bridge]");
            if (m.isSynthetic()) sb.append(" [synthetic]");

            sb.append(formatAnnotations(m));
            sb.append('\n');
        });
        return sb;
    }

    static Set<String> seenTypes = new HashSet<>();

    // td.getSimpleName(), td.getInterfaces(), td.getDeclaredMethods(), etc.
    // — all without Class.forName / classloader involvement
    static void dumpType(TypeDescription td) {
        if (seenTypes.contains(td.getName())) return;
        seenTypes.add(td.getName());

        StringBuilder sb = new StringBuilder();
        sb.append(formatModifiers(td));
        sb.append(td);
        sb.append(formatAnnotations(td));
        sb.append('\n');

        sb.append(indent(dumpFields(td)));
        sb.append(indent(dumpMethods(td)));

        System.out.println(sb.toString());
    }

    static int forcePublic(int access) {
        access &= ~(Opcodes.ACC_PRIVATE | Opcodes.ACC_PROTECTED);
        access |= Opcodes.ACC_PUBLIC;
        return access;
    }

    static byte[] publicize(ClassFileLocator locator, TypeDescription td) {
        // System.out.println("[d] locator = " + locator + ", td = " + td);

        try {
            byte[] bytes = locator.locate(td.getName()).resolve();
            ClassReader cr = new ClassReader(bytes);
            ClassWriter cw = new ClassWriter(0);  // no COMPUTE_FRAMES, no COMPUTE_MAXS

            cr.accept(new ClassVisitor(Opcodes.ASM9, cw) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                    MethodVisitor mv = super.visitMethod(forcePublic(access), name, descriptor, signature, exceptions);
                    if (name.equals("exit")) {
                        return new MethodVisitor(Opcodes.ASM9, mv) {
                            @Override
                            public void visitCode() {
                                // append annotation before the method body is emitted
                                AnnotationVisitor av = mv.visitAnnotation(
                                        "Lme/zed_0xff/zombie_buddy/Patch$OnExit;", true);
                                av.visitEnd();
                                super.visitCode();
                            }
                        };
                    }
                    return mv;
                }
            }, 0);

            return cw.toByteArray();
        } catch (Exception e) {
            System.err.println("Failed to publicize " + td.getName() + ": " + e);
            return null;
        }
    }

    static ArrayList<String> parseArgs(String[] args) {
        ArrayList<String> positionalArgs = new ArrayList<>();
        for (String arg : args) {
            if (!arg.startsWith("--")) {
                positionalArgs.add(arg);
            } else {
                     if (arg.equals("--help")) _showHelp  = true;
                else if (arg.equals("--pub"))  _publicize = true;
                else {
                    System.err.println("Unknown option: " + arg);
                }
            }
        }
        return positionalArgs;
    }

    public static void main(String[] args) throws IOException {
        ArrayList<String> positionalArgs = parseArgs(args);
        if (positionalArgs.size() == 0 || _showHelp) {
            System.out.println("Usage: java -jar JarDump.jar [options] <path_to_jar>");
            System.out.println("Options:");
            System.out.println("    --help  Show this help message");
            System.out.println("    --pub   Mark all methods as public");
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

            jar.stream()
                .map(JarEntry::getName)
                .filter(n -> n.endsWith(".class") && !n.startsWith("META-INF/"))
                .map(n -> n.substring(0, n.length() - 6).replace('/', '.'))  // → binary class name
                .forEach(className -> {
                    TypePool.Resolution res = pool.describe(className);
                    if (res.isResolved()) {
                        TypeDescription td = res.resolve();
                        if ( _publicize ) {
                            byte[] rewritten = publicize(locator, td);
                            if (rewritten != null) {
                                // describe again from the rewritten bytes
                                ClassFileLocator patchedLocator = new ClassFileLocator.Compound(
                                        ClassFileLocator.Simple.of(className, rewritten),
                                        locator   // fallback for annotation types etc.
                                        );
                                td = TypePool.Default.of(patchedLocator).describe(className).resolve();
                            }
                        }
                        dumpType(td);
                    } else {
                        System.err.println("Failed to resolve " + className);
                    }
                });
        }
    }
}
