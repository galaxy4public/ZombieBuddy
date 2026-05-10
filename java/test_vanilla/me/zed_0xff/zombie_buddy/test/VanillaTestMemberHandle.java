package me.zed_0xff.zombie_buddy.test;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import testjar.MemberHandleHelper;
import testjar.MemberHandleTarget;

public class VanillaTestMemberHandle {
    @Test
    void testNoGreetWithoutPatch() {
        MemberHandleHelper.capturedGreet = null;
        new MemberHandleTarget().doGreet();
        assertNull(MemberHandleHelper.capturedGreet);
    }

    @Test
    void testNoSkipPatchWithoutPatch() {
        MemberHandleHelper.skipPatchRan = false;
        new MemberHandleTarget().doSkipPatch();
        assertFalse(MemberHandleHelper.skipPatchRan);
    }

    @Test
    void testNoVarHandleWithoutPatch() {
        MemberHandleHelper.capturedVarHandle = null;
        new MemberHandleTarget().doVarHandle();
        assertNull(MemberHandleHelper.capturedVarHandle);
    }

    @Test
    void testNoParamHandleWithoutPatch() {
        MemberHandleHelper.capturedParamHandle = null;
        new MemberHandleTarget().doParamHandle();
        assertNull(MemberHandleHelper.capturedParamHandle);
    }

    @Test
    void testNoParamVarHandleWithoutPatch() {
        MemberHandleHelper.capturedParamVarHandle = null;
        new MemberHandleTarget().doParamVarHandle();
        assertNull(MemberHandleHelper.capturedParamVarHandle);
    }
}
