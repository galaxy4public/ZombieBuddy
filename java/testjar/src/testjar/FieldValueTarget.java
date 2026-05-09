package testjar;

public class FieldValueTarget {
    public String name = "initial";
    public int counter = 0;

    public static String capturedName = null;
    public static Object capturedCounter = null;

    public void doSomething() {}
    public void doSomethingExplicit() {}
    public void increment() {}
    public void incrementExplicit() {}
    public void readCounterBoxed() {}
    public void readNameAsObject() {}
}
