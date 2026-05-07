package me.zed_0xff.zombie_buddy.testpatches;

import me.zed_0xff.zombie_buddy.Patch;
import testjar.TrampolineTarget;

// verifies: explicit className on trampoline, static target, name inference
// NOTE: trampolines can only target classes *other* than the class being intercepted at first load.
//       Class.forName inside the transform would cause a duplicate class definition error.
@Patch(className = "testjar.TrampolineTarget", methodName = "checkNpc")
public class TrampolinePatch {

    @Patch.Trampoline(className = "testjar.TrampolineHelper")  // explicit class, name inferred → help()
    public static String help() { return "fallback"; }

    @Patch.OnEnter
    public static void enter(@Patch.This Object self) {
        TrampolineTarget.trampolineResult = help();
    }
}
