package dev.appiaworks.shiguchi.grpc;

import com.google.protobuf.DescriptorProtos.DescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorSet;
import com.google.protobuf.DescriptorProtos.ServiceDescriptorProto;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A context's descriptors with the methods hidden by {@code shiguchi.exposure} removed, so gRPC
 * reflection does not reveal them.
 *
 * <p>Services left with no method are dropped. Top-level messages and enums that no remaining
 * method can reach are dropped from the files that are safe to rewrite: not {@code google/} or
 * {@code shiguchi/} files, and not files that declare extensions. Nested types of a kept message
 * stay with it.
 *
 * @param files the descriptor files to serve
 * @param services fully-qualified names of the services that remain
 * @param types fully-qualified names of the top-level messages and enums that remain
 */
record HiddenReflectionView(List<FileDescriptorProto> files, Set<String> services, Set<String> types) {

    static HiddenReflectionView of(FileDescriptorSet raw, Set<String> hiddenMethods) {
        Map<String, DescriptorProto> messages = new HashMap<>();
        for (FileDescriptorProto fdp : raw.getFileList()) {
            for (DescriptorProto m : fdp.getMessageTypeList()) indexMessage(prefix(fdp), m, messages);
        }

        Set<String> services = new LinkedHashSet<>();
        List<FileDescriptorProto> kept = new ArrayList<>();
        ArrayDeque<String> pending = new ArrayDeque<>();
        for (FileDescriptorProto fdp : raw.getFileList()) {
            String pkg = fdp.getPackage();
            FileDescriptorProto.Builder fb = fdp.toBuilder().clearService();
            for (ServiceDescriptorProto svc : fdp.getServiceList()) {
                String fqn = pkg.isEmpty() ? svc.getName() : pkg + "." + svc.getName();
                ServiceDescriptorProto.Builder sb = svc.toBuilder().clearMethod();
                for (var m : svc.getMethodList()) {
                    if (hiddenMethods.contains(fqn + "/" + m.getName())) continue;
                    sb.addMethod(m);
                    pending.add(m.getInputType());
                    pending.add(m.getOutputType());
                }
                if (sb.getMethodCount() == 0 && svc.getMethodCount() > 0) continue;
                services.add(fqn);
                fb.addService(sb);
            }
            kept.add(fb.build());
        }

        Set<String> reachable = new HashSet<>();
        while (!pending.isEmpty()) {
            String name = pending.poll();
            if (!reachable.add(name)) continue;
            // A reachable nested type keeps every enclosing message.
            for (int dot = name.lastIndexOf('.'); dot > 0; dot = name.lastIndexOf('.', dot - 1)) {
                String parent = name.substring(0, dot);
                if (messages.containsKey(parent)) reachable.add(parent);
            }
            DescriptorProto msg = messages.get(name);
            if (msg == null) continue;
            for (FieldDescriptorProto f : msg.getFieldList()) {
                if (!f.getTypeName().isEmpty()) pending.add(f.getTypeName());
            }
        }

        List<FileDescriptorProto> files = new ArrayList<>();
        Set<String> types = new HashSet<>();
        for (FileDescriptorProto fdp : kept) {
            String prefix = prefix(fdp);
            boolean prunable = !fdp.getName().startsWith("google/")
                    && !fdp.getName().startsWith("shiguchi/")
                    && fdp.getExtensionCount() == 0;
            FileDescriptorProto.Builder fb = fdp.toBuilder();
            if (prunable) {
                fb.clearMessageType().clearEnumType();
                for (DescriptorProto m : fdp.getMessageTypeList()) {
                    if (reachable.contains(prefix + m.getName())) fb.addMessageType(m);
                }
                for (var e : fdp.getEnumTypeList()) {
                    if (reachable.contains(prefix + e.getName())) fb.addEnumType(e);
                }
            }
            FileDescriptorProto out = fb.build();
            for (DescriptorProto m : out.getMessageTypeList()) types.add((prefix + m.getName()).substring(1));
            for (var e : out.getEnumTypeList()) types.add((prefix + e.getName()).substring(1));
            files.add(out);
        }
        return new HiddenReflectionView(List.copyOf(files), Set.copyOf(services), Set.copyOf(types));
    }

    private static String prefix(FileDescriptorProto fdp) {
        return fdp.getPackage().isEmpty() ? "." : "." + fdp.getPackage() + ".";
    }

    private static void indexMessage(String prefix, DescriptorProto m, Map<String, DescriptorProto> out) {
        String name = prefix + m.getName();
        out.put(name, m);
        for (DescriptorProto nested : m.getNestedTypeList()) indexMessage(name + ".", nested, out);
    }
}
