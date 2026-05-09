package me.zed_0xff.zombie_buddy.test;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import testjar.MemberHandleHelper;
import testjar.MemberHandleTarget;

public class PatchedTestMemberHandle {
    @Test
    void testMemberHandleResolvedAndCalled() {
        MemberHandleHelper.capturedGreet = null;
        new MemberHandleTarget().doGreet();
        assertEquals("hello world", MemberHandleHelper.capturedGreet);
    }

    @Test
    void testNonOptionalMissingDropsPatch() {
        MemberHandleHelper.skipPatchRan = false;
        new MemberHandleTarget().doSkipPatch();
        assertFalse(MemberHandleHelper.skipPatchRan);
    }

    @Test
    void testVarHandleResolvedAndCalled() {
        MemberHandleHelper.capturedVarHandle = null;
        new MemberHandleTarget().doVarHandle();
        assertEquals("via_varhandle", MemberHandleHelper.capturedVarHandle);
    }
}
