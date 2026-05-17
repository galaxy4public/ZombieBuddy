package me.zed_0xff.zombie_buddy.transformers;

import me.zed_0xff.zombie_buddy.Patch;
import net.bytebuddy.asm.Advice;

import java.io.IOException;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static net.bytebuddy.matcher.ElementMatchers.*;

import net.bytebuddy.description.type.TypeDescription;

class AnnotationConverter_Patch_Field_Test extends AbstractTest {
    static class Target1 {
        static void m1(@Patch.Field int foo) {}
    }

    @Test
    void test_OnEnter() throws IOException {
        var ctx = new TestClassContext(Target1.class);
        byte[] bytes = ctx.getBytes();

        var p = ctx.getMethod("m1").getParameters().getOnly();
        assertThat(p.getDeclaredAnnotations())
            .hasSize(1);

        var result = new AnnotationConverter().transform(bytes, ctx);
        assertThat(result.modified()).isTrue();
        assertThat(result.bytes()).isNotNull();

        p = ctx.getMethod("m1").getParameters().getOnly();
        assertThat(p.getDeclaredAnnotations())
            .hasSize(2);

        // var a = p.getDeclaredAnnotations().filter(x -> x.getAnnotationType().isAssignableTo(Advice.FieldValue.class)).getOnly();
        // assertThat(a.getValue("value").resolve())
        //     .isEqualTo("foo");
    }
}
