package me.zed_0xff.zombie_buddy.testpatches;

import me.zed_0xff.zombie_buddy.Patch;
import testjar.StaticAliasTarget;

@Patch(className = "testjar.StaticAliasTarget", methodName = "compute")
public class PatchStaticFieldAlias {
    @Patch.StaticFieldAlias(className = "testjar.StaticAliasHelper")
    static int multiplier;

    @Patch.OnExit
    public static void exit(@Patch.Argument(0) int value) {
        StaticAliasTarget.lastResult = value * multiplier;
    }
}
