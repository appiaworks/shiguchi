package dev.appiaworks.shiguchi.sample.merge;

import com.google.protobuf.DescriptorProtos.DescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.DescriptorProtos.MethodDescriptorProto;
import com.google.protobuf.DescriptorProtos.ServiceDescriptorProto;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FileDescriptor;
import com.google.protobuf.DynamicMessage;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * One of the two order backends this sample merges.
 *
 * <p>Both return the same {@code Order} message and hold disjoint rows — a European and a US
 * shard of the same logical table. What differs is the interesting part:
 *
 * <ul>
 *   <li>{@code orders-eu} honours {@code sort_fields} and returns rows already ordered, so the
 *       gateway pushes the caller's sort down and streams it.
 *   <li>{@code orders-us} ignores {@code sort_fields} entirely and returns rows in insertion
 *       order, standing in for a legacy service nobody has taught to sort yet. The gateway has to
 *       read it whole and sort in memory.
 * </ul>
 *
 * <p>That contrast is the point of the sample: the same merged endpoint, served two different ways
 * depending on what each backend can promise.
 */
public final class OrderBackend {

    /** Shared schema for both backends: {@code Order}, its list request, and the service. */
    public static final FileDescriptor FILE = buildFile();

    public static final Descriptor ORDER = FILE.findMessageTypeByName("Order");
    public static final Descriptor LIST_REQUEST = FILE.findMessageTypeByName("ListOrdersRequest");

    private OrderBackend() {}

    private static FileDescriptor buildFile() {
        try {
            DescriptorProto order = DescriptorProto.newBuilder()
                    .setName("Order")
                    .addField(scalar("id", 1, FieldDescriptorProto.Type.TYPE_STRING))
                    .addField(scalar("customer", 2, FieldDescriptorProto.Type.TYPE_STRING))
                    .addField(scalar("placed_at", 3, FieldDescriptorProto.Type.TYPE_INT64))
                    .addField(scalar("total_cents", 4, FieldDescriptorProto.Type.TYPE_INT64))
                    .addField(scalar("region", 5, FieldDescriptorProto.Type.TYPE_STRING))
                    .build();

            // page_request / sort_fields are the gateway's shared conventions — see
            // docs/projections-paging-sorting.md. A backend that declares them can be sent a sort
            // and a page; one that does not gets neither.
            DescriptorProto pageRequest = DescriptorProto.newBuilder()
                    .setName("PageRequest")
                    .addField(scalar("page_size", 1, FieldDescriptorProto.Type.TYPE_INT32))
                    .addField(scalar("page_token", 2, FieldDescriptorProto.Type.TYPE_STRING))
                    .build();

            DescriptorProto sortField = DescriptorProto.newBuilder()
                    .setName("SortField")
                    .addField(scalar("field", 1, FieldDescriptorProto.Type.TYPE_STRING))
                    .addField(scalar("sort_order", 2, FieldDescriptorProto.Type.TYPE_STRING))
                    .build();

            DescriptorProto listRequest = DescriptorProto.newBuilder()
                    .setName("ListOrdersRequest")
                    .addField(FieldDescriptorProto.newBuilder()
                            .setName("page_request")
                            .setNumber(1)
                            .setType(FieldDescriptorProto.Type.TYPE_MESSAGE)
                            .setTypeName(".sample.merge.PageRequest")
                            .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL))
                    .addField(FieldDescriptorProto.newBuilder()
                            .setName("sort_fields")
                            .setNumber(2)
                            .setType(FieldDescriptorProto.Type.TYPE_MESSAGE)
                            .setTypeName(".sample.merge.SortField")
                            .setLabel(FieldDescriptorProto.Label.LABEL_REPEATED))
                    .build();

            // The REST binding the gateway discovers over reflection. Without a google.api.http
            // annotation a method has no HTTP path, so there would be no route for the merge to
            // attach to.
            var http = com.google.api.HttpRule.newBuilder().setGet("/v1/orders").build();
            var methodOptions = com.google.protobuf.DescriptorProtos.MethodOptions.newBuilder()
                    .setExtension(com.google.api.AnnotationsProto.http, http)
                    .build();

            ServiceDescriptorProto service = ServiceDescriptorProto.newBuilder()
                    .setName("OrderService")
                    .addMethod(MethodDescriptorProto.newBuilder()
                            .setName("listOrders")
                            .setInputType(".sample.merge.ListOrdersRequest")
                            .setOutputType(".sample.merge.Order")
                            .setServerStreaming(true)
                            .setOptions(methodOptions))
                    .build();

            FileDescriptorProto file = FileDescriptorProto.newBuilder()
                    .setName("sample_merge.proto")
                    .setPackage("sample.merge")
                    .setSyntax("proto3")
                    .addDependency("google/api/annotations.proto")
                    .addMessageType(order)
                    .addMessageType(pageRequest)
                    .addMessageType(sortField)
                    .addMessageType(listRequest)
                    .addService(service)
                    .build();

            return FileDescriptor.buildFrom(
                    file, new FileDescriptor[] {com.google.api.AnnotationsProto.getDescriptor()});
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static FieldDescriptorProto scalar(String name, int number, FieldDescriptorProto.Type type) {
        return FieldDescriptorProto.newBuilder()
                .setName(name)
                .setNumber(number)
                .setType(type)
                .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL)
                .build();
    }

    /** Seed rows for one region. Ids interleave across regions so a merge visibly interleaves. */
    public static List<DynamicMessage> seed(String region, int count, int idOffset) {
        List<DynamicMessage> orders = new ArrayList<>(count);
        String[] customers = {"ada", "grace", "alan", "edsger", "barbara", "donald"};
        for (int i = 0; i < count; i++) {
            int id = idOffset + i * 2; // even ids for one region, odd for the other
            orders.add(DynamicMessage.newBuilder(ORDER)
                    .setField(ORDER.findFieldByName("id"), String.format("ord-%04d", id))
                    .setField(ORDER.findFieldByName("customer"), customers[i % customers.length])
                    .setField(ORDER.findFieldByName("placed_at"), 1_700_000_000L + id * 3_600L)
                    .setField(ORDER.findFieldByName("total_cents"), 1_000L + (id * 37L) % 9_000L)
                    .setField(ORDER.findFieldByName("region"), region)
                    .build());
        }
        return orders;
    }

    /** Orders a row list by a {@code sort_fields} entry, for the backend that honours it. */
    public static Comparator<DynamicMessage> comparatorFor(String field, boolean ascending) {
        var fd = ORDER.findFieldByName(field);
        if (fd == null) {
            throw new IllegalArgumentException("unknown sort field: " + field);
        }
        Comparator<DynamicMessage> byField = Comparator.comparing(
                m -> String.valueOf(m.getField(fd)), Comparator.naturalOrder());
        return ascending ? byField : byField.reversed();
    }
}
