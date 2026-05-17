package me.zed_0xff.zombie_buddy;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import net.bytebuddy.jar.asm.ClassWriter;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;
import net.bytebuddy.jar.asm.Type;

// TODO: check
// builder
//         .defineField(name, boolean.class, Visibility.PRIVATE, Ownership.STATIC, FieldManifestation.FINAL)
//         .visit(new AsmVisitorWrapper.ForDeclaredMethods().method(isAnnotatedWith(Enhance.class), new AccessControlWrapper(name)))
//         .initializer(property == null
//                 ? new Initializer.WithoutProperty(typeDescription, name)
//                 : new Initializer.WithProperty(typeDescription, name, property));

public final class AdapterFactory {
    private AdapterFactory() {}

    public interface Bound<T> {
        T unwrap();
    }

    public interface ClassAdapter<T> extends Bound<T> {
        interface RO<F> {
            F get();
            void set(F value);
        }
        interface RW<F> extends RO<F> {
            void set(F value);
        }
    }

    /** VarHandle-backed implementation of both RO and RW. */
    public static final class FieldAccessorImpl<F> implements ClassAdapter.RO<F>, ClassAdapter.RW<F> {
        private final VarHandle handle;
        private final Object target;

        public FieldAccessorImpl(VarHandle handle, Object target) {
            this.handle = handle;
            this.target = target;
        }

        @SuppressWarnings("unchecked")
        public F get() { return (F) handle.get(target); }

        public void set(F value) { handle.set(target, value); }
    }

    private record AdapterClassInfo(java.lang.reflect.Constructor<?> constructor, Object[] sharedHandles) {}
    private record FieldSpec(Method method, int fieldIndex, int handleIndex) {}
    private record MethodSpec(Method method, int methodIndex, int handleIndex) {}

    private static final ConcurrentHashMap<Class<?>, Optional<AdapterClassInfo>> CACHE = new ConcurrentHashMap<>();

    @SuppressWarnings("unchecked")
    public static <T, A extends ClassAdapter<T>> A create(T target, Class<A> adapterType) {
        if (target == null) return null;

        Optional<AdapterClassInfo> opt = CACHE.computeIfAbsent(adapterType, k -> Optional.ofNullable(buildAdapterClass(k)));
        if (opt.isEmpty()) return null;

        AdapterClassInfo info = opt.get();
        try {
            return (A) info.constructor().newInstance(new Object[]{ target, info.sharedHandles() });
        } catch (Exception e) {
            Logger.warn("AdapterFactory.create failed: " + e);
            return null;
        }
    }

    // -----------------------------------------------------------------------

    private static Class<?> resolveTargetClass(Class<?> adapterType) {
        for (java.lang.reflect.Type iface : adapterType.getGenericInterfaces()) {
            if (iface instanceof ParameterizedType pt && pt.getRawType() == ClassAdapter.class) {
                java.lang.reflect.Type arg = pt.getActualTypeArguments()[0];
                if (arg instanceof Class<?> c) return c;
            }
        }
        for (Class<?> parent : adapterType.getInterfaces()) {
            Class<?> found = resolveTargetClass(parent);
            if (found != null) return found;
        }
        return null;
    }

    private static AdapterClassInfo buildAdapterClass(Class<?> adapterType) {
        Class<?> targetClass = resolveTargetClass(adapterType);
        if (targetClass == null) {
            Logger.warn("AdapterFactory: cannot determine target class for " + adapterType.getName());
            return null;
        }

        List<FieldSpec>  fieldSpecs  = new ArrayList<>();
        List<MethodSpec> methodSpecs = new ArrayList<>();
        List<Object>     handles     = new ArrayList<>();

        for (Method m : adapterType.getMethods()) {
            if (m.getDeclaringClass() == Object.class) continue;
            if ("unwrap".equals(m.getName()) && m.getParameterCount() == 0) continue;
            if (Modifier.isStatic(m.getModifiers())) continue;

            Patch.Field   fieldAnn  = m.getAnnotation(Patch.Field.class);
            Patch.Method  methodAnn = m.getAnnotation(Patch.Method.class);

            if (fieldAnn != null) {
                String[] names = fieldAnn.value().length > 0 ? fieldAnn.value() : new String[]{ m.getName() };
                VarHandle vh = resolveVarHandle(targetClass, names);
                if (vh == null) {
                    Logger.warn("AdapterFactory: cannot resolve field " + Arrays.toString(names) + " on " + targetClass.getName());
                    return null;
                }
                fieldSpecs.add(new FieldSpec(m, fieldSpecs.size(), handles.size()));
                handles.add(vh);
            } else if (methodAnn != null) {
                String[] names = methodAnn.value().length > 0 ? methodAnn.value() : new String[]{ m.getName() };
                MethodHandle mh = resolveMethodHandle(targetClass, names, m);
                if (mh == null) {
                    Logger.warn("AdapterFactory: cannot resolve method " + Arrays.toString(names) + " on " + targetClass.getName());
                    return null;
                }
                methodSpecs.add(new MethodSpec(m, methodSpecs.size(), handles.size()));
                handles.add(mh);
            }
        }

        byte[] classBytes = generateClass(adapterType, targetClass, fieldSpecs, methodSpecs);
        try {
            // defineClass requires the class to be in the same package as the lookup class (adapterType),
            // and loads it into adapterType's classloader+module so the impl can access the interface.
            MethodHandles.Lookup adapterLookup = MethodHandles.privateLookupIn(adapterType, MethodHandles.lookup());
            Class<?> implClass = adapterLookup.defineClass(classBytes);
            java.lang.reflect.Constructor<?> ctor = implClass.getDeclaredConstructor(Object.class, Object[].class);
            return new AdapterClassInfo(ctor, handles.toArray());
        } catch (Exception e) {
            Logger.warn("AdapterFactory: failed to load generated class for " + adapterType.getSimpleName() + ": " + e);
            return null;
        }
    }

