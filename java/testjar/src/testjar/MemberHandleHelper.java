package testjar;

public class MemberHandleHelper {
    public static String capturedGreet = null;
    public static boolean skipPatchRan = false;
    public static String capturedVarHandle = null;

    public static String greet(String name, String prefix) { return prefix + name; }
    public static String noArgsGreet() { return "no-args"; }
}
