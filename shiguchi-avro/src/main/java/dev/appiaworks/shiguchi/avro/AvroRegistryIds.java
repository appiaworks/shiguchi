package dev.appiaworks.shiguchi.avro;

import org.apache.avro.Schema;

/** Small helpers shared by the registry-aware codecs. */
final class AvroRegistryIds {

    private AvroRegistryIds() {}

    static String fingerprintHex(Schema schema) {
        return String.format("%016x", AvroSchemaRegistry.fingerprint(schema));
    }
}
