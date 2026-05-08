package me.zed_0xff.zombie_buddy.testpatches;

import me.zed_0xff.zombie_buddy.Patch;
import testjar.FieldValueTarget;

@Patch(className = "testjar.FieldValueTarget", methodName = "doSomethingExplicit")
public class PatchFieldValueExplicit {
    @Patch.OnEnter
    public static void enter(@Patch.This Object self, @Patch.Field("name") String name) {
        FieldValueTarget.capturedName = name;
    }
}
