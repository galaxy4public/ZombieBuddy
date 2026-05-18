package me.zed_0xff.zombie_buddy.transformers;

import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;
import java.io.IOException;

import net.bytebuddy.description.type.TypeDescription;

class PublicizerTest extends AbstractTest {
    static class Target {
        private int f1;
        static Object f2;
        private void m1() {}
        static protected boolean m2() { return false; }
    }

    @ParameterizedTest
    @ValueSource(classes = {
        me.zed_0xff.zombie_buddy.transformers.asmtree.Publicizer.class,
        me.zed_0xff.zombie_buddy.transformers.bytebuddy.Publicizer.class
    })
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
