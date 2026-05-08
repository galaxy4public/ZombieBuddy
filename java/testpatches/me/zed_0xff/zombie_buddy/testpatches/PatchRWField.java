package me.zed_0xff.zombie_buddy.testpatches;

import me.zed_0xff.zombie_buddy.Patch;

@Patch(className = "testjar.FieldValueTarget", methodName = "increment")
public class PatchRWField {
    @Patch.OnEnter
    public static void enter(@Patch.RWField("counter") int counter) {
        counter++;
    }
}
