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

    @Test
    void testParamMemberHandleResolvedAndCalled() {
        MemberHandleHelper.capturedParamHandle = null;
        new MemberHandleTarget().doParamHandle();
        assertEquals("param world", MemberHandleHelper.capturedParamHandle);
    }

    @Test
    void testParamVarHandleResolvedAndCalled() {
        MemberHandleHelper.capturedParamVarHandle = null;
        new MemberHandleTarget().doParamVarHandle();
        assertEquals("via_param_varhandle", MemberHandleHelper.capturedParamVarHandle);
    }
}
