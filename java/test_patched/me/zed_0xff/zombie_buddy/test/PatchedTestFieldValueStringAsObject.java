package me.zed_0xff.zombie_buddy.test;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import testjar.FieldValueTarget;

public class PatchedTestFieldValueStringAsObject {
    @Test
    void testStringFieldReadAsObject() {
        FieldValueTarget t = new FieldValueTarget();
        t.name = "hello";
        FieldValueTarget.capturedCounter = null;
        t.readNameAsObject();
        assertEquals("hello", FieldValueTarget.capturedCounter);
    }

    @Test
    void testStringFieldReadAsObjectReflectsInstance() {
        FieldValueTarget a = new FieldValueTarget();
        FieldValueTarget b = new FieldValueTarget();
        a.name = "alpha";
        b.name = "beta";

        FieldValueTarget.capturedCounter = null;
        a.readNameAsObject();
        assertEquals("alpha", FieldValueTarget.capturedCounter);

        FieldValueTarget.capturedCounter = null;
        b.readNameAsObject();
        assertEquals("beta", FieldValueTarget.capturedCounter);
    }
}
