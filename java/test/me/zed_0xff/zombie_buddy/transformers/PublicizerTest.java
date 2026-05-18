package me.zed_0xff.zombie_buddy.transformers.bytebuddy;

import me.zed_0xff.zombie_buddy.transformers.*;

import org.junit.jupiter.api.Test;
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

    @Test
    void test() throws IOException {
        var ctx = new TestClassContext(Target.class);
        byte[] bytes = ctx.getBytes();

        var result = new Publicizer().transform(bytes, ctx);
        assertThat(result.modified()).isTrue();
        assertThat(result.bytes()).isNotNull();

        assertThat(ctx.getOriginalTypeDesc().getDeclaredFields() ).allMatch(f -> !f.isPublic());
        assertThat(ctx.getOriginalTypeDesc().getDeclaredMethods()).allMatch(f -> !f.isPublic());

        assertThat(ctx.getCurrentTypeDesc().getDeclaredFields() ).allMatch(f -> f.isPublic());
        assertThat(ctx.getCurrentTypeDesc().getDeclaredMethods()).allMatch(f -> f.isPublic());
    }
}
