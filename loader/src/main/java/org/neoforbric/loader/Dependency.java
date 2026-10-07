package org.neoforbric.loader;

/** Loader-neutral dependency constraint; presence and ordering are separate contracts. */
public record Dependency(String id, String range, Scheme scheme, Kind kind, Ordering ordering, Side side, String reason) {
    public enum Scheme { MAVEN }
    public enum Kind { REQUIRED, OPTIONAL, INCOMPATIBLE, DISCOURAGED }
    public enum Ordering { NONE, BEFORE, AFTER }
    public enum Side { BOTH, CLIENT, SERVER;
        public boolean applies(String side) { return this == BOTH || name().equalsIgnoreCase(side); }
    }
}
