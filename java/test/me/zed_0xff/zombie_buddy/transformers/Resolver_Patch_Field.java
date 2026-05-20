package me.zed_0xff.zombie_buddy.transformers;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import me.zed_0xff.zombie_buddy.Patch;
import me.zed_0xff.zombie_buddy.transformers.asmtree.AnnotationConverter;
import me.zed_0xff.zombie_buddy.transformers.asmtree.Resolver;
import net.bytebuddy.asm.Advice;

class Resolver_Patch_Field_Test extends AbstractTest {
    protected static Stream<Arguments> provideClasses() {
        List<Class<?>[]> converters = List.of(
            new Class<?>[]{ AnnotationConverter.class, Resolver.class },
            new Class<?>[]{ Resolver.class, AnnotationConverter.class }
        );

        List<Object[]> objects = List.of(
            new Object[]{ Patch1.class, "first" },
            new Object[]{ Patch2.class, "second" }
        );

        return converters.stream().flatMap(c ->
            objects.stream().map(p ->
                Arguments.of(
                    c[0], c[1],
                    p[0], p[1]
                )
            )
        );
    }

    static class Target1 {
        private int first;
        void getFoo() {}
    }

    @Patch(className = "me.zed_0xff.zombie_buddy.transformers.Resolver_Patch_Field_Test$Target1", methodName = "getFoo")
    static class Patch1 {
        static void m0(@Patch.Field int implicit) {}
        static void m1(@Patch.Field("renamed") int bar) {}
        static void m2(@Patch.Field({"first", "second"}) int bar) {}
    }

    static class Target2 {
        private int second;
        void getFoo() {}
    }

    @Patch(className = "me.zed_0xff.zombie_buddy.transformers.Resolver_Patch_Field_Test$Target2", methodName = "getFoo")
    static class Patch2 {
        static void m0(@Patch.Field int implicit) {}
        static void m1(@Patch.Field("renamed") int bar) {}
        static void m2(@Patch.Field({"first", "second"}) int bar) {}
    }

    @ParameterizedTest
    @MethodSource("provideClasses")
    void test_OnEnter(
            Class<? extends Transformer> converterCls,
            Class<? extends Transformer> resolverCls,
            Class<?> patchCls,
            Object expectedFieldName
    ) throws Exception {
        var ctx = new TestClassContext(patchCls);
        byte[] bytes = ctx.getBytes();

        var p = ctx.getMethod("m1").getParameters().getOnly();
        assertThat(p.getDeclaredAnnotations()).hasSize(1);

        Transformer converter = converterCls.getDeclaredConstructor().newInstance();
        var result = converter.transform(bytes, ctx);

        Transformer resolver = resolverCls.getDeclaredConstructor().newInstance();
        result = resolver.transform(result.bytes(), ctx);

        assertThat(result.modified()).isTrue();
        assertThat(result.bytes()).isNotNull();

        result = resolver.transform(result.bytes(), ctx);
        assertThat(result.modified()).isTrue();
        assertThat(result.bytes()).isNotNull();

        p = ctx.getMethod("m0").getParameters().getOnly();
        // assertThat(p.getDeclaredAnnotations()).hasSize(2);
        var a = p.getDeclaredAnnotations().ofType(Advice.FieldValue.class).load();
        assertThat(a.value()).isEqualTo("implicit");

        p = ctx.getMethod("m1").getParameters().getOnly();
        // assertThat(p.getDeclaredAnnotations()).hasSize(2);
        
        a = p.getDeclaredAnnotations().ofType(Advice.FieldValue.class).load();
        assertThat(a.value()).isEqualTo("renamed");

        p = ctx.getMethod("m2").getParameters().getOnly();
        // assertThat(p.getDeclaredAnnotations()).hasSize(2);

        a = p.getDeclaredAnnotations().ofType(Advice.FieldValue.class).load();
        assertThat(a.value()).isEqualTo(expectedFieldName);
    }
}
