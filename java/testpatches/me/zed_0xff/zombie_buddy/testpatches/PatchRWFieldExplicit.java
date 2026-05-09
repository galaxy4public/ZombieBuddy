package me.zed_0xff.zombie_buddy.testpatches;

import me.zed_0xff.zombie_buddy.Patch;

@Patch(className = "testjar.FieldValueTarget", methodName = "incrementExplicit")
public class PatchRWFieldExplicit {
    @Patch.OnEnter
    public static void enter(@Patch.FieldRW("counter") int counter) {
        counter++;
    }
}
