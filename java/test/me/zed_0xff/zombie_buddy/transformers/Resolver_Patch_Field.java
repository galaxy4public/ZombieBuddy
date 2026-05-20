package me.zed_0xff.zombie_buddy.transformers;

import me.zed_0xff.zombie_buddy.Patch;
import net.bytebuddy.asm.Advice;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.*;

import java.util.stream.Stream;

class Resolver_Patch_Field_Test extends AbstractTest {
    protected static Stream<Arguments> provideClasses() {
        return Stream.of(
                Arguments.of(
                    me.zed_0xff.zombie_buddy.transformers.asmtree.AnnotationConverter.class,
                    me.zed_0xff.zombie_buddy.transformers.asmtree.Resolver.class,
                    Patch1.class,
                    "first"
                    ),
                Arguments.of(
                    me.zed_0xff.zombie_buddy.transformers.asmtree.AnnotationConverter.class,
                    me.zed_0xff.zombie_buddy.transformers.asmtree.Resolver.class,
                    Patch2.class,
                    "second"
                    )
                // Arguments.of(
                //     me.zed_0xff.zombie_buddy.transformers.bytebuddy.AnnotationConverter.class,
                //     me.zed_0xff.zombie_buddy.transformers.bytebuddy.Resolver.class
                //     )
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
            String expectedFieldName
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
        assertThat(p.getDeclaredAnnotations()).hasSize(2);
        var a = p.getDeclaredAnnotations().ofType(Advice.FieldValue.class).load();
        assertThat(a.value()).isEqualTo("implicit");

        p = ctx.getMethod("m1").getParameters().getOnly();
        assertThat(p.getDeclaredAnnotations()).hasSize(2);

        // if (resolverCls.getName().contains(".bytebuddy."))
        //     assumeTrue(false, "Not implemented for bytebuddy-based transformers");
        
        a = p.getDeclaredAnnotations().ofType(Advice.FieldValue.class).load();
        assertThat(a.value()).isEqualTo("renamed");

        p = ctx.getMethod("m2").getParameters().getOnly();
        assertThat(p.getDeclaredAnnotations()).hasSize(2);

        a = p.getDeclaredAnnotations().ofType(Advice.FieldValue.class).load();
        assertThat(a.value()).isEqualTo(expectedFieldName);
    }
}
