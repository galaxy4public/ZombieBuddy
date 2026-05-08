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
  
  /** Alias for net.bytebuddy.asm.Advice.Return - mods should use Patch.Return instead */
  @Retention(RetentionPolicy.RUNTIME)
  @Target(ElementType.PARAMETER)
  public @interface Return {
    boolean readOnly() default true;
  }

  /** Alias for net.bytebuddy.asm.Advice.Thrown - mods should use Patch.Thrown instead */
  @Retention(RetentionPolicy.RUNTIME)
  @Target(ElementType.PARAMETER)
  public @interface Thrown {
    boolean readOnly() default true;
  }
  
  /** Alias for net.bytebuddy.asm.Advice.This - mods should use Patch.This instead */
  @Retention(RetentionPolicy.RUNTIME)
  @Target(ElementType.PARAMETER)
  public @interface This {
    boolean readOnly() default true;
  }
  
  /** Alias for net.bytebuddy.asm.Advice.Argument - mods should use Patch.Argument instead */
  @Retention(RetentionPolicy.RUNTIME)
  @Target(ElementType.PARAMETER)
  public @interface Argument {
    int value() default 0;
    boolean readOnly() default true;
  }
  
  /** Alias for net.bytebuddy.asm.Advice.AllArguments - mods should use Patch.AllArguments instead */
  @Retention(RetentionPolicy.RUNTIME)
  @Target(ElementType.PARAMETER)
  public @interface AllArguments {
    boolean readOnly() default true;
  }
  
  /** Alias for net.bytebuddy.implementation.bind.annotation.RuntimeType - mods should use Patch.RuntimeType instead */
  @Retention(RetentionPolicy.RUNTIME)
  @Target(ElementType.METHOD)
  public @interface RuntimeType {
  }
  
  /** Alias for net.bytebuddy.implementation.bind.annotation.SuperMethod - mods should use Patch.SuperMethod instead */
  @Retention(RetentionPolicy.RUNTIME)
  @Target(ElementType.PARAMETER)
  public @interface SuperMethod {
  }
  
  /** Alias for net.bytebuddy.implementation.bind.annotation.SuperCall - mods should use Patch.SuperCall instead */
  @Retention(RetentionPolicy.RUNTIME)
  @Target(ElementType.PARAMETER)
  public @interface SuperCall {
  }
  
  /** Alias for net.bytebuddy.asm.Advice.Local - mods should use Patch.Local instead */
  @Retention(RetentionPolicy.RUNTIME)
  @Target(ElementType.PARAMETER)
  public @interface Local {
    String value();
  }

  /** Alias for net.bytebuddy.asm.Advice.FieldValue - mods should use Patch.Field instead */
  @Retention(RetentionPolicy.RUNTIME)
  @Target(ElementType.PARAMETER)
  public @interface Field {
    String value();
    boolean readOnly() default true;
  }

  /** Shorthand for @Patch.Field(value = ..., readOnly = false) */
  @Retention(RetentionPolicy.RUNTIME)
  @Target(ElementType.PARAMETER)
  public @interface RWField {
    String value();
  }
}
