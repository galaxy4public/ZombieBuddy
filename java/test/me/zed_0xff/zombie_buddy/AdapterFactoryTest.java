package me.zed_0xff.zombie_buddy;

import me.zed_0xff.zombie_buddy.Patch.Adapter.Field;
import me.zed_0xff.zombie_buddy.Patch.Adapter.Method;

public class AdapterFactoryTest {

    @SuppressWarnings("unused")
    static class Target {
        int value = 10;
        private String label = "hello";
        String name = "alpha";

        int add(int x, int y) { return x + y; }
        private String join(String a, String b) { return a + "+" + b; }
    }

    @SuppressWarnings("unused")
    static class SubTarget extends Target {
        String extra = "sub";
    }

    class TargetAdapter {
        @Field int value;
        @Field String label;
        @Field({"missing", "name"}) String nameViaFallback;

        @Method int    add(int x, int y) { return 0; }
        @Method String join(String a, String b) { return null; }
    }

    class SubAdapter {
        @Field("value") int inherited;
        @Field          int extra;
    }

    class BadFieldAdapter {
        @Field("noSuchField") Object gone;
    }

    class BadMethodAdapter {
        @Method void noSuchMethod() {}
    }

    // ---- null target ----

    /*
    @Test
    void create_nullTarget_returnsNull() {
        assertNull(AdapterFactory.create(null, TargetAdapter.class));
    }

    // ---- unwrap ----

    @Test
    void unwrap_returnsOriginalTarget() {
        Target t = new Target();
        TargetAdapter adp = AdapterFactory.create(t, TargetAdapter.class);
        assertNotNull(adp);
        assertSame(t, adp.unwrap());
    }

    // ---- @Field get ----

    @Test
    void field_get_packagePrivateField() {
        Target t = new Target();
        TargetAdapter adp = AdapterFactory.create(t, TargetAdapter.class);
        assertNotNull(adp);
        assertEquals(10, adp.value().get());
    }

    @Test
    void field_get_privateField() {
        Target t = new Target();
        TargetAdapter adp = AdapterFactory.create(t, TargetAdapter.class);
        assertNotNull(adp);
        assertEquals("hello", adp.label().get());
    }

    // ---- @Field set ----

    @Test
    void field_set_mutatesOriginalObject() {
        Target t = new Target();
        TargetAdapter adp = AdapterFactory.create(t, TargetAdapter.class);
        assertNotNull(adp);
        adp.value().set(99);
        assertEquals(99, t.value);
    }

    @Test
    void field_set_privateField_mutatesOriginalObject() {
        Target t = new Target();
        TargetAdapter adp = AdapterFactory.create(t, TargetAdapter.class);
        assertNotNull(adp);
        adp.label().set("world");
        assertEquals("world", adp.label().get());
    }

    // ---- multi-name @Field fallback ----

    @Test
    void field_multiName_skipsMissingUsesFirstPresent() {
        Target t = new Target();
        TargetAdapter adp = AdapterFactory.create(t, TargetAdapter.class);
        assertNotNull(adp);
        assertEquals("alpha", adp.nameViaFallback().get());
    }

    // ---- @Method ----

    @Test
    void method_invokesPackagePrivateMethod() {
        Target t = new Target();
        TargetAdapter adp = AdapterFactory.create(t, TargetAdapter.class);
        assertNotNull(adp);
        assertEquals(7, adp.add(3, 4));
    }

    @Test
    void method_invokesPrivateMethod() {
        Target t = new Target();
        TargetAdapter adp = AdapterFactory.create(t, TargetAdapter.class);
        assertNotNull(adp);
        assertEquals("foo+bar", adp.join("foo", "bar"));
    }

    // ---- each adapter instance wraps its own target ----

    @Test
    void field_set_independentAcrossInstances() {
        Target t1 = new Target();
        Target t2 = new Target();
        TargetAdapter adp1 = AdapterFactory.create(t1, TargetAdapter.class);
        TargetAdapter adp2 = AdapterFactory.create(t2, TargetAdapter.class);
        adp1.value().set(1);
        adp2.value().set(2);
        assertEquals(1, t1.value);
        assertEquals(2, t2.value);
    }

    @Test
    void method_usesWrappedInstance() {
        Target t1 = new Target();
        Target t2 = new Target();
        TargetAdapter adp1 = AdapterFactory.create(t1, TargetAdapter.class);
        TargetAdapter adp2 = AdapterFactory.create(t2, TargetAdapter.class);
        assertEquals(adp1.add(10, 1), adp2.add(10, 1)); // both call Target.add, same result
        assertNotSame(adp1.unwrap(), adp2.unwrap());
    }

    // ---- inherited fields ----

    @Test
    void field_inheritedField_accessible() {
        SubTarget sub = new SubTarget();
        sub.value = 42;
        SubAdapter adp = AdapterFactory.create(sub, SubAdapter.class);
        assertNotNull(adp);
        assertEquals(42, adp.inherited().get());
    }

    @Test
    void field_ownFieldOnSubclass_accessible() {
        SubTarget sub = new SubTarget();
        SubAdapter adp = AdapterFactory.create(sub, SubAdapter.class);
        assertNotNull(adp);
        assertEquals("sub", adp.extra().get());
    }

    // ---- caching ----

    @Test
    void caching_sameAdapterType_sameGeneratedClass() {
        TargetAdapter adp1 = AdapterFactory.create(new Target(), TargetAdapter.class);
        TargetAdapter adp2 = AdapterFactory.create(new Target(), TargetAdapter.class);
        assertNotNull(adp1);
        assertNotNull(adp2);
        assertSame(adp1.getClass(), adp2.getClass());
    }

    @Test
    void caching_differentAdapterTypes_differentGeneratedClasses() {
        TargetAdapter adp1  = AdapterFactory.create(new Target(), TargetAdapter.class);
        SubAdapter    adp2  = AdapterFactory.create(new SubTarget(), SubAdapter.class);
        assertNotNull(adp1);
        assertNotNull(adp2);
        assertNotSame(adp1.getClass(), adp2.getClass());
    }

    // ---- error cases ----

    @Test
    void create_unknownField_returnsNull() {
        assertNull(AdapterFactory.create(new Target(), BadFieldAdapter.class));
    }

    @Test
    void create_unknownMethod_returnsNull() {
        assertNull(AdapterFactory.create(new Target(), BadMethodAdapter.class));
    }

    // ---- FieldAccessorImpl directly ----

    @Test
    void fieldAccessorImpl_getAndSet() throws Exception {
        Target t = new Target();
        java.lang.reflect.Field f = Target.class.getDeclaredField("value");
        f.setAccessible(true);
        java.lang.invoke.VarHandle vh = MethodHandles.privateLookupIn(Target.class, MethodHandles.lookup()).unreflectVarHandle(f);
        AdapterFactory.FieldAccessorImpl<Integer> acc = new AdapterFactory.FieldAccessorImpl<>(vh, t);
        assertEquals(10, acc.get());
        acc.set(55);
        assertEquals(55, t.value);
        assertEquals(55, acc.get());
    }
    */
}
