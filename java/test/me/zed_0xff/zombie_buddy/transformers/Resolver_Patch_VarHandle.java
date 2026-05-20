package me.zed_0xff.zombie_buddy.transformers;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.invoke.VarHandle;
import java.util.ArrayList;
import java.util.List;
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
        List<Class<?>[]> converters = List.of(
            new Class<?>[]{ AnnotationConverter.class, Resolver.class },
            new Class<?>[]{ Resolver.class, AnnotationConverter.class }
        );

        List<Object[]> objects = List.of(
            new Object[]{ Patch1.class, "implicit" },
            new Object[]{ Patch2.class, "explicit" }
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
    void test_patch(
            Class<? extends Transformer> converterCls,
            Class<? extends Transformer> resolverCls,
            Class<?> patchCls,
            Object expectedFieldName
    ) throws Exception {
        var ctx = new TestClassContext(patchCls);
        byte[] bytes = ctx.getBytes();
        ArrayList<String> dumps = new ArrayList<>();

        var p = ctx.getMethod("m0").getParameters().getOnly();
        assertThat(p.getDeclaredAnnotations()).hasSize(1);

        Transformer t1 = converterCls.getDeclaredConstructor().newInstance();
        var res1 = t1.transform(bytes, ctx);
        if (res1.modified()) bytes = res1.bytes();
        dumps.add(ctx.dumpClass(bytes));

        Transformer t2 = resolverCls.getDeclaredConstructor().newInstance();
        var res2 = t2.transform(bytes, ctx);
        if (res2.modified()) bytes = res2.bytes();
        dumps.add(ctx.dumpClass(bytes));

        try {
            p = ctx.getMethod("m0").getParameters().getOnly();
            assertThat(p.getDeclaredAnnotations()).hasSize(2);
            var a = p.getDeclaredAnnotations().ofType(Advice.Local.class).load();
            assertThat(a.value()).isEqualTo(expectedFieldName);
        } catch (Throwable t) {
            dumps.forEach(d -> { System.out.println(d); });
            throw t;
        }
    }
}
