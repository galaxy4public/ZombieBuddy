package me.zed_0xff.zombie_buddy.transformers;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import me.zed_0xff.zombie_buddy.Patch;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;

class Resolver_Patch_OnEnter_skipOn_Test extends AbstractTest {
    protected static Stream<Arguments> provideClasses() {
        return Stream.of(
                Arguments.of(
                    me.zed_0xff.zombie_buddy.transformers.asmtree.AnnotationConverter.class,
                    me.zed_0xff.zombie_buddy.transformers.asmtree.Resolver.class
                    )
                );
    }

    @Patch(className = "me.zed_0xff.TestClass", methodName = "getFoo")
    static class Target1 {
        @Patch.OnEnter
        static void m1() {}
    }

    @ParameterizedTest
    @MethodSource("provideClasses")
    void test_OnEnter(Class<? extends Transformer> converterCls, Class<? extends Transformer> resolverCls) throws Exception {
        var ctx = new TestClassContext(Target1.class);
        byte[] bytes = ctx.getBytes();

        var m = ctx.getMethod("m1");
        assertThat(m.getDeclaredAnnotations())
            .hasSize(1);

        Transformer converter = converterCls.getDeclaredConstructor().newInstance();
        var result = converter.transform(bytes, ctx);

        assertThat(result.modified()).isTrue();
        assertThat(result.bytes()).isNotNull();

        Transformer resolver = resolverCls.getDeclaredConstructor().newInstance();
        var result2 = resolver.transform(result.bytes(), ctx);

        assertThat(result2.modified()).isFalse(); // nothing to resolve here

        m = ctx.getMethod("m1");
        assertThat(m.getDeclaredAnnotations())
            .hasSize(2);

        var a = m.getDeclaredAnnotations().filter(x -> x.getAnnotationType().isAssignableTo(Advice.OnMethodEnter.class)).getOnly();
        assertThat(a.getValue("skipOn").resolve())
            .isEqualTo(TypeDescription.ForLoadedType.of(void.class));
    }

    @Patch(className = "me.zed_0xff.TestClass", methodName = "getFoo")
    static class Target2 {
        @Patch.OnEnter(skipOn = true)
        static boolean m1() { return true; }
    }

    @ParameterizedTest
    @MethodSource("provideClasses")
    void test_OnEnter_skipOn_true(Class<? extends Transformer> converterCls, Class<? extends Transformer> resolverCls) throws Exception {
        var ctx = new TestClassContext(Target2.class);
        byte[] bytes = ctx.getBytes();

        var m = ctx.getMethod("m1");
        assertThat(m.getDeclaredAnnotations())
            .hasSize(1);

        Transformer converter = converterCls.getDeclaredConstructor().newInstance();
        var result = converter.transform(bytes, ctx);

        Transformer resolver = resolverCls.getDeclaredConstructor().newInstance();
        result = resolver.transform(result.bytes(), ctx);

        assertThat(result.modified()).isTrue();
        assertThat(result.bytes()).isNotNull();

        m = ctx.getMethod("m1");
        assertThat(m.getDeclaredAnnotations())
            .hasSize(2);

        var a = m.getDeclaredAnnotations().filter(x -> x.getAnnotationType().isAssignableTo(Advice.OnMethodEnter.class)).getOnly();
        assertThat(a.getValue("skipOn").resolve())
            .isEqualTo(TypeDescription.ForLoadedType.of(Advice.OnNonDefaultValue.class));
    }

    @Patch(className = "me.zed_0xff.TestClass", methodName = "getFoo")
    static class Target3 {
        @Patch.OnEnter(skipOn = false)
        static boolean m1() { return true; }
    }

    @ParameterizedTest
    @MethodSource("provideClasses")
    void test_OnEnter_skipOn_false(Class<? extends Transformer> converterCls, Class<? extends Transformer> resolverCls) throws Exception {
        var ctx = new TestClassContext(Target3.class);
        byte[] bytes = ctx.getBytes();

        var m = ctx.getMethod("m1");
        assertThat(m.getDeclaredAnnotations())
            .hasSize(1);

        Transformer converter = converterCls.getDeclaredConstructor().newInstance();
        var result = converter.transform(bytes, ctx);

        Transformer resolver = resolverCls.getDeclaredConstructor().newInstance();
        result = resolver.transform(result.bytes(), ctx);

        assertThat(result.modified()).isTrue();
        assertThat(result.bytes()).isNotNull();

        m = ctx.getMethod("m1");
        assertThat(m.getDeclaredAnnotations())
            .hasSize(2);

        var a = m.getDeclaredAnnotations().filter(x -> x.getAnnotationType().isAssignableTo(Advice.OnMethodEnter.class)).getOnly();
        assertThat(a.getValue("skipOn").resolve())
            .isEqualTo(TypeDescription.ForLoadedType.of(void.class));
    }
}
