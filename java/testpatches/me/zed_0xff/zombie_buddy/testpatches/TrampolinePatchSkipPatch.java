package me.zed_0xff.zombie_buddy.testpatches;

import me.zed_0xff.zombie_buddy.Patch;
import testjar.TrampolineTarget;

// verifies: unresolvable trampoline with SKIP_PATCH (default) — entire patch class is dropped
@Patch(className = "testjar.TrampolineTarget", methodName = "checkSkipPatch")
public class TrampolinePatchSkipPatch {

    @Patch.Trampoline(className = "testjar.TrampolineHelper", methodNames = {"noSuchMethod"})
    public static int missingMethod(Object t) { return -42; }

    @Patch.OnEnter
    public static void enter(@Patch.This Object self) {
        TrampolineTarget.skipPatchCalled = true;
    }
}
