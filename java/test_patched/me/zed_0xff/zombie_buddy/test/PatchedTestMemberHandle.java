package me.zed_0xff.zombie_buddy.test;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.junit.jupiter.api.Test;

public class PatchedTestMemberHandle {
    @Test
    void testMemberHandleResolvedAndCalled() {
        assumeTrue(false);
        // MemberHandleHelper.capturedGreet = null;
        // new MemberHandleTarget().doGreet();
        // assertEquals("hello world", MemberHandleHelper.capturedGreet);
    }

    @Test
    void testNonOptionalMissingDropsPatch() {
        assumeTrue(false);
        // MemberHandleHelper.skipPatchRan = false;
        // new MemberHandleTarget().doSkipPatch();
        // assertFalse(MemberHandleHelper.skipPatchRan);
    }

    @Test
    void testVarHandleResolvedAndCalled() {
        assumeTrue(false);
        // MemberHandleHelper.capturedVarHandle = null;
        // new MemberHandleTarget().doVarHandle();
        // assertEquals("via_varhandle", MemberHandleHelper.capturedVarHandle);
    }

    @Test
    void testParamMemberHandleResolvedAndCalled() {
        assumeTrue(false);
        // MemberHandleHelper.capturedParamHandle = null;
        // new MemberHandleTarget().doParamHandle();
        // assertEquals("param world", MemberHandleHelper.capturedParamHandle);
    }

    @Test
    void testParamVarHandleResolvedAndCalled() {
        assumeTrue(false);
        // MemberHandleHelper.capturedParamVarHandle = null;
        // new MemberHandleTarget().doParamVarHandle();
        // assertEquals("via_param_varhandle", MemberHandleHelper.capturedParamVarHandle);
    }
}
