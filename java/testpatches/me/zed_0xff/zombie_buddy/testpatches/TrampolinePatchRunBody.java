package me.zed_0xff.zombie_buddy.testpatches;

import me.zed_0xff.zombie_buddy.Patch;
import testjar.TrampolineTarget;

// verifies: unresolvable trampoline with RUN_BODY — patch class is kept, fallback body runs
@Patch(className = "testjar.TrampolineTarget", methodName = "checkRunBody")
public class TrampolinePatchRunBody {

    @Patch.Trampoline(className = "testjar.TrampolineHelper", methodNames = {"noSuchMethod"}, onMethodMissing = Patch.OnMethodMissing.RUN_BODY)
    public static int missingMethod(Object t) { return -42; }

    @Patch.OnEnter
    public static void enter(@Patch.This Object self) {
        TrampolineTarget.runBodyResult = missingMethod(self);
    }
}
