package me.zed_0xff.zombie_buddy.transformers;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class PublicizerTest extends AbstractTest {
    protected static Stream<Arguments> provideClasses() {
        return Stream.of(
                Arguments.of(me.zed_0xff.zombie_buddy.transformers.asmtree.Publicizer.class),
                Arguments.of(me.zed_0xff.zombie_buddy.transformers.bytebuddy.Publicizer.class)
                );
    }

    static class Target {
        private int f1;
        static Object f2;
        private void m1() {}
        static protected boolean m2() { return false; }
    }

    @ParameterizedTest
    @MethodSource("provideClasses")
    void test(Class<? extends Transformer> cls) throws Exception {
        var ctx = new TestClassContext(Target.class);
        byte[] bytes = ctx.getBytes();

        Transformer transformer = cls.getDeclaredConstructor().newInstance();
        var result = transformer.transform(bytes, ctx);
        assertThat(result.modified()).isTrue();
        assertThat(result.bytes()).isNotNull();

        assertThat(ctx.getOriginalTypeDesc().getDeclaredFields() ).allMatch(f -> !f.isPublic());
        assertThat(ctx.getOriginalTypeDesc().getDeclaredMethods()).allMatch(f -> !f.isPublic());

        assertThat(ctx.getCurrentTypeDesc().getDeclaredFields() ).allMatch(f -> f.isPublic());
        assertThat(ctx.getCurrentTypeDesc().getDeclaredMethods()).allMatch(f -> f.isPublic());
    }
}
