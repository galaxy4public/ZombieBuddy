package me.zed_0xff.zombie_buddy.patches;

import me.zed_0xff.zombie_buddy.Callbacks;
import me.zed_0xff.zombie_buddy.Patch;

public class Patch_Exposer {
    @Patch(className = "zombie.Lua.LuaManager$Exposer", methodName = "exposeAll", warmUp = true, IKnowWhatIAmDoing = true)
    public static class Patch_exposeAll {
        @Patch.OnExit
        public static void exit() {
            Callbacks.afterExposeAll.run();
        }
    }
}
