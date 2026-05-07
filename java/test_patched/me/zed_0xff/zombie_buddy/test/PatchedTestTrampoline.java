package me.zed_0xff.zombie_buddy.test;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import testjar.TrampolineTarget;

public class PatchedTestTrampoline {
    @Test
    void testTrampolinesResolvedAndCalled() {
        TrampolineTarget.trampolineResult = null;
        new TrampolineTarget().checkNpc();
        // help→"helper" (name inferred, explicit className = TrampolineHelper)
        assertEquals("helper", TrampolineTarget.trampolineResult);
    }

    @Test
    void testRunBodyFallback() {
        TrampolineTarget.runBodyResult = 0;
        new TrampolineTarget().checkRunBody();
        // trampoline body ran unchanged, returned -42
        assertEquals(-42, TrampolineTarget.runBodyResult);
    }

    @Test
    void testSkipPatchDropsPatch() {
        TrampolineTarget.skipPatchCalled = false;
        new TrampolineTarget().checkSkipPatch();
        // patch class was dropped — advice never ran
        assertFalse(TrampolineTarget.skipPatchCalled);
    }
}
