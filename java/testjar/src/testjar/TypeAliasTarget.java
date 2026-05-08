package testjar;

public class TypeAliasTarget {
    public static String lastResult = null;

    public void process(String input) {}

    static class Result {
        final String value;
        Result(String v) { this.value = "processed:" + v; }
    }
}
