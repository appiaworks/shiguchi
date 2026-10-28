package dev.appiaworks.protobuf.extras.protoc;

import com.google.protobuf.DescriptorProtos.DescriptorProto;
import com.google.protobuf.DescriptorProtos.EnumDescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.DescriptorProtos.ServiceDescriptorProto;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.EnumDescriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.Descriptors.FileDescriptor;
import java.util.Set;

/**
 * The names protoc's Java generator gives things. Accessors are inserted into its output, so
 * these must agree with it exactly: a file path that is one character off is an insertion point
 * that does not exist.
 */
final class JavaNames {

    private JavaNames() {}

    /**
     * The field names protoc's Java generator keeps clear of, because every generated message
     * already has {@code getClass()}, {@code getSerializedSize()} and the rest: a field with one of
     * these names gets a trailing underscore in its accessors ({@code getClass_()}).
     */
    private static final Set<String> FORBIDDEN = Set.of(
            "class",
            "default_instance_for_type",
            "parser_for_type",
            "serialized_size",
            "all_fields",
            "descriptor_for_type",
            "initialization_error_string",
            "unknown_fields",
            "cached_size");

    /**
     * A proto field name as it appears in accessor names: {@code session_date} gives
     * {@code SessionDate}. As protoc does it: a letter is capitalized at the start, after an
     * underscore and after a digit ({@code line_2x} gives {@code Line2X}), and any character that
     * is not a letter or digit is dropped. A name protoc reserves gets a trailing underscore
     * ({@code class} gives {@code Class_}).
     *
     * <p>This is the name on its own; {@link #accessorName(DescriptorProto, FieldDescriptorProto)}
     * adds the field number protoc appends when two fields of a message share it.
     */
    static String accessorName(String fieldName) {
        String camel = camelCase(fieldName, true);
        return FORBIDDEN.contains(fieldName) ? camel + "_" : camel;
    }

    /**
     * The accessor name of a field within its message: {@link #accessorName(String)}, with the
     * field number appended when another field of the message camel-cases to the same name,
     * as protoc does to keep the accessors apart.
     */
    static String accessorName(DescriptorProto message, FieldDescriptorProto field) {
        String name = accessorName(field.getName());
        int holders = 0;
        for (FieldDescriptorProto other : message.getFieldList()) {
            if (accessorName(other.getName()).equals(name)) holders++;
        }
        return holders > 1 ? name + field.getNumber() : name;
    }

    /** {@link #accessorName(DescriptorProto, FieldDescriptorProto)} on built descriptors. */
    static String accessorName(Descriptor message, FieldDescriptor field) {
        return accessorName(message.toProto(), field.toProto());
    }

    /**
     * The fully qualified Java class of an enum: in its file's Java package, inside the outer
     * class unless the file has {@code java_multiple_files}, and inside the message classes that
     * contain it.
     */
    static String javaClassName(EnumDescriptor type) {
        return javaClassName(type.getFile(), type.getName(), type.getContainingType());
    }

    /** {@link #javaClassName(EnumDescriptor)} for a message. */
    static String javaClassName(Descriptor type) {
        return javaClassName(type.getFile(), type.getName(), type.getContainingType());
    }

    private static String javaClassName(FileDescriptor file, String name, Descriptor containing) {
        StringBuilder nesting = new StringBuilder(name);
        for (Descriptor outer = containing; outer != null; outer = outer.getContainingType()) {
            nesting.insert(0, outer.getName() + ".");
        }
        FileDescriptorProto proto = file.toProto();
        String pkg = javaPackage(proto);
        String prefix = pkg.isEmpty() ? "" : pkg + ".";
        if (!proto.getOptions().getJavaMultipleFiles()) prefix += outerClassName(proto) + ".";
        return prefix + nesting;
    }

    static String camelCase(String name, boolean capitalizeFirst) {
        StringBuilder out = new StringBuilder(name.length());
        boolean capitalizeNext = capitalizeFirst;
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c >= 'a' && c <= 'z') {
                out.append(capitalizeNext ? Character.toUpperCase(c) : c);
                capitalizeNext = false;
            } else if (c >= 'A' && c <= 'Z') {
                // The first letter is forced lower only when the caller asked for lowerCamelCase.
                out.append(i == 0 && !capitalizeNext ? Character.toLowerCase(c) : c);
                capitalizeNext = false;
            } else if (c >= '0' && c <= '9') {
                out.append(c);
                capitalizeNext = true;
            } else {
                capitalizeNext = true;
            }
        }
        return out.toString();
    }

    /** {@code java_package} when set, else the proto package. */
    static String javaPackage(FileDescriptorProto file) {
        return file.getOptions().hasJavaPackage() ? file.getOptions().getJavaPackage() : file.getPackage();
    }

    /**
     * The outer class protoc wraps a file's types in: {@code java_outer_classname} when set, else
     * the file name in CamelCase, with {@code OuterClass} appended when a top-level message, enum
     * or service already has that name.
     */
    static String outerClassName(FileDescriptorProto file) {
        if (file.getOptions().hasJavaOuterClassname()) return file.getOptions().getJavaOuterClassname();
        String base = file.getName();
        base = base.substring(base.lastIndexOf('/') + 1);
        if (base.endsWith(".proto")) base = base.substring(0, base.length() - ".proto".length());
        String name = camelCase(base, true);
        return clashes(file, name) ? name + "OuterClass" : name;
    }

    private static boolean clashes(FileDescriptorProto file, String name) {
        for (DescriptorProto message : file.getMessageTypeList()) {
            if (declares(message, name)) return true;
        }
        for (EnumDescriptorProto e : file.getEnumTypeList()) {
            if (e.getName().equals(name)) return true;
        }
        for (ServiceDescriptorProto service : file.getServiceList()) {
            if (service.getName().equals(name)) return true;
        }
        return false;
    }

    /** protoc checks nested types too: an inner class may not share its outer class's name either. */
    private static boolean declares(DescriptorProto message, String name) {
        if (message.getName().equals(name)) return true;
        for (DescriptorProto nested : message.getNestedTypeList()) {
            if (declares(nested, name)) return true;
        }
        for (EnumDescriptorProto e : message.getEnumTypeList()) {
            if (e.getName().equals(name)) return true;
        }
        return false;
    }

    /**
     * The file protoc generates a top-level message into: its own with
     * {@code java_multiple_files}, the outer class's otherwise. Nested messages live in the file
     * of the top-level message that contains them.
     */
    static String javaFile(FileDescriptorProto file, String topLevelMessage) {
        String pkg = javaPackage(file);
        String dir = pkg.isEmpty() ? "" : pkg.replace('.', '/') + "/";
        String cls = file.getOptions().getJavaMultipleFiles() ? topLevelMessage : outerClassName(file);
        return dir + cls + ".java";
    }
}
