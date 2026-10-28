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
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.google.protobuf.ByteString;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.EnumValueDescriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.DynamicMessage;
import dev.appiaworks.protobuf.extras.serde.AbstractDynamicSerializer;
import dev.appiaworks.protobuf.extras.serde.ProjectionNode;
import dev.appiaworks.protobuf.extras.serde.ProtoMapConverter;
import dev.appiaworks.protobuf.extras.serde.ProtoWkt;
import dev.appiaworks.protobuf.extras.serde.StrictScalars;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassHierarchyResolver;
import java.lang.classfile.CodeBuilder;
import java.lang.classfile.Label;
import java.lang.classfile.Opcode;
import java.lang.classfile.TypeKind;
import java.lang.constant.ClassDesc;
import java.lang.constant.MethodTypeDesc;
import java.lang.invoke.MethodHandles;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * The bytes of an {@link AbstractDynamicSerializer} subclass for one message type: the Tier 2
 * serializer.
 *
 * <p>The class holds the {@code FieldDescriptor} of every field (resolved once, in the
 * constructor, so the hot paths never look a field up by name), a serializer slot per nested
 * message field that {@code nestedBound} fills, and the five generated methods — {@code
 * serialize}, {@code deserialize}, {@code toMap}, the projected {@code toMap} and {@code
 * fromMap} — each the per-field statements of the proto3 JSON mapping unrolled: a field's
 * presence check, its JSON name, and its value through the typed Jackson call, with the shared
 * helpers in {@link ProtoMapConverter}, {@link ProtoWkt} and {@link StrictScalars} for maps,
 * single-value types and strict reads.
 *
 * <p>Strict mode bakes the dialect's input rule in: {@code deserialize} refuses a value that
 * does not fit its field through {@link StrictScalars}, and {@code fromMap} is
 * {@link StrictScalars#fill}.
 */
final class DynamicSerializerEmitter {

    private static final ClassDesc CD_ADS = cd(AbstractDynamicSerializer.class);
    private static final ClassDesc CD_DM = cd(DynamicMessage.class);
    private static final ClassDesc CD_DMB = cd(DynamicMessage.Builder.class);
    private static final ClassDesc CD_FD = cd(FieldDescriptor.class);
    private static final ClassDesc CD_DESC = cd(Descriptor.class);
    private static final ClassDesc CD_EVD = cd(EnumValueDescriptor.class);
    private static final ClassDesc CD_GEN = cd(JsonGenerator.class);
    private static final ClassDesc CD_PARSER = cd(JsonParser.class);
    private static final ClassDesc CD_TOKEN = cd(JsonToken.class);
    private static final ClassDesc CD_CONVERTER = cd(ProtoMapConverter.class);
    private static final ClassDesc CD_WKT = cd(ProtoWkt.class);
    private static final ClassDesc CD_STRICT = cd(StrictScalars.class);
    private static final ClassDesc CD_PROJ = cd(ProjectionNode.class);
    private static final ClassDesc CD_LIST = cd(List.class);
    private static final ClassDesc CD_MAP = cd(Map.class);
    private static final ClassDesc CD_ARRAYLIST = cd(ArrayList.class);
    private static final ClassDesc CD_LINKEDHASHMAP = cd(LinkedHashMap.class);
    private static final ClassDesc CD_INTEGER = cd(Integer.class);
    private static final ClassDesc CD_LONG = cd(Long.class);
    private static final ClassDesc CD_FLOAT = cd(Float.class);
    private static final ClassDesc CD_DOUBLE = cd(Double.class);
    private static final ClassDesc CD_BOOLEAN = cd(Boolean.class);
    private static final ClassDesc CD_BYTESTRING = cd(ByteString.class);
    private static final ClassDesc CD_BASE64 = cd(Base64.class);
    private static final ClassDesc CD_ENCODER = cd(Base64.Encoder.class);
    private static final ClassDesc CD_DECODER = cd(Base64.Decoder.class);
    private static final ClassDesc CD_RUNTIME_EXCEPTION = cd(RuntimeException.class);
    private static final ClassDesc CD_BYTES = CD_byte.arrayType();

    private static final MethodTypeDesc MTD_VOID = MethodTypeDesc.of(CD_void);
    private static final MethodTypeDesc MTD_VOID_STRING = MethodTypeDesc.of(CD_void, CD_String);
    private static final MethodTypeDesc MTD_OBJECT_FD = MethodTypeDesc.of(CD_Object, CD_FD);
    private static final MethodTypeDesc MTD_BOOLEAN_FD = MethodTypeDesc.of(CD_boolean, CD_FD);
    private static final MethodTypeDesc MTD_TOKEN = MethodTypeDesc.of(CD_TOKEN);
    private static final MethodTypeDesc MTD_SET = MethodTypeDesc.of(CD_DMB, CD_FD, CD_Object);
    private static final MethodTypeDesc MTD_DM_PARSER_DMB = MethodTypeDesc.of(CD_DM, CD_PARSER, CD_DMB);
    private static final MethodTypeDesc MTD_DM_MAP_DMB = MethodTypeDesc.of(CD_DM, CD_MAP, CD_DMB);
    private static final MethodTypeDesc MTD_DM_MAP_DESC = MethodTypeDesc.of(CD_DM, CD_MAP, CD_DESC);
    private static final MethodTypeDesc MTD_MAP_DM = MethodTypeDesc.of(CD_MAP, CD_DM);
    private static final MethodTypeDesc MTD_MAP_DM_PROJ = MethodTypeDesc.of(CD_MAP, CD_DM, CD_PROJ);
    private static final MethodTypeDesc MTD_VOID_GEN_DM = MethodTypeDesc.of(CD_void, CD_GEN, CD_DM);
    private static final MethodTypeDesc MTD_OBJECT_PARSER = MethodTypeDesc.of(CD_Object, CD_PARSER);
    private static final MethodTypeDesc MTD_MAP_PARSER = MethodTypeDesc.of(CD_MAP, CD_PARSER);

    /** Slot of the first parameter of every generated method. */
    private static final int ARG0 = 1;

    private static final int ARG1 = 2;

    private final Descriptor descriptor;
    private final boolean strict;
    private final MethodHandles.Lookup lookup;
    private final ClassDesc self;
    private final List<FieldDescriptor> fields;

    DynamicSerializerEmitter(Descriptor descriptor, boolean strict, MethodHandles.Lookup lookup) {
        this.descriptor = descriptor;
        this.strict = strict;
        this.lookup = lookup;
        this.fields = descriptor.getFields();
        this.self = ClassDesc.of(
                lookup.lookupClass().getPackageName(),
                descriptor.getFullName().replace('.', '_').replace('-', '_') + "$$DynSerializer");
    }

    byte[] emit() {
        ClassFile classFile = ClassFile.of(ClassFile.ClassHierarchyResolverOption.of(
                ClassHierarchyResolver.ofClassLoading(lookup).orElse(ClassHierarchyResolver.defaultResolver())));
        return classFile.build(self, cb -> {
            cb.withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_FINAL | ClassFile.ACC_SUPER);
            cb.withSuperclass(CD_ADS);
            cb.withField("descriptor", CD_DESC, ClassFile.ACC_PRIVATE | ClassFile.ACC_FINAL);
            for (FieldDescriptor fd : fields) {
                cb.withField(fdField(fd), CD_FD, ClassFile.ACC_PRIVATE | ClassFile.ACC_FINAL);
                if (isNested(fd)) cb.withField(nestedField(fd), CD_ADS, ClassFile.ACC_PRIVATE);
            }
            cb.withMethodBody("<init>", MethodTypeDesc.of(CD_void, CD_DESC), ClassFile.ACC_PUBLIC, this::constructor);
            cb.withMethodBody(
                    "nestedBound",
                    MethodTypeDesc.of(CD_void, CD_String, CD_ADS),
                    ClassFile.ACC_PROTECTED,
                    this::nestedBound);
            cb.withMethodBody(
                    "serialize", MethodTypeDesc.of(CD_void, CD_DM, CD_GEN), ClassFile.ACC_PUBLIC, this::serialize);
            cb.withMethodBody("deserialize", MTD_DM_PARSER_DMB, ClassFile.ACC_PUBLIC, this::deserialize);
            cb.withMethodBody("toMap", MTD_MAP_DM, ClassFile.ACC_PUBLIC, this::toMap);
            cb.withMethodBody("toMap", MTD_MAP_DM_PROJ, ClassFile.ACC_PUBLIC, this::toMapProjected);
            cb.withMethodBody("fromMap", MTD_DM_MAP_DMB, ClassFile.ACC_PUBLIC, this::fromMap);
        });
    }

    // ── Constructor and wiring ────────────────────────────────────────────────────────

    private void constructor(CodeBuilder c) {
        c.aload(0).invokespecial(CD_ADS, "<init>", MTD_VOID);
        c.aload(0).aload(1).putfield(self, "descriptor", CD_DESC);
        for (FieldDescriptor fd : fields) {
            c.aload(0)
                    .aload(1)
                    .loadConstant(fd.getNumber())
                    .invokevirtual(CD_DESC, "findFieldByNumber", MethodTypeDesc.of(CD_FD, CD_int))
                    .putfield(self, fdField(fd), CD_FD);
        }
        c.return_();
    }

    private void nestedBound(CodeBuilder c) {
        for (FieldDescriptor fd : fields) {
            if (!isNested(fd)) continue;
            c.loadConstant(fd.getMessageType().getFullName())
                    .aload(1)
                    .invokevirtual(CD_String, "equals", MethodTypeDesc.of(CD_boolean, CD_Object))
                    .ifThen(Opcode.IFNE, then -> then.aload(0).aload(2).putfield(self, nestedField(fd), CD_ADS));
        }
        c.return_();
    }

    // ── serialize(DynamicMessage msg, JsonGenerator gen) ──────────────────────────────

    private void serialize(CodeBuilder c) {
        final int msg = ARG0;
        final int gen = ARG1;
        c.aload(gen).invokevirtual(CD_GEN, "writeStartObject", MTD_VOID);
        for (FieldDescriptor fd : fields) {
            Label skip = c.newLabel();
            if (!fd.isRepeated() && fd.hasPresence()) {
                hasField(c, msg, fd);
                c.ifeq(skip);
            }
            c.aload(gen).loadConstant(fd.getJsonName()).invokevirtual(CD_GEN, "writeFieldName", MTD_VOID_STRING);
            serializeField(c, fd, msg, gen);
            c.labelBinding(skip);
        }
        c.aload(gen).invokevirtual(CD_GEN, "writeEndObject", MTD_VOID);
        c.return_();
    }

    private void serializeField(CodeBuilder c, FieldDescriptor fd, int msg, int gen) {
        if (fd.isMapField()) {
            field(c, fd);
            getField(c, msg, fd);
            c.checkcast(CD_LIST)
                    .aload(gen)
                    .invokestatic(CD_CONVERTER, "writeMapField", MethodTypeDesc.of(CD_void, CD_FD, CD_LIST, CD_GEN));
            return;
        }
        if (fd.isRepeated()) {
            int list = c.allocateLocal(TypeKind.REFERENCE);
            getField(c, msg, fd);
            c.checkcast(CD_LIST).astore(list);
            c.aload(gen).invokevirtual(CD_GEN, "writeStartArray", MTD_VOID);
            forEachInList(c, list, (cc, element) -> {
                if (isWktField(fd)) {
                    cc.aload(gen);
                    element.accept(cc);
                    cc.checkcast(CD_DM).invokestatic(CD_WKT, "writeJson", MTD_VOID_GEN_DM);
                } else if (fd.getType() == FieldDescriptor.Type.MESSAGE) {
                    int item = cc.allocateLocal(TypeKind.REFERENCE);
                    element.accept(cc);
                    cc.checkcast(CD_DM).astore(item);
                    writeNested(cc, fd, item, gen);
                } else {
                    writeScalar(cc, fd, gen, element);
                }
            });
            c.aload(gen).invokevirtual(CD_GEN, "writeEndArray", MTD_VOID);
            return;
        }
        if (isWktField(fd)) {
            c.aload(gen);
            getField(c, msg, fd);
            c.checkcast(CD_DM).invokestatic(CD_WKT, "writeJson", MTD_VOID_GEN_DM);
            return;
        }
        if (fd.getType() == FieldDescriptor.Type.MESSAGE) {
            int value = c.allocateLocal(TypeKind.REFERENCE);
            getField(c, msg, fd);
            c.checkcast(CD_DM).astore(value);
            writeNested(c, fd, value, gen);
            return;
        }
        writeScalar(c, fd, gen, cc -> getField(cc, msg, fd));
    }

    /** {@code n != null ? n.serialize(value, gen) : ProtoMapConverter.writeMessageJson(gen, value)}. */
    private void writeNested(CodeBuilder c, FieldDescriptor fd, int value, int gen) {
        nested(c, fd);
        c.ifThenElse(
                Opcode.IFNONNULL,
                then -> {
                    nested(then, fd);
                    then.aload(value)
                            .aload(gen)
                            .invokevirtual(CD_ADS, "serialize", MethodTypeDesc.of(CD_void, CD_DM, CD_GEN));
                },
                otherwise -> otherwise
                        .aload(gen)
                        .aload(value)
                        .invokestatic(CD_CONVERTER, "writeMessageJson", MTD_VOID_GEN_DM));
    }

    /** Writes one boxed scalar ({@code value} pushes it as an Object) with the proto3 JSON rules. */
    private static void writeScalar(CodeBuilder c, FieldDescriptor fd, int gen, Consumer<CodeBuilder> value) {
        c.aload(gen);
        switch (fd.getType()) {
            case STRING -> {
                value.accept(c);
                c.checkcast(CD_String).invokevirtual(CD_GEN, "writeString", MTD_VOID_STRING);
            }
            case BYTES -> {
                c.invokestatic(CD_BASE64, "getEncoder", MethodTypeDesc.of(CD_ENCODER));
                value.accept(c);
                c.checkcast(CD_BYTESTRING)
                        .invokevirtual(CD_BYTESTRING, "toByteArray", MethodTypeDesc.of(CD_BYTES))
                        .invokevirtual(CD_ENCODER, "encodeToString", MethodTypeDesc.of(CD_String, CD_BYTES))
                        .invokevirtual(CD_GEN, "writeString", MTD_VOID_STRING);
            }
            case INT32, SINT32, SFIXED32 -> {
                value.accept(c);
                unboxInt(c).invokevirtual(CD_GEN, "writeNumber", MethodTypeDesc.of(CD_void, CD_int));
            }
            case UINT32, FIXED32 -> {
                value.accept(c);
                unboxInt(c)
                        .i2l()
                        .loadConstant(0xFFFFFFFFL)
                        .land()
                        .invokevirtual(CD_GEN, "writeNumber", MethodTypeDesc.of(CD_void, CD_long));
            }
            case INT64, SINT64, SFIXED64 -> {
                value.accept(c);
                unboxLong(c).invokestatic(CD_CONVERTER, "writeInt64", MethodTypeDesc.of(CD_void, CD_GEN, CD_long));
            }
            case UINT64, FIXED64 -> {
                value.accept(c);
                unboxLong(c).invokestatic(CD_CONVERTER, "writeUInt64", MethodTypeDesc.of(CD_void, CD_GEN, CD_long));
            }
            case FLOAT -> {
                value.accept(c);
                c.checkcast(CD_FLOAT)
                        .invokevirtual(CD_FLOAT, "floatValue", MethodTypeDesc.of(CD_float))
                        .invokevirtual(CD_GEN, "writeNumber", MethodTypeDesc.of(CD_void, CD_float));
            }
            case DOUBLE -> {
                value.accept(c);
                c.checkcast(CD_DOUBLE)
                        .invokevirtual(CD_DOUBLE, "doubleValue", MethodTypeDesc.of(CD_double))
                        .invokevirtual(CD_GEN, "writeNumber", MethodTypeDesc.of(CD_void, CD_double));
            }
            case BOOL -> {
                value.accept(c);
                c.checkcast(CD_BOOLEAN)
                        .invokevirtual(CD_BOOLEAN, "booleanValue", MethodTypeDesc.of(CD_boolean))
                        .invokevirtual(CD_GEN, "writeBoolean", MethodTypeDesc.of(CD_void, CD_boolean));
            }
            case ENUM -> {
                // The name, or the number for an unknown value, as every other path writes it.
                value.accept(c);
                c.checkcast(CD_EVD).invokestatic(CD_CONVERTER, "writeEnum", MethodTypeDesc.of(CD_void, CD_GEN, CD_EVD));
            }
            default -> {
                value.accept(c);
                c.invokestatic(CD_String, "valueOf", MethodTypeDesc.of(CD_String, CD_Object))
                        .invokevirtual(CD_GEN, "writeString", MTD_VOID_STRING);
            }
        }
    }

    // ── deserialize(JsonParser p, DynamicMessage.Builder b) ───────────────────────────

    private void deserialize(CodeBuilder c) {
        final int p = ARG0;
        final int b = ARG1;
        if (strict) {
            Label ok = c.newLabel();
            currentToken(c, p);
            token(c, "START_OBJECT");
            c.if_acmpeq(ok);
            c.aload(0).getfield(self, "descriptor", CD_DESC).aload(p);
            readTree(c);
            c.invokestatic(CD_STRICT, "notAnObject", MethodTypeDesc.of(CD_RUNTIME_EXCEPTION, CD_DESC, CD_Object))
                    .athrow();
            c.labelBinding(ok);
        }
        int name = c.allocateLocal(TypeKind.REFERENCE);
        Label loop = c.newBoundLabel();
        Label end = c.newLabel();
        // while (p.nextToken() != END_OBJECT)
        c.aload(p).invokevirtual(CD_PARSER, "nextToken", MTD_TOKEN);
        token(c, "END_OBJECT");
        c.if_acmpeq(end);
        c.aload(p)
                .invokevirtual(CD_PARSER, "currentName", MethodTypeDesc.of(CD_String))
                .astore(name);
        nextToken(c, p);
        for (FieldDescriptor fd : fields) {
            Label matched = c.newLabel();
            Label next = c.newLabel();
            c.loadConstant(fd.getJsonName())
                    .aload(name)
                    .invokevirtual(CD_String, "equals", MethodTypeDesc.of(CD_boolean, CD_Object))
                    .ifne(matched);
            if (!fd.getJsonName().equals(fd.getName())) {
                c.loadConstant(fd.getName())
                        .aload(name)
                        .invokevirtual(CD_String, "equals", MethodTypeDesc.of(CD_boolean, CD_Object))
                        .ifne(matched);
            }
            c.goto_(next);
            c.labelBinding(matched);
            // JSON null leaves a field unset, except google.protobuf.Value, where null is a value.
            boolean acceptsNull = !fd.isRepeated()
                    && fd.getType() == FieldDescriptor.Type.MESSAGE
                    && "google.protobuf.Value".equals(fd.getMessageType().getFullName());
            Label skip = c.newLabel();
            if (!acceptsNull) {
                currentToken(c, p);
                token(c, "VALUE_NULL");
                c.if_acmpeq(skip);
            }
            if (strict) {
                strictRead(c, fd, p, b);
            } else {
                lenientRead(c, fd, p, b);
            }
            c.labelBinding(skip);
            c.goto_(loop); // continue
            c.labelBinding(next);
        }
        c.aload(p)
                .invokevirtual(CD_PARSER, "skipChildren", MethodTypeDesc.of(CD_PARSER))
                .pop(); // unknown field
        c.goto_(loop);
        c.labelBinding(end);
        c.aload(b).invokevirtual(CD_DMB, "build", MethodTypeDesc.of(CD_DM)).areturn();
    }

    /** One field's read, coercing what it can: the parser is on the value token. */
    private void lenientRead(CodeBuilder c, FieldDescriptor fd, int p, int b) {
        if (fd.isMapField()) {
            ifToken(c, p, "START_OBJECT", cc -> {
                field(cc, fd);
                cc.aload(p);
                readObjectTree(cc);
                cc.aload(b)
                        .invokestatic(
                                CD_CONVERTER, "mapFieldFromMap", MethodTypeDesc.of(CD_void, CD_FD, CD_MAP, CD_DMB));
            });
            return;
        }
        if (fd.isRepeated()) {
            if (isWktField(fd)) {
                ifToken(c, p, "START_ARRAY", cc -> {
                    int list = cc.allocateLocal(TypeKind.REFERENCE);
                    cc.aload(p);
                    readTree(cc);
                    cc.checkcast(CD_LIST).astore(list);
                    forEachInList(cc, list, (ccc, element) -> {
                        int w = ccc.allocateLocal(TypeKind.REFERENCE);
                        element.accept(ccc);
                        messageType(ccc, fd);
                        ccc.invokestatic(CD_WKT, "fromJava", MethodTypeDesc.of(CD_DM, CD_Object, CD_DESC))
                                .astore(w);
                        ccc.aload(w).ifThen(Opcode.IFNONNULL, then -> {
                            then.aload(b);
                            field(then, fd);
                            then.aload(w);
                            addRepeated(then);
                        });
                    });
                });
                return;
            }
            if (fd.getType() == FieldDescriptor.Type.MESSAGE) {
                // Streaming: the nested serializer consumes the object tokens directly; databind
                // fallback only when no nested serializer is wired.
                ifToken(c, p, "START_ARRAY", cc -> {
                    nextToken(cc, p);
                    whileTokenNot(cc, p, "END_ARRAY", ccc -> {
                        currentToken(ccc, p);
                        token(ccc, "START_OBJECT");
                        ccc.ifThenElse(
                                Opcode.IF_ACMPEQ,
                                then -> {
                                    then.aload(b);
                                    field(then, fd);
                                    readNestedObject(then, fd, p);
                                    addRepeated(then);
                                },
                                otherwise -> otherwise
                                        .aload(p)
                                        .invokevirtual(CD_PARSER, "skipChildren", MethodTypeDesc.of(CD_PARSER))
                                        .pop());
                    });
                });
                return;
            }
            if (fd.getType() == FieldDescriptor.Type.ENUM) {
                ifToken(c, p, "START_ARRAY", cc -> {
                    nextToken(cc, p);
                    whileTokenNot(cc, p, "END_ARRAY", ccc -> {
                        int e = ccc.allocateLocal(TypeKind.REFERENCE);
                        field(ccc, fd);
                        enumToken(ccc, p);
                        ccc.invokestatic(CD_WKT, "enumValue", MethodTypeDesc.of(CD_EVD, CD_FD, CD_Object))
                                .astore(e);
                        ccc.aload(e).ifThen(Opcode.IFNONNULL, then -> {
                            then.aload(b);
                            field(then, fd);
                            then.aload(e);
                            addRepeated(then);
                        });
                    });
                });
                return;
            }
            if (!isLenientScalar(fd)) {
                c.aload(p)
                        .invokevirtual(CD_PARSER, "skipChildren", MethodTypeDesc.of(CD_PARSER))
                        .pop();
                return;
            }
            ifToken(c, p, "START_ARRAY", cc -> {
                nextToken(cc, p);
                whileTokenNot(cc, p, "END_ARRAY", ccc -> {
                    currentToken(ccc, p);
                    token(ccc, "VALUE_NULL");
                    ccc.ifThen(Opcode.IF_ACMPNE, then -> {
                        then.aload(b);
                        field(then, fd);
                        lenientScalarValue(then, fd, p);
                        addRepeated(then);
                    });
                });
            });
            return;
        }
        if (isWktField(fd)) {
            int w = c.allocateLocal(TypeKind.REFERENCE);
            c.aload(p);
            readTree(c);
            messageType(c, fd);
            c.invokestatic(CD_WKT, "fromJava", MethodTypeDesc.of(CD_DM, CD_Object, CD_DESC))
                    .astore(w);
            c.aload(w).ifThen(Opcode.IFNONNULL, then -> {
                then.aload(b);
                field(then, fd);
                then.aload(w);
                setField(then);
            });
            return;
        }
        switch (fd.getType()) {
            case ENUM -> {
                int e = c.allocateLocal(TypeKind.REFERENCE);
                field(c, fd);
                enumToken(c, p);
                c.invokestatic(CD_WKT, "enumValue", MethodTypeDesc.of(CD_EVD, CD_FD, CD_Object))
                        .astore(e);
                c.aload(e).ifThen(Opcode.IFNONNULL, then -> {
                    then.aload(b);
                    field(then, fd);
                    then.aload(e);
                    setField(then);
                });
            }
            case MESSAGE ->
                ifToken(c, p, "START_OBJECT", cc -> {
                    cc.aload(b);
                    field(cc, fd);
                    readNestedObject(cc, fd, p);
                    setField(cc);
                });
            default -> {
                if (!isLenientScalar(fd)) {
                    c.aload(p)
                            .invokevirtual(CD_PARSER, "skipChildren", MethodTypeDesc.of(CD_PARSER))
                            .pop();
                    return;
                }
                c.aload(b);
                field(c, fd);
                lenientScalarValue(c, fd, p);
                setField(c);
            }
        }
    }

    /**
     * {@code n != null ? n.deserialize(p, newBuilder(type)) : ProtoMapConverter.dynamicFromMap(readObjectTree(p), type)}:
     * the parser is on START_OBJECT and ends on END_OBJECT either way. Leaves the message on the stack.
     */
    private void readNestedObject(CodeBuilder c, FieldDescriptor fd, int p) {
        nested(c, fd);
        c.ifThenElse(
                Opcode.IFNONNULL,
                then -> {
                    nested(then, fd);
                    then.aload(p);
                    newBuilder(then, fd);
                    then.invokevirtual(CD_ADS, "deserialize", MTD_DM_PARSER_DMB);
                },
                otherwise -> {
                    otherwise.aload(p);
                    readObjectTree(otherwise);
                    messageType(otherwise, fd);
                    otherwise.invokestatic(CD_CONVERTER, "dynamicFromMap", MTD_DM_MAP_DESC);
                });
    }

    /** The current token as the enum's number ({@code Integer}) or name ({@code String}). */
    private static void enumToken(CodeBuilder c, int p) {
        currentToken(c, p);
        token(c, "VALUE_NUMBER_INT");
        c.ifThenElse(
                Opcode.IF_ACMPEQ,
                then -> then.aload(p)
                        .invokevirtual(CD_PARSER, "getIntValue", MethodTypeDesc.of(CD_int))
                        .invokestatic(CD_INTEGER, "valueOf", MethodTypeDesc.of(CD_INTEGER, CD_int)),
                otherwise -> otherwise.aload(p).invokevirtual(CD_PARSER, "getText", MethodTypeDesc.of(CD_String)));
    }

    private static boolean isLenientScalar(FieldDescriptor fd) {
        return switch (fd.getType()) {
            case STRING,
                    BYTES,
                    INT32,
                    SINT32,
                    SFIXED32,
                    UINT32,
                    FIXED32,
                    INT64,
                    SINT64,
                    SFIXED64,
                    UINT64,
                    FIXED64,
                    FLOAT,
                    DOUBLE,
                    BOOL -> true;
            default -> false;
        };
    }

    /** Reads the current scalar token as the boxed value the builder takes. */
    private static void lenientScalarValue(CodeBuilder c, FieldDescriptor fd, int p) {
        switch (fd.getType()) {
            case STRING -> c.aload(p).invokevirtual(CD_PARSER, "getText", MethodTypeDesc.of(CD_String));
            case BYTES ->
                c.invokestatic(CD_BASE64, "getDecoder", MethodTypeDesc.of(CD_DECODER))
                        .aload(p)
                        .invokevirtual(CD_PARSER, "getText", MethodTypeDesc.of(CD_String))
                        .invokevirtual(CD_DECODER, "decode", MethodTypeDesc.of(CD_BYTES, CD_String))
                        .invokestatic(CD_BYTESTRING, "copyFrom", MethodTypeDesc.of(CD_BYTESTRING, CD_BYTES));
            case INT32, SINT32, SFIXED32 ->
                c.aload(p)
                        .invokevirtual(CD_PARSER, "getValueAsInt", MethodTypeDesc.of(CD_int))
                        .invokestatic(CD_INTEGER, "valueOf", MethodTypeDesc.of(CD_INTEGER, CD_int));
            case UINT32, FIXED32 ->
                c.aload(p)
                        .invokevirtual(CD_PARSER, "getValueAsLong", MethodTypeDesc.of(CD_long))
                        .l2i()
                        .invokestatic(CD_INTEGER, "valueOf", MethodTypeDesc.of(CD_INTEGER, CD_int));
            case INT64, SINT64, SFIXED64 ->
                c.aload(p)
                        .invokevirtual(CD_PARSER, "getValueAsLong", MethodTypeDesc.of(CD_long))
                        .invokestatic(CD_LONG, "valueOf", MethodTypeDesc.of(CD_LONG, CD_long));
            case UINT64, FIXED64 -> {
                currentToken(c, p);
                token(c, "VALUE_STRING");
                c.ifThenElse(
                        Opcode.IF_ACMPEQ,
                        then -> then.aload(p)
                                .invokevirtual(CD_PARSER, "getText", MethodTypeDesc.of(CD_String))
                                .invokestatic(CD_LONG, "parseUnsignedLong", MethodTypeDesc.of(CD_long, CD_String)),
                        otherwise -> otherwise
                                .aload(p)
                                .invokevirtual(CD_PARSER, "getValueAsLong", MethodTypeDesc.of(CD_long)));
                c.invokestatic(CD_LONG, "valueOf", MethodTypeDesc.of(CD_LONG, CD_long));
            }
            case FLOAT ->
                c.aload(p)
                        .invokevirtual(CD_PARSER, "getValueAsDouble", MethodTypeDesc.of(CD_double))
                        .d2f()
                        .invokestatic(CD_FLOAT, "valueOf", MethodTypeDesc.of(CD_FLOAT, CD_float));
            case DOUBLE ->
                c.aload(p)
                        .invokevirtual(CD_PARSER, "getValueAsDouble", MethodTypeDesc.of(CD_double))
                        .invokestatic(CD_DOUBLE, "valueOf", MethodTypeDesc.of(CD_DOUBLE, CD_double));
            case BOOL ->
                c.aload(p)
                        .invokevirtual(CD_PARSER, "getBooleanValue", MethodTypeDesc.of(CD_boolean))
                        .invokestatic(CD_BOOLEAN, "valueOf", MethodTypeDesc.of(CD_BOOLEAN, CD_boolean));
            default -> throw new IllegalArgumentException("Not a scalar: " + fd);
        }
    }

    /**
     * One field's read under strict input: every value goes through {@link StrictScalars}, so the
     * parser accepts and builds exactly what the reflective converter does. A nested object
     * streams into its own serializer.
     */
    private void strictRead(CodeBuilder c, FieldDescriptor fd, int p, int b) {
        if (fd.isMapField()) {
            field(c, fd);
            c.aload(p);
            readTree(c);
            c.aload(b).invokestatic(CD_STRICT, "mapField", MethodTypeDesc.of(CD_void, CD_FD, CD_Object, CD_DMB));
            return;
        }
        if (fd.isRepeated()) {
            Label isArray = c.newLabel();
            currentToken(c, p);
            token(c, "START_ARRAY");
            c.if_acmpeq(isArray);
            field(c, fd);
            c.aload(p);
            readTree(c);
            c.loadConstant("takes a JSON array")
                    .invokestatic(
                            CD_STRICT, "reject", MethodTypeDesc.of(CD_RUNTIME_EXCEPTION, CD_FD, CD_Object, CD_String))
                    .athrow();
            c.labelBinding(isArray);
            nextToken(c, p);
            whileTokenNot(c, p, "END_ARRAY", cc -> strictElement(cc, fd, p, b, true));
            return;
        }
        strictElement(c, fd, p, b, false);
    }

    /** One strict value into the builder: added to a repeated field or set on a singular one. */
    private void strictElement(CodeBuilder c, FieldDescriptor fd, int p, int b, boolean repeated) {
        if (isNested(fd)) {
            Label streamed = c.newLabel();
            Label done = c.newLabel();
            currentToken(c, p);
            token(c, "START_OBJECT");
            c.if_acmpne(streamed);
            nested(c, fd);
            c.ifnull(streamed);
            c.aload(b);
            field(c, fd);
            nested(c, fd);
            c.aload(p);
            newBuilder(c, fd);
            c.invokevirtual(CD_ADS, "deserialize", MTD_DM_PARSER_DMB);
            put(c, repeated);
            c.goto_(done);
            c.labelBinding(streamed);
            strictScalar(c, fd, p, b, repeated);
            c.labelBinding(done);
            return;
        }
        strictScalar(c, fd, p, b, repeated);
    }

    /** {@code Object x = StrictScalars.read(p, f); if (x != null) b.set/add(f, x)}. */
    private void strictScalar(CodeBuilder c, FieldDescriptor fd, int p, int b, boolean repeated) {
        int x = c.allocateLocal(TypeKind.REFERENCE);
        c.aload(p);
        field(c, fd);
        c.invokestatic(CD_STRICT, "read", MethodTypeDesc.of(CD_Object, CD_PARSER, CD_FD))
                .astore(x);
        c.aload(x).ifThen(Opcode.IFNONNULL, then -> {
            then.aload(b);
            field(then, fd);
            then.aload(x);
            put(then, repeated);
        });
    }

    // ── toMap(DynamicMessage msg) ─────────────────────────────────────────────────────

    private void toMap(CodeBuilder c) {
        final int msg = ARG0;
        int map = newMap(c);
        for (FieldDescriptor fd : fields) {
            toMapField(c, fd, msg, map);
        }
        c.aload(map).areturn();
    }

    private int newMap(CodeBuilder c) {
        int map = c.allocateLocal(TypeKind.REFERENCE);
        c.new_(CD_LINKEDHASHMAP)
                .dup()
                .loadConstant(fields.size() * 2)
                .invokespecial(CD_LINKEDHASHMAP, "<init>", MethodTypeDesc.of(CD_void, CD_int))
                .astore(map);
        return map;
    }

    /** {@code map.put(key, <value>)}: {@code value} pushes the value. */
    private static void put(CodeBuilder c, int map, FieldDescriptor fd, Consumer<CodeBuilder> value) {
        c.aload(map).loadConstant(fd.getJsonName());
        value.accept(c);
        c.invokeinterface(CD_MAP, "put", MethodTypeDesc.of(CD_Object, CD_Object, CD_Object))
                .pop();
    }

    private void toMapField(CodeBuilder c, FieldDescriptor fd, int msg, int map) {
        if (fd.isMapField()) {
            put(c, map, fd, cc -> {
                field(cc, fd);
                getField(cc, msg, fd);
                cc.checkcast(CD_LIST)
                        .invokestatic(CD_CONVERTER, "mapFieldToJavaMap", MethodTypeDesc.of(CD_MAP, CD_FD, CD_LIST));
            });
            return;
        }
        if (fd.isRepeated()) {
            if (isWktField(fd) || fd.getType() == FieldDescriptor.Type.MESSAGE) {
                int list = c.allocateLocal(TypeKind.REFERENCE);
                int result = c.allocateLocal(TypeKind.REFERENCE);
                getField(c, msg, fd);
                c.checkcast(CD_LIST).astore(list);
                c.new_(CD_ARRAYLIST)
                        .dup()
                        .aload(list)
                        .invokeinterface(CD_LIST, "size", MethodTypeDesc.of(CD_int))
                        .invokespecial(CD_ARRAYLIST, "<init>", MethodTypeDesc.of(CD_void, CD_int))
                        .astore(result);
                forEachInList(c, list, (cc, element) -> {
                    cc.aload(result);
                    if (isWktField(fd)) {
                        element.accept(cc);
                        cc.checkcast(CD_DM).invokestatic(CD_WKT, "toJavaValue", MethodTypeDesc.of(CD_Object, CD_DM));
                    } else {
                        int item = cc.allocateLocal(TypeKind.REFERENCE);
                        element.accept(cc);
                        cc.checkcast(CD_DM).astore(item);
                        nestedToMap(cc, fd, item, -1);
                    }
                    cc.invokeinterface(CD_LIST, "add", MethodTypeDesc.of(CD_boolean, CD_Object))
                            .pop();
                });
                put(c, map, fd, cc -> cc.aload(result));
                return;
            }
            if (fd.getType() == FieldDescriptor.Type.ENUM || fd.getType() == FieldDescriptor.Type.BYTES) {
                put(c, map, fd, cc -> {
                    field(cc, fd);
                    getField(cc, msg, fd);
                    cc.checkcast(CD_LIST)
                            .invokestatic(CD_CONVERTER, "repeatedToJava", MethodTypeDesc.of(CD_LIST, CD_FD, CD_LIST));
                });
                return;
            }
            // Scalar repeated: DynamicMessage already stores as List<T>
            put(c, map, fd, cc -> getField(cc, msg, fd));
            return;
        }
        if (fd.getType() == FieldDescriptor.Type.MESSAGE && !isWktField(fd)) {
            // Singular non-WKT message field
            hasField(c, msg, fd);
            c.ifThen(Opcode.IFNE, then -> {
                int value = then.allocateLocal(TypeKind.REFERENCE);
                getField(then, msg, fd);
                then.checkcast(CD_DM).astore(value);
                put(then, map, fd, cc -> nestedToMap(cc, fd, value, -1));
            });
            return;
        }
        Consumer<CodeBuilder> value = cc -> {
            getField(cc, msg, fd);
            switch (fd.getType()) {
                case MESSAGE ->
                    cc.checkcast(CD_DM).invokestatic(CD_WKT, "toJavaValue", MethodTypeDesc.of(CD_Object, CD_DM));
                case BYTES -> {
                    int bytes = cc.allocateLocal(TypeKind.REFERENCE);
                    cc.checkcast(CD_BYTESTRING).astore(bytes);
                    cc.invokestatic(CD_BASE64, "getEncoder", MethodTypeDesc.of(CD_ENCODER))
                            .aload(bytes)
                            .invokevirtual(CD_BYTESTRING, "toByteArray", MethodTypeDesc.of(CD_BYTES))
                            .invokevirtual(CD_ENCODER, "encodeToString", MethodTypeDesc.of(CD_String, CD_BYTES));
                }
                case ENUM ->
                    cc.checkcast(CD_EVD).invokestatic(CD_CONVERTER, "enumToJava", MethodTypeDesc.of(CD_Object, CD_EVD));
                case STRING,
                        BOOL,
                        INT32,
                        UINT32,
                        FIXED32,
                        SFIXED32,
                        SINT32,
                        INT64,
                        UINT64,
                        FIXED64,
                        SFIXED64,
                        SINT64,
                        FLOAT,
                        DOUBLE -> {
                    // already the boxed value the map holds
                }
                default -> cc.invokestatic(CD_String, "valueOf", MethodTypeDesc.of(CD_String, CD_Object));
            }
        };
        if (fd.hasPresence()) {
            hasField(c, msg, fd);
            c.ifThen(Opcode.IFNE, then -> put(then, map, fd, value));
        } else {
            put(c, map, fd, value);
        }
    }

    /**
     * {@code n != null ? n.toMap(item[, projection]) : ProtoMapConverter.dynamicToMap(item)} (or
     * {@code projectToMap} with a projection). {@code projection} is a slot, or -1 for none.
     */
    private void nestedToMap(CodeBuilder c, FieldDescriptor fd, int item, int projection) {
        nested(c, fd);
        c.ifThenElse(
                Opcode.IFNONNULL,
                then -> {
                    nested(then, fd);
                    then.aload(item);
                    if (projection >= 0) {
                        then.aload(projection).invokevirtual(CD_ADS, "toMap", MTD_MAP_DM_PROJ);
                    } else {
                        then.invokevirtual(CD_ADS, "toMap", MTD_MAP_DM);
                    }
                },
                otherwise -> {
                    otherwise.aload(item);
                    if (projection >= 0) {
                        otherwise.aload(projection).invokestatic(CD_CONVERTER, "projectToMap", MTD_MAP_DM_PROJ);
                    } else {
                        otherwise.invokestatic(CD_CONVERTER, "dynamicToMap", MTD_MAP_DM);
                    }
                });
    }

    // ── toMap(DynamicMessage msg, ProjectionNode projection) ──────────────────────────

    private void toMapProjected(CodeBuilder c) {
        final int msg = ARG0;
        final int projection = ARG1;
        Label project = c.newLabel();
        c.aload(projection).ifnull(project);
        c.aload(projection)
                .invokevirtual(CD_PROJ, "isIncludeAll", MethodTypeDesc.of(CD_boolean))
                .ifeq(project);
        c.aload(0).aload(msg).invokevirtual(CD_ADS, "toMap", MTD_MAP_DM).areturn();
        c.labelBinding(project);
        int map = newMap(c);
        for (FieldDescriptor fd : fields) {
            boolean composite = fd.isMapField() || (fd.getType() == FieldDescriptor.Type.MESSAGE && !isWktField(fd));
            if (!composite) {
                // Scalars, enums, bytes, repeated scalars and WKT-valued fields: always converted.
                toMapField(c, fd, msg, map);
                continue;
            }
            Label skip = c.newLabel();
            if (!fd.isRepeated() && !fd.isMapField()) {
                hasField(c, msg, fd);
                c.ifeq(skip);
            }
            int node = c.allocateLocal(TypeKind.REFERENCE);
            c.aload(projection)
                    .loadConstant(fd.getJsonName())
                    .invokevirtual(CD_PROJ, "lookup", MethodTypeDesc.of(CD_PROJ, CD_String))
                    .astore(node);
            c.aload(node).ifnull(skip);
            if (fd.isMapField()) {
                put(c, map, fd, cc -> {
                    field(cc, fd);
                    getField(cc, msg, fd);
                    cc.checkcast(CD_LIST)
                            .invokestatic(CD_CONVERTER, "mapFieldToJavaMap", MethodTypeDesc.of(CD_MAP, CD_FD, CD_LIST));
                });
            } else if (fd.isRepeated()) {
                int list = c.allocateLocal(TypeKind.REFERENCE);
                int result = c.allocateLocal(TypeKind.REFERENCE);
                getField(c, msg, fd);
                c.checkcast(CD_LIST).astore(list);
                c.new_(CD_ARRAYLIST)
                        .dup()
                        .aload(list)
                        .invokeinterface(CD_LIST, "size", MethodTypeDesc.of(CD_int))
                        .invokespecial(CD_ARRAYLIST, "<init>", MethodTypeDesc.of(CD_void, CD_int))
                        .astore(result);
                forEachInList(c, list, (cc, element) -> {
                    int item = cc.allocateLocal(TypeKind.REFERENCE);
                    element.accept(cc);
                    cc.checkcast(CD_DM).astore(item);
                    cc.aload(result);
                    nestedToMap(cc, fd, item, node);
                    cc.invokeinterface(CD_LIST, "add", MethodTypeDesc.of(CD_boolean, CD_Object))
                            .pop();
                });
                put(c, map, fd, cc -> cc.aload(result));
            } else {
                int value = c.allocateLocal(TypeKind.REFERENCE);
                getField(c, msg, fd);
                c.checkcast(CD_DM).astore(value);
                put(c, map, fd, cc -> nestedToMap(cc, fd, value, node));
            }
            c.labelBinding(skip);
        }
        c.aload(map).areturn();
    }

    // ── fromMap(Map map, DynamicMessage.Builder b) ────────────────────────────────────

    private void fromMap(CodeBuilder c) {
        final int map = ARG0;
        final int b = ARG1;
        if (strict) {
            c.aload(map)
                    .aload(b)
                    .invokestatic(CD_STRICT, "fill", MTD_DM_MAP_DMB)
                    .areturn();
            return;
        }
        for (FieldDescriptor fd : fields) {
            int v = c.allocateLocal(TypeKind.REFERENCE);
            c.aload(map)
                    .loadConstant(fd.getJsonName())
                    .invokeinterface(CD_MAP, "get", MethodTypeDesc.of(CD_Object, CD_Object))
                    .astore(v);
            fromMapField(c, fd, v, b);
        }
        c.aload(b).invokevirtual(CD_DMB, "build", MethodTypeDesc.of(CD_DM)).areturn();
    }

    private void fromMapField(CodeBuilder c, FieldDescriptor fd, int v, int b) {
        if (fd.isMapField()) {
            c.aload(v).instanceOf(CD_MAP).ifThen(Opcode.IFNE, then -> {
                field(then, fd);
                then.aload(v)
                        .checkcast(CD_MAP)
                        .aload(b)
                        .invokestatic(
                                CD_CONVERTER, "mapFieldFromMap", MethodTypeDesc.of(CD_void, CD_FD, CD_MAP, CD_DMB));
            });
            return;
        }
        if (fd.isRepeated()) {
            c.aload(v).instanceOf(CD_LIST).ifThen(Opcode.IFNE, then -> {
                int list = then.allocateLocal(TypeKind.REFERENCE);
                then.aload(v).checkcast(CD_LIST).astore(list);
                forEachInList(then, list, (cc, element) -> {
                    int elem = cc.allocateLocal(TypeKind.REFERENCE);
                    element.accept(cc);
                    cc.astore(elem);
                    cc.aload(elem).ifThen(Opcode.IFNONNULL, inner -> {
                        int converted = inner.allocateLocal(TypeKind.REFERENCE);
                        fromMapElement(inner, fd, elem);
                        inner.astore(converted);
                        inner.aload(converted).ifThen(Opcode.IFNONNULL, add -> {
                            add.aload(b);
                            field(add, fd);
                            add.aload(converted);
                            addRepeated(add);
                        });
                    });
                });
            });
            return;
        }
        if (isWktField(fd)) {
            c.aload(v).ifThen(Opcode.IFNONNULL, then -> {
                int w = then.allocateLocal(TypeKind.REFERENCE);
                then.aload(v);
                messageType(then, fd);
                then.invokestatic(CD_WKT, "fromJava", MethodTypeDesc.of(CD_DM, CD_Object, CD_DESC))
                        .astore(w);
                then.aload(w).ifThen(Opcode.IFNONNULL, set -> {
                    set.aload(b);
                    field(set, fd);
                    set.aload(w);
                    setField(set);
                });
            });
            return;
        }
        switch (fd.getType()) {
            case STRING ->
                c.aload(v).instanceOf(CD_String).ifThen(Opcode.IFNE, then -> {
                    then.aload(b);
                    field(then, fd);
                    then.aload(v);
                    setField(then);
                });
            case BYTES ->
                c.aload(v).instanceOf(CD_String).ifThen(Opcode.IFNE, then -> {
                    then.aload(b);
                    field(then, fd);
                    bytesFromString(then, cc -> cc.aload(v).checkcast(CD_String));
                    setField(then);
                });
            case BOOL -> coercedSet(c, fd, v, b, "coerceBool", CD_BOOLEAN, false);
            case INT32, SINT32, SFIXED32 -> coercedSet(c, fd, v, b, "coerceInt", CD_int, true);
            case UINT32, FIXED32 -> coercedSet(c, fd, v, b, "coerceUInt", CD_INTEGER, false);
            case INT64, SINT64, SFIXED64 -> coercedSet(c, fd, v, b, "coerceLong", CD_long, true);
            case UINT64, FIXED64 -> coercedSet(c, fd, v, b, "coerceULong", CD_LONG, false);
            case FLOAT -> coercedSet(c, fd, v, b, "coerceFloat", CD_FLOAT, false);
            case DOUBLE -> coercedSet(c, fd, v, b, "coerceDouble", CD_DOUBLE, false);
            case ENUM ->
                c.aload(v).ifThen(Opcode.IFNONNULL, then -> {
                    int e = then.allocateLocal(TypeKind.REFERENCE);
                    field(then, fd);
                    then.aload(v)
                            .invokestatic(CD_WKT, "enumValue", MethodTypeDesc.of(CD_EVD, CD_FD, CD_Object))
                            .astore(e);
                    then.aload(e).ifThen(Opcode.IFNONNULL, set -> {
                        set.aload(b);
                        field(set, fd);
                        set.aload(e);
                        setField(set);
                    });
                });
            case MESSAGE ->
                c.aload(v).instanceOf(CD_MAP).ifThen(Opcode.IFNE, then -> {
                    then.aload(b);
                    field(then, fd);
                    nestedFromMap(then, fd, cc -> cc.aload(v).checkcast(CD_MAP));
                    setField(then);
                });
            default -> {
                // skip
            }
        }
    }

    /**
     * A coercion with a primitive result ({@code primitive}): {@code if (v != null) set(box(coerce(v)))};
     * with a boxed, nullable result: {@code Boxed x = coerce(v); if (x != null) set(x)}.
     */
    private void coercedSet(
            CodeBuilder c, FieldDescriptor fd, int v, int b, String coercion, ClassDesc result, boolean primitive) {
        if (primitive) {
            c.aload(v).ifThen(Opcode.IFNONNULL, then -> {
                then.aload(b);
                field(then, fd);
                then.aload(v).invokestatic(CD_CONVERTER, coercion, MethodTypeDesc.of(result, CD_Object));
                box(then, result);
                setField(then);
            });
            return;
        }
        int x = c.allocateLocal(TypeKind.REFERENCE);
        c.aload(v)
                .invokestatic(CD_CONVERTER, coercion, MethodTypeDesc.of(result, CD_Object))
                .astore(x);
        c.aload(x).ifThen(Opcode.IFNONNULL, then -> {
            then.aload(b);
            field(then, fd);
            then.aload(x);
            setField(then);
        });
    }

    /** One element of a repeated field from its Java value ({@code elem} slot): the converted value, or null. */
    private void fromMapElement(CodeBuilder c, FieldDescriptor fd, int elem) {
        switch (fd.getType()) {
            case MESSAGE -> {
                if (isWktField(fd)) {
                    c.aload(elem);
                    messageType(c, fd);
                    c.invokestatic(CD_WKT, "fromJava", MethodTypeDesc.of(CD_DM, CD_Object, CD_DESC));
                } else {
                    c.aload(elem).instanceOf(CD_MAP);
                    c.ifThenElse(
                            Opcode.IFNE,
                            then -> nestedFromMap(then, fd, cc -> cc.aload(elem).checkcast(CD_MAP)),
                            otherwise -> otherwise.aconst_null());
                }
            }
            case ENUM -> {
                field(c, fd);
                c.aload(elem).invokestatic(CD_WKT, "enumValue", MethodTypeDesc.of(CD_EVD, CD_FD, CD_Object));
            }
            case STRING -> {
                c.aload(elem).instanceOf(CD_String);
                c.ifThenElse(Opcode.IFNE, then -> then.aload(elem), otherwise -> otherwise.aconst_null());
            }
            case BYTES -> {
                c.aload(elem).instanceOf(CD_String);
                c.ifThenElse(
                        Opcode.IFNE,
                        then -> bytesFromString(then, cc -> cc.aload(elem).checkcast(CD_String)),
                        otherwise -> otherwise.aconst_null());
            }
            case INT32, SINT32, SFIXED32 -> {
                c.aload(elem).invokestatic(CD_CONVERTER, "coerceInt", MethodTypeDesc.of(CD_int, CD_Object));
                box(c, CD_int);
            }
            case UINT32, FIXED32 ->
                c.aload(elem).invokestatic(CD_CONVERTER, "coerceUInt", MethodTypeDesc.of(CD_INTEGER, CD_Object));
            case INT64, SINT64, SFIXED64 -> {
                c.aload(elem).invokestatic(CD_CONVERTER, "coerceLong", MethodTypeDesc.of(CD_long, CD_Object));
                box(c, CD_long);
            }
            case UINT64, FIXED64 ->
                c.aload(elem).invokestatic(CD_CONVERTER, "coerceULong", MethodTypeDesc.of(CD_LONG, CD_Object));
            case FLOAT ->
                c.aload(elem).invokestatic(CD_CONVERTER, "coerceFloat", MethodTypeDesc.of(CD_FLOAT, CD_Object));
            case DOUBLE ->
                c.aload(elem).invokestatic(CD_CONVERTER, "coerceDouble", MethodTypeDesc.of(CD_DOUBLE, CD_Object));
            case BOOL ->
                c.aload(elem).invokestatic(CD_CONVERTER, "coerceBool", MethodTypeDesc.of(CD_BOOLEAN, CD_Object));
            default -> c.aconst_null();
        }
    }

    /** {@code n != null ? n.fromMap(map, newBuilder(type)) : ProtoMapConverter.dynamicFromMap(map, type)}; {@code map} pushes the Map. */
    private void nestedFromMap(CodeBuilder c, FieldDescriptor fd, Consumer<CodeBuilder> map) {
        nested(c, fd);
        c.ifThenElse(
                Opcode.IFNONNULL,
                then -> {
                    nested(then, fd);
                    map.accept(then);
                    newBuilder(then, fd);
                    then.invokevirtual(CD_ADS, "fromMap", MTD_DM_MAP_DMB);
                },
                otherwise -> {
                    map.accept(otherwise);
                    messageType(otherwise, fd);
                    otherwise.invokestatic(CD_CONVERTER, "dynamicFromMap", MTD_DM_MAP_DESC);
                });
    }

    // ── Bytecode helpers ──────────────────────────────────────────────────────────────

    private boolean isNested(FieldDescriptor fd) {
        return fd.getType() == FieldDescriptor.Type.MESSAGE && !fd.isMapField() && !isWktField(fd);
    }

    private static boolean isWktField(FieldDescriptor fd) {
        return fd.getType() == FieldDescriptor.Type.MESSAGE && ProtoWkt.isWkt(fd.getMessageType());
    }

    private static String fdField(FieldDescriptor fd) {
        return "f" + fd.getNumber();
    }

    private static String nestedField(FieldDescriptor fd) {
        return "n" + fd.getNumber();
    }

    /** Pushes the field's {@code FieldDescriptor}. */
    private void field(CodeBuilder c, FieldDescriptor fd) {
        c.aload(0).getfield(self, fdField(fd), CD_FD);
    }

    /** Pushes the nested serializer slot (possibly null). */
    private void nested(CodeBuilder c, FieldDescriptor fd) {
        c.aload(0).getfield(self, nestedField(fd), CD_ADS);
    }

    /** Pushes {@code msg.getField(f)} as an Object. */
    private void getField(CodeBuilder c, int msg, FieldDescriptor fd) {
        c.aload(msg);
        field(c, fd);
        c.invokevirtual(CD_DM, "getField", MTD_OBJECT_FD);
    }

    /** Pushes {@code msg.hasField(f)}. */
    private void hasField(CodeBuilder c, int msg, FieldDescriptor fd) {
        c.aload(msg);
        field(c, fd);
        c.invokevirtual(CD_DM, "hasField", MTD_BOOLEAN_FD);
    }

    /** Pushes {@code f.getMessageType()}. */
    private void messageType(CodeBuilder c, FieldDescriptor fd) {
        field(c, fd);
        c.invokevirtual(CD_FD, "getMessageType", MethodTypeDesc.of(CD_DESC));
    }

    /** Pushes {@code DynamicMessage.newBuilder(f.getMessageType())}. */
    private void newBuilder(CodeBuilder c, FieldDescriptor fd) {
        messageType(c, fd);
        c.invokestatic(CD_DM, "newBuilder", MethodTypeDesc.of(CD_DMB, CD_DESC));
    }

    /** Pushes {@code JsonToken.<name>}. */
    private static void token(CodeBuilder c, String name) {
        c.getstatic(CD_TOKEN, name, CD_TOKEN);
    }

    private static void currentToken(CodeBuilder c, int p) {
        c.aload(p).invokevirtual(CD_PARSER, "currentToken", MTD_TOKEN);
    }

    private static void nextToken(CodeBuilder c, int p) {
        c.aload(p).invokevirtual(CD_PARSER, "nextToken", MTD_TOKEN).pop();
    }

    /** {@code readTree(p)}: the parser is on the stack. */
    private static void readTree(CodeBuilder c) {
        c.invokestatic(CD_ADS, "readTree", MTD_OBJECT_PARSER);
    }

    /** {@code readObjectTree(p)}: the parser is on the stack. */
    private static void readObjectTree(CodeBuilder c) {
        c.invokestatic(CD_ADS, "readObjectTree", MTD_MAP_PARSER);
    }

    /** {@code if (p.currentToken() == JsonToken.<name>) { body }}. */
    private static void ifToken(CodeBuilder c, int p, String name, Consumer<CodeBuilder> body) {
        currentToken(c, p);
        token(c, name);
        c.ifThen(Opcode.IF_ACMPEQ, body::accept);
    }

    /** {@code while (p.currentToken() != JsonToken.<name>) { body; p.nextToken(); }}. */
    private static void whileTokenNot(CodeBuilder c, int p, String name, Consumer<CodeBuilder> body) {
        Label loop = c.newBoundLabel();
        Label end = c.newLabel();
        currentToken(c, p);
        token(c, name);
        c.if_acmpeq(end);
        body.accept(c);
        nextToken(c, p);
        c.goto_(loop);
        c.labelBinding(end);
    }

    /** A body run for each element of a {@code List} in a slot; {@code element} pushes the element. */
    private interface ElementBody {
        void accept(CodeBuilder c, Consumer<CodeBuilder> element);
    }

    private static void forEachInList(CodeBuilder c, int list, ElementBody body) {
        int size = c.allocateLocal(TypeKind.INT);
        int i = c.allocateLocal(TypeKind.INT);
        c.aload(list)
                .invokeinterface(CD_LIST, "size", MethodTypeDesc.of(CD_int))
                .istore(size);
        c.iconst_0().istore(i);
        Label loop = c.newBoundLabel();
        Label end = c.newLabel();
        c.iload(i).iload(size).if_icmpge(end);
        body.accept(
                c, cc -> cc.aload(list).iload(i).invokeinterface(CD_LIST, "get", MethodTypeDesc.of(CD_Object, CD_int)));
        c.iinc(i, 1).goto_(loop);
        c.labelBinding(end);
    }

    /** {@code b.setField(f, value)} with builder, field and value on the stack. */
    private static void setField(CodeBuilder c) {
        c.invokevirtual(CD_DMB, "setField", MTD_SET).pop();
    }

    /** {@code b.addRepeatedField(f, value)} with builder, field and value on the stack. */
    private static void addRepeated(CodeBuilder c) {
        c.invokevirtual(CD_DMB, "addRepeatedField", MTD_SET).pop();
    }

    private static void put(CodeBuilder c, boolean repeated) {
        if (repeated) {
            addRepeated(c);
        } else {
            setField(c);
        }
    }

    /** {@code ByteString.copyFrom(Base64.getDecoder().decode(text))}; {@code text} pushes the String. */
    private static void bytesFromString(CodeBuilder c, Consumer<CodeBuilder> text) {
        c.invokestatic(CD_BASE64, "getDecoder", MethodTypeDesc.of(CD_DECODER));
        text.accept(c);
        c.invokevirtual(CD_DECODER, "decode", MethodTypeDesc.of(CD_BYTES, CD_String))
                .invokestatic(CD_BYTESTRING, "copyFrom", MethodTypeDesc.of(CD_BYTESTRING, CD_BYTES));
    }

    private static CodeBuilder unboxInt(CodeBuilder c) {
        return c.checkcast(CD_INTEGER).invokevirtual(CD_INTEGER, "intValue", MethodTypeDesc.of(CD_int));
    }

    private static CodeBuilder unboxLong(CodeBuilder c) {
        return c.checkcast(CD_LONG).invokevirtual(CD_LONG, "longValue", MethodTypeDesc.of(CD_long));
    }

    private static void box(CodeBuilder c, ClassDesc primitive) {
        if (primitive.equals(CD_int)) {
            c.invokestatic(CD_INTEGER, "valueOf", MethodTypeDesc.of(CD_INTEGER, CD_int));
        } else if (primitive.equals(CD_long)) {
            c.invokestatic(CD_LONG, "valueOf", MethodTypeDesc.of(CD_LONG, CD_long));
        } else {
            throw new IllegalArgumentException("No boxing for " + primitive);
        }
    }

    private static ClassDesc cd(Class<?> type) {
        return ClassDesc.ofDescriptor(type.descriptorString());
    }
}
