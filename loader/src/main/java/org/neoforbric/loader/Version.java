package org.neoforbric.loader;

import java.math.BigInteger;
import java.util.*;
import java.util.regex.*;

/** Strict SemVer for our prototype descriptor, not a replacement for Fabric or Maven version predicates. */
public record Version(BigInteger major, BigInteger minor, BigInteger patch, List<String> pre) implements Comparable<Version> {
    private static final Pattern FORMAT = Pattern.compile("(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)(?:-([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?(?:\\+[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?");

    public Version { pre = List.copyOf(pre); }
    public static Version parse(String text) {
        Matcher match = FORMAT.matcher(text);
        if (text.length() > 128 || !match.matches()) throw new Failure("VERSION_SYNTAX", "Expected SemVer, got " + text);
        List<String> pre = match.group(4) == null ? List.of() : List.of(match.group(4).split("\\."));
        if (pre.stream().anyMatch(p -> p.matches("[0-9]+") && p.length() > 1 && p.startsWith("0")))
            throw new Failure("VERSION_SYNTAX", "Leading zero in prerelease version " + text);
        return new Version(new BigInteger(match.group(1)), new BigInteger(match.group(2)), new BigInteger(match.group(3)), pre);
    }

    @Override public int compareTo(Version other) {
        int c = major.compareTo(other.major);
        if (c == 0) c = minor.compareTo(other.minor);
        if (c == 0) c = patch.compareTo(other.patch);
        if (c != 0) return c;
        if (pre.isEmpty() || other.pre.isEmpty()) return Boolean.compare(pre.isEmpty(), other.pre.isEmpty());
        for (int i = 0; i < Math.min(pre.size(), other.pre.size()); i++) {
            String a = pre.get(i), b = other.pre.get(i);
            boolean an = a.matches("[0-9]+"), bn = b.matches("[0-9]+");
            c = an && bn ? new BigInteger(a).compareTo(new BigInteger(b)) : an != bn ? (an ? -1 : 1) : a.compareTo(b);
            if (c != 0) return c;
        }
        return Integer.compare(pre.size(), other.pre.size());
    }

    public static boolean matches(String expression, String actual) {
        Version value = parse(actual);
        if (expression.equals("*")) return true;
        boolean result = true;
        for (String term : expression.trim().split("\\s+")) {
            Matcher match = Pattern.compile("(>=|<=|>|<|=)?(.+)").matcher(term);
            if (!match.matches()) throw new Failure("VERSION_SYNTAX", "Invalid prototype range " + expression);
            String operator = Objects.requireNonNullElse(match.group(1), "=");
            int c = value.compareTo(parse(match.group(2)));
            result &= switch (operator) { case ">=" -> c >= 0; case "<=" -> c <= 0; case ">" -> c > 0; case "<" -> c < 0; default -> c == 0; };
        }
        return result;
    }
}
