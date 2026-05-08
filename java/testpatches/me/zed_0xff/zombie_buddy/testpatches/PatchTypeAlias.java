package me.zed_0xff.zombie_buddy.testpatches;

import me.zed_0xff.zombie_buddy.Patch;
import testjar.TypeAliasTarget;

@Patch(className = "testjar.TypeAliasTarget", methodName = "process")
public class PatchTypeAlias {

    @Patch.TypeAlias("testjar.TypeAliasTarget$Result")
    static class Result {
        String value;
        Result(String v) {}
    }

    @Patch.OnEnter
    public static void enter(@Patch.Argument(0) String input) {
        Result r = new Result(input);
        TypeAliasTarget.lastResult = r.value;
    }
}
