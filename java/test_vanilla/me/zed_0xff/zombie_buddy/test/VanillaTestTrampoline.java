package me.zed_0xff.zombie_buddy.test;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import testjar.TrampolineTarget;
import testjar.TrampolineHelper;

public class VanillaTestTrampoline {
    @Test
    void testTargetMethodsUnpatched() {
        TrampolineTarget t = new TrampolineTarget();
        assertTrue(t.isNPC());
        assertEquals("label", t.getLabel());
        assertEquals(99, TrampolineTarget.getCount());
        assertEquals("helper", TrampolineHelper.help());
    }

    @Test
    void testCheckNpcUnpatched() {
        TrampolineTarget.trampolineResult = null;
        new TrampolineTarget().checkNpc();
        assertNull(TrampolineTarget.trampolineResult);
    }

    @Test
    void testCheckRunBodyUnpatched() {
        TrampolineTarget.runBodyResult = 0;
        new TrampolineTarget().checkRunBody();
        assertEquals(0, TrampolineTarget.runBodyResult);
    }

    @Test
    void testCheckSkipPatchUnpatched() {
        TrampolineTarget.skipPatchCalled = false;
        new TrampolineTarget().checkSkipPatch();
        assertFalse(TrampolineTarget.skipPatchCalled);
    }
}
