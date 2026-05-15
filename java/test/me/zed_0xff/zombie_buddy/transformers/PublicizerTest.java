package me.zed_0xff.zombie_buddy.transformers;

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
        Class<?> cls = Target.class;
        byte[] bytes = getClassBytes(cls);
        ClassContext ctx = new ClassContext(cls.getName(), bytes, null);

        var result = new Publicizer().transform(bytes, ctx);
        assertThat(result.modified()).isTrue();
        assertThat(result.bytes()).isNotNull();

        assertThat(ctx.getOriginalTypeDesc().getDeclaredFields() ).allMatch(f -> !f.isPublic());
        assertThat(ctx.getOriginalTypeDesc().getDeclaredMethods()).allMatch(f -> !f.isPublic());

        assertThat(ctx.getTypeDesc().getDeclaredFields() ).allMatch(f -> f.isPublic());
        assertThat(ctx.getTypeDesc().getDeclaredMethods()).allMatch(f -> f.isPublic());
    }
}
