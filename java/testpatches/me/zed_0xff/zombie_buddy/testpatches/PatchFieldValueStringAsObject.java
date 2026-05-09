package me.zed_0xff.zombie_buddy.testpatches;

import me.zed_0xff.zombie_buddy.Patch;
import testjar.FieldValueTarget;

@Patch(className = "testjar.FieldValueTarget", methodName = "readNameAsObject")
public class PatchFieldValueStringAsObject {
    @Patch.OnEnter
    public static void enter(@Patch.This Object self, @Patch.Field final Object name) {
        FieldValueTarget.capturedCounter = name;
    }
}
