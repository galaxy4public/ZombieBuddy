package me.zed_0xff.zombie_buddy.test;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import testjar.StaticAliasTarget;

public class VanillaTestStaticFieldAlias {
    @Test
    void testUnpatched() {
        StaticAliasTarget t = new StaticAliasTarget();
        t.compute(5);
        assertEquals(5, StaticAliasTarget.lastResult);
    }
}
