package me.zed_0xff.zombie_buddy.jardump;

import me.zed_0xff.zombie_buddy.Logger;
import me.zed_0xff.zombie_buddy.Utils;
import me.zed_0xff.zombie_buddy.transformers.*;

import java.io.File;
import java.io.IOException;
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

    private static class CondPublicizer extends Publicizer {}

    private record TransInfo(
            String id,
            Supplier<Transformer> factory,
            boolean isDefault,
            String description) {}

    private static final List<TransInfo> TRANS_LIST = List.of(
        new TransInfo("ann",      AnnotationConverter::new, true,  "Convert ZombieBuddy annotations to ByteBuddy annotations"),
        new TransInfo("pub-all",  Publicizer::new,          false, "Publicize all members unconditionally"),
        new TransInfo("pub-cond", CondPublicizer::new,      true,  "Publicize if any annotations were converted by the previous steps"),
        new TransInfo("nop",      NoopTransformer::new,     false, "Do nothing (for testing/debugging purposes)")
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

    public static void processFile(String fname) throws IOException {
        System.out.println(fname);
        try (
             JarFile jar   = new JarFile(new File(fname), false);
             JarFile zbJar = new JarFile(Utils.getCurrentJarPath().toFile(), false);
             ClassFileLocator locator = new ClassFileLocator.Compound(
                 new ClassFileLocator.ForJarFile(jar),       // target
                 ClassFileLocator.ForClassLoader.of(Main.class.getClassLoader()) // ZB + JDK
//                 ClassFileLocator.ForModuleFile.ofBootPath() // JDK
                 );
        ) {
            List<Transformer> transformers = _transformers.stream()
                .map(TRANS_MAP::get)
                .map(t -> t.factory().get())
                .toList();
            TypePool pool = TypePool.Default.of(locator);
            var dumper = new AsmDump(pool);
            // TypePool pool = TypePool.Default.of(locator, TypePool.ClassLoading.ofBootPath());
            jar.stream()
                .map(JarEntry::getName)
                .filter(n -> n.endsWith(".class") && !n.startsWith("META-INF/") && !n.equals("module-info.class"))
                .map(n -> n.substring(0, n.length() - 6).replace('/', '.'))  // → binary class name
                .forEach(className -> {
                    try {
                        byte[] classBytes   = locator.locate(className).resolve();
                        byte[] rewritten    = classBytes.clone();
                        boolean changed     = false;
                        boolean ann_changed = false;

                        for (Transformer t : transformers) {
                            if (t instanceof CondPublicizer && !ann_changed) {
                                continue; // skip if no changes from previous transformers
                            }
                            var result = t.transform(rewritten);
                            if (result.modified() && result.bytes() != null) {
                                rewritten = result.bytes();
                                changed = true;
                                if (t instanceof AnnotationConverter) {
                                    ann_changed = true;
                                }
                            }
                        }

                        if (changed || !_changedOnly) {
                            System.out.println(dumper.dump(rewritten));
                        }
                    } catch (IOException e) {
                        System.err.println("Failed to read class: " + className);
                        e.printStackTrace();
                    }
                });
        }
    }

    public static void main(String[] args) throws IOException {
        Logger.get(null).setLevel(Logger.DEBUG);

        ArrayList<String> positionalArgs = parseArgs(args);
        if (positionalArgs.size() == 0 || _showHelp) {
            showHelp();
            return;
        }

        for (String fname : positionalArgs) {
            processFile(fname);
        }
    }
}