    private static VarHandle resolveVarHandle(Class<?> targetClass, String[] names) {
        for (String name : names) {
            for (Class<?> c = targetClass; c != null; c = c.getSuperclass()) {
                try {
                    java.lang.reflect.Field f = c.getDeclaredField(name);
                    f.setAccessible(true);
                    MethodHandles.Lookup lk = privateLookup(f.getDeclaringClass());
                    return lk.unreflectVarHandle(f);
                } catch (NoSuchFieldException ignored) {
                } catch (Exception e) {
                    Logger.warn("AdapterFactory: VarHandle unreflect failed for '" + name + "': " + e);
                }
            }
        }
        return null;
    }

    private static MethodHandle resolveMethodHandle(Class<?> targetClass, String[] names, Method adapterMethod) {
        Class<?>[] params = adapterMethod.getParameterTypes();
        Class<?>   ret    = adapterMethod.getReturnType();
        for (String name : names) {
            for (Class<?> c = targetClass; c != null; c = c.getSuperclass()) {
                for (Method m : c.getDeclaredMethods()) {
                    if (!m.getName().equals(name)) continue;
                    if (!m.getReturnType().equals(ret)) continue;
                    if (!Arrays.equals(m.getParameterTypes(), params)) continue;
                    try {
                        m.setAccessible(true);
                        return privateLookup(m.getDeclaringClass()).unreflect(m);
                    } catch (Exception e) {
                        Logger.warn("AdapterFactory: MethodHandle unreflect failed for '" + name + "': " + e);
                    }
                }
            }
        }
        return null;
    }

    private static MethodHandles.Lookup privateLookup(Class<?> targetClass) {
        try {
            return MethodHandles.privateLookupIn(targetClass, MethodHandles.lookup());
        } catch (IllegalAccessException e) {
            return MethodHandles.lookup();
        }
    }

    // -----------------------------------------------------------------------

