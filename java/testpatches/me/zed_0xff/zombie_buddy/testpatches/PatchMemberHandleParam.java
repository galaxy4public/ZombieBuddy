package me.zed_0xff.zombie_buddy.testpatches;

import java.lang.invoke.MethodHandle;
import me.zed_0xff.zombie_buddy.Patch;
import testjar.MemberHandleHelper;
import testjar.MemberHandleTarget;

// verifies: parameter-level @MemberHandle resolves a method handle and injects it as a local in the advice body
@Patch(className = "testjar.MemberHandleTarget", methodName = "doParamHandle")
public class PatchMemberHandleParam {
    @Patch.OnEnter
    public static void enter(@Patch.Field String name,
                             @Patch.MemberHandle(className = "testjar.MemberHandleHelper",
                                                 returnType = String.class,
                                                 parameterTypes = {String.class, String.class}) MethodHandle greet) throws Throwable {
        MemberHandleHelper.capturedParamHandle = (String) greet.invoke(name, "param ");
    }
}
