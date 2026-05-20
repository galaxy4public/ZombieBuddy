package me.zed_0xff.zombie_buddy.jardump;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Collectors;

import me.zed_0xff.zombie_buddy.Logger;
import me.zed_0xff.zombie_buddy.Reflect;
import me.zed_0xff.zombie_buddy.Utils;
import me.zed_0xff.zombie_buddy.transformers.ClassContext;
import me.zed_0xff.zombie_buddy.transformers.ConditionalTransformer;
import me.zed_0xff.zombie_buddy.transformers.JarContext;
import me.zed_0xff.zombie_buddy.transformers.Transformer;

public class Main extends CLIUtil {
    static boolean _showHelp    = false;
    static boolean _changedOnly = false;
    static HashSet<String> _classFilter = new HashSet<>();

    private enum Backend {
        AT("at.", "me.zed_0xff.zombie_buddy.transformers.asmtree",   "ASM tree API-based"),
        BB("bb.", "me.zed_0xff.zombie_buddy.transformers.bytebuddy", "ByteBuddy API-based"),
        NO("",    "me.zed_0xff.zombie_buddy.transformers", "");

        final String prefix;
        final String pkg;
        final String description;

        Backend(String prefix, String pkg, String description) {
            this.prefix = prefix;
            this.pkg = pkg;
            this.description = description;
        }

        String pkg()         { return pkg; }
        String prefix()      { return prefix; }
        String description() { return description; }
    }

    private static String transformerIdPrefix(String id) {
        int dot = id.indexOf('.');
        if (dot < 0) return "";

        return id.substring(0, dot);
    }

    private static String transformerIdSuffix(String id) {
        int dot = id.indexOf('.');
        if (dot < 0) return id;

        return id.substring(dot + 1);
    }

    private record TransInfo(
            Backend backend,
            TransSpec spec,
            Supplier<Transformer> factory,
            boolean isDefault,
            String description) {

        public String id() {
            return backend == null ? spec.id() : (backend.prefix() + spec.id());
        }
    }

    private enum TransOpt {
        /** Wrap factory with {@link ConditionalTransformer}. */
        CONDITIONAL,
        /** First backend that resolves this row may be included in the default transformer pipeline. */
        DEFAULT,
    }

    /** Row order: {@link #id()} CLI suffix; {@link #simpleName()} class under each {@link Backend} package; optional {@link TransOpt} flags (last). */
    private record TransSpec(String id, String simpleName, String description, EnumSet<TransOpt> opts) {
        TransSpec(String id, String simpleName, String description) {
            this(id, simpleName, description, EnumSet.noneOf(TransOpt.class));
        }

        TransSpec(String id, String simpleName, String description, TransOpt... opts) {
            this(id, simpleName, description, EnumSet.copyOf(Arrays.asList(opts)));
        }
    }

    private static final TransSpec[] TRANS_SPECS = {
        new TransSpec("convert",  "AnnotationConverter", "Convert ZombieBuddy annotations to ByteBuddy annotations", TransOpt.DEFAULT),
        new TransSpec("pub-all",  "Publicizer",          "Publicize all members unconditionally"),
        new TransSpec("pub-cond", "Publicizer",          "Publicize if any annotations were converted by the previous steps", TransOpt.CONDITIONAL, TransOpt.DEFAULT),
        new TransSpec("resolve",  "Resolver",            "Resolve alternative names in annotations", TransOpt.DEFAULT),
        new TransSpec("noop",     "NoopTransformer",     "Do nothing (for testing/debugging purposes)"),
    };

    private static Supplier<Transformer> conditional(Supplier<? extends Transformer> factory) {
        return () -> new ConditionalTransformer(factory);
    }

    private static Class<?> tryLoadTransformerClass(String pkg, String simpleName) {
        return Reflect.on(pkg + "." + simpleName, Reflect.PUBLIC).getType();
    }

    private static Supplier<Transformer> ctorSupplier(Class<?> cls) {
        return () -> {
            try {
                return (Transformer) cls.getDeclaredConstructor().newInstance();
            } catch (ReflectiveOperationException e) {
                throw new ExceptionInInitializerError(e);
            }
        };
    }

    private static List<TransInfo> buildTransList() {
        ArrayList<TransInfo> out = new ArrayList<>();

        for (TransSpec spec : TRANS_SPECS) {
            boolean found = false;
            boolean defaultAssigned = false;

            for (Backend backend : Backend.values()) {
                Class<?> cls = tryLoadTransformerClass(backend.pkg(), spec.simpleName());
                if (cls == null) continue;

                found = true;

                if (!Transformer.class.isAssignableFrom(cls)) {
                    throw new IllegalStateException("Not a Transformer: " + cls.getName());
                }

                Supplier<Transformer> factory = ctorSupplier(cls);
                if (spec.opts().contains(TransOpt.CONDITIONAL)) factory = conditional(factory);

                boolean isDefault = spec.opts().contains(TransOpt.DEFAULT) && !defaultAssigned;
                if (isDefault) defaultAssigned = true;

                out.add(new TransInfo(backend, spec, factory, isDefault, spec.description()));
            }

            if (!found) {
                throw new IllegalStateException("Transformer class not found in either backend package: " + spec.simpleName());
            }
        }

        return List.copyOf(out);
    }

