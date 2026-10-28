package dev.appiaworks.shiguchi.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.protobuf.Message;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCall.Listener;
import io.grpc.ServerCallHandler;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * BDD-style tests for ValidationServerInterceptor.
 *
 * Tests verify that:
 * - interceptCall returns a wrapped listener that validates incoming messages
 * - Valid messages pass through to the delegate listener's onMessage
 * - Invalid messages close the call with INVALID_ARGUMENT without calling delegate onMessage
 * - Non-proto messages are passed through without validation
 * - The interceptor properly delegates lifecycle methods (onReady, onHalfClose)
 * - Metadata and headers are handled correctly
 */
class ValidationServerInterceptorTest {

    private ProtoValidator mockValidator;
    private ValidationServerInterceptor interceptor;

    @BeforeEach
    void setUp() {
        mockValidator = mock(ProtoValidator.class);
        interceptor = new ValidationServerInterceptor(mockValidator);
    }

    // ── interceptCall: listener wrapping and delegation ──

    @Test
    @DisplayName(
            "Given interceptCall is invoked with call and headers, when handler is called, should return wrapped listener")
    void given_interceptCallInvoked_when_handlerStartsCalled_should_returnWrappedListener() {
        // Arrange
        ServerCall<String, String> mockCall = mock(ServerCall.class);
        ServerCallHandler<String, String> mockHandler = mock(ServerCallHandler.class);
        Listener<String> mockDelegate = mock(Listener.class);
        Metadata headers = new Metadata();

        when(mockHandler.startCall(mockCall, headers)).thenReturn(mockDelegate);

        // Act
        Listener<String> result = interceptor.interceptCall(mockCall, headers, mockHandler);

        // Assert
        assertThat(result).isNotNull();
        assertThat(result).isNotSameAs(mockDelegate); // Should be wrapped
        verify(mockHandler).startCall(mockCall, headers);
    }

    // ── interceptCall: valid message delegation ──

    @Test
    @DisplayName(
            "Given valid proto message without validation errors, when onMessage is called, should delegate to listener")
    void given_validProtoMessage_when_onMessage_should_delegateToListener() {
        // Arrange
        ServerCall<Message, String> mockCall = mock(ServerCall.class);
        ServerCallHandler<Message, String> mockHandler = mock(ServerCallHandler.class);
        Listener<Message> mockDelegate = mock(Listener.class);
        Metadata headers = new Metadata();

        Message validMessage = com.google.protobuf.StringValue.of("valid");
        when(mockHandler.startCall(mockCall, headers)).thenReturn(mockDelegate);
        doNothing().when(mockValidator).validate(validMessage);

        Listener<Message> wrappedListener = interceptor.interceptCall(mockCall, headers, mockHandler);

        // Act
        wrappedListener.onMessage(validMessage);

        // Assert
        verify(mockValidator).validate(validMessage);
        verify(mockDelegate).onMessage(validMessage);
    }

    // ── interceptCall: invalid message rejection ──

    @Test
    @DisplayName(
            "Given proto message with constraint violation, when onMessage is called, should close call with INVALID_ARGUMENT")
    void given_messageWithConstraintViolation_when_onMessage_should_closeCallWithInvalidArgument() {
        // Arrange
        ServerCall<Message, String> mockCall = mock(ServerCall.class);
        ServerCallHandler<Message, String> mockHandler = mock(ServerCallHandler.class);
        Listener<Message> mockDelegate = mock(Listener.class);
        Metadata headers = new Metadata();

        Message invalidMessage = com.google.protobuf.StringValue.of("invalid");
        StatusRuntimeException validationError = Status.INVALID_ARGUMENT
                .withDescription("Field 'name': length must be >= 1")
                .asRuntimeException();

        when(mockHandler.startCall(mockCall, headers)).thenReturn(mockDelegate);
        doThrow(validationError).when(mockValidator).validate(invalidMessage);

        Listener<Message> wrappedListener = interceptor.interceptCall(mockCall, headers, mockHandler);

        // Act
        wrappedListener.onMessage(invalidMessage);

        // Assert
        verify(mockCall).close(any(Status.class), any(Metadata.class));
    }

    // ── interceptCall: invalid message does not call delegate onMessage ──