    private static byte[] generateClass(Class<?> adapterType, Class<?> targetClass,
                                        List<FieldSpec> fieldSpecs, List<MethodSpec> methodSpecs) {
        final String implInternal         = Type.getInternalName(adapterType) + "$$Impl";
        final String adapterInternal      = Type.getInternalName(adapterType);
        final String targetInternal       = Type.getInternalName(targetClass);
        final String targetDesc           = Type.getDescriptor(targetClass);
        final String fieldAccessorInternal = Type.getInternalName(FieldAccessorImpl.class);
        final String fieldAccessorDesc    = "L" + fieldAccessorInternal + ";";

        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
            @Override protected String getCommonSuperClass(String t1, String t2) { return "java/lang/Object"; }
        };
        cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SYNTHETIC,
            implInternal, null, "java/lang/Object", new String[]{ adapterInternal });

        cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, "target", "Ljava/lang/Object;", null, null).visitEnd();
        for (FieldSpec fs : fieldSpecs)
            cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, "f" + fs.fieldIndex(), fieldAccessorDesc, null, null).visitEnd();
        for (MethodSpec ms : methodSpecs)
            cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, "m" + ms.methodIndex(), "Ljava/lang/invoke/MethodHandle;", null, null).visitEnd();

        // <init>(Object target, Object[] handles)
        {
            MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "(Ljava/lang/Object;[Ljava/lang/Object;)V", null, null);
            mv.visitCode();
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);

            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitVarInsn(Opcodes.ALOAD, 1);
            mv.visitFieldInsn(Opcodes.PUTFIELD, implInternal, "target", "Ljava/lang/Object;");

            for (FieldSpec fs : fieldSpecs) {
                mv.visitVarInsn(Opcodes.ALOAD, 0);
                mv.visitTypeInsn(Opcodes.NEW, fieldAccessorInternal);
                mv.visitInsn(Opcodes.DUP);
                mv.visitVarInsn(Opcodes.ALOAD, 2);
                pushInt(mv, fs.handleIndex());
                mv.visitInsn(Opcodes.AALOAD);
                mv.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/invoke/VarHandle");
                mv.visitVarInsn(Opcodes.ALOAD, 1);
                mv.visitMethodInsn(Opcodes.INVOKESPECIAL, fieldAccessorInternal, "<init>",
                    "(Ljava/lang/invoke/VarHandle;Ljava/lang/Object;)V", false);
                mv.visitFieldInsn(Opcodes.PUTFIELD, implInternal, "f" + fs.fieldIndex(), fieldAccessorDesc);
            }

            for (MethodSpec ms : methodSpecs) {
                mv.visitVarInsn(Opcodes.ALOAD, 0);
                mv.visitVarInsn(Opcodes.ALOAD, 2);
                pushInt(mv, ms.handleIndex());
                mv.visitInsn(Opcodes.AALOAD);
                mv.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/invoke/MethodHandle");
                mv.visitFieldInsn(Opcodes.PUTFIELD, implInternal, "m" + ms.methodIndex(), "Ljava/lang/invoke/MethodHandle;");
            }

            mv.visitInsn(Opcodes.RETURN);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        }

        // unwrap() -> Object (erased from T)
        {
            MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "unwrap", "()Ljava/lang/Object;", null, null);
            mv.visitCode();
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitFieldInsn(Opcodes.GETFIELD, implInternal, "target", "Ljava/lang/Object;");
            mv.visitInsn(Opcodes.ARETURN);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        }

        // @Field methods: return pre-built FieldAccessorImpl
        for (FieldSpec fs : fieldSpecs) {
            Method m  = fs.method();
            MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, m.getName(), Type.getMethodDescriptor(m), null, null);
            mv.visitCode();
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitFieldInsn(Opcodes.GETFIELD, implInternal, "f" + fs.fieldIndex(), fieldAccessorDesc);
            mv.visitInsn(Opcodes.ARETURN);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        }

        // @Method methods: MethodHandle.invokeExact(target, args...)
        for (MethodSpec ms : methodSpecs) {
            Method m          = ms.method();
            String methodDesc = Type.getMethodDescriptor(m);
            Type[] argTypes   = Type.getArgumentTypes(methodDesc);
            Type   returnType = Type.getReturnType(methodDesc);

            MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, m.getName(), methodDesc, null, null);
            mv.visitCode();

            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitFieldInsn(Opcodes.GETFIELD, implInternal, "m" + ms.methodIndex(), "Ljava/lang/invoke/MethodHandle;");

            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitFieldInsn(Opcodes.GETFIELD, implInternal, "target", "Ljava/lang/Object;");
            mv.visitTypeInsn(Opcodes.CHECKCAST, targetInternal);

            int slot = 1;
            for (Type argType : argTypes) {
                mv.visitVarInsn(argType.getOpcode(Opcodes.ILOAD), slot);
                slot += argType.getSize();
            }

            // invokeExact descriptor must include the receiver as first arg
            StringBuilder invokeDesc = new StringBuilder("(").append(targetDesc);
            for (Type argType : argTypes) invokeDesc.append(argType.getDescriptor());
            invokeDesc.append(")").append(returnType.getDescriptor());

            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/invoke/MethodHandle", "invokeExact", invokeDesc.toString(), false);
            mv.visitInsn(returnType.getOpcode(Opcodes.IRETURN));
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        }

        cw.visitEnd();
        return cw.toByteArray();
    }

    private static void pushInt(MethodVisitor mv, int v) {
        if (v >= -1 && v <= 5)      mv.visitInsn(Opcodes.ICONST_0 + v);
        else if (v >= Byte.MIN_VALUE && v <= Byte.MAX_VALUE)  mv.visitIntInsn(Opcodes.BIPUSH, v);
        else if (v >= Short.MIN_VALUE && v <= Short.MAX_VALUE) mv.visitIntInsn(Opcodes.SIPUSH, v);
        else mv.visitLdcInsn(v);
    }
}
