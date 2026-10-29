package dev.appiaworks.protobuf.extras;

import com.fasterxml.jackson.core.JsonParser;
import com.google.protobuf.Message;
import dev.appiaworks.protobuf.extras.api.ApiStatus;
import java.io.IOException;

/**
 * A generated {@code Builder} that reads its message from proto JSON: what
 * {@code protoc-gen-java-extras} adds to the Builder of every class protoc generates, so the typed
 * parse path streams the text straight into the message through its setters, with no
 * {@code DynamicMessage} and no code generation at run time, on any Java the library runs on.
 *
 * <p>{@link ProtoJson#parse(String, Message)} parses into a class whose Builder implements this
 * through {@link #mergeJson}. The rules are the strict ones every {@code ProtoJson} parse follows:
 * a value that does not fit its field is refused with a {@link ProtoJsonException}, unknown fields
 * are ignored, JSON null leaves a field unset.
 *
 * <p>Generated code implements this; application code has no reason to.
 */
@ApiStatus(value = ApiStatus.Status.EXPERIMENTAL, since = "0.14.0")
public interface JsonReadable {

    /**
     * Reads one JSON object into this builder, in the dialect in force ({@code ProtoJsonMapping}).
     * The parser must be on the object's {@code START_OBJECT} token and is left on its
     * {@code END_OBJECT}.
     *
     * @return this builder
     * @throws ProtoJsonException when the parser is not on an object, or a value does not fit its
     *     field (the host's own exception type outside a {@code ProtoJson} call)
     */
    Message.Builder mergeJson(JsonParser parser) throws IOException;
}
