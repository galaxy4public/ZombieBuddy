package me.zed_0xff.zombie_buddy.patches;

import me.zed_0xff.zombie_buddy.Callbacks;
import me.zed_0xff.zombie_buddy.Patch;

public class Patch_GameWindow {
    @Patch(className = "zombie.GameWindow", methodName = "init")
    public static class Patch_GameWindow_init {
        @Patch.OnExit
        static void exit() {
            Callbacks.onGameInitComplete.run();
        }
    }
}
