package dev.appiaworks.protobuf.extras;

import com.fasterxml.jackson.core.JsonGenerator;
import dev.appiaworks.protobuf.extras.api.ApiStatus;
import java.io.IOException;

/**
 * A generated message class that writes its own proto JSON: what {@code protoc-gen-java-extras}
 * adds to every class protoc generates, so the typed write path needs no code generation at run
 * time and works on any Java the library runs on.
 *
 * <p>{@link ProtoJson} and the gateway print a message that implements this through
 * {@link #writeJson} whenever they would otherwise generate a writer for its class. The output
 * is the default layout of the dialect in force, the same JSON the other paths write.
 *
 * <p>Generated code implements this; application code has no reason to.
 */
@ApiStatus(value = ApiStatus.Status.EXPERIMENTAL, since = "0.10.0")
public interface JsonWritable {

    /**
     * Writes this message as one JSON object: every field, lowerCamelCase names, the dialect in
     * force ({@code ProtoJsonMapping}), the default layout.
     *
     * <p>A message whose JSON form is a single value, because a codec maps its type, is written
     * as an object here all the same: the callers check for that first, as {@link ProtoJson}
     * does, and this method is for them.
     */
    void writeJson(JsonGenerator gen) throws IOException;
}
