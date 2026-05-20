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
import net.bytebuddy.description.type.TypeDescription;

class Resolver_Patch_skipOn extends AbstractTest {
    protected static Stream<Arguments> provideClasses() {
        List<Class<?>[]> converters = List.of(
            new Class<?>[]{ AnnotationConverter.class, Resolver.class },
            new Class<?>[]{ Resolver.class, AnnotationConverter.class }
        );

        List<Class<?>[]> patches = List.of(
            new Class<?>[]{ Patch1.class, void.class },
            new Class<?>[]{ Patch2.class, Advice.OnNonDefaultValue.class },
            new Class<?>[]{ Patch3.class, void.class }
        );

        return converters.stream().flatMap(c ->
            patches.stream().map(p ->
                Arguments.of(
                    c[0], c[1],
                    p[0], p[1]
                )
            )
        );
    }

    @Patch(className = "me.zed_0xff.TestClass", methodName = "getFoo")
    static class Patch1 {
        @Patch.OnEnter
        static void m1() {}
    }

    @Patch(className = "me.zed_0xff.TestClass", methodName = "getFoo")
    static class Patch2 {
        @Patch.OnEnter(skipOn = true)
        static boolean m1() { return true; }
    }

    @Patch(className = "me.zed_0xff.TestClass", methodName = "getFoo")
    static class Patch3 {
        @Patch.OnEnter(skipOn = false)
        static boolean m1() { return true; }
    }

    @ParameterizedTest
    @MethodSource("provideClasses")
    void test_OnEnter(
            Class<? extends Transformer> converterCls,
            Class<? extends Transformer> resolverCls,
            Class<?> patchCls,
            Class<?> resultCls
    ) throws Exception {
        var ctx = new TestClassContext(patchCls);
        byte[] bytes = ctx.getBytes();

        var m = ctx.getMethod("m1");
        assertThat(m.getDeclaredAnnotations())
            .hasSize(1);

        Transformer t1 = converterCls.getDeclaredConstructor().newInstance();
        var res1 = t1.transform(bytes, ctx);
        if (res1.modified()) bytes = res1.bytes();

        Transformer t2 = resolverCls.getDeclaredConstructor().newInstance();
        var res2 = t2.transform(bytes, ctx);

        m = ctx.getMethod("m1");
        assertThat(m.getDeclaredAnnotations())
            .hasSize(2);

        var a = m.getDeclaredAnnotations().filter(x -> x.getAnnotationType().isAssignableTo(Advice.OnMethodEnter.class)).getOnly();
        assertThat(a.getValue("skipOn").resolve())
            .isEqualTo(TypeDescription.ForLoadedType.of(resultCls));
    }
}
