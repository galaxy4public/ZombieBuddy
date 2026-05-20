package me.zed_0xff.zombie_buddy.transformers;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.invoke.VarHandle;
import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import me.zed_0xff.zombie_buddy.Patch;
import me.zed_0xff.zombie_buddy.transformers.asmtree.AnnotationConverter;
import me.zed_0xff.zombie_buddy.transformers.asmtree.Resolver;
import net.bytebuddy.asm.Advice;

class Resolver_Patch_VarHandle extends AbstractTest {
    protected static Stream<Arguments> provideClasses() {
        return Stream.of(
                Arguments.of( Patch1.class, "implicit" ),
                Arguments.of( Patch2.class, "explicit" )
                );
    }

    static class Target1 {
        private int first;
        private int implicit;
        void getFoo() {}
    }

    @Patch(className = "me.zed_0xff.zombie_buddy.transformers.Resolver_Patch_VarHandle$Target1", methodName = "getFoo")
    static class Patch1 {
        static void m0(@Patch.VarHandle(type=int.class) VarHandle implicit) {}
    }

    @Patch(className = "me.zed_0xff.zombie_buddy.transformers.Resolver_Patch_VarHandle$Target1", methodName = "getFoo")
    static class Patch2 {
        static void m0(@Patch.VarHandle(type=int.class, name="explicit") VarHandle vh) {}
    }

    @ParameterizedTest
    @MethodSource("provideClasses")
    void test_Patch( Class<?> patchCls, String expectedFieldName ) throws Exception {
        var ctx = new TestClassContext(patchCls);
        byte[] bytes = ctx.getBytes();

        var p = ctx.getMethod("m0").getParameters().getOnly();
        assertThat(p.getDeclaredAnnotations()).hasSize(1);

        Transformer converter = new AnnotationConverter();
        var result = converter.transform(bytes, ctx);

        Transformer resolver = new Resolver();
        result = resolver.transform(result.bytes(), ctx);

        assertThat(result.modified()).isTrue();
        assertThat(result.bytes()).isNotNull();

        p = ctx.getMethod("m0").getParameters().getOnly();
        assertThat(p.getDeclaredAnnotations()).hasSize(2);
        var a = p.getDeclaredAnnotations().ofType(Advice.Local.class).load();
        assertThat(a.value()).isEqualTo(expectedFieldName);
    }
}
