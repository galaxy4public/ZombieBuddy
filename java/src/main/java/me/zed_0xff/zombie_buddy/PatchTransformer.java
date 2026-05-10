package me.zed_0xff.zombie_buddy;

import java.io.IOException;
import java.lang.instrument.Instrumentation;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.bytebuddy.jar.asm.commons.ClassRemapper;
import net.bytebuddy.jar.asm.commons.SimpleRemapper;

import net.bytebuddy.dynamic.loading.ByteArrayClassLoader;

import net.bytebuddy.asm.Advice;
import net.bytebuddy.jar.asm.AnnotationVisitor;
import net.bytebuddy.jar.asm.ClassReader;
import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.jar.asm.ClassWriter;
import net.bytebuddy.jar.asm.Label;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;
import net.bytebuddy.jar.asm.Type;

/**
 * Transforms patch classes by replacing Patch.* annotations with ByteBuddy equivalents.
 */
final class PatchTransformer {
    private static final Type NO_EXCEPTION_HANDLER = Type.getType("Lnet/bytebuddy/asm/Advice$NoExceptionHandler;"); // private

    // Track the class currently being transformed in a new-load callback. When Class.forName is
    // called on this same class from inside the callback, it triggers a circular inner load that
    // defines the class prematurely; the outer defineClass then fails with "duplicate class
    // definition". forName() uses these to avoid loading a class that's still being defined.
    private static final ThreadLocal<String>      g_transformingClass  = new ThreadLocal<>();
    private static final ThreadLocal<ClassLoader> g_transformingLoader = new ThreadLocal<>();

    private static final Map<String, String> ADVICE_DESCRIPTOR_MAP;
    private static final Map<String, String> DELEGATION_DESCRIPTOR_MAP;
    private static final Map<Type, Type> TYPE_MAP;

    static {
        final Map<String, String> common_map = new HashMap<>();
        common_map.put(Type.getDescriptor(Patch.This.class),         Type.getDescriptor(Advice.This.class));
        common_map.put(Type.getDescriptor(Patch.Adapter.class),      Type.getDescriptor(Advice.Local.class));
        common_map.put(Type.getDescriptor(Patch.Argument.class),     Type.getDescriptor(Advice.Argument.class));
        common_map.put(Type.getDescriptor(Patch.AllArguments.class), Type.getDescriptor(Advice.AllArguments.class));
        common_map.put(Type.getDescriptor(Patch.Field.class),        Type.getDescriptor(Advice.FieldValue.class));
        common_map.put(Type.getDescriptor(Patch.FieldRW.class),      Type.getDescriptor(Advice.FieldValue.class));

        ADVICE_DESCRIPTOR_MAP = new HashMap<>(common_map);
        ADVICE_DESCRIPTOR_MAP.put(Type.getDescriptor(Patch.Local.class),   Type.getDescriptor(Advice.Local.class));
        ADVICE_DESCRIPTOR_MAP.put(Type.getDescriptor(Patch.OnEnter.class), Type.getDescriptor(Advice.OnMethodEnter.class));
        ADVICE_DESCRIPTOR_MAP.put(Type.getDescriptor(Patch.OnExit.class),  Type.getDescriptor(Advice.OnMethodExit.class));
        ADVICE_DESCRIPTOR_MAP.put(Type.getDescriptor(Patch.Return.class),  Type.getDescriptor(Advice.Return.class));
        ADVICE_DESCRIPTOR_MAP.put(Type.getDescriptor(Patch.Thrown.class),  Type.getDescriptor(Advice.Thrown.class));

        DELEGATION_DESCRIPTOR_MAP = new HashMap<>(common_map);
        DELEGATION_DESCRIPTOR_MAP.put(Type.getDescriptor(Patch.AllArguments.class), Type.getDescriptor(net.bytebuddy.implementation.bind.annotation.AllArguments.class));
        DELEGATION_DESCRIPTOR_MAP.put(Type.getDescriptor(Patch.Argument.class),     Type.getDescriptor(net.bytebuddy.implementation.bind.annotation.Argument.class));
        DELEGATION_DESCRIPTOR_MAP.put(Type.getDescriptor(Patch.RuntimeType.class),  Type.getDescriptor(net.bytebuddy.implementation.bind.annotation.RuntimeType.class));
        DELEGATION_DESCRIPTOR_MAP.put(Type.getDescriptor(Patch.SuperCall.class),    Type.getDescriptor(net.bytebuddy.implementation.bind.annotation.SuperCall.class));
        DELEGATION_DESCRIPTOR_MAP.put(Type.getDescriptor(Patch.SuperMethod.class),  Type.getDescriptor(net.bytebuddy.implementation.bind.annotation.SuperMethod.class));
        DELEGATION_DESCRIPTOR_MAP.put(Type.getDescriptor(Patch.This.class),         Type.getDescriptor(net.bytebuddy.implementation.bind.annotation.This.class));

        TYPE_MAP = new HashMap<>();
        TYPE_MAP.put(Type.getType(Patch.NoException.class),       NO_EXCEPTION_HANDLER);
        TYPE_MAP.put(Type.getType(Patch.OnDefaultValue.class),    Type.getType(Advice.OnDefaultValue.class));
        TYPE_MAP.put(Type.getType(Patch.OnNonDefaultValue.class), Type.getType(Advice.OnNonDefaultValue.class));
    }

