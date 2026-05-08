package me.zed_0xff.zombie_buddy.test;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import testjar.TypeAliasTarget;

public class VanillaTestTypeAlias {
    @Test
    void testUnpatched() {
        TypeAliasTarget.lastResult = null;
        new TypeAliasTarget().process("hello");
        assertNull(TypeAliasTarget.lastResult);
    }
}
