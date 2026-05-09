package me.zed_0xff.zombie_buddy.testpatches;

import java.lang.invoke.MethodHandle;
import me.zed_0xff.zombie_buddy.Patch;
import testjar.MemberHandleHelper;

// verifies: non-optional unresolvable @MemberHandle drops the entire patch class
@Patch(className = "testjar.MemberHandleTarget", methodName = "doSkipPatch")
public class PatchMemberHandleSkip {
    @Patch.MemberHandle(value = "noSuchMethod", className = "testjar.MemberHandleHelper")
    static MethodHandle missing;

    @Patch.OnEnter
    public static void enter() {
        MemberHandleHelper.skipPatchRan = true;
    }
}
