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
                    me.zed_0xff.zombie_buddy.transformers.asmtree.Resolver.class
                    )
                // Arguments.of(
                //     me.zed_0xff.zombie_buddy.transformers.bytebuddy.AnnotationConverter.class,
                //     me.zed_0xff.zombie_buddy.transformers.bytebuddy.Resolver.class
                //     )
                );
    }

    @Patch(className = "me.zed_0xff.TestClass", methodName = "getFoo")
    static class Target1 {
        static void m0(@Patch.Field int implicit) {}
        static void m1(@Patch.Field("renamed") int bar) {}
        static void m2(@Patch.Field({"first", "second"}) int bar) {} // TODO
    }

    @ParameterizedTest
    @MethodSource("provideClasses")
    void test_OnEnter(Class<? extends Transformer> converterCls, Class<? extends Transformer> resolverCls) throws Exception {
        var ctx = new TestClassContext(Target1.class);
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

        if (resolverCls.getName().contains(".bytebuddy."))
            assumeTrue(false, "Not implemented for bytebuddy-based transformers");
        
        a = p.getDeclaredAnnotations().ofType(Advice.FieldValue.class).load();
        assertThat(a.value()).isEqualTo("renamed");
    }
}
