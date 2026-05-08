package me.zed_0xff.zombie_buddy.test;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import testjar.FieldValueTarget;

public class PatchedTestFieldValue {
    @Test
    void testFieldValueRead() {
        FieldValueTarget t = new FieldValueTarget();
        t.name = "hello";
        FieldValueTarget.capturedName = null;
        t.doSomething();
        assertEquals("hello", FieldValueTarget.capturedName);
    }

    @Test
    void testFieldValueReadExplicit() {
        FieldValueTarget t = new FieldValueTarget();
        t.name = "hello";
        FieldValueTarget.capturedName = null;
        t.doSomethingExplicit();
        assertEquals("hello", FieldValueTarget.capturedName);
    }

    @Test
    void testFieldValueReflectsInstance() {
        FieldValueTarget a = new FieldValueTarget();
        FieldValueTarget b = new FieldValueTarget();
        a.name = "alpha";
        b.name = "beta";

        FieldValueTarget.capturedName = null;
        a.doSomething();
        assertEquals("alpha", FieldValueTarget.capturedName);

        FieldValueTarget.capturedName = null;
        b.doSomething();
        assertEquals("beta", FieldValueTarget.capturedName);
    }
}
