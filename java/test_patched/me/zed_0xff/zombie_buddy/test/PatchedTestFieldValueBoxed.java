package me.zed_0xff.zombie_buddy.test;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import testjar.FieldValueTarget;

public class PatchedTestFieldValueBoxed {
    @Test
    void testBoxedFieldReadYieldsInteger() {
        FieldValueTarget t = new FieldValueTarget();
        t.counter = 42;
        FieldValueTarget.capturedCounter = null;
        t.readCounterBoxed();
        assertEquals(Integer.valueOf(42), FieldValueTarget.capturedCounter);
    }

    @Test
    void testBoxedFieldReadReflectsCurrentValue() {
        FieldValueTarget t = new FieldValueTarget();
        FieldValueTarget.capturedCounter = null;
        t.counter = 7;
        t.readCounterBoxed();
        assertEquals(7, FieldValueTarget.capturedCounter);
        FieldValueTarget.capturedCounter = null;
        t.counter = 99;
        t.readCounterBoxed();
        assertEquals(99, FieldValueTarget.capturedCounter);
    }
}
