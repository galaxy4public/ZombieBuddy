package me.zed_0xff.zombie_buddy.test;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import testjar.StaticAliasTarget;

public class PatchedTestStaticFieldAlias {
    @Test
    void testMultiplierApplied() {
        StaticAliasTarget t = new StaticAliasTarget();
        t.compute(5);
        assertEquals(15, StaticAliasTarget.lastResult);
    }

    @Test
    void testMultiplierOtherValue() {
        StaticAliasTarget t = new StaticAliasTarget();
        t.compute(7);
        assertEquals(21, StaticAliasTarget.lastResult);
    }
}
