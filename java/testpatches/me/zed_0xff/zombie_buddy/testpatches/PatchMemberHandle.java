package me.zed_0xff.zombie_buddy.testpatches;

import java.lang.invoke.MethodHandle;

import me.zed_0xff.zombie_buddy.Patch;
import testjar.MemberHandleHelper;

// verifies: @MethodHandle resolves a method by name+signature and makes it callable via MethodHandle
@Patch(className = "testjar.MemberHandleTarget", methodName = "doGreet")
public class PatchMemberHandle {
    // targets MemberHandleHelper (a separate class, already loaded before the transform callback)
    @Patch.MethodHandle(className = "testjar.MemberHandleHelper", returnType = String.class, paramTypes = {String.class, String.class})
    public static MethodHandle greet;

    @Patch.OnEnter
    public static void enter(@Patch.Field String name) throws Throwable {
        MemberHandleHelper.capturedGreet = (String) greet.invoke(name, "hello ");
    }
}
