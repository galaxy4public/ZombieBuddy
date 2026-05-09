package me.zed_0xff.zombie_buddy.test;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import testjar.FieldValueTarget;

public class VanillaTestFieldValueStringAsObject {
    @Test
    void testUnpatched() {
        FieldValueTarget t = new FieldValueTarget();
        t.name = "hello";
        FieldValueTarget.capturedCounter = null;
        t.readNameAsObject();
        assertNull(FieldValueTarget.capturedCounter);
    }
}
