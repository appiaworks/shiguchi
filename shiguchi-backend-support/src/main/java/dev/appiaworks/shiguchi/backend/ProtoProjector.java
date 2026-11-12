package dev.appiaworks.shiguchi.backend;

import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.Message;
import dev.appiaworks.protobuf.extras.api.ApiStatus;
import java.util.HashMap;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * Builds a protobuf message from named values, so a row does not need a hand-written
 * {@code switch} of setters.
 *
 * <pre>{@code
 * ProtoProjector<Book> projector = ProtoProjector.of(Book.getDefaultInstance());
 * Book book = projector.project(Map.of("id", "7", "title", "Dune", "year", 1965));
 * }</pre>
 *
 * <p>Values are matched to message fields by name (case-insensitive, {@code snake_case} or
 * {@code camelCase}) and set through the message's descriptor, so any message type works without
 * generated code of its own. Conversions applied:
 *
 * <table>
 * <caption>Java value to protobuf field</caption>
 * <tr><th>Field</th><th>Accepted values</th></tr>
 * <tr><td>int / long / float / double</td><td>any {@link Number}</td></tr>
 * <tr><td>bool</td><td>{@link Boolean}</td></tr>
 * <tr><td>string</td><td>any value, via {@code toString()}; enums by {@code name()}</td></tr>
 * <tr><td>bytes</td><td>{@code byte[]}, {@code ByteString}</td></tr>
 * <tr><td>enum</td><td>a Java enum (by name), a number (by value), a name, a descriptor value</td></tr>
 * <tr><td>{@code google.protobuf.Timestamp}</td><td>{@code Instant}, {@code OffsetDateTime},
 *     {@code ZonedDateTime}, {@code LocalDateTime} (as UTC), {@code java.util.Date}</td></tr>
 * <tr><td>{@code google.protobuf.Duration}</td><td>{@code java.time.Duration}</td></tr>
 * <tr><td>repeated</td><td>an {@code Iterable} of the above</td></tr>
 * </table>
 *
 * Anything else (a nested message, a map, a type of your own) needs a {@linkplain
 * Builder#mapper mapper} for that field. A {@code null} value leaves the field unset. A key that
 * names no field and has no mapper is a programming error and fails with {@link
 * IllegalArgumentException}.
 *
 * <p>Instances are immutable and thread-safe.
 */
@ApiStatus(value = ApiStatus.Status.EXPERIMENTAL, since = "0.21.0")
public final class ProtoProjector<M extends Message> {

    private final M prototype;
    private final Map<String, FieldDescriptor> fieldsByName;
    private final Map<String, BiConsumer<Message.Builder, Object>> mappers;

    private ProtoProjector(M prototype, Map<String, BiConsumer<Message.Builder, Object>> mappers) {
        this.prototype = prototype;
        this.mappers = Map.copyOf(mappers);
        Descriptor descriptor = prototype.getDescriptorForType();
        Map<String, FieldDescriptor> byName = new HashMap<>();
        for (FieldDescriptor field : descriptor.getFields()) {
            byName.put(Names.normalize(field.getName()), field);
        }
        this.fieldsByName = Map.copyOf(byName);
    }

    /** A projector for messages like {@code prototype}, e.g. {@code Book.getDefaultInstance()}. */
    public static <M extends Message> ProtoProjector<M> of(M prototype) {
        return new ProtoProjector<>(prototype, Map.of());
    }

    public static <M extends Message> Builder<M> builder(M prototype) {
        return new Builder<>(prototype);
    }

    /** The message type this projector builds. */
    public Descriptor descriptor() {
        return prototype.getDescriptorForType();
    }

    /**
     * Builds a message from {@code values}, keyed by field name.
     *
     * @throws IllegalArgumentException for an unknown key or a value that cannot convert
     */
    @SuppressWarnings("unchecked")
    public M project(Map<String, ?> values) {
        Message.Builder builder = prototype.newBuilderForType();
        for (Map.Entry<String, ?> entry : values.entrySet()) {
            Object value = entry.getValue();
            if (value == null) {
                continue;
            }
            String key = Names.normalize(entry.getKey());
            BiConsumer<Message.Builder, Object> mapper = mappers.get(key);
            if (mapper != null) {
                mapper.accept(builder, value);
                continue;
            }
            FieldDescriptor field = fieldsByName.get(key);
            if (field == null) {
                throw new IllegalArgumentException("No field '" + entry.getKey() + "' on "
                        + descriptor().getFullName() + " and no mapper registered for it");
            }
            ProtoValues.set(builder, field, value);
        }
        return (M) builder.build();
    }

    /** Builds a {@link ProtoProjector} with per-field overrides. */
    public static final class Builder<M extends Message> {

        private final M prototype;
        private final Map<String, BiConsumer<Message.Builder, Object>> mappers = new HashMap<>();

        private Builder(M prototype) {
            this.prototype = prototype;
        }

        /**
         * Takes over one field: {@code fn} receives the message builder and the (non-null)
         * value, and sets whatever it likes. For nested messages, value objects and anything
         * outside the built-in conversions.
         *
         * <pre>{@code
         * .mapper("price", (b, v) -> ((Book.Builder) b).setPrice(Money.of((BigDecimal) v)))
         * }</pre>
         */
        public Builder<M> mapper(String field, BiConsumer<Message.Builder, Object> fn) {
            mappers.put(Names.normalize(field), fn);
            return this;
        }

        public ProtoProjector<M> build() {
            return new ProtoProjector<>(prototype, mappers);
        }
    }
}
