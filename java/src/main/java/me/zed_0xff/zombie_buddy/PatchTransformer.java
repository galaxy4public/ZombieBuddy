package me.zed_0xff.zombie_buddy;

import java.io.IOException;
import java.lang.instrument.Instrumentation;
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
 * Transforms patch classes by replacing Patch.* annotations with ByteBuddy equivalents and
 * rewriting @Patch.Trampoline method bodies to direct INVOKEVIRTUAL/INVOKESTATIC calls.
 */
final class PatchTransformer {
    private static final Type NO_EXCEPTION_HANDLER = Type.getType("Lnet/bytebuddy/asm/Advice$NoExceptionHandler;"); // private

    private static final Map<String, String> ADVICE_DESCRIPTOR_MAP;
    private static final Map<String, String> DELEGATION_DESCRIPTOR_MAP;
    private static final Map<Type, Type> TYPE_MAP;

    static {
        final Map<String, String> common_map = new HashMap<>();
        common_map.put(Type.getDescriptor(Patch.This.class),         Type.getDescriptor(Advice.This.class));
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

    /**
     * Transforms a patch class: replaces Patch.* annotations with ByteBuddy equivalents and
     * rewrites @Patch.Trampoline method bodies to direct INVOKEVIRTUAL/INVOKESTATIC calls.
     * Returns null if a SKIP_PATCH trampoline cannot be resolved (caller should drop the patch).
     *
     * NOTE: Trampoline resolution uses Class.forName, so the target class must already be loaded
     * at the time this method is called. Trampolines cannot be used when intercepting the first
     * load of a class (preload-time patching); they work only with retransformation (already-loaded classes).
     */
    public static Class<?> transformPatchClass(Class<?> patchClass, Instrumentation instrumentation, int verbosity, boolean isMethodDelegation) {
        try {
            boolean needsTransformation = false;
            boolean hasAnyTrampolines   = false;
            for (Method method : patchClass.getDeclaredMethods()) {
                if (method.isAnnotationPresent(Patch.Trampoline.class)) { hasAnyTrampolines = true; continue; }
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
                        if (param.isAnnotationPresent(Patch.Return.class)      ||
                            param.isAnnotationPresent(Patch.Thrown.class)      ||
                            param.isAnnotationPresent(Patch.This.class)        ||
                            param.isAnnotationPresent(Patch.Argument.class)    ||
                            param.isAnnotationPresent(Patch.Field.class)       ||
                            param.isAnnotationPresent(Patch.FieldRW.class)     ||
                            param.isAnnotationPresent(Patch.Local.class)       ||
                            param.isAnnotationPresent(Patch.SuperMethod.class) ||
                            param.isAnnotationPresent(Patch.SuperCall.class)) {
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
                    resolvedName = resolveFieldName(Arrays.asList(candidates), targetClass);
                    if (resolvedName == null) resolvedName = candidates[0];
                }
                staticAliases.put(f.getName(), new StaticAlias(Utils.toInternalName(targetClass), resolvedName, readOnly));
                needsTransformation = true;
            }

            if (!needsTransformation && !hasAnyTrampolines) {
                if (verbosity > 1) Logger.info("class " + patchClass.getName() + " needs transformation: false");
                return patchClass;
            }

            Map<String, TrampolineProcessor.ResolvedMethod> trampolines = new HashMap<>();
            if (hasAnyTrampolines) {
                for (Method m : patchClass.getDeclaredMethods()) {
                    Patch.Trampoline ann = m.getAnnotation(Patch.Trampoline.class);
                    if (ann == null) continue;
                    TrampolineProcessor.ResolvedMethod target = resolveTrampoline(m, ann, defaultTargetCls);
                    if (target == null) {
                        Logger.warn("Trampoline not resolved: " + patchClass.getSimpleName() + "." + m.getName());
                        if (ann.onMethodMissing() == Patch.OnMethodMissing.SKIP_PATCH) return null;
                        continue; // RUN_BODY — leave method body unchanged
                    }
                    Logger.debug("Trampoline resolved: " + m.getName() + " -> " + target.owner() + "." + target.name());
                    trampolines.put(m.getName(), target);
                    needsTransformation = true;
                }
            }

            if (verbosity > 1) Logger.info("class " + patchClass.getName() + " needs transformation: " + needsTransformation);
            if (!needsTransformation && trampolines.isEmpty()) return patchClass;

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
                    TrampolineProcessor.ResolvedMethod trampolineTarget = trampolines.get(name);
                    if (trampolineTarget != null) mv = TrampolineProcessor.makeBodyRewriter(mv, descriptor, trampolineTarget);
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
                            boolean isField   = Type.getDescriptor(Patch.Field.class).equals(descriptor);
                            boolean isRWField = Type.getDescriptor(Patch.FieldRW.class).equals(descriptor);
                            if (isField || isRWField) {
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
                                            resolved = resolveFieldName(candidates, defaultTargetCls);
                                            if (resolved == null) resolved = candidates.get(0);
                                        }
                                        if (resolved != null && inferred != null) fieldResolutions.putIfAbsent(inferred, resolved);
                                        if (resolved != null) super.visit("value", resolved);
                                        if (isRWField) super.visit("readOnly", false);
                                        super.visitEnd();
                                    }
                                };
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

            byte[] transformedBytes = cw.toByteArray();

            if (instrumentation != null) {
                try {
                    instrumentation.redefineClasses(new java.lang.instrument.ClassDefinition(patchClass, transformedBytes));
                } catch (Exception e) {
                    Logger.error("Failed to redefine class " + patchClass.getName() + ": " + e.getMessage());
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

    private static TrampolineProcessor.ResolvedMethod resolveTrampoline(Method trampolineMethod, Patch.Trampoline ann, String defaultTargetClass) {
        String targetClassName = ann.className().isEmpty() ? defaultTargetClass : ann.className();
        if (targetClassName.isEmpty()) {
            Logger.warn("Trampoline " + trampolineMethod.getName() + " has no target class");
            return null;
        }
        Class<?> targetClass;
        try {
            targetClass = Class.forName(targetClassName);
        } catch (ClassNotFoundException e) {
            Logger.warn("Trampoline target class not found: " + targetClassName);
            return null;
        } catch (LinkageError e) {
            // Target class is currently being loaded (preload-time intercept) — trampolines can't resolve in this case.
            Logger.warn("Trampoline target class not yet loadable (preload-time limitation): " + targetClassName);
            return null;
        }
        String[]   names   = ann.value().length > 0 ? ann.value() : ann.methodName().length > 0 ? ann.methodName() : new String[]{ trampolineMethod.getName() };
        Class<?>[] tParams = trampolineMethod.getParameterTypes();
        Class<?>   tReturn = trampolineMethod.getReturnType();
        for (String name : names) {
            if (tParams.length > 0) {
                Method m = findTrampolineTarget(targetClass, name, tReturn, Arrays.copyOfRange(tParams, 1, tParams.length), false);
                if (m != null) return toResolved(m);
            }
            Method m = findTrampolineTarget(targetClass, name, tReturn, tParams, true);
            if (m != null) return toResolved(m);
        }
        return null;
    }

    private static TrampolineProcessor.ResolvedMethod toResolved(Method m) {
        return new TrampolineProcessor.ResolvedMethod(
            Utils.toInternalName(m.getDeclaringClass()),
            m.getName(),
            Type.getMethodDescriptor(m),
            Modifier.isStatic(m.getModifiers()),
            m.getDeclaringClass().isInterface()
        );
    }

    private static Method findTrampolineTarget(Class<?> cls, String name, Class<?> returnType, Class<?>[] params, boolean wantStatic) {
        for (Class<?> c = cls; c != null; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (!m.getName().equals(name)) continue;
                if (Modifier.isStatic(m.getModifiers()) != wantStatic) continue;
                if (!m.getReturnType().equals(returnType)) continue;
                if (!Arrays.equals(m.getParameterTypes(), params)) continue;
                return m;
            }
        }
        return null;
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

    /** Returns the first name from {@code candidates} that exists as a field on {@code targetClassName} or any superclass. */
    private static String resolveFieldName(List<String> candidates, String targetClassName) {
        if (targetClassName.isEmpty()) return null;
        try {
            Class<?> cls = Class.forName(targetClassName);
            for (String name : candidates) {
                for (Class<?> c = cls; c != null; c = c.getSuperclass()) {
                    try { c.getDeclaredField(name); return name; } catch (NoSuchFieldException ignored) {}
                }
            }
        } catch (ClassNotFoundException e) {
            Logger.warn("Field name resolution: target class not found: " + targetClassName);
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
                int paramCount = Type.getArgumentTypes(mDesc).length;
                String[] names = new String[paramCount];
                result.put(mName + mDesc, names);
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitLocalVariable(String varName, String varDesc, String varSig, Label start, Label end, int index) {
                        int paramIdx = isStatic ? index : index - 1;
                        if (paramIdx >= 0 && paramIdx < paramCount) names[paramIdx] = varName;
                    }
                };
            }
        }, ClassReader.SKIP_FRAMES);
        return result;
    }
}