    @Test
    @DisplayName("Given message with validation error, when onMessage is called, should not call delegate.onMessage")
    void given_validationError_when_onMessage_should_notDelegateOnMessage() {
        // Arrange
        ServerCall<Message, String> mockCall = mock(ServerCall.class);
        ServerCallHandler<Message, String> mockHandler = mock(ServerCallHandler.class);
        Listener<Message> mockDelegate = mock(Listener.class);
        Metadata headers = new Metadata();

        Message invalidMessage = com.google.protobuf.StringValue.of("invalid");
        StatusRuntimeException validationError =
                Status.INVALID_ARGUMENT.withDescription("Constraint violation").asRuntimeException();

        when(mockHandler.startCall(mockCall, headers)).thenReturn(mockDelegate);
        doThrow(validationError).when(mockValidator).validate(invalidMessage);

        Listener<Message> wrappedListener = interceptor.interceptCall(mockCall, headers, mockHandler);

        // Act
        wrappedListener.onMessage(invalidMessage);

        // Assert
        verify(mockDelegate, never()).onMessage(invalidMessage);
    }

    // ── interceptCall: non-proto message bypass ──

    @Test
    @DisplayName("Given non-proto message (plain String), when onMessage is called, should not invoke validator")
    void given_nonProtoMessage_when_onMessage_should_skipValidation() {
        // Arrange
        ServerCall<String, String> mockCall = mock(ServerCall.class);
        ServerCallHandler<String, String> mockHandler = mock(ServerCallHandler.class);
        Listener<String> mockDelegate = mock(Listener.class);
        Metadata headers = new Metadata();

        String nonProtoMessage = "just a string";
        when(mockHandler.startCall(mockCall, headers)).thenReturn(mockDelegate);

        Listener<String> wrappedListener = interceptor.interceptCall(mockCall, headers, mockHandler);

        // Act
        wrappedListener.onMessage(nonProtoMessage);

        // Assert
        verify(mockValidator, never()).validate(any());
        verify(mockDelegate).onMessage(nonProtoMessage);
    }

    // ── interceptCall: onHalfClose delegation after valid message ──

    @Test
    @DisplayName("Given valid message processed, when onHalfClose is called, should delegate to listener")
    void given_validMessageProcessed_when_onHalfClose_should_delegateToListener() {
        // Arrange
        ServerCall<Message, String> mockCall = mock(ServerCall.class);
        ServerCallHandler<Message, String> mockHandler = mock(ServerCallHandler.class);
        Listener<Message> mockDelegate = mock(Listener.class);
        Metadata headers = new Metadata();

        Message validMessage = com.google.protobuf.StringValue.of("valid");
        when(mockHandler.startCall(mockCall, headers)).thenReturn(mockDelegate);
        doNothing().when(mockValidator).validate(validMessage);

        Listener<Message> wrappedListener = interceptor.interceptCall(mockCall, headers, mockHandler);

        // Act
        wrappedListener.onMessage(validMessage);
        wrappedListener.onHalfClose();

        // Assert
        verify(mockDelegate).onMessage(validMessage);
        verify(mockDelegate).onHalfClose();
    }

    // ── interceptCall: handler receives correct metadata ──

    @Test
    @DisplayName("Given interceptCall with populated Metadata, when handler is called, should pass metadata unchanged")
    void given_handlerWithMetadata_when_interceptCalled_should_passMetadataCorrectly() {
        // Arrange
        ServerCall<String, String> mockCall = mock(ServerCall.class);
        ServerCallHandler<String, String> mockHandler = mock(ServerCallHandler.class);
        Listener<String> mockDelegate = mock(Listener.class);

        Metadata headers = new Metadata();
        Metadata.Key<String> testKey = Metadata.Key.of("test-key", Metadata.ASCII_STRING_MARSHALLER);
        headers.put(testKey, "test-value");

        when(mockHandler.startCall(mockCall, headers)).thenReturn(mockDelegate);

        // Act
        Listener<String> result = interceptor.interceptCall(mockCall, headers, mockHandler);

        // Assert
        assertThat(result).isNotNull();
        verify(mockHandler).startCall(mockCall, headers);
    }

    // ── interceptCall: multiple valid messages ──

