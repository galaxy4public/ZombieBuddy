package me.zed_0xff.zombie_buddy.test;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import testjar.FieldValueTarget;

public class PatchedTestRWField {
    @Test
    void testRWFieldWritesBack() {
        FieldValueTarget t = new FieldValueTarget();
        t.counter = 0;
        t.increment();
        assertEquals(1, t.counter);
    }

    @Test
    void testRWFieldAccumulatesAcrossCalls() {
        FieldValueTarget t = new FieldValueTarget();
        t.counter = 5;
        t.increment();
        t.increment();
        t.increment();
        assertEquals(8, t.counter);
    }
}
