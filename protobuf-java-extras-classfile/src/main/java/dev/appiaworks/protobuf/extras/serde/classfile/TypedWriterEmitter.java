package dev.appiaworks.protobuf.extras.serde.classfile;

import static java.lang.constant.ConstantDescs.CD_Object;
import static java.lang.constant.ConstantDescs.CD_String;
import static java.lang.constant.ConstantDescs.CD_boolean;
import static java.lang.constant.ConstantDescs.CD_byte;
import static java.lang.constant.ConstantDescs.CD_double;
import static java.lang.constant.ConstantDescs.CD_float;
import static java.lang.constant.ConstantDescs.CD_int;
import static java.lang.constant.ConstantDescs.CD_long;
import static java.lang.constant.ConstantDescs.CD_void;

import com.fasterxml.jackson.core.JsonGenerator;
import com.google.protobuf.ByteString;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.Message;
import dev.appiaworks.protobuf.extras.serde.ProtoMapConverter;
import dev.appiaworks.protobuf.extras.serde.ProtoWkt;
import dev.appiaworks.protobuf.extras.serde.TypedMessageWriter;
import dev.appiaworks.protobuf.extras.serde.TypedWriterPlan;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassHierarchyResolver;
import java.lang.classfile.CodeBuilder;
import java.lang.classfile.Label;
import java.lang.classfile.Opcode;
import java.lang.classfile.TypeKind;
import java.lang.constant.ClassDesc;
import java.lang.constant.MethodTypeDesc;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.util.Base64;
import java.util.Map;
import java.util.function.Consumer;

/**
 * The bytes of a {@link TypedMessageWriter} subclass for one {@link TypedWriterPlan}.
 *
 * <p>The class holds a {@code FieldDescriptor} per enum and map field (what the shared helpers
 * need), a writer slot per message-typed field that {@code nestedBound} fills, and one
 * {@code write} method that is the plan read out as straight-line bytecode: for every field,
 * the presence check protoc generated, the JSON name, and the value through the accessor protoc
 * generated — a repeated field as a counted loop, a map through {@code writeMap}, an enum through
 * {@code writeEnum}, a single-value type through {@link ProtoWkt}, a nested message through its
 * own writer or, when none is wired, the reflective one.
 */
final class TypedWriterEmitter {

    private static final ClassDesc CD_GEN = cd(JsonGenerator.class);
    private static final ClassDesc CD_MESSAGE = cd(Message.class);
    private static final ClassDesc CD_DESCRIPTOR = cd(Descriptor.class);
    private static final ClassDesc CD_FD = cd(FieldDescriptor.class);
    private static final ClassDesc CD_WRITER = cd(TypedMessageWriter.class);
    private static final ClassDesc CD_WKT = cd(ProtoWkt.class);
    private static final ClassDesc CD_CONVERTER = cd(ProtoMapConverter.class);
    private static final ClassDesc CD_BYTESTRING = cd(ByteString.class);
    private static final ClassDesc CD_BASE64 = cd(Base64.class);
    private static final ClassDesc CD_ENCODER = cd(Base64.Encoder.class);
    private static final ClassDesc CD_MAP = cd(Map.class);

    private static final MethodTypeDesc MTD_VOID = MethodTypeDesc.of(CD_void);
    private static final MethodTypeDesc MTD_VOID_STRING = MethodTypeDesc.of(CD_void, CD_String);

    /** Slots of {@code write(Message, JsonGenerator)}'s parameters. */
    private static final int MESSAGE = 1;

    private static final int GEN = 2;

    private final TypedWriterPlan plan;
    private final MethodHandles.Lookup lookup;
    private final ClassDesc self;
    private final ClassDesc stub;