    @Test
    @DisplayName("Given multiple valid messages, when onMessage called repeatedly, should delegate all messages")
    void given_multipleValidMessages_when_onMessageCalledRepeatedly_should_delegateAllMessages() {
        // Arrange
        ServerCall<Message, String> mockCall = mock(ServerCall.class);
        ServerCallHandler<Message, String> mockHandler = mock(ServerCallHandler.class);
        Listener<Message> mockDelegate = mock(Listener.class);
        Metadata headers = new Metadata();

        Message msg1 = com.google.protobuf.StringValue.of("msg1");
        Message msg2 = com.google.protobuf.StringValue.of("msg2");

        when(mockHandler.startCall(mockCall, headers)).thenReturn(mockDelegate);
        doNothing().when(mockValidator).validate(any(Message.class));

        Listener<Message> wrappedListener = interceptor.interceptCall(mockCall, headers, mockHandler);

        // Act
        wrappedListener.onMessage(msg1);
        wrappedListener.onMessage(msg2);

        // Assert
        verify(mockValidator).validate(msg1);
        verify(mockValidator).validate(msg2);
        verify(mockDelegate).onMessage(msg1);
        verify(mockDelegate).onMessage(msg2);
    }

    // ── interceptCall: first message fails, call closed ──

    @Test
    @DisplayName("Given first message violates constraint, when validation fails, should close call immediately")
    void given_firstMessageInvalid_when_validationFails_should_closeCallImmediately() {
        // Arrange
        ServerCall<Message, String> mockCall = mock(ServerCall.class);
        ServerCallHandler<Message, String> mockHandler = mock(ServerCallHandler.class);
        Listener<Message> mockDelegate = mock(Listener.class);
        Metadata headers = new Metadata();

        Message invalidMsg = com.google.protobuf.StringValue.of("invalid");

        StatusRuntimeException error =
                Status.INVALID_ARGUMENT.withDescription("Constraint violation").asRuntimeException();

        when(mockHandler.startCall(mockCall, headers)).thenReturn(mockDelegate);
        doThrow(error).when(mockValidator).validate(invalidMsg);

        Listener<Message> wrappedListener = interceptor.interceptCall(mockCall, headers, mockHandler);

        // Act
        wrappedListener.onMessage(invalidMsg);

        // Assert
        verify(mockCall).close(any(Status.class), any(Metadata.class));
        verify(mockDelegate, never()).onMessage(invalidMsg);
    }

    // ── interceptCall: onReady delegation ──

    @Test
    @DisplayName("Given wrapped listener, when onReady is called, should delegate to underlying listener")
    void given_wrappedListener_when_onReady_should_delegateToUnderlyingListener() {
        // Arrange
        ServerCall<String, String> mockCall = mock(ServerCall.class);
        ServerCallHandler<String, String> mockHandler = mock(ServerCallHandler.class);
        Listener<String> mockDelegate = mock(Listener.class);
        Metadata headers = new Metadata();

        when(mockHandler.startCall(mockCall, headers)).thenReturn(mockDelegate);

        Listener<String> wrappedListener = interceptor.interceptCall(mockCall, headers, mockHandler);

        // Act
        wrappedListener.onReady();

        // Assert
        verify(mockDelegate).onReady();
    }

    // ── interceptCall: validation happens before delegation ──

    @Test
    @DisplayName(
            "Given valid message, when onMessage is called, validation should happen before delegate receives message")
    void given_validMessage_when_onMessage_should_validateBeforeDelegating() {
        // Arrange
        ServerCall<Message, String> mockCall = mock(ServerCall.class);
        ServerCallHandler<Message, String> mockHandler = mock(ServerCallHandler.class);
        Listener<Message> mockDelegate = mock(Listener.class);
        Metadata headers = new Metadata();

        Message validMessage = com.google.protobuf.StringValue.of("valid");
        when(mockHandler.startCall(mockCall, headers)).thenReturn(mockDelegate);
        doNothing().when(mockValidator).validate(validMessage);

        Listener<Message> wrappedListener = interceptor.interceptCall(mockCall, headers, mockHandler);

        // Act
        wrappedListener.onMessage(validMessage);

        // Assert - inOrder verify that validate is called before onMessage
        inOrder(mockValidator, mockDelegate).verify(mockValidator).validate(validMessage);
        inOrder(mockValidator, mockDelegate).verify(mockDelegate).onMessage(validMessage);
    }
}