    private static final List<TransInfo> TRANS_LIST = buildTransList();

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
        System.out.println("    -C, --class CLASS  Dump only the specified class (can be used multiple times)");
        System.out.println();
        System.out.println("transformers:");
        Map<String, List<TransInfo>> bySuffix = TRANS_LIST.stream()
            .filter(t -> !"none".equals(t.id()))
            .collect(Collectors.groupingBy(t -> transformerIdSuffix(t.id()), LinkedHashMap::new, Collectors.toList()));

        var tbl = new CompactTable(3);
        tbl.setAlign(0, CompactTable.Align.RIGHT);
        for (Map.Entry<String, List<TransInfo>> e : bySuffix.entrySet()) {
            List<TransInfo> row = e.getValue();
            LinkedHashSet<String> prefs = row.stream().map(TransInfo::id).map(Main::transformerIdPrefix).collect(Collectors.toCollection(LinkedHashSet::new));

            tbl.addRow(String.join(",", prefs), row.get(0).spec().id(), row.get(0).description());
        }
        System.out.println(indent(tbl.render()));

        for (Backend b : Backend.values()) {
            if (Utils.isBlank(b.prefix())) continue;
            System.out.println(indent(b.prefix().replace(".","") + " - " + b.description()));
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
                else if (arg.equals("-C") || arg.equals("--class"))        _classFilter.add(args[++i]);
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

    public static void processClass(String className, byte[] classBytes, JarContext jctx) throws IOException {
        byte[] rewritten      = classBytes.clone();
        ClassContext classCtx = new ClassContext(className, jctx);

        // instantiate transformers for each class bc they might have internal state
        for (String trans_id : _transformers) {
            Transformer t = TRANS_MAP.get(trans_id).factory().get();

            var result = t.transform(rewritten, classCtx);
            if (result.modified() && result.bytes() != null) {
                rewritten = result.bytes();
            }
        }

        if (classCtx.isChanged() || !_changedOnly) {
            var dumper = new AsmDump(jctx);
            System.out.println(dumper.dump(rewritten));
        }
    }

    public static void processJar(String fname) throws IOException {
        HashMap<String, byte[]> classes = new HashMap<>();

        try (JarFile jar = new JarFile(new File(fname), false)) {
            for (Enumeration<JarEntry> en = jar.entries(); en.hasMoreElements(); ) {
                JarEntry je = en.nextElement();
                String n = je.getName();
                if (!n.endsWith(".class") || n.startsWith("META-INF/") || n.equals("module-info.class")) continue;

                String className = n.substring(0, n.length() - 6).replace('/', '.');
                if (!_classFilter.isEmpty() && !_classFilter.contains(className)) continue;

                try (InputStream in = jar.getInputStream(je)) {
                    classes.put(className, in.readAllBytes());
                }
            }
        }

        ArrayList<String> classNames = new ArrayList<>(classes.keySet());
        Collections.sort(classNames);

        try (JarContext jctx = JarContext.forClasses(classes)) {
            for (String className : classNames) {
                try {
                    byte[] classBytes = jctx.getClassBytes(className);
                    if (classBytes != null) {
                        processClass(className, classBytes, jctx);
                    }
                } catch (IOException e) {
                    System.err.println("Failed to read class: " + className);
                    e.printStackTrace();
                }
            }
        }
    }

    public static void processClassFile(String fname) throws IOException {
        // String className = fname.endsWith(".class") ? fname.substring(0, fname.length() - 6).replace('/', '.') : fname;
        // byte[] bytes = Files.readAllBytes(Path.of(fname));
        //
        // ClassFileLocator locator = new ClassFileLocator.Compound(
        //         new ClassFileLocator.Simple(Map.of(className, bytes)),
        //         ClassFileLocator.ForClassLoader.ofSystemLoader()
        // );
        //
        // TypePool pool = TypePool.Default.of(locator);
        // processClass(className, bytes, pool);
    }

    public static void main(String[] args) throws IOException {
        Logger.get(null).setLevel(Logger.DEBUG);

        ArrayList<String> positionalArgs = parseArgs(args);
        if (positionalArgs.size() == 0 || _showHelp) {
            showHelp();
            return;
        }

        var t0 = System.currentTimeMillis();
        for (String fname : positionalArgs) {
            System.out.println(fname);
            if (fname.endsWith(".jar")) processJar(fname);
            else if (fname.endsWith(".class")) processClassFile(fname);
            else {
                Logger.error("Unsupported file type: ", fname);
            }
        }
        System.out.println("[=] Done in " + (System.currentTimeMillis() - t0) + "ms");
    }
}
