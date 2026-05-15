package me.zed_0xff.zombie_buddy.transformers;

import me.zed_0xff.zombie_buddy.Patch;
import net.bytebuddy.asm.Advice;

import java.io.IOException;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static net.bytebuddy.matcher.ElementMatchers.*;

import net.bytebuddy.description.type.TypeDescription;

class AnnotationConverterTest extends AbstractTest {
    static class Target1 {
        @Patch.OnEnter
        static void m1() {}
    }

    @Test
    void test_OnEnter() throws IOException {
        Class<?> cls = Target1.class;
        byte[] bytes = getClassBytes(cls);
        ClassContext ctx = new ClassContext(cls.getName(), bytes, null);

        var m = ctx.getMethod("m1");
        assertThat(m.getDeclaredAnnotations())
            .hasSize(1);

        var result = new AnnotationConverter().transform(bytes, ctx);
        assertThat(result.modified()).isTrue();
        assertThat(result.bytes()).isNotNull();

        m = ctx.getMethod("m1");
        assertThat(m.getDeclaredAnnotations())
            .hasSize(2);

        var a = m.getDeclaredAnnotations().filter(x -> x.getAnnotationType().isAssignableTo(Advice.OnMethodEnter.class)).getOnly();
        assertThat(a.getValue("skipOn").resolve())
            .isEqualTo(TypeDescription.ForLoadedType.of(void.class));
    }

    static class Target2 {
        @Patch.OnEnter(skipOn = true)
        static boolean m1() { return true; }
    }

    @Test
    void test_OnEnter_skipOn_true() throws IOException {
        Class<?> cls = Target2.class;
        byte[] bytes = getClassBytes(cls);
        ClassContext ctx = new ClassContext(cls.getName(), bytes, null);

        var m = ctx.getMethod("m1");
        assertThat(m.getDeclaredAnnotations())
            .hasSize(1);

        var result = new AnnotationConverter().transform(bytes, ctx);
        assertThat(result.modified()).isTrue();
        assertThat(result.bytes()).isNotNull();

        m = ctx.getMethod("m1");
        assertThat(m.getDeclaredAnnotations())
            .hasSize(2);

        var a = m.getDeclaredAnnotations().filter(x -> x.getAnnotationType().isAssignableTo(Advice.OnMethodEnter.class)).getOnly();
        assertThat(a.getValue("skipOn").resolve())
            .isEqualTo(TypeDescription.ForLoadedType.of(Advice.OnNonDefaultValue.class));
    }
}
