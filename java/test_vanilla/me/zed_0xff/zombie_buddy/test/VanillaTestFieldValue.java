package me.zed_0xff.zombie_buddy.test;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import testjar.FieldValueTarget;

public class VanillaTestFieldValue {
    @Test
    void testUnpatched() {
        FieldValueTarget t = new FieldValueTarget();
        t.name = "hello";
        FieldValueTarget.capturedName = null;
        t.doSomething();
        assertNull(FieldValueTarget.capturedName);
    }

    @Test
    void testUnpatchedExplicit() {
        FieldValueTarget t = new FieldValueTarget();
        t.name = "hello";
        FieldValueTarget.capturedName = null;
        t.doSomethingExplicit();
        assertNull(FieldValueTarget.capturedName);
    }
}
