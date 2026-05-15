package me.zed_0xff.zombie_buddy.jardump;

import me.zed_0xff.zombie_buddy.Logger;
import me.zed_0xff.zombie_buddy.Utils;
import me.zed_0xff.zombie_buddy.transformers.*;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.pool.TypePool;

public class Main extends CLIUtil {
    static boolean _showHelp    = false;
    static boolean _changedOnly = false;

    private record TransInfo(
            String id,
            Supplier<Transformer> factory,
            boolean isDefault,
            String description) {}

    private static final List<TransInfo> TRANS_LIST = List.of(
        new TransInfo("ann",      AnnotationConverter::new,   true,  "Convert ZombieBuddy annotations to ByteBuddy annotations"),
        new TransInfo("pub-all",  Publicizer::new,            false, "Publicize all members unconditionally"),
        new TransInfo("pub-cond", ConditionalPublicizer::new, true,  "Publicize if any annotations were converted by the previous steps"),
        new TransInfo("resolve",  AlternativeResolver::new,   true,  "Resolve alternative names in annotations"),
        new TransInfo("nop",      NoopTransformer::new,       false, "Do nothing (for testing/debugging purposes)")
    );

    private static final Map<String, TransInfo> TRANS_MAP =
        TRANS_LIST.stream().collect(Collectors.toMap(
                    TransInfo::id,
                    Function.identity(),
                    (a, b) -> b,
                    LinkedHashMap::new
                    ));

    private static final ArrayList<String> _transformers = TRANS_LIST.stream()
        .filter(t -> t.isDefault)
        .map(TransInfo::id)
        .collect(Collectors.toCollection(ArrayList::new));

    public static void showHelp() {
        System.out.println("Usage: java -jar JarDump.jar [options] <path_to_jar>");
        System.out.println("Options:");
        System.out.println("    -h, --help         Show this help message");
        System.out.println("    -t, --transformers Specify which transformers to apply (default:all)");
        System.out.println("    -c, --changed-only Dump only classes that were modified by transformers");
        System.out.println();
        System.out.println("transformers:");
        for (TransInfo t : TRANS_LIST) {
            System.out.printf("    %-18s %s%n", t.id(), t.description());
        }
        System.out.println();
        System.out.println("default transformers: " + String.join(", ", _transformers));
    }

    static ArrayList<String> parseArgs(String[] args) {
        ArrayList<String> positionalArgs = new ArrayList<>();
        for(int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (!arg.startsWith("-")) {
                positionalArgs.add(arg);
            } else {
                     if (arg.equals("-h") || arg.equals("--help"))         _showHelp    = true;
                else if (arg.equals("-c") || arg.equals("--changed-only")) _changedOnly = true;
                else if (arg.equals("-t") || arg.equals("--transformers")) {
                    if (i + 1 >= args.length) {
                        System.err.println("Error: Missing value for " + arg);
                        System.exit(1);
                    }
                    _transformers.clear();
                    for (String key : args[++i].split(",")) {
                        if ( TRANS_MAP.containsKey(key) ) {
                            _transformers.add(key);
                        } else {
                            System.err.println("Unknown transformer: " + key);
                            System.exit(1);
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

    public static void processClass(String className, byte[] classBytes, TypePool pool) throws IOException {
        byte[] rewritten      = classBytes.clone();
        ClassContext classCtx = new ClassContext(className, classBytes, pool);

        // instantiate transformers for each class bc they might have internal state
        for (String trans_id : _transformers) {
            Transformer t = TRANS_MAP.get(trans_id).factory().get();

            var result = t.transform(rewritten, classCtx);
            if (result.modified() && result.bytes() != null) {
                rewritten = result.bytes();
            }
        }

        if (classCtx.isChanged() || !_changedOnly) {
            var dumper = new AsmDump(classCtx);
            System.out.println(dumper.dump(rewritten));
        }
    }

    public static void processJar(String fname) throws IOException {
        try (
             JarFile jar   = new JarFile(new File(fname), false);
             // JarFile zbJar = new JarFile(Utils.getCurrentJarPath().toFile(), false);
             ClassFileLocator locator = new ClassFileLocator.Compound(
                 new ClassFileLocator.ForJarFile(jar),       // target
                 ClassFileLocator.ForClassLoader.of(Main.class.getClassLoader()) // ZB + JDK
                 // ClassFileLocator.ForModuleFile.ofBootPath() // JDK
                 );
        ) {
            TypePool pool = TypePool.Default.of(locator);
            jar.stream()
                .map(JarEntry::getName)
                .filter(n -> n.endsWith(".class") && !n.startsWith("META-INF/") && !n.equals("module-info.class"))
                .map(n -> n.substring(0, n.length() - 6).replace('/', '.'))  // → binary class name
                .sorted() // ensures that all parent classes are processed before their nested classes, which is important for the transformers to work correctly
                .forEach(className -> {
                    try {
                        var res = locator.locate(className);
                        if (!res.isResolved()) {
                            System.err.println("Failed to locate class: " + className);
                            return; // returns from lambda, not method
                        }
                        byte[] classBytes = res.resolve();
                        processClass(className, classBytes, pool);
                    } catch (IOException e) {
                        System.err.println("Failed to read class: " + className);
                        e.printStackTrace();
                    }
                });
        }
    }

    public static void processClassFile(String fname) throws IOException {
        String className = fname.endsWith(".class") ? fname.substring(0, fname.length() - 6).replace('/', '.') : fname;
        byte[] bytes = Files.readAllBytes(Path.of(fname));

        ClassFileLocator locator = new ClassFileLocator.Compound(
                new ClassFileLocator.Simple(Map.of(className, bytes)),
                ClassFileLocator.ForClassLoader.ofSystemLoader()
        );

        TypePool pool = TypePool.Default.of(locator);
        processClass(className, bytes, pool);
    }

    public static void main(String[] args) throws IOException {
        Logger.get(null).setLevel(Logger.DEBUG);

        ArrayList<String> positionalArgs = parseArgs(args);
        if (positionalArgs.size() == 0 || _showHelp) {
            showHelp();
            return;
        }

        for (String fname : positionalArgs) {
            System.out.println(fname);
            if (fname.endsWith(".jar")) processJar(fname);
            else if (fname.endsWith(".class")) processClassFile(fname);
            else {
                Logger.error("Unsupported file type: ", fname);
            }
        }
    }
}
