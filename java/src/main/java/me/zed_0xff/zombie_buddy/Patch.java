package me.zed_0xff.zombie_buddy;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

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

  enum OnMethodMissing { SKIP_PATCH, RUN_BODY }

  /**
   * Marks a static method as a trampoline: PatchEngine rewrites its body at load time to a
   * direct call to the resolved method in {@link #className()}, with zero runtime overhead.
   *
   * <p><b>Name resolution:</b> if {@link #methodNames()} is non-empty, those names are tried in
   * order and the annotated method's own name is ignored. Otherwise the annotated method's name
   * is used as the sole candidate.
   *
   * <p><b>Signature matching:</b> the annotated method must be {@code static}. For instance
   * targets, the first parameter is the receiver and the remaining parameters are the method
   * arguments. For static targets, all parameters are arguments. Return type must match exactly.
   *
   * <p><b>Missing method:</b> {@link OnMethodMissing#SKIP_PATCH} (default) drops the entire
   * containing patch class; {@link OnMethodMissing#RUN_BODY} leaves the method body unchanged.
   *
   * <p><b>className:</b> if empty, defaults to the {@link Patch#className()} of the enclosing
   * {@code @Patch} class, i.e. the same target class being patched.
   *
   * <pre>{@code
   * @Patch.Trampoline(className = "IsoGameCharacter", methodNames = {"isNPC", "isNpc"})
   * public static boolean isNPC(IsoGameCharacter chr) { return false; }
   *
   * // shorthand when target class == @Patch className:
   * @Patch.Trampoline
   * public static boolean isNPC(IsoGameCharacter chr) { return false; }
   * }</pre>
   */
  @Retention(RetentionPolicy.RUNTIME)
  @Target(ElementType.METHOD)
  public @interface Trampoline {
    String className() default "";   // empty = same as enclosing @Patch className
    String[] methodNames() default {};
    OnMethodMissing onMethodMissing() default OnMethodMissing.SKIP_PATCH;
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

  /** Binds the n-th argument (0-based) of the target method. Use {@code readOnly = false} to overwrite it. */
  @Retention(RetentionPolicy.RUNTIME)
  @Target(ElementType.PARAMETER)
  public @interface Argument {
    int value() default 0;
    boolean readOnly() default true;
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
   *
   * <p>Use {@code readOnly = false} to write the (possibly modified) value back after the
   * advice returns, or prefer the shorthand {@link RWField}.
   *
   * <pre>{@code
   * @Patch.OnEnter
   * public static void enter(@Patch.This Object self,
   *                          @Patch.Field String name,              // inferred: reads field "name"
   *                          @Patch.Field("counter") int c) { ... } // explicit field name
   * }</pre>
   */
  @Retention(RetentionPolicy.RUNTIME)
  @Target(ElementType.PARAMETER)
  public @interface Field {
    String value() default "";  // empty = infer from parameter name
    boolean readOnly() default true;
  }

  /**
   * Shorthand for {@code @Patch.Field(readOnly = false)}: binds a parameter to an instance or
   * static field of the target class and writes the value back after the advice returns.
   *
   * <p>The field name is inferred from the parameter name when {@link #value()} is omitted.
   *
   * <pre>{@code
   * @Patch.OnEnter
   * public static void enter(@Patch.RWField int counter) {
   *     counter++;  // increments the field on the target object
   * }
   * }</pre>
   */
  @Retention(RetentionPolicy.RUNTIME)
  @Target(ElementType.PARAMETER)
  public @interface RWField {
    String value() default "";  // empty = infer from parameter name
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

  /**
   * Marks a static field in the patch class as a stand-in for a static field in another
   * (potentially inaccessible) class. PatchTransformer rewrites GETSTATIC/PUTSTATIC
   * instructions referencing this stub field to the real class's field.
   *
   * <p>Since advice is inlined into the target class, the rewritten references have full
   * access to the real type's private/package-private members without any reflection.
   *
   * <p>An annotation processor validates at compile time that the stub field is not {@code final}
   * and that the enclosing class is annotated with {@code @Patch}.
   *
   * <pre>{@code
   * @Patch(className = "game.Renderer", methodName = "render")
   * public class RendererPatch {
   *     @Patch.StaticFieldAlias(className = "game.VertexBuffer")
   *     static int VERTEX_SIZE;   // alias for VertexBuffer.VERTEX_SIZE
   *
   *     @Patch.OnEnter
   *     public static void enter() {
   *         int stride = VERTEX_SIZE * 4;   // → GETSTATIC game/VertexBuffer.VERTEX_SIZE at runtime
   *     }
   * }
   * }</pre>
   */
  @Retention(RetentionPolicy.RUNTIME)
  @Target(ElementType.FIELD)
  public @interface StaticFieldAlias {
    String className() default "";  // empty = infer from enclosing @Patch.className()
    boolean readOnly() default true;
  }
}
