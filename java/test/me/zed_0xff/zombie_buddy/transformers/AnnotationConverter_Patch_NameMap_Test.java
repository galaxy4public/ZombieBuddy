package me.zed_0xff.zombie_buddy.transformers;

import me.zed_0xff.zombie_buddy.Patch;
import net.bytebuddy.asm.Advice;

import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import static org.assertj.core.api.Assertions.*;

class AlternativeResolver_Patch_NameMap_Test extends AbstractTest {
    protected static Stream<Arguments> provideClasses() {
        return Stream.of(
                Arguments.of(me.zed_0xff.zombie_buddy.transformers.asmtree.AnnotationConverter.class),
                Arguments.of(me.zed_0xff.zombie_buddy.transformers.bytebuddy.AnnotationConverter.class)
                );
    }

    @Patch(className = "me.zed_0xff.TestClass", methodName = "getFoo")
    static class Target1 {
        @Patch.OnEnter
        static void m1(@Patch.Local(Patch.NAMEMAP_LOCAL_NAME) Map<String, String> nameMap) {
        }
    }

    @ParameterizedTest
    @MethodSource("provideClasses")
    void test(Class<? extends Transformer> cls) throws Exception {
        var ctx = new TestClassContext(Target1.class);
        byte[] bytes = ctx.getBytes();

        var p = ctx.getMethod("m1").getParameters().getOnly();
        assertThat(p.getDeclaredAnnotations())
            .hasSize(1);

        Transformer transformer = cls.getDeclaredConstructor().newInstance();
        var result = transformer.transform(bytes, ctx);

        assertThat(result.modified()).isTrue();
        assertThat(result.bytes()).isNotNull();

        p = ctx.getMethod("m1").getParameters().getOnly();
        assertThat(p.getDeclaredAnnotations())
            .hasSize(2);

        var a = p.getDeclaredAnnotations().filter(x -> x.getAnnotationType().isAssignableTo(Advice.Local.class)).getOnly();
        assertThat(a.getValue("value").resolve())
            .isEqualTo(Patch.NAMEMAP_LOCAL_NAME);
    }
}
