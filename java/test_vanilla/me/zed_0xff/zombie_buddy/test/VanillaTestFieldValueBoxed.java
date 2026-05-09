package me.zed_0xff.zombie_buddy.test;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import testjar.FieldValueTarget;

public class VanillaTestFieldValueBoxed {
    @Test
    void testUnpatched() {
        FieldValueTarget t = new FieldValueTarget();
        t.counter = 42;
        FieldValueTarget.capturedCounter = null;
        t.readCounterBoxed();
        assertNull(FieldValueTarget.capturedCounter);
    }
}
