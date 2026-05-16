package me.zed_0xff.zombie_buddy.transformers;

import me.zed_0xff.zombie_buddy.Patch;
import net.bytebuddy.asm.Advice;

import java.io.IOException;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static net.bytebuddy.matcher.ElementMatchers.*;

import net.bytebuddy.description.type.TypeDescription;

class AlternativeResolver_Patch_Field_Test extends AbstractTest {
    static class Target1 {
        static void m1(@Patch.Field("renamed") int bar) {}
        static void m2(@Patch.Field({"first", "second"}) int bar) {} // TODO
    }

    @Test
    void test_OnEnter() throws IOException {
        Class<?> cls = Target1.class;
        byte[] bytes = getClassBytes(cls);
        ClassContext ctx = new ClassContext(cls.getName(), bytes, null);

        var p = ctx.getMethod("m1").getParameters().getOnly();
        assertThat(p.getDeclaredAnnotations())
            .hasSize(1);

        var result = new AnnotationConverter().transform(bytes, ctx);
        assertThat(result.modified()).isTrue();
        assertThat(result.bytes()).isNotNull();

        result = new AlternativeResolver().transform(result.bytes(), ctx);
        assertThat(result.modified()).isTrue();
        assertThat(result.bytes()).isNotNull();

        p = ctx.getMethod("m1").getParameters().getOnly();
        assertThat(p.getDeclaredAnnotations())
            .hasSize(2);

        var a = p.getDeclaredAnnotations().filter(x -> x.getAnnotationType().isAssignableTo(Advice.FieldValue.class)).getOnly();
        assertThat(a.getValue("value").resolve())
            .isEqualTo("renamed");
    }
}
