package dev.appiaworks.protobuf.extras;

import dev.appiaworks.protobuf.extras.api.ApiStatus;
import java.math.BigDecimal;
import java.util.Objects;

/**
 * A {@code google.type.Money} as Java values: the currency code and one exact decimal amount in
 * place of the message's separate {@code units} and {@code nanos}.
 *
 * @param currencyCode the ISO 4217 code, e.g. {@code USD}; kept as text because the message also
 *     allows codes {@link java.util.Currency} does not know
 * @param amount the amount; a Money holds at most 9 fractional digits
 */
@ApiStatus(value = ApiStatus.Status.EXPERIMENTAL, since = "0.14.0")
public record MoneyValue(String currencyCode, BigDecimal amount) {

    public MoneyValue {
        Objects.requireNonNull(currencyCode, "currencyCode");
        Objects.requireNonNull(amount, "amount");
    }
}
