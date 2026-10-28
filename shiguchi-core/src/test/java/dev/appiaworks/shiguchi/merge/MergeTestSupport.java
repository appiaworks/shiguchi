package dev.appiaworks.shiguchi.merge;

import com.google.protobuf.DescriptorProtos.DescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FileDescriptor;
import com.google.protobuf.DynamicMessage;
import com.google.protobuf.Message;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import reactor.core.publisher.Flux;

/**
 * Fixtures for the merge tests: a small record type, builders for sources with given sort keys, and
 * instrumented sources that record demand and cancellation.
 *
 * <p>The merge engine is pure logic over {@link Flux} and {@link Message}, so none of these tests
 * need a gRPC server or a Spring context.
 */
final class MergeTestSupport {

    /** Test record: {@code rank} is the sort key, {@code id} the natural tie-break. */
    static final Descriptor RECORD;

    private static final Descriptor NESTED_HOLDER;

    static {
        try {
            DescriptorProto record = DescriptorProto.newBuilder()
                    .setName("Record")
                    .addField(scalar("id", 1, FieldDescriptorProto.Type.TYPE_INT64))
                    .addField(scalar("rank", 2, FieldDescriptorProto.Type.TYPE_INT64))
                    .addField(scalar("name", 3, FieldDescriptorProto.Type.TYPE_STRING))
                    .addField(scalar("score", 4, FieldDescriptorProto.Type.TYPE_DOUBLE))
                    .addField(scalar("active", 5, FieldDescriptorProto.Type.TYPE_BOOL))
                    .addField(FieldDescriptorProto.newBuilder()
                            .setName("tags")
                            .setNumber(6)
                            .setType(FieldDescriptorProto.Type.TYPE_STRING)
                            .setLabel(FieldDescriptorProto.Label.LABEL_REPEATED)
                            .build())
                    .build();

            DescriptorProto inner = DescriptorProto.newBuilder()
                    .setName("Inner")
                    .addField(scalar("start_date", 1, FieldDescriptorProto.Type.TYPE_INT64))
                    .build();

            DescriptorProto holder = DescriptorProto.newBuilder()
                    .setName("Holder")
                    .addField(scalar("id", 1, FieldDescriptorProto.Type.TYPE_INT64))
                    .addField(FieldDescriptorProto.newBuilder()
                            .setName("effective")
                            .setNumber(2)
                            .setType(FieldDescriptorProto.Type.TYPE_MESSAGE)
                            .setTypeName(".merge.test.Inner")
                            .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL)
                            .build())
                    .build();

            FileDescriptorProto file = FileDescriptorProto.newBuilder()
                    .setName("merge_test.proto")
                    .setPackage("merge.test")
                    .setSyntax("proto3")
                    .addMessageType(record)
                    .addMessageType(inner)
                    .addMessageType(holder)
                    .build();

            FileDescriptor fd = FileDescriptor.buildFrom(file, new FileDescriptor[0]);
            RECORD = fd.findMessageTypeByName("Record");
            NESTED_HOLDER = fd.findMessageTypeByName("Holder");
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private MergeTestSupport() {}

    private static FieldDescriptorProto scalar(String name, int number, FieldDescriptorProto.Type type) {
        return FieldDescriptorProto.newBuilder()
                .setName(name)
                .setNumber(number)
                .setType(type)
                .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL)
                .build();
    }

    /**
     * A second, independently built descriptor for the same Record shape — stands in for a second
     * backend serving the same .proto. Structurally identical, but a different object, which is
     * exactly what breaks identity-based field lookups.
     */
    static Descriptor independentRecordDescriptor() {
        try {
            DescriptorProto record = DescriptorProto.newBuilder()
                    .setName("Record")
                    .addField(scalar("id", 1, FieldDescriptorProto.Type.TYPE_INT64))
                    .addField(scalar("rank", 2, FieldDescriptorProto.Type.TYPE_INT64))
                    .addField(scalar("name", 3, FieldDescriptorProto.Type.TYPE_STRING))
                    .build();
            FileDescriptorProto file = FileDescriptorProto.newBuilder()
                    .setName("merge_test_other.proto")
                    .setPackage("merge.test.other")
                    .setSyntax("proto3")
                    .addMessageType(record)
                    .build();
            return FileDescriptor.buildFrom(file, new FileDescriptor[0]).findMessageTypeByName("Record");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static Descriptor nestedHolder() {
        return NESTED_HOLDER;
    }

    /** A record with the given sort key; {@code id} defaults to the key so ordering stays total. */
    static Message record(long rank) {
        return record(rank, rank);
    }

    static Message record(long rank, long id) {
        return DynamicMessage.newBuilder(RECORD)
                .setField(RECORD.findFieldByName("rank"), rank)
                .setField(RECORD.findFieldByName("id"), id)
                .build();
    }

    static Message named(long rank, String name) {
        return DynamicMessage.newBuilder(RECORD)
                .setField(RECORD.findFieldByName("rank"), rank)
                .setField(RECORD.findFieldByName("id"), rank)
                .setField(RECORD.findFieldByName("name"), name)
                .build();
    }

    static long rankOf(Message message) {
        return (Long) message.getField(RECORD.findFieldByName("rank"));
    }

    static long idOf(Message message) {
        return (Long) message.getField(RECORD.findFieldByName("id"));
    }

    static List<Long> ranksOf(List<Message> messages) {
        return messages.stream().map(MergeTestSupport::rankOf).toList();
    }

    /** A source emitting records with the given sort keys, in the order given. */
    static Flux<Message> source(long... ranks) {
        List<Message> records = new ArrayList<>(ranks.length);
        for (long rank : ranks) {
            records.add(record(rank));
        }
        return Flux.fromIterable(records);
    }

    static List<Message> records(long... ranks) {
        List<Message> records = new ArrayList<>(ranks.length);
        for (long rank : ranks) {
            records.add(record(rank));
        }
        return records;
    }

    /** Ascending sort on {@code rank}, tie-broken by {@code id} — the default test ordering. */
    static java.util.Comparator<Message> byRank() {
        return SortComparator.build(RECORD, List.of(new SortComparator.SortKey("rank", true)), "id");
    }

    /** Wraps a source so tests can assert how much was pulled and whether it was cancelled. */
    static final class RecordingSource {
        private final AtomicLong requested = new AtomicLong();
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final Flux<Message> flux;

        RecordingSource(List<Message> records) {
            this.flux =
                    Flux.fromIterable(records).doOnRequest(requested::addAndGet).doOnCancel(() -> cancelled.set(true));
        }

        Flux<Message> flux() {
            return flux;
        }

        long requested() {
            return requested.get();
        }

        boolean cancelled() {
            return cancelled.get();
        }
    }

    static RecordingSource recordingSource(long... ranks) {
        return new RecordingSource(records(ranks));
    }
}
