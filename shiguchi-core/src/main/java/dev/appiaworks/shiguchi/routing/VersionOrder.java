package dev.appiaworks.shiguchi.routing;

import java.util.Comparator;

/**
 * Orders backend version strings the way people write them: part by part, numbers numerically.
 *
 * <p>{@code 1.10.0} comes after {@code 1.9.2}, {@code v2} after {@code v1}, and a pre-release
 * suffix sorts before its release ({@code 2.0.0-rc1} before {@code 2.0.0}), as semver has it. A
 * leading {@code v} is ignored. Parts that are not numbers compare as text. A missing version
 * (null or blank) sorts before every version: an instance that does not say what it runs is
 * treated as the oldest, which is the cautious reading.
 */
public final class VersionOrder implements Comparator<String> {

    public static final VersionOrder INSTANCE = new VersionOrder();

    private VersionOrder() {}

    @Override
    public int compare(String a, String b) {
        boolean aMissing = a == null || a.isBlank();
        boolean bMissing = b == null || b.isBlank();
        if (aMissing || bMissing) return aMissing == bMissing ? 0 : aMissing ? -1 : 1;

        String[] aCore = strip(a).split("-", 2);
        String[] bCore = strip(b).split("-", 2);
        int core = compareParts(aCore[0].split("[._+]"), bCore[0].split("[._+]"));
        if (core != 0) return core;
        // Same release: no suffix is the release itself, which follows its pre-releases.
        boolean aPre = aCore.length > 1;
        boolean bPre = bCore.length > 1;
        if (aPre != bPre) return aPre ? -1 : 1;
        return aPre ? compareParts(aCore[1].split("[._]"), bCore[1].split("[._]")) : 0;
    }

    private static String strip(String version) {
        String v = version.trim();
        return (v.startsWith("v") || v.startsWith("V")) && v.length() > 1 && Character.isDigit(v.charAt(1))
                ? v.substring(1)
                : v;
    }

    private static int compareParts(String[] a, String[] b) {
        int n = Math.max(a.length, b.length);
        for (int i = 0; i < n; i++) {
            // A missing part counts as 0, so 1.2 equals 1.2.0.
            String x = i < a.length ? a[i] : "0";
            String y = i < b.length ? b[i] : "0";
            int c = comparePart(x, y);
            if (c != 0) return c;
        }
        return 0;
    }

    private static int comparePart(String x, String y) {
        boolean xNum = isNumber(x);
        boolean yNum = isNumber(y);
        if (xNum && yNum) {
            String xs = x.replaceFirst("^0+(?=.)", "");
            String ys = y.replaceFirst("^0+(?=.)", "");
            return xs.length() != ys.length() ? Integer.compare(xs.length(), ys.length()) : xs.compareTo(ys);
        }
        // Semver: numeric identifiers sort before alphanumeric ones.
        if (xNum != yNum) return xNum ? -1 : 1;
        return x.compareTo(y);
    }

    private static boolean isNumber(String s) {
        if (s.isEmpty()) return false;
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) return false;
        }
        return true;
    }
}
