package me.zed_0xff.zombie_buddy.testpatches;

import java.lang.invoke.VarHandle;
import me.zed_0xff.zombie_buddy.Patch;
import testjar.MemberHandleHelper;

// verifies: @MemberHandle resolves a field by name and makes it accessible via VarHandle
// Uses MemberHandleHelper (already loaded via PatchMemberHandle) as target to avoid the
// preload-time limitation: cannot resolve to the patch target class during its own new-load transform.
@Patch(className = "testjar.MemberHandleTarget", methodName = "doVarHandle")
public class PatchMemberHandleVarHandle {
    // field name inferred from stub: targets MemberHandleHelper.capturedVarHandle (static String)
    @Patch.MemberHandle(className = "testjar.MemberHandleHelper")
    public static VarHandle capturedVarHandle;

    @Patch.OnEnter
    public static void enter() throws Throwable {
        capturedVarHandle.set("via_varhandle");
    }
}
