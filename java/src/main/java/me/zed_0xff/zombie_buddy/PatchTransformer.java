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
import java.util.function.BiFunction;

import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.loading.ByteArrayClassLoader;
import net.bytebuddy.jar.asm.AnnotationVisitor;
import net.bytebuddy.jar.asm.ClassReader;
import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.jar.asm.ClassWriter;
import net.bytebuddy.jar.asm.commons.ClassRemapper;
import net.bytebuddy.jar.asm.commons.SimpleRemapper;
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
    // definition". findClass() uses these to avoid loading a class that's still being defined.
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

    private abstract interface IMemberHandle {
        String targetClass();
        String[] candidates();
        boolean optional();
    }

    private interface IVarHandle extends IMemberHandle {
        Class<?> fieldType();
    }

    private interface IMethodHandle extends IMemberHandle {
        Class<?> returnType();
        Class<?>[] parameterTypes();
    }

    private record MemberHandleInfo (
            String targetClass,
            String[] candidates,
            boolean optional,

            Class<?> returnType,
            Class<?>[] parameterTypes
    ) implements IMethodHandle {}

    private record VarHandleInfo (
            String targetClass,
            String[] candidates,
            boolean optional,

            Class<?> fieldType
    ) implements IVarHandle {}

    /**
     * Transforms a patch class: replaces Patch.* annotations with ByteBuddy equivalents and
     * Returns null if a non-optional MemberHandle cannot be resolved (caller should drop the patch).
     */
    public static Class<?> transformPatch(Class<?> patchClass, TypeDescription td, int verbosity, boolean isMethodDelegation) {
        Patch patchAnn0 = patchClass.getAnnotation(Patch.class);
        String targetCls0 = (patchAnn0 != null) ? patchAnn0.className() : "";
        String prevClass = g_transformingClass.get();
        ClassLoader prevLoader = g_transformingLoader.get();
        if (!targetCls0.isEmpty()) {
            g_transformingClass.set(targetCls0);
            g_transformingLoader.set(patchClass.getClassLoader());
        }
        try {
            return transformPatchClassInner(patchClass, td, verbosity, isMethodDelegation);
        } finally {
            if (prevClass != null) { g_transformingClass.set(prevClass); g_transformingLoader.set(prevLoader); }
            else { g_transformingClass.remove(); g_transformingLoader.remove(); }
        }
    }

    private static Class<?> transformPatchClassInner(Class<?> patchClass, TypeDescription td, int verbosity, boolean isMethodDelegation) {
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
                        if (param.isAnnotationPresent(Patch.Adapter.class)      ||
                            param.isAnnotationPresent(Patch.Argument.class)     ||
                            param.isAnnotationPresent(Patch.Field.class)        ||
                            param.isAnnotationPresent(Patch.FieldRW.class)      ||
                            param.isAnnotationPresent(Patch.Local.class)        ||
                            param.isAnnotationPresent(Patch.MemberHandle.class) ||
                            param.isAnnotationPresent(Patch.SuperCall.class)    ||
                            param.isAnnotationPresent(Patch.SuperMethod.class)  ||
                            param.isAnnotationPresent(Patch.This.class)         ||
                            param.isAnnotationPresent(Patch.Thrown.class)       ||
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

            Map<String, IMemberHandle> memberHandles = new HashMap<>();
            for (java.lang.reflect.Field f : patchClass.getDeclaredFields()) {
                Patch.MemberHandle ann = f.getAnnotation(Patch.MemberHandle.class);
                if (ann == null) continue;
                String tc   = ann.owner() != void.class ? ann.owner().getName()
                            : ann.className().isEmpty()  ? defaultTargetCls
                            : ann.className();
                String[] cs = ann.value().length > 0 ? ann.value() : ann.name().length > 0 ? ann.name() : new String[]{ f.getName() };
                IMemberHandle info = f.getType() == java.lang.invoke.VarHandle.class
                    ? new VarHandleInfo(tc, cs, ann.optional(), ann.type())
                    : new MemberHandleInfo(tc, cs, ann.optional(), ann.returnType(), ann.parameterTypes());
                memberHandles.put(f.getName(), info);
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
            final Map<String, Map<String, String>> methodResolutions = new HashMap<>(); // methodName -> (paramName -> resolvedFieldName)
            final boolean[] hasUnresolvableField = {false};

            // Pre-scan parameter-level @Patch.MemberHandle and @Patch.NameMap annotations.
            // Maps (methodName+descriptor) -> (paramSlot -> storeKey).
            final Map<String, Map<Integer, String>> paramAllHandleSlots = new HashMap<>();
            final Map<String, IMemberHandle> paramHandleInfos = new HashMap<>();
            final Map<String, Map<Integer, String>> paramNameMapSlots = new HashMap<>(); // slot -> storeKey for @Patch.NameMap params
            final Map<String, String> nameMapKeyToMethod = new HashMap<>();              // storeKey -> methodName
            final boolean[] hasUnresolvableParamHandle = {false};
            for (Method method : patchClass.getDeclaredMethods()) {
                java.lang.annotation.Annotation[][] paramAnns = method.getParameterAnnotations();
                if (paramAnns.length == 0) continue;
                boolean isStaticM = java.lang.reflect.Modifier.isStatic(method.getModifiers());
                String mKey = method.getName() + Type.getMethodDescriptor(method);
                String[] pNames = paramNames.get(mKey);
                Type[] argTypes = Type.getArgumentTypes(Type.getMethodDescriptor(method));
                int slot = isStaticM ? 0 : 1;
                for (int pi = 0; pi < paramAnns.length; pi++) {
                    for (java.lang.annotation.Annotation a : paramAnns[pi]) {
                        if (a instanceof Patch.NameMap) {
                            String storeKey = patchClass.getName() + "#" + method.getName() + "#" + pi;
                            paramNameMapSlots.computeIfAbsent(mKey, k -> new HashMap<>()).put(slot, storeKey);
                            nameMapKeyToMethod.put(storeKey, method.getName());
                            continue;
                        }
                        if (!(a instanceof Patch.MemberHandle mh)) continue;
                        boolean isVarHandleParam = method.getParameterTypes()[pi] == java.lang.invoke.VarHandle.class;
                        String pName = (pNames != null && pi < pNames.length && pNames[pi] != null) ? pNames[pi] : null;
                        String tc = mh.owner() != void.class  ? mh.owner().getName()
                                  : !mh.className().isEmpty() ? mh.className()
                                  : defaultTargetCls;

                        String[] cs = mh.value().length > 0 ? mh.value()
                                    : mh.name().length > 0  ? mh.name()
                                    : pName != null         ? new String[]{pName}
                                    : new String[]{};

                        if (tc.isEmpty() || cs.length == 0) {
                            Logger.error("@Patch.MemberHandle on param " + pi + " of " + method.getName() + " in " + patchClass.getName() +
                                (tc.isEmpty() ? ": no target class" : ": no candidate names (specify value/name or compile with -g)"));
                            if (!mh.optional()) hasUnresolvableParamHandle[0] = true;
                            break;
                        }
                        String storeKey = patchClass.getName() + "#" + method.getName() + "#" + pi;
                        paramAllHandleSlots.computeIfAbsent(mKey, k -> new HashMap<>()).put(slot, storeKey);
                        paramHandleInfos.put(storeKey, isVarHandleParam
                            ? new VarHandleInfo(tc, cs, mh.optional(), mh.type())
                            : new MemberHandleInfo(tc, cs, mh.optional(), mh.returnType(), mh.parameterTypes()));
                    }
                    if (pi < argTypes.length) slot += argTypes[pi].getSize();
                }
            }
            if (hasUnresolvableParamHandle[0]) {
                Logger.error("Dropping patch class " + patchClass.getName() + " due to unresolvable parameter-level @Patch.MemberHandle");
                return null;
            }

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
                    final int mAccess = access;
                    MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
                    return new MethodVisitor(Opcodes.ASM9, mv) {
                        // slot -> storeKey for @Patch.MemberHandle / @Patch.NameMap parameters of THIS method
                        private final Map<Integer, String> allHandleSlots = new HashMap<>();
                        private final Map<Integer, String> nameMapSlots   = new HashMap<>();
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
                        public void visitVarInsn(int opcode, int var) {
                            if (opcode == Opcodes.ALOAD) {
                                String storeKey = allHandleSlots.get(var);
                                if (storeKey != null) {
                                    boolean isVar = paramHandleInfos.get(storeKey) instanceof IVarHandle;
                                    mv.visitLdcInsn(storeKey);
                                    mv.visitMethodInsn(Opcodes.INVOKESTATIC, "me/zed_0xff/zombie_buddy/Patch$HandleStore",
                                        isVar ? "getVar" : "getMethod",
                                        isVar ? "(Ljava/lang/String;)Ljava/lang/invoke/VarHandle;" : "(Ljava/lang/String;)Ljava/lang/invoke/MethodHandle;",
                                        false);
                                    return;
                                }
                                storeKey = nameMapSlots.get(var);
                                if (storeKey != null) {
                                    mv.visitLdcInsn(storeKey);
                                    mv.visitMethodInsn(Opcodes.INVOKESTATIC, "me/zed_0xff/zombie_buddy/Patch$NameStore",
                                        "get", "(Ljava/lang/String;)Ljava/util/Map;", false);
                                    return;
                                }
                            }
                            super.visitVarInsn(opcode, var);
                        }

                        @Override
                        public AnnotationVisitor visitParameterAnnotation(int parameter, String descriptor, boolean visible) {
                            if (Type.getDescriptor(Patch.MemberHandle.class).equals(descriptor) ||
                                Type.getDescriptor(Patch.NameMap.class).equals(descriptor)) {
                                // Compute the local variable slot for this parameter
                                Type[] argTypes = Type.getArgumentTypes(mDesc);
                                boolean isSt = (mAccess & Opcodes.ACC_STATIC) != 0;
                                int s = isSt ? 0 : 1;
                                for (int i = 0; i < parameter && i < argTypes.length; i++) s += argTypes[i].getSize();
                                // Populate the appropriate slot map for use in visitVarInsn
                                if (Type.getDescriptor(Patch.MemberHandle.class).equals(descriptor)) {
                                    Map<Integer, String> slotMap = paramAllHandleSlots.get(mName + mDesc);
                                    if (slotMap != null) { String key = slotMap.get(s); if (key != null) allHandleSlots.put(s, key); }
                                } else {
                                    Map<Integer, String> slotMap = paramNameMapSlots.get(mName + mDesc);
                                    if (slotMap != null) { String key = slotMap.get(s); if (key != null) nameMapSlots.put(s, key); }
                                }
                                // Replace with @Advice.Local so ByteBuddy recognizes the parameter slot;
                                // the actual value is provided at runtime by the ALOAD rewrite above.
                                String localName = Type.getDescriptor(Patch.NameMap.class).equals(descriptor)
                                    ? "__nm_param_" + parameter + "__" : "__mh_param_" + parameter + "__";
                                AnnotationVisitor av = super.visitParameterAnnotation(parameter, Type.getDescriptor(Advice.Local.class), visible);
                                if (av != null) { av.visit("value", localName); av.visitEnd(); }
                                return null;
                            }
                            AnnotationVisitor av = super.visitParameterAnnotation(parameter, rewriteAnnotationDescriptor(descriptor, isDelegation), visible);
                            boolean isField      = Type.getDescriptor(Patch.Field.class).equals(descriptor);
                            boolean isFieldRW    = Type.getDescriptor(Patch.FieldRW.class).equals(descriptor);

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
                                    @Override public void visit(String paramName, Object paramValue) {
                                        if ("optional".equals(paramName)) return; // Advice.FieldValue has no optional
                                        super.visit(paramName, paramValue);
                                    }
                                    @Override public void visitEnd() {
                                        String resolved;
                                        if (candidates.isEmpty()) {
                                            resolved = inferred;
                                        } else if (candidates.size() == 1) {
                                            resolved = candidates.get(0);
                                        } else {
                                            resolved = resolveFieldName(candidates, defaultTargetCls, patchClass.getName(), td);
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
                                        if (inferred != null) methodResolutions.computeIfAbsent(mName, k -> new HashMap<>()).putIfAbsent(inferred, resolved);
                                        super.visit("value", resolved);
                                        if (isFieldRW) super.visit("readOnly", false);
                                        super.visitEnd();
                                    }
                                };
                            }
                            return av;
                        }

                    };
                }
            }, ClassReader.EXPAND_FRAMES);

            if (hasUnresolvableField[0]) {
                Logger.error("Dropping patch class " + patchClass.getName() + " due to unresolvable @Patch.Field");
                return null;
            }

            byte[] transformedBytes = cw.toByteArray();

            try {
                Loader.g_instrumentation.redefineClasses(new java.lang.instrument.ClassDefinition(patchClass, transformedBytes));
            } catch (Exception e) {
                // Non-fatal: ChildFirst loader carries the updated bytes. Failure is expected when
                // TypeAlias remapping changes NestHost/NestMembers attributes (JVM forbids that change).
                Logger.debug("Could not redefine patch class " + patchClass.getName() + " (non-fatal): " + e.getMessage());
            }

            // Load in a fresh ChildFirst ClassLoader so Java reflection sees updated annotations
            // (HotSpot does not reliably refresh parameter annotations after redefineClasses alone).
            try {
                ClassLoader freshLoader = new ByteArrayClassLoader.ChildFirst(
                        patchClass.getClassLoader(),
                        Collections.singletonMap(patchClass.getName(), transformedBytes),
                        ByteArrayClassLoader.PersistenceHandler.MANIFEST);
                Class<?> freshClass = freshLoader.loadClass(patchClass.getName());
                for (var e : nameMapKeyToMethod.entrySet())
                    Patch.NameStore.put(e.getKey(), Map.copyOf(methodResolutions.getOrDefault(e.getValue(), Map.of())));
                if (!memberHandles.isEmpty() && !populateMemberHandles(freshClass, patchClass, memberHandles)) return null;
                if (!paramHandleInfos.isEmpty() && !populateParamHandles(paramHandleInfos)) return null;
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

    private static boolean populateHandles(Map<String, IMemberHandle> infos,
            BiFunction<String, IMemberHandle, String> nullMsg, BiFunction<String, Object, Boolean> setter) {
        MethodHandles.Lookup lookup = MethodHandles.lookup();
        boolean allOk = true;
        for (var entry : infos.entrySet()) {
            String key = entry.getKey(); IMemberHandle info = entry.getValue();
            Object handle = resolveMemberHandle(info, lookup);
            if (handle == null) {
                if (!info.optional()) { Logger.error(nullMsg.apply(key, info)); allOk = false; }
                continue;
            }
            if (!setter.apply(key, handle)) allOk = false;
        }
        return allOk;
    }

    /** Injects resolved {@link MethodHandle}s into static fields annotated with {@code @Patch.MemberHandle}.
     *  Sets the field on both {@code freshClass} (used by ByteBuddy for advice reading) and {@code originalClass}
     *  (whose static fields are accessed at runtime when the inlined advice executes in the target class).
     *  Returns false if any non-optional handle could not be resolved (caller should drop the patch). */
    private static boolean populateMemberHandles(Class<?> freshClass, Class<?> originalClass, Map<String, IMemberHandle> infos) {
        return populateHandles(infos,
            (k, i) -> "MemberHandle not resolved: " + k + " in " + i.targetClass(),
            (k, h) -> { setStaticField(freshClass, k, h); if (originalClass != freshClass) setStaticField(originalClass, k, h); return true; });
    }

    /** Resolves handles for parameter-level {@code @Patch.MemberHandle} and stores them in {@link Patch.HandleStore}.
     *  Returns false if any non-optional handle could not be resolved (caller should drop the patch). */
    private static boolean populateParamHandles(Map<String, IMemberHandle> infos) {
        return populateHandles(infos,
            (k, i) -> "Parameter MemberHandle not resolved: " + k,
            (k, h) -> {
                if (h instanceof MethodHandle mh) { Patch.HandleStore.putMethod(k, mh); return true; }
                if (h instanceof VarHandle    vh) { Patch.HandleStore.putVar(k, vh); return true; }
                Logger.error("Parameter MemberHandle resolved to unexpected type " + h.getClass() + " for key " + k);
                return false;
            });
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

    private static Object resolveMemberHandle(IMemberHandle info, MethodHandles.Lookup lookup) {
        if (info.targetClass().isEmpty()) {
            Logger.warn("MemberHandle has no target class");
            return null;
        }

        Class<?> targetClass = findClass(info.targetClass());
        if (targetClass == null) {
            Logger.warn("MemberHandle target class " + info.targetClass() + " not found");
            return null;
        }

        if (info instanceof IVarHandle ivh) {
            return Reflect.on(targetClass).getVarHandle(ivh.fieldType(), ivh.candidates());
        }

        if (info instanceof IMethodHandle imh) {
            boolean hasSignature = imh.returnType() != void.class || imh.parameterTypes().length > 0;
            for (String name : info.candidates()) {
                Method m = findMemberHandleMethod(targetClass, name, hasSignature ? imh.returnType() : null, imh.parameterTypes());
                if (m != null) {
                    try { m.setAccessible(true); return lookup.unreflect(m); }
                    catch (Exception e) { Logger.warn("MemberHandle unreflect failed for " + name + ": " + e.getMessage()); }
                }
            }
        }
        return null;
    }

    /** Class.forName that avoids circular loading: if {@code name} equals the class currently being
     *  transformed (new-load callback), uses findAlreadyLoadedClass to avoid a premature defineClass
     *  that would cause "duplicate class definition" when the outer defineClass fires. Returns null
     *  on ClassNotFoundException, LinkageError, or when the class is still being loaded. */
    private static Class<?> findClass(String name) {
        String busy = g_transformingClass.get();
        if (busy != null && busy.equals(name)) {
            return findAlreadyLoadedClass(g_transformingLoader.get(), name);
        }
        return Accessor.findClass(name);
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
    private static String resolveFieldName(List<String> candidates, String targetClassName, String title, TypeDescription td) {
        if (Utils.isBlank(targetClassName)) return null;

        if (targetClassName.equals(td.getCanonicalName())) {
            while (td != null) {
                for(var field : td.getDeclaredFields()) {
                    if (candidates.contains(field.getName())) return field.getName();
                }
                td = td.getSuperClass() != null ? td.getSuperClass().asErasure() : null;
            }
            return null;
        }

        Class<?> cls = findClass(targetClassName);
        if (cls == null) {
            Logger.warn(title + ": target class " + targetClassName + " not found");
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
