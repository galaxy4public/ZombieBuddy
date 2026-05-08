package me.zed_0xff.zombie_buddy.test;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import testjar.TypeAliasTarget;

public class PatchedTestTypeAlias {
    @Test
    void testTypeAliasCreatesRealInnerInstance() {
        TypeAliasTarget.lastResult = null;
        new TypeAliasTarget().process("hello");
        assertEquals("processed:hello", TypeAliasTarget.lastResult);
    }

    @Test
    void testTypeAliasWithDifferentInput() {
        TypeAliasTarget.lastResult = null;
        new TypeAliasTarget().process("world");
        assertEquals("processed:world", TypeAliasTarget.lastResult);
    }
}
