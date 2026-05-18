package me.zed_0xff.zombie_buddy.transformers.bytebuddy;

import me.zed_0xff.zombie_buddy.transformers.*;
import me.zed_0xff.zombie_buddy.Patch;
import net.bytebuddy.asm.Advice;

import java.io.IOException;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static net.bytebuddy.matcher.ElementMatchers.*;

import net.bytebuddy.description.type.TypeDescription;

class AlternativeResolver_Patch_NameMap_Test extends AbstractTest {
    @Patch(className = "foo", methodName = "baz")
    static class Target1 {
        @Patch.OnEnter
        static void m1(@Patch.NameMap Map<String, String> nameMap) {
        }
    }

    @Test
    void test() throws IOException {
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

        var a = p.getDeclaredAnnotations().filter(x -> x.getAnnotationType().isAssignableTo(Advice.Local.class)).getOnly();
        assertThat(a.getValue("value").resolve())
            .isEqualTo(Patch.Internal.NAMEMAP_LOCAL_NAME);
    }
}
