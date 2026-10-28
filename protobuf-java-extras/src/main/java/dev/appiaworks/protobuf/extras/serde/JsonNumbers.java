package dev.appiaworks.protobuf.extras.serde;

import com.fasterxml.jackson.core.JsonGenerator;
import java.io.IOException;
import java.math.BigDecimal;

/**
 * Writes {@code double} and {@code float} values in the configured {@link JsonNumberFormat}.
 *
 * <p>The helpers only see finite values. {@code NaN} and the infinities are written as the proto3
 * strings by the callers; for safety a non-finite value is given its Java text here.
 */
public final class JsonNumbers {

    private JsonNumbers() {}

    /** Writes a finite double as a JSON number in the given format. */
    public static void writeDouble(JsonGenerator g, double v, JsonNumberFormat format) throws IOException {
        if (format == JsonNumberFormat.ECMASCRIPT && Double.isFinite(v)) {
            g.writeNumber(ecmascript(v));
        } else {
            g.writeNumber(v);
        }
    }

    /** Writes a finite float as a JSON number in the given format. */
    public static void writeFloat(JsonGenerator g, float v, JsonNumberFormat format) throws IOException {
        if (format == JsonNumberFormat.ECMASCRIPT && Float.isFinite(v)) {
            g.writeNumber(ecmascript(v));
        } else {
            g.writeNumber(v);
        }
    }

    /** The ECMAScript {@code Number::toString} text of a double. */
    public static String ecmascript(double v) {
        if (!Double.isFinite(v)) return Double.toString(v);
        if (v == 0d) return "0";
        double abs = Math.abs(v);
        return layout(v < 0, Double.toString(abs), abs, true);
    }

    /** The ECMAScript layout of a float, keeping the digits {@code Float.toString} chose. */
    public static String ecmascript(float v) {
        if (!Float.isFinite(v)) return Float.toString(v);
        if (v == 0f) return "0";
        float abs = Math.abs(v);
        return layout(v < 0, Float.toString(abs), 0d, false);
    }

    /**
     * Re-lays out a number token written by {@code JsonFormat} (its double or float text). Integer
     * tokens, with no point or exponent, are returned unchanged.
     */
    public static String ecmascriptFromText(String javaNumberText) {
        if (javaNumberText.indexOf('.') < 0 && javaNumberText.indexOf('e') < 0 && javaNumberText.indexOf('E') < 0) {
            return javaNumberText;
        }
        boolean negative = javaNumberText.startsWith("-");
        String unsigned = negative ? javaNumberText.substring(1) : javaNumberText;
        double abs = Double.parseDouble(unsigned);
        if (abs == 0d) return "0";
        return layout(negative, unsigned, abs, true);
    }

    /** Lays out the digits of a non-zero, non-negative number text; {@code abs} is its value for the one-digit check. */
    private static String layout(boolean negative, String unsignedText, double abs, boolean oneDigitCheck) {
        BigDecimal bd = new BigDecimal(unsignedText).stripTrailingZeros();
        String digits = bd.unscaledValue().toString();
        int n = digits.length() - bd.scale();
        if (oneDigitCheck && digits.length() == 2) {
            int d1 = digits.charAt(0) - '0';
            int d2 = digits.charAt(1) - '0';
            int rounded = d2 > 5 || (d2 == 5 && (d1 & 1) == 1) ? d1 + 1 : d1;
            int n1 = n;
            if (rounded == 10) {
                rounded = 1;
                n1 = n + 1;
            }
            String candidate = render(false, Integer.toString(rounded), n1);
            if (Double.parseDouble(candidate) == abs) {
                digits = Integer.toString(rounded);
                n = n1;
            }
        }
        return render(negative, digits, n);
    }

    /** ECMA-262 Number::toString for the digits {@code d1..dk} and the exponent {@code n} (0.d1..dk x 10^n). */
    private static String render(boolean negative, String digits, int n) {
        int k = digits.length();
        StringBuilder sb = new StringBuilder(k + 8);
        if (negative) sb.append('-');
        if (k <= n && n <= 21) {
            sb.append(digits);
            for (int i = 0; i < n - k; i++) sb.append('0');
        } else if (0 < n && n <= 21) {
            sb.append(digits, 0, n).append('.').append(digits, n, k);
        } else if (-6 < n && n <= 0) {
            sb.append("0.");
            for (int i = 0; i < -n; i++) sb.append('0');
            sb.append(digits);
        } else {
            sb.append(digits.charAt(0));
            if (k > 1) sb.append('.').append(digits, 1, k);
            int e = n - 1;
            sb.append('e').append(e < 0 ? '-' : '+').append(Math.abs(e));
        }
        return sb.toString();
    }
}
