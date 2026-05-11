package me.zed_0xff.zombie_buddy;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.VarHandle;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface Patch {
  String className();
  String methodName();
  boolean isAdvice() default true; // false => MethodDelegation
                                   // warning! advices can be chained, delegations can't, so only one delegation per method EVER
  boolean warmUp() default false;  // mandatory for some internal classes like LuaManager$Exposer or the patch will not be applied
  boolean IKnowWhatIAmDoing() default false; // if true, the patch will be applied even if it is risky
  boolean strictMatch() default false; // if true, advice methods without arguments match only methods with no arguments
                                       // if false (default), advice methods without arguments match any method
  
  /** Alias for net.bytebuddy.asm.Advice.OnMethodEnter - mods should use Patch.OnEnter instead */
  @Retention(RetentionPolicy.RUNTIME)
  @Target(ElementType.METHOD)
  public @interface OnEnter {
    boolean skipOn() default false;
  }
  
  /** Alias for net.bytebuddy.asm.Advice.OnMethodExit - mods should use Patch.OnExit instead */
  @Retention(RetentionPolicy.RUNTIME)
  @Target(ElementType.METHOD)
  public @interface OnExit {
    Class<? extends Throwable> onThrowable() default NoException.class;
    Class<? extends Throwable> suppress() default NoException.class;
  }

  public abstract static class NoException extends Throwable {}
  public abstract static class OnDefaultValue extends Throwable {}
  public abstract static class OnNonDefaultValue extends Throwable {}
  
  /** Binds the return value of the target method. {@code @Patch.OnExit} only. Use {@code readOnly = false} to overwrite it. */
  @Retention(RetentionPolicy.RUNTIME)
  @Target(ElementType.PARAMETER)
  public @interface Return {
    boolean readOnly() default true;
  }

  /** Binds the exception thrown by the target method, or {@code null} if none. {@code @Patch.OnExit} only. */
  @Retention(RetentionPolicy.RUNTIME)
  @Target(ElementType.PARAMETER)
  public @interface Thrown {
    boolean readOnly() default true;
  }

  /** Binds the target object ({@code this}). Not available for static target methods. */
  @Retention(RetentionPolicy.RUNTIME)
  @Target(ElementType.PARAMETER)
  public @interface This {
    boolean readOnly() default true;
  }

  /** Binds the n-th argument (0-based) of the target method. Use {@code readOnly = false} to overwrite it.
   *  Set {@code optional = true} to bind null / the default primitive value when the index is out of range. */
  @Retention(RetentionPolicy.RUNTIME)
  @Target(ElementType.PARAMETER)
  public @interface Argument {
    int value() default 0;
    boolean readOnly() default true;
    boolean optional() default false;
  }

  /** Binds all target method arguments as {@code Object[]}. Use {@code readOnly = false} to overwrite them. */
  @Retention(RetentionPolicy.RUNTIME)
  @Target(ElementType.PARAMETER)
  public @interface AllArguments {
    boolean readOnly() default true;
  }

  /** Marks a {@code @Patch.OnEnter} / {@code @Patch.OnExit} method return type as dynamically typed (delegation only). */
  @Retention(RetentionPolicy.RUNTIME)
  @Target(ElementType.METHOD)
  public @interface RuntimeType {
  }

  /** Binds a {@code Method} handle to the overridden super-method (delegation only). */
  @Retention(RetentionPolicy.RUNTIME)
  @Target(ElementType.PARAMETER)
  public @interface SuperMethod {
  }

  /** Binds a {@code Callable} that invokes the overridden super-method (delegation only). */
  @Retention(RetentionPolicy.RUNTIME)
  @Target(ElementType.PARAMETER)
  public @interface SuperCall {
  }

  /** Binds a named local variable of the target method. The variable must exist in the target's debug info. */
  @Retention(RetentionPolicy.RUNTIME)
  @Target(ElementType.PARAMETER)
  public @interface Local {
    String value();
  }

  /**
   * Binds a parameter to an instance or static field of the target class (read-only by default).
   *
   * <p>The field name is inferred from the parameter name when {@link #value()} is omitted,
   * which requires debug info (present in all standard Gradle builds).
   * Provide {@link #value()} explicitly when the parameter name differs from the field name.
   * Provide multiple names to try them in order; the first name that exists on the target class is used.
   * Multi-name resolution requires the target class to be already loaded, so it cannot be used in preload-time patches.
   *
   * <p>Use {@code readOnly = false} to write the (possibly modified) value back after the
   * advice returns, or prefer the shorthand {@link FieldRW}.
   *
   * <p>{@link #name()} is an alias for {@link #value()}; specifying both is a compile error.
   *
   * <pre>{@code
   * @Patch.OnEnter
   * public static void enter(@Patch.This Object self,
   *                          @Patch.Field String name,                      // inferred: reads this.name
   *                          @Patch.Field("counter") int c,                 // explicit field name
   *                          @Patch.Field({"speedNew", "speed"}) float spd) // tries "speedNew", falls back to "speed"
   * }</pre>
   */
  @Retention(RetentionPolicy.RUNTIME)
  @Target({ElementType.PARAMETER, ElementType.FIELD, ElementType.METHOD})
  public @interface Field {
    String[] value() default {};    // field name(s): empty = infer from parameter name; multiple = try in order
    String[] name() default {};     // alias for value(); specifying both is a compile error
    Class<?> declaringType() default void.class; // the class that declares the field; void.class = infer from target class
    boolean readOnly() default true;
    boolean optional() default false;
  }

  /**
   * Shorthand for {@code @Patch.Field(readOnly = false)}: binds a parameter to an instance or
   * static field of the target class and writes the value back after the advice returns.
   *
   * <p>The field name is inferred from the parameter name when {@link #value()} is omitted.
   * Multiple names may be specified and are tried in order against the target class.
   * Multi-name resolution requires the target class to be already loaded, so it cannot be used in preload-time patches.
   *
   * <pre>{@code
   * @Patch.OnEnter
   * public static void enter(@Patch.FieldRW int counter) {
   *     counter++;  // increments the field on the target object
   * }
   * }</pre>
   */
  @Retention(RetentionPolicy.RUNTIME)
  @Target({ElementType.PARAMETER, ElementType.METHOD})
  public @interface FieldRW {
    String[] value() default {};  // empty = infer from parameter name; multiple = try in order
    boolean optional() default false;
  }

  @Retention(RetentionPolicy.RUNTIME)
  @Target(ElementType.METHOD)
  public @interface Method {
    String[] value() default {};  // empty = infer from parameter name; multiple = try in order
  }

  /**
   * Marks a nested stub class as a stand-in for an inaccessible type (e.g. a private inner class
   * of the target). At patch transpile time PatchEngine rewrites every bytecode reference to the
   * stub to the real class named by {@link #value()}.
   *
   * <p>Since advice is inlined into the target class's bytecode, the rewritten references have
   * full access to the real type's private members without any reflection.
   *
   * <pre>{@code
   * @Patch(className = "game.Foo", methodName = "bar")
   * public class FooPatch {
   *     @Patch.TypeAlias("game.Foo$Inner")
   *     static class Inner { String field; Inner(String v) {} }
   *
   *     @Patch.OnEnter
   *     public static void enter(@Patch.This Object self) {
   *         Inner i = new Inner("x");   // → new game/Foo$Inner at runtime
   *         Foo.result = i.field;       // → GETFIELD game/Foo$Inner.field
   *     }
   * }
   * }</pre>
   */
  @Retention(RetentionPolicy.RUNTIME)
  @Target(ElementType.TYPE)
  public @interface TypeAlias {
    String value();  // fully qualified name of the real class
  }

  // https://javadoc.io/doc/net.bytebuddy/byte-buddy/1.18.8/net/bytebuddy/asm/Advice.Handle.html            - returns only MethodHandle, no VarHandle support
  // https://javadoc.io/doc/net.bytebuddy/byte-buddy/1.18.8/net/bytebuddy/asm/Advice.FieldGetterHandle.html - respects field visibility
  // https://javadoc.io/doc/net.bytebuddy/byte-buddy/1.18.8/net/bytebuddy/asm/Advice.FieldSetterHandle.html - --//--
  @Retention(RetentionPolicy.RUNTIME)
  @Target({ElementType.FIELD, ElementType.PARAMETER})
  public @interface MemberHandle {
    String[] value() default {};              // empty = infer from stub field name; multiple = try in order
    String[] name() default {};               // alias for value(); specifying both is a compile error
    String className() default "";            // empty = infer from enclosing @Patch.className(); mutually exclusive with owner()
    Class<?> owner() default void.class;      // type-safe alternative to className(); mutually exclusive with className()
    boolean optional() default false;         // false = drop patch class on missing field; true = leave field as null

    // MethodHandle:
    Class<?> returnType() default void.class;
    Class<?>[] parameterTypes() default {};

    // VarHandle:
    Class<?> type() default void.class;
  }

  /**
   * Binds a {@code Map<String, String>} parameter to the immutable field-name resolution map
   * for the enclosing advice method.
   *
   * <p>Keys are parameter names (e.g. {@code "chunkGridWidth"}); values are the actual field
   * names resolved on the target class (e.g. {@code "ChunkGridWidth"}).
   * Only {@link Field} / {@link FieldRW} params with multiple candidate names or inferred names
   * produce entries. The map is immutable.
   *
   * <pre>{@code
   * @Patch.OnExit
   * public static void exit(
   *         @Patch.NameMap Map<String, String> names,
   *         @Patch.FieldRW({"chunkGridWidth", "ChunkGridWidth"}) int chunkGridWidth) {
   *     tbl.rawset(names.get("chunkGridWidth"), chunkGridWidth);
   * }
   * }</pre>
   */
  @Retention(RetentionPolicy.RUNTIME)
  @Target(ElementType.PARAMETER)
  public @interface NameMap {}

  /** Runtime registry for field-name resolution maps bound via {@code @Patch.NameMap} parameters.
   *  Populated by PatchTransformer at instrumentation time; read by inlined advice bytecode. */
  public final class NameStore {
      private static final ConcurrentHashMap<String, Map<String, String>> store = new ConcurrentHashMap<>();

      public static Map<String, String> get(String key) { return store.get(key); }
      static void put(String key, Map<String, String> map) { if (map != null) store.put(key, map); }

      private NameStore() {}
  }

  @Retention(RetentionPolicy.RUNTIME)
  @Target({ElementType.PARAMETER, ElementType.TYPE})
  public @interface Adapter {
      Class<?> value() default void.class;    // void.class = infer from enclosing @Patch.className()
  }

  /** Runtime registry for method handles bound via parameter-level {@code @Patch.MemberHandle}.
   *  Populated by PatchTransformer at instrumentation time; read by inlined advice bytecode. */
  final class HandleStore {
      private static final ConcurrentHashMap<String, MethodHandle> methodHandles = new ConcurrentHashMap<>();
      private static final ConcurrentHashMap<String, VarHandle>    varHandles    = new ConcurrentHashMap<>();

      public static MethodHandle getMethod(String key)   { return methodHandles.get(key); }
      public static VarHandle    getVar(String key)      { return varHandles.get(key); }
      static void putMethod(String key, MethodHandle mh) { if (mh != null) methodHandles.put(key, mh); }
      static void putVar(String key, VarHandle vh)       { if (vh != null) varHandles.put(key, vh); }

      private HandleStore() {}
  }
}
