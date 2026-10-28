package dev.appiaworks.protobuf.extras.serde;

/** How {@code double} and {@code float} values are laid out in JSON output. Integers are never affected. */
public enum JsonNumberFormat {
    /** {@code Double.toString}/{@code Float.toString} text, for example {@code 1.0} and {@code 1.0E20}. The default. */
    JAVA,
    /**
     * The JavaScript {@code Number::toString} layout, for example {@code 1}, {@code 100000000000000000000}
     * and {@code 5e-324}.
     */
    ECMASCRIPT
}