    /**
     * @param lookup where the class will be defined: its package names the class, its loader
     *     resolves the types the stack maps need
     */
    TypedWriterEmitter(TypedWriterPlan plan, MethodHandles.Lookup lookup) {
        this.plan = plan;
        this.lookup = lookup;
        this.stub = cd(plan.type());
        String stubName = plan.type().getName();
        String simple = stubName.substring(stubName.lastIndexOf('.') + 1);
        this.self = ClassDesc.of(lookup.lookupClass().getPackageName(), simple + "$$JsonWriter");
    }

    byte[] emit() {
        ClassFile classFile = ClassFile.of(ClassFile.ClassHierarchyResolverOption.of(
                ClassHierarchyResolver.ofClassLoading(lookup).orElse(ClassHierarchyResolver.defaultResolver())));
        return classFile.build(self, cb -> {
            cb.withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_FINAL | ClassFile.ACC_SUPER);
            cb.withSuperclass(CD_WRITER);
            for (TypedWriterPlan.Field field : plan.fields()) {
                if (needsDescriptor(field)) {
                    cb.withField(descriptorField(field), CD_FD, ClassFile.ACC_PRIVATE | ClassFile.ACC_FINAL);
                }
                if (field.messageClass() != null) {
                    cb.withField(writerField(field), CD_WRITER, ClassFile.ACC_PRIVATE);
                }
            }
            cb.withMethodBody(
                    "<init>", MethodTypeDesc.of(CD_void, CD_DESCRIPTOR), ClassFile.ACC_PUBLIC, this::constructor);
            cb.withMethodBody(
                    "nestedBound",
                    MethodTypeDesc.of(CD_void, CD_String, CD_WRITER),
                    ClassFile.ACC_PROTECTED,
                    this::nestedBound);
            cb.withMethodBody(
                    "write", MethodTypeDesc.of(CD_void, CD_MESSAGE, CD_GEN), ClassFile.ACC_PUBLIC, this::write);
        });
    }

    // ── Methods ───────────────────────────────────────────────────────────────────────

    private void constructor(CodeBuilder c) {
        c.aload(0).invokespecial(CD_WRITER, "<init>", MTD_VOID);
        for (TypedWriterPlan.Field field : plan.fields()) {
            if (!needsDescriptor(field)) continue;
            c.aload(0)
                    .aload(1)
                    .loadConstant(field.descriptor().getNumber())
                    .invokevirtual(CD_DESCRIPTOR, "findFieldByNumber", MethodTypeDesc.of(CD_FD, CD_int))
                    .putfield(self, descriptorField(field), CD_FD);
        }
        c.return_();
    }

    private void nestedBound(CodeBuilder c) {
        for (TypedWriterPlan.Field field : plan.fields()) {
            if (field.messageClass() == null) continue;
            c.loadConstant(field.nestedMessageType().getFullName())
                    .aload(1)
                    .invokevirtual(CD_String, "equals", MethodTypeDesc.of(CD_boolean, CD_Object))
                    .ifThen(Opcode.IFNE, then -> then.aload(0).aload(2).putfield(self, writerField(field), CD_WRITER));
        }
        c.return_();
    }

    private void write(CodeBuilder c) {
        int msg = c.allocateLocal(TypeKind.REFERENCE);
        c.aload(MESSAGE).checkcast(stub).astore(msg);
        c.aload(GEN).invokevirtual(CD_GEN, "writeStartObject", MTD_VOID);
        for (TypedWriterPlan.Field field : plan.fields()) {
            if (field.has() != null) {
                c.aload(msg);
                invoke(c, field.has());
                c.ifThen(Opcode.IFNE, then -> fieldWrite(then, field, msg));
            } else {
                fieldWrite(c, field, msg);
            }
        }
        c.aload(GEN).invokevirtual(CD_GEN, "writeEndObject", MTD_VOID);
        c.return_();
    }

    /** The JSON name, then the value: a map, a counted loop over a repeated field, or one value. */
    private void fieldWrite(CodeBuilder c, TypedWriterPlan.Field field, int msg) {
        c.aload(GEN)
                .loadConstant(field.descriptor().getJsonName())
                .invokevirtual(CD_GEN, "writeFieldName", MTD_VOID_STRING);
        if (field.isMap()) {
            c.aload(GEN).aload(0).getfield(self, descriptorField(field), CD_FD).aload(msg);
            invoke(c, field.getter());
            if (field.messageClass() != null) {
                c.aload(0).getfield(self, writerField(field), CD_WRITER);
            } else {
                c.aconst_null();
            }
            c.invokestatic(CD_WRITER, "writeMap", MethodTypeDesc.of(CD_void, CD_GEN, CD_FD, CD_MAP, CD_WRITER));
            return;
        }
        if (field.isRepeated()) {
            int n = c.allocateLocal(TypeKind.INT);
            int i = c.allocateLocal(TypeKind.INT);
            c.aload(msg);
            invoke(c, field.count());
            c.istore(n).iconst_0().istore(i);
            c.aload(GEN).invokevirtual(CD_GEN, "writeStartArray", MTD_VOID);
            Label loop = c.newBoundLabel();
            Label end = c.newLabel();
            c.iload(i).iload(n).if_icmpge(end);
            valueWrite(
                    c,
                    field,
                    cc -> {
                        cc.aload(msg).iload(i);
                        invoke(cc, field.getter());
                    },
                    field.valueGetter() == null
                            ? null
                            : cc -> {
                                cc.aload(msg).iload(i);
                                invoke(cc, field.valueGetter());
                            });
            c.iinc(i, 1).goto_(loop);
            c.labelBinding(end);
            c.aload(GEN).invokevirtual(CD_GEN, "writeEndArray", MTD_VOID);
            return;
        }
        valueWrite(
                c,
                field,
                cc -> {
                    cc.aload(msg);
                    invoke(cc, field.getter());
                },
                field.valueGetter() == null
                        ? null
                        : cc -> {
                            cc.aload(msg);
                            invoke(cc, field.valueGetter());
                        });
    }

    /**
     * One value's write. {@code load} pushes the value the accessor returns; {@code loadNumber},
     * when present, pushes a proto3 enum's number instead.
     */
    private void valueWrite(
            CodeBuilder c, TypedWriterPlan.Field field, Consumer<CodeBuilder> load, Consumer<CodeBuilder> loadNumber) {
        FieldDescriptor fd = field.descriptor();
        if (fd.getType() == FieldDescriptor.Type.MESSAGE) {
            if (field.isWkt()) {
                c.aload(GEN);
                load.accept(c);
                c.invokestatic(CD_WKT, "writeJson", MethodTypeDesc.of(CD_void, CD_GEN, CD_MESSAGE));
                return;
            }
            int value = c.allocateLocal(TypeKind.REFERENCE);
            load.accept(c);
            c.astore(value);
            c.aload(0).getfield(self, writerField(field), CD_WRITER);
            c.ifThenElse(
                    Opcode.IFNONNULL,
                    then -> then.aload(0)
                            .getfield(self, writerField(field), CD_WRITER)
                            .aload(value)
                            .aload(GEN)
                            .invokevirtual(CD_WRITER, "write", MethodTypeDesc.of(CD_void, CD_MESSAGE, CD_GEN)),
                    otherwise -> otherwise
                            .aload(GEN)
                            .aload(value)
                            .invokestatic(
                                    CD_WRITER, "writeReflectively", MethodTypeDesc.of(CD_void, CD_GEN, CD_MESSAGE)));
            return;
        }
        switch (fd.getType()) {
            case STRING -> {
                c.aload(GEN);
                load.accept(c);
                c.invokevirtual(CD_GEN, "writeString", MTD_VOID_STRING);
            }
            case BYTES -> {
                c.aload(GEN).invokestatic(CD_BASE64, "getEncoder", MethodTypeDesc.of(CD_ENCODER));
                load.accept(c);
                c.invokevirtual(CD_BYTESTRING, "toByteArray", MethodTypeDesc.of(CD_byte.arrayType()))
                        .invokevirtual(CD_ENCODER, "encodeToString", MethodTypeDesc.of(CD_String, CD_byte.arrayType()))
                        .invokevirtual(CD_GEN, "writeString", MTD_VOID_STRING);
            }
            case INT32, SINT32, SFIXED32 -> {
                c.aload(GEN);
                load.accept(c);
                c.invokevirtual(CD_GEN, "writeNumber", MethodTypeDesc.of(CD_void, CD_int));
            }
            case UINT32, FIXED32 -> {
                c.aload(GEN);
                load.accept(c);
                c.i2l()
                        .loadConstant(0xFFFFFFFFL)
                        .land()
                        .invokevirtual(CD_GEN, "writeNumber", MethodTypeDesc.of(CD_void, CD_long));
            }
            case INT64, SINT64, SFIXED64 -> {
                c.aload(GEN);
                load.accept(c);
                c.invokestatic(CD_CONVERTER, "writeInt64", MethodTypeDesc.of(CD_void, CD_GEN, CD_long));
            }
            case UINT64, FIXED64 -> {
                c.aload(GEN);
                load.accept(c);
                c.invokestatic(CD_CONVERTER, "writeUInt64", MethodTypeDesc.of(CD_void, CD_GEN, CD_long));
            }
            case FLOAT -> {
                c.aload(GEN);
                load.accept(c);
                c.invokevirtual(CD_GEN, "writeNumber", MethodTypeDesc.of(CD_void, CD_float));
            }
            case DOUBLE -> {
                c.aload(GEN);
                load.accept(c);
                c.invokevirtual(CD_GEN, "writeNumber", MethodTypeDesc.of(CD_void, CD_double));
            }
            case BOOL -> {
                c.aload(GEN);
                load.accept(c);
                c.invokevirtual(CD_GEN, "writeBoolean", MethodTypeDesc.of(CD_void, CD_boolean));
            }
            case ENUM -> {
                c.aload(GEN).aload(0).getfield(self, descriptorField(field), CD_FD);
                if (loadNumber != null) {
                    loadNumber.accept(c);
                } else {
                    // proto2: the constant is always a known one, with a number
                    load.accept(c);
                    Class<?> enumClass = field.getter().getReturnType();
                    c.invoke(
                            enumClass.isInterface() ? Opcode.INVOKEINTERFACE : Opcode.INVOKEVIRTUAL,
                            cd(enumClass),
                            "getNumber",
                            MethodTypeDesc.of(CD_int),
                            enumClass.isInterface());
                }
                c.invokestatic(CD_WRITER, "writeEnum", MethodTypeDesc.of(CD_void, CD_GEN, CD_FD, CD_int));
            }
            default -> throw new IllegalArgumentException("Unsupported field type " + fd.getType() + " on " + fd);
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────────────

    /** Calls the accessor on the receiver (and arguments) already on the stack. */
    private static void invoke(CodeBuilder c, Method accessor) {
        Class<?> owner = accessor.getDeclaringClass();
        MethodTypeDesc type = MethodTypeDesc.ofDescriptor(
                MethodType.methodType(accessor.getReturnType(), accessor.getParameterTypes())
                        .descriptorString());
        c.invoke(
                owner.isInterface() ? Opcode.INVOKEINTERFACE : Opcode.INVOKEVIRTUAL,
                cd(owner),
                accessor.getName(),
                type,
                owner.isInterface());
    }

    private static boolean needsDescriptor(TypedWriterPlan.Field field) {
        return field.isMap() || field.descriptor().getType() == FieldDescriptor.Type.ENUM;
    }

    private static String descriptorField(TypedWriterPlan.Field field) {
        return "f" + field.descriptor().getNumber();
    }

    private static String writerField(TypedWriterPlan.Field field) {
        return "n" + field.descriptor().getNumber();
    }

    private static ClassDesc cd(Class<?> type) {
        return ClassDesc.ofDescriptor(type.descriptorString());
    }
}