    private record MemberHandleInfo(String targetClass, String[] candidates, boolean optional,
                                    Class<?> returnType, Class<?>[] parameterTypes, boolean isVarHandle) {}

    /**
     * Transforms a patch class: replaces Patch.* annotations with ByteBuddy equivalents and
     * Returns null if a non-optional MemberHandle cannot be resolved (caller should drop the patch).
     */
    public static Class<?> transformPatchClass(Class<?> patchClass, Instrumentation instrumentation, int verbosity, boolean isMethodDelegation) {
        Patch patchAnn0 = patchClass.getAnnotation(Patch.class);
        String targetCls0 = (patchAnn0 != null) ? patchAnn0.className() : "";
        String prevClass = g_transformingClass.get();
        ClassLoader prevLoader = g_transformingLoader.get();
        if (!targetCls0.isEmpty()) {
            g_transformingClass.set(targetCls0);
            g_transformingLoader.set(patchClass.getClassLoader());
        }
        try {
        return transformPatchClassInner(patchClass, instrumentation, verbosity, isMethodDelegation);
        } finally {
            if (prevClass != null) { g_transformingClass.set(prevClass); g_transformingLoader.set(prevLoader); }
            else { g_transformingClass.remove(); g_transformingLoader.remove(); }
        }
    }

