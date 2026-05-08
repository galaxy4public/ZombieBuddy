package testjar;

public class StaticAliasTarget {
    public static int lastResult = -1;

    public void compute(int value) {
        lastResult = value;
    }
}
