package me.zed_0xff.zombie_buddy.patches;

import me.zed_0xff.zombie_buddy.Patch;
import me.zed_0xff.zombie_buddy.Callbacks;

import zombie.core.Core;

public class Patch_Core {
    @Patch(className = "zombie.core.Core", methodName = "EndFrameUI")
    class Patch_Core_EndFrameUI {
        @Patch.OnEnter
        static void enter() {
            Callbacks.onEndFrameUI.run();
        }
    }
}