    private static Class<?> transformPatchClassInner(Class<?> patchClass, Instrumentation instrumentation, int verbosity, boolean isMethodDelegation) {
        try {
            boolean needsTransformation = false;
            for (Method method : patchClass.getDeclaredMethods()) {
                boolean hasOnEnter     = method.isAnnotationPresent(Patch.OnEnter.class);
                boolean hasOnExit      = method.isAnnotationPresent(Patch.OnExit.class);
                boolean hasRuntimeType = method.isAnnotationPresent(Patch.RuntimeType.class);
                if (hasOnEnter || hasOnExit || hasRuntimeType) needsTransformation = true;
                if (hasOnEnter || hasOnExit) {
                    if (method.getReturnType() != void.class) {
                        boolean hasSkipOnSet = false;
                        if (hasOnEnter && method.getAnnotation(Patch.OnEnter.class).skipOn()) hasSkipOnSet = true;
                        if (!hasSkipOnSet) {
                            Logger.error("!!!!!!!");
                            Logger.error("WARNING: Annotated method " + method.getName() + "() in patch class " + patchClass.getName() +
                                " returns non-void. This may cause UB and diarrhea.");
                            Logger.error("!!!!!!!");
                        }
                    }
                }
                if (!needsTransformation) {
                    for (java.lang.reflect.Parameter param : method.getParameters()) {
                        if (param.isAnnotationPresent(Patch.Adapter.class)     ||
                            param.isAnnotationPresent(Patch.Argument.class)    ||
                            param.isAnnotationPresent(Patch.Field.class)       ||
                            param.isAnnotationPresent(Patch.FieldRW.class)     ||
                            param.isAnnotationPresent(Patch.Local.class)       ||
                            param.isAnnotationPresent(Patch.SuperCall.class)   ||
                            param.isAnnotationPresent(Patch.SuperMethod.class) ||
                            param.isAnnotationPresent(Patch.This.class)        ||
                            param.isAnnotationPresent(Patch.Thrown.class)      ||
                            param.isAnnotationPresent(Patch.Return.class)) {
                            needsTransformation = true;
                            break;
                        }
                    }
                }
            }

            Map<String, String> typeAliases = new HashMap<>();
            for (Class<?> inner : patchClass.getDeclaredClasses()) {
                Patch.TypeAlias ann = inner.getAnnotation(Patch.TypeAlias.class);
                if (ann == null) continue;
                typeAliases.put(Utils.toInternalName(inner), Utils.toInternalName(ann.value()));
                needsTransformation = true;
            }

            Patch patchAnn = patchClass.getAnnotation(Patch.class);
            String defaultTargetCls = (patchAnn != null) ? patchAnn.className() : "";

            record StaticAlias(String owner, String resolvedName, boolean readOnly) {}
            Map<String, StaticAlias> staticAliases = new HashMap<>();
            for (java.lang.reflect.Field f : patchClass.getDeclaredFields()) {
                Patch.StaticFieldAlias    ann   = f.getAnnotation(Patch.StaticFieldAlias.class);
                Patch.StaticFieldAliasRW  annRW = f.getAnnotation(Patch.StaticFieldAliasRW.class);
                if (ann == null && annRW == null) continue;
                String   targetClass = ann != null
                    ? (ann.className().isEmpty()   ? defaultTargetCls : ann.className())
                    : (annRW.className().isEmpty()  ? defaultTargetCls : annRW.className());
                boolean  readOnly    = ann != null && ann.readOnly();
                String[] candidates  = ann != null ? ann.value() : annRW.value();
                if (targetClass.isEmpty()) { Logger.warn("StaticFieldAlias " + f.getName() + " has no target class"); continue; }
                String resolvedName;
                if (candidates.length == 0) {
                    resolvedName = f.getName();
                } else if (candidates.length == 1) {
                    resolvedName = candidates[0];
                } else {
                    resolvedName = resolveFieldName(Arrays.asList(candidates), targetClass, f.getName());
                    if (resolvedName == null) resolvedName = candidates[0];
                }
                staticAliases.put(f.getName(), new StaticAlias(Utils.toInternalName(targetClass), resolvedName, readOnly));
                needsTransformation = true;
            }

            Map<String, MemberHandleInfo> memberHandles = new HashMap<>();
            for (java.lang.reflect.Field f : patchClass.getDeclaredFields()) {
                Patch.MemberHandle ann = f.getAnnotation(Patch.MemberHandle.class);
                if (ann == null) continue;
                String tc   = ann.owner() != void.class ? ann.owner().getName()
                            : ann.className().isEmpty()  ? defaultTargetCls
                            : ann.className();
                String[] cs = ann.value().length > 0 ? ann.value() : ann.name().length > 0 ? ann.name() : new String[]{ f.getName() };
                memberHandles.put(f.getName(), new MemberHandleInfo(tc, cs, ann.optional(), ann.returnType(), ann.parameterTypes(), f.getType() == VarHandle.class));
                needsTransformation = true;
            }

            if (!needsTransformation) {
                if (verbosity > 1) Logger.info("class " + patchClass.getName() + " needs transformation: false");
                return patchClass;
            }

            if (verbosity > 1) Logger.info("class " + patchClass.getName() + " needs transformation: true");

            final String patchOwner = Utils.toInternalName(patchClass);
            String resourceName = patchOwner + ".class";
            byte[] classBytes;
            try (var is = patchClass.getClassLoader().getResourceAsStream(resourceName)) {
                if (is == null) { Logger.error("Could not read class file for " + patchClass.getName()); return patchClass; }
                classBytes = is.readAllBytes();
            } catch (IOException e) {
                Logger.error("Could not read class file for " + patchClass.getName() + ": " + e.getMessage());
                return patchClass;
            }

            final boolean isDelegation = isMethodDelegation;
            final Map<String, String[]> paramNames = collectParamNames(classBytes);
            final Map<String, String> fieldResolutions = new HashMap<>(); // paramName -> resolvedFieldName
            final boolean[] hasUnresolvableField = {false};

            ClassReader cr = new ClassReader(classBytes);
            ClassWriter cw = typeAliases.isEmpty()
                ? new ClassWriter(cr, ClassWriter.COMPUTE_FRAMES)
                : new ClassWriter(ClassWriter.COMPUTE_FRAMES);
            ClassVisitor sink = cw;
            if (!typeAliases.isEmpty()) {
                final Set<String> stubNames = typeAliases.keySet();
                sink = new ClassRemapper(cw, new SimpleRemapper(Opcodes.ASM9, typeAliases)) {
                    @Override public void visitInnerClass(String name, String outerName, String innerName, int access) {
                        if (!stubNames.contains(name)) super.visitInnerClass(name, outerName, innerName, access);
                    }
                };
                if (verbosity > 0) Logger.debug("TypeAliases: " + typeAliases);
            }
            cr.accept(new ClassVisitor(Opcodes.ASM9, sink) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                    final String mName = name;
                    final String mDesc = descriptor;
                    MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
                    return new MethodVisitor(Opcodes.ASM9, mv) {
                        @Override
                        public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                            String newDescriptor = rewriteAnnotationDescriptor(descriptor, isDelegation);
                            AnnotationVisitor av = super.visitAnnotation(newDescriptor, visible);
                            if (!newDescriptor.equals(descriptor)) {
                                return new AnnotationVisitor(Opcodes.ASM9, av) {
                                    @Override
                                    public void visit(String name, Object value) {
                                        if ("skipOn".equals(name) && value instanceof Boolean skip) {
                                            value = skip ? Type.getType(Advice.OnNonDefaultValue.class) : NO_EXCEPTION_HANDLER;
                                        } else if (value instanceof Type type) {
                                            value = TYPE_MAP.getOrDefault(type, type);
                                        }
                                        super.visit(name, value);
                                    }
                                };
                            }
                            return av;
                        }

                        @Override
                        public AnnotationVisitor visitParameterAnnotation(int parameter, String descriptor, boolean visible) {
                            AnnotationVisitor av = super.visitParameterAnnotation(parameter, rewriteAnnotationDescriptor(descriptor, isDelegation), visible);
                            boolean isField      = Type.getDescriptor(Patch.Field.class).equals(descriptor);
                            boolean isFieldRW    = Type.getDescriptor(Patch.FieldRW.class).equals(descriptor);
                            boolean isAdapter    = Type.getDescriptor(Patch.Adapter.class).equals(descriptor);

                            if (isField || isFieldRW) {
                                String[] mParams = paramNames.get(mName + mDesc);
                                String inferred  = (mParams != null && parameter < mParams.length) ? mParams[parameter] : null;
                                return new AnnotationVisitor(Opcodes.ASM9, av) {
                                    private final List<String> candidates = new ArrayList<>();
                                    @Override public AnnotationVisitor visitArray(String n) {
                                        if ("value".equals(n) || "name".equals(n)) {
                                            return new AnnotationVisitor(Opcodes.ASM9) {
                                                @Override public void visit(String ignored, Object v) {
                                                    if (v instanceof String s) candidates.add(s);
                                                }
                                            };
                                        }
                                        return super.visitArray(n);
                                    }
                                    @Override public void visit(String n, Object v) {
                                        if ("optional".equals(n)) return; // Advice.FieldValue has no optional
                                        super.visit(n, v);
                                    }
                                    @Override public void visitEnd() {
                                        String resolved;
                                        if (candidates.isEmpty()) {
                                            resolved = inferred;
                                        } else if (candidates.size() == 1) {
                                            resolved = candidates.get(0);
                                        } else {
                                            resolved = resolveFieldName(candidates, defaultTargetCls, descriptor);
                                            if (resolved == null) resolved = candidates.get(0);
                                        }
                                        if (resolved == null) {
                                            Logger.error("@Patch.Field on parameter " + parameter + " of " + mName +
                                                " in " + patchClass.getName() + ": cannot infer field name" +
                                                " (no LocalVariableTable? compile with -g or specify @Patch.Field(\"fieldName\") explicitly)");
                                            hasUnresolvableField[0] = true;
                                            super.visitEnd();
                                            return;
                                        }
                                        if (inferred != null) fieldResolutions.putIfAbsent(inferred, resolved);
                                        super.visit("value", resolved);
                                        if (isFieldRW) super.visit("readOnly", false);
                                        super.visitEnd();
                                    }
                                };
                            } else if (isAdapter) {
                            }
                            return av;
                        }

                        @Override
                        public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
                            if (!staticAliases.isEmpty() && patchOwner.equals(owner)) {
                                StaticAlias alias = staticAliases.get(name);
                                if (alias != null && (opcode == Opcodes.GETSTATIC || (opcode == Opcodes.PUTSTATIC && !alias.readOnly()))) {
                                    super.visitFieldInsn(opcode, alias.owner(), alias.resolvedName(), descriptor);
                                    return;
                                }
                            }
                            super.visitFieldInsn(opcode, owner, name, descriptor);
                        }
                    };
                }
            }, ClassReader.EXPAND_FRAMES);

            if (hasUnresolvableField[0]) {
                Logger.error("Dropping patch class " + patchClass.getName() + " due to unresolvable @Patch.Field");
                return null;
            }

            byte[] transformedBytes = cw.toByteArray();

            if (instrumentation != null) {
                try {
                    instrumentation.redefineClasses(new java.lang.instrument.ClassDefinition(patchClass, transformedBytes));
                } catch (Exception e) {
                    // Non-fatal: ChildFirst loader carries the updated bytes. Failure is expected when
                    // TypeAlias remapping changes NestHost/NestMembers attributes (JVM forbids that change).
                    Logger.debug("Could not redefine patch class " + patchClass.getName() + " (non-fatal): " + e.getMessage());
                }
            }

            // Load in a fresh ChildFirst ClassLoader so Java reflection sees updated annotations
            // (HotSpot does not reliably refresh parameter annotations after redefineClasses alone).
            try {
                ClassLoader freshLoader = new ByteArrayClassLoader.ChildFirst(
                        patchClass.getClassLoader(),
                        Collections.singletonMap(patchClass.getName(), transformedBytes),
                        ByteArrayClassLoader.PersistenceHandler.MANIFEST);
                Class<?> freshClass = freshLoader.loadClass(patchClass.getName());
                populateResolvedFields(freshClass, defaultTargetCls, fieldResolutions);
                if (!memberHandles.isEmpty() && !populateMemberHandles(freshClass, patchClass, memberHandles)) return null;
                return freshClass;
            } catch (Exception e) {
                Logger.error("Failed to load transformed class " + patchClass.getName() + ": " + e.getMessage());
                if (verbosity > 0) Logger.printStackTrace(e);
            }

            return patchClass;
        } catch (Exception e) {
            Logger.error("Failed to transform patch class " + patchClass.getName() + ": " + e.getMessage());
            if (verbosity > 0) Logger.printStackTrace(e);
            return patchClass;
        }
    }

    private static String rewriteAnnotationDescriptor(String descriptor, boolean isMethodDelegation) {
        Map<String, String> map = isMethodDelegation ? DELEGATION_DESCRIPTOR_MAP : ADVICE_DESCRIPTOR_MAP;
        return map.getOrDefault(descriptor, descriptor);
    }

    /**
     * If the transformed patch class declares a {@code static Map<String, String> ZB_RESOLVED_FIELDS},
     * fills it from the pre-collected {@code fieldResolutions} map (paramName → resolvedFieldName)
     * that was built during the ASM pass.
     */
    @SuppressWarnings("unchecked")
    private static void populateResolvedFields(Class<?> transformedClass, String targetClassName, Map<String, String> fieldResolutions) {
        if (fieldResolutions.isEmpty()) return;
        Field mapField;
        try {
            mapField = transformedClass.getDeclaredField("ZB_RESOLVED_FIELDS");
        } catch (NoSuchFieldException e) {
            return;
        }
        if (!java.util.Map.class.isAssignableFrom(mapField.getType())) return;
        mapField.setAccessible(true);
        java.util.Map<String, String> map;
        try {
            map = (java.util.Map<String, String>) mapField.get(null);
        } catch (IllegalAccessException e) {
            return;
        }
        if (map != null) map.putAll(fieldResolutions);
    }

    /** Injects resolved {@link MethodHandle}s into static fields annotated with {@code @Patch.MemberHandle}.
     *  Sets the field on both {@code freshClass} (used by ByteBuddy for advice reading) and {@code originalClass}
     *  (whose static fields are accessed at runtime when the inlined advice executes in the target class).
     *  Returns false if any non-optional handle could not be resolved (caller should drop the patch). */
    private static boolean populateMemberHandles(Class<?> freshClass, Class<?> originalClass, Map<String, MemberHandleInfo> infos) {
        MethodHandles.Lookup lookup = MethodHandles.lookup();
        boolean allOk = true;
        for (Map.Entry<String, MemberHandleInfo> entry : infos.entrySet()) {
            String fieldName = entry.getKey();
            MemberHandleInfo info = entry.getValue();
            Object handle = resolveMemberHandle(info, lookup);
            if (handle == null) {
                if (!info.optional()) {
                    Logger.error("MemberHandle not resolved: " + fieldName + " in " + info.targetClass());
                    allOk = false;
                }
                continue;
            }
            setStaticField(freshClass, fieldName, handle);
            if (originalClass != freshClass) setStaticField(originalClass, fieldName, handle);
        }
        return allOk;
    }

    private static void setStaticField(Class<?> cls, String fieldName, Object value) {
        try {
            java.lang.reflect.Field f = cls.getDeclaredField(fieldName);
            f.setAccessible(true);
            f.set(null, value);
        } catch (Exception e) {
            Logger.warn("Failed to set field " + fieldName + " on " + cls.getName() + ": " + e.getMessage());
        }
    }

    private static Object resolveMemberHandle(MemberHandleInfo info, MethodHandles.Lookup lookup) {
        if (info.targetClass().isEmpty()) { Logger.warn("MemberHandle has no target class"); return null; }
        Class<?> targetClass = forName(info.targetClass());
        if (targetClass == null) { Logger.warn("MemberHandle target class not found or not yet loaded: " + info.targetClass()); return null; }
        if (info.isVarHandle()) {
            for (String name : info.candidates()) {
                Field f = findMemberHandleField(targetClass, name);
                if (f != null) {
                    try { f.setAccessible(true); return lookup.unreflectVarHandle(f); }
                    catch (Exception e) { Logger.warn("MemberHandle(VarHandle) unreflect failed for " + name + ": " + e.getMessage()); }
                }
            }
            return null;
        }
        // Use signature only when caller specified something non-default
        boolean hasSignature = info.returnType() != void.class || info.parameterTypes().length > 0;
        for (String name : info.candidates()) {
            Method m = findMemberHandleMethod(targetClass, name, hasSignature ? info.returnType() : null, info.parameterTypes());
            if (m != null) {
                try { m.setAccessible(true); return lookup.unreflect(m); }
                catch (Exception e) { Logger.warn("MemberHandle unreflect failed for " + name + ": " + e.getMessage()); }
            }
        }
        return null;
    }

    /** Class.forName that avoids circular loading: if {@code name} equals the class currently being
     *  transformed (new-load callback), uses findAlreadyLoadedClass to avoid a premature defineClass
     *  that would cause "duplicate class definition" when the outer defineClass fires. Returns null
     *  on ClassNotFoundException, LinkageError, or when the class is still being loaded. */
    private static Class<?> forName(String name) {
        String busy = g_transformingClass.get();
        if (busy != null && busy.equals(name)) {
            return findAlreadyLoadedClass(g_transformingLoader.get(), name);
        }
        try { return Class.forName(name); }
        catch (ClassNotFoundException | LinkageError e) { return null; }
    }

    /** Returns the class if already loaded by {@code cl} without triggering a new class load. */
    private static Class<?> findAlreadyLoadedClass(ClassLoader cl, String name) {
        try {
            java.lang.reflect.Method m = ClassLoader.class.getDeclaredMethod("findLoadedClass", String.class);
            m.setAccessible(true);
            return (Class<?>) m.invoke(cl, name);
        } catch (Exception e) {
            return null;
        }
    }

    private static Field findMemberHandleField(Class<?> cls, String name) {
        for (Class<?> c = cls; c != null; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (f.getName().equals(name)) return f;
            }
        }
        return null;
    }

    private static Method findMemberHandleMethod(Class<?> cls, String name, Class<?> returnType, Class<?>[] params) {
        for (Class<?> c = cls; c != null; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (!m.getName().equals(name)) continue;
                if (returnType != null && !m.getReturnType().equals(returnType)) continue;
                if (!Arrays.equals(m.getParameterTypes(), params)) continue;
                return m;
            }
        }
        return null;
    }

    /** Returns the first name from {@code candidates} that exists as a field on {@code targetClassName} or any superclass. */
    private static String resolveFieldName(List<String> candidates, String targetClassName, String fieldName) {
        if (targetClassName.isEmpty()) return null;
        Class<?> cls = forName(targetClassName);
        if (cls == null) {
            Logger.warn("Field " + fieldName + ": target class " + targetClassName + " not found or not yet loaded");
            return null;
        }
        for (String name : candidates) {
            for (Class<?> c = cls; c != null; c = c.getSuperclass()) {
                try { c.getDeclaredField(name); return name; } catch (NoSuchFieldException ignored) {}
            }
        }
        return null;
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
}
