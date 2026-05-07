package testjar;

public class TrampolineTarget {
    // tracking fields written by patched methods
    public static String trampolineResult = null;
    public static int    runBodyResult    = 0;
    public static boolean skipPatchCalled = false;

    // methods resolved by trampolines
    public boolean isNPC()      { return true;    }  // alt name in older versions: isNpc
    public String  getLabel()   { return "label"; }
    public static int getCount(){ return 99;      }

    // methods patched by the three test patch classes
    public void checkNpc()       {}
    public void checkRunBody()   {}
    public void checkSkipPatch() {}
}
