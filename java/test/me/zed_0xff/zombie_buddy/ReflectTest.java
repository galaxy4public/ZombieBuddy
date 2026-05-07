package me.zed_0xff.zombie_buddy;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

public class ReflectTest {

    @SuppressWarnings("unused")
    static class Subject {
        public static String publicStaticField = "ps";
        private static int privateStaticField = -1;
        public int publicInstanceField = 1;
        private String privateInstanceField = "pi";

        public String publicInstanceMethod() { return "pub"; }
        private int privateInstanceMethod() { return -1; }
        public static String publicStaticMethod() { return "pubs"; }
        private static void privateStaticMethod() {}
    }

    @SuppressWarnings("unused")
    static class SubSubject extends Subject {
        public double subPublicField = 3.14;
        private boolean subPrivateField = true;
        public void subPublicMethod() {}
        private void subPrivateMethod() {}
    }

    private static Set<String> methodNames(List<Method> methods) {
        return methods.stream().map(Method::getName).collect(Collectors.toSet());
    }

    private static Set<String> fieldNames(List<Field> fields) {
        return fields.stream().map(Field::getName).collect(Collectors.toSet());
    }

    // --- methods() ---

    @Test
    void methods_noFlags_returnsAllNonObjectMethods() {
        Set<String> names = methodNames(Reflect.on(Subject.class).methods());
        assertEquals(Set.of("publicInstanceMethod", "privateInstanceMethod", "publicStaticMethod", "privateStaticMethod"), names);
    }

    @Test
    void methods_noFlags_excludesObjectMethods() {
        Set<String> names = methodNames(Reflect.on(Subject.class).methods());
        assertFalse(names.contains("toString"));
        assertFalse(names.contains("hashCode"));
        assertFalse(names.contains("equals"));
        assertFalse(names.contains("getClass"));
    }

    @Test
    void methods_noFlags_includesInheritedMethods() {
        Set<String> names = methodNames(Reflect.on(SubSubject.class).methods());
        assertTrue(names.containsAll(Set.of(
            "subPublicMethod", "subPrivateMethod",
            "publicInstanceMethod", "privateInstanceMethod", "publicStaticMethod", "privateStaticMethod"
        )));
    }

    @Test
    void methods_onInstance_worksLikeOnClass() {
        assertEquals(
            methodNames(Reflect.on(Subject.class).methods()),
            methodNames(Reflect.on(new Subject()).methods())
        );
    }

    @Test
    void methods_publicFlag_returnsOnlyPublic() {
        Set<String> names = methodNames(Reflect.on(Subject.class).methods(Reflect.Flag.PUBLIC));
        assertEquals(Set.of("publicInstanceMethod", "publicStaticMethod"), names);
    }

    @Test
    void methods_privateFlag_returnsOnlyPrivate() {
        Set<String> names = methodNames(Reflect.on(Subject.class).methods(Reflect.Flag.PRIVATE));
        assertEquals(Set.of("privateInstanceMethod", "privateStaticMethod"), names);
    }

    @Test
    void methods_staticFlag_returnsOnlyStatic() {
        Set<String> names = methodNames(Reflect.on(Subject.class).methods(Reflect.Flag.STATIC));
        assertEquals(Set.of("publicStaticMethod", "privateStaticMethod"), names);
    }

    @Test
    void methods_instanceFlag_returnsOnlyInstance() {
        Set<String> names = methodNames(Reflect.on(Subject.class).methods(Reflect.Flag.INSTANCE));
        assertEquals(Set.of("publicInstanceMethod", "privateInstanceMethod"), names);
    }

    @Test
    void methods_publicAndStatic_returnsIntersection() {
        Set<String> names = methodNames(Reflect.on(Subject.class).methods(Reflect.Flag.PUBLIC, Reflect.Flag.STATIC));
        assertEquals(Set.of("publicStaticMethod"), names);
    }

    @Test
    void methods_publicAndInstance_returnsIntersection() {
        Set<String> names = methodNames(Reflect.on(Subject.class).methods(Reflect.Flag.PUBLIC, Reflect.Flag.INSTANCE));
        assertEquals(Set.of("publicInstanceMethod"), names);
    }

    @Test
    void methods_publicOrPrivate_returnsUnion() {
        Set<String> names = methodNames(Reflect.on(Subject.class).methods(Reflect.Flag.PUBLIC, Reflect.Flag.PRIVATE));
        assertEquals(Set.of("publicInstanceMethod", "privateInstanceMethod", "publicStaticMethod", "privateStaticMethod"), names);
    }

    @Test
    void methods_nullFlag_treatedAsNoFilter() {
        assertEquals(
            methodNames(Reflect.on(Subject.class).methods()),
            methodNames(Reflect.on(Subject.class).methods((Reflect.Flag) null))
        );
    }

    @Test
    void methods_nullChain_returnsEmptyList() {
        assertTrue(Reflect.on("no.such.Class").methods().isEmpty());
    }

    // --- fields() ---

    @Test
    void fields_noFlags_returnsAllFields() {
        Set<String> names = fieldNames(Reflect.on(Subject.class).fields());
        assertEquals(Set.of("publicStaticField", "privateStaticField", "publicInstanceField", "privateInstanceField"), names);
    }

    @Test
    void fields_noFlags_includesInheritedFields() {
        Set<String> names = fieldNames(Reflect.on(SubSubject.class).fields());
        assertTrue(names.containsAll(Set.of(
            "subPublicField", "subPrivateField",
            "publicStaticField", "privateStaticField", "publicInstanceField", "privateInstanceField"
        )));
    }

    @Test
    void fields_onInstance_worksLikeOnClass() {
        assertEquals(
            fieldNames(Reflect.on(Subject.class).fields()),
            fieldNames(Reflect.on(new Subject()).fields())
        );
    }

    @Test
    void fields_publicFlag_returnsOnlyPublic() {
        Set<String> names = fieldNames(Reflect.on(Subject.class).fields(Reflect.Flag.PUBLIC));
        assertEquals(Set.of("publicStaticField", "publicInstanceField"), names);
    }

    @Test
    void fields_privateFlag_returnsOnlyPrivate() {
        Set<String> names = fieldNames(Reflect.on(Subject.class).fields(Reflect.Flag.PRIVATE));
        assertEquals(Set.of("privateStaticField", "privateInstanceField"), names);
    }

    @Test
    void fields_staticFlag_returnsOnlyStatic() {
        Set<String> names = fieldNames(Reflect.on(Subject.class).fields(Reflect.Flag.STATIC));
        assertEquals(Set.of("publicStaticField", "privateStaticField"), names);
    }

    @Test
    void fields_instanceFlag_returnsOnlyInstance() {
        Set<String> names = fieldNames(Reflect.on(Subject.class).fields(Reflect.Flag.INSTANCE));
        assertEquals(Set.of("publicInstanceField", "privateInstanceField"), names);
    }

    @Test
    void fields_publicAndStatic_returnsIntersection() {
        Set<String> names = fieldNames(Reflect.on(Subject.class).fields(Reflect.Flag.PUBLIC, Reflect.Flag.STATIC));
        assertEquals(Set.of("publicStaticField"), names);
    }

    @Test
    void fields_nullFlag_treatedAsNoFilter() {
        assertEquals(
            fieldNames(Reflect.on(Subject.class).fields()),
            fieldNames(Reflect.on(Subject.class).fields((Reflect.Flag) null))
        );
    }

    @Test
    void fields_nullChain_returnsEmptyList() {
        assertTrue(Reflect.on("no.such.Class").fields().isEmpty());
    }
}
