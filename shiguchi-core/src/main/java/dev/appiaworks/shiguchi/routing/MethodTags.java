package dev.appiaworks.shiguchi.routing;

import com.google.protobuf.Descriptors.MethodDescriptor;
import dev.appiaworks.shiguchi.proto.MethodProto;
import java.util.List;

/** Reads the tags a method declares in {@code (shiguchi.method).tags}. */
public final class MethodTags {

    private MethodTags() {}

    /** The method's tags, or an empty list when it declares none. */
    public static List<String> of(MethodDescriptor md) {
        return md.getOptions().getExtension(MethodProto.method).getTagsList();
    }
}
