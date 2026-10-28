package dev.appiaworks.shiguchi.avro;

import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FileDescriptor;
import dev.appiaworks.shiguchi.routing.GrpcRouteRegistry;
import dev.appiaworks.shiguchi.routing.ServiceEndpointContext;
import java.util.Optional;
import org.apache.avro.Schema;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * Serves the Avro schemas the REST surface derives from proto messages, so a caller can hold the
 * reader schema for a response it asked for in {@code avro/binary}:
 *
 * <pre>
 * GET /avro/schemas/{proto message full name}     e.g. /avro/schemas/library.v1.Book
 * GET /avro/schemas/{16-hex-digit fingerprint}    the value of X-Avro-Schema-Fingerprint
 * </pre>
 *
 * The prefix is {@code shiguchi.avro.schema-endpoint}. A message not yet derived is looked
 * up in every registered service's descriptors and derived on demand.
 */
@RestController
@ConditionalOnProperty(prefix = "shiguchi.avro", name = "enabled", havingValue = "true", matchIfMissing = true)
public class AvroSchemaController {

    private final AvroRestBodyCodec codec;
    private final GrpcRouteRegistry registry;

    public AvroSchemaController(AvroRestBodyCodec codec, GrpcRouteRegistry registry) {
        this.codec = codec;
        this.registry = registry;
    }

    @GetMapping(
            value = "${shiguchi.avro.schema-endpoint:/avro/schemas}/{name:.+}",
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> schema(@PathVariable("name") String name) {
        return resolve(name)
                .map(s -> ResponseEntity.ok()
                        .header(AvroRestBodyCodec.FINGERPRINT_HEADER, AvroRestBodyCodec.fingerprintHex(s))
                        .body(s.toString(true)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    Optional<Schema> resolve(String name) {
        if (name.length() == 16 && name.chars().allMatch(c -> Character.digit(c, 16) >= 0)) {
            Optional<Schema> byFp = codec.schemas().byFingerprint(Long.parseUnsignedLong(name, 16));
            if (byFp.isPresent()) return byFp;
        }
        Optional<Schema> cached = codec.schemas().find(name);
        if (cached.isPresent()) return cached;
        for (ServiceEndpointContext ctx : registry.allContexts()) {
            for (FileDescriptor file : ctx.getFileDescriptors().values()) {
                Descriptor d = findMessage(file, name);
                if (d != null) return Optional.of(codec.schemas().schemaFor(d));
            }
        }
        return Optional.empty();
    }

    private static Descriptor findMessage(FileDescriptor file, String fullName) {
        for (Descriptor d : file.getMessageTypes()) {
            Descriptor found = findMessage(d, fullName);
            if (found != null) return found;
        }
        return null;
    }

    private static Descriptor findMessage(Descriptor d, String fullName) {
        if (d.getFullName().equals(fullName)) return d;
        for (Descriptor nested : d.getNestedTypes()) {
            Descriptor found = findMessage(nested, fullName);
            if (found != null) return found;
        }
        return null;
    }
}
