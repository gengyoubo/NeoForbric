package demo.neoprobe;

public final class AccessTarget {
    private static final String VALUE = initialize();
    private static String initialize() { return "before"; }
    private static String value() { return VALUE; }
}
