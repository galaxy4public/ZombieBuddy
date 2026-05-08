package me.zed_0xff.zombie_buddy.test;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import testjar.FieldValueTarget;

public class VanillaTestRWField {
    @Test
    void testUnpatched() {
        FieldValueTarget t = new FieldValueTarget();
        t.counter = 0;
        t.increment();
        assertEquals(0, t.counter);
    }

    @Test
    void testUnpatchedExplicit() {
        FieldValueTarget t = new FieldValueTarget();
        t.counter = 0;
        t.incrementExplicit();
        assertEquals(0, t.counter);
    }
}
