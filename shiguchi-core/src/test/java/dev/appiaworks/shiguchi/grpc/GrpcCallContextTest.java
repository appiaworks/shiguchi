package dev.appiaworks.shiguchi.grpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ForwardingClientCall;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * BDD-style tests for GrpcCallContext.
 *
 * Tests verify that:
 * - METADATA_KEY can store per-call metadata in CallOptions
 * - INTERCEPTOR extracts metadata and conditionally wraps calls in ForwardingClientCall
 * - Metadata is properly merged into outbound headers without overwriting existing keys
 * - Null or empty metadata is handled without unnecessary wrapping
 */
class GrpcCallContextTest {

    private Channel mockChannel;
    private MethodDescriptor<Object, Object> mockMethod;
    private ClientCall<Object, Object> mockCall;

    // ── METADATA_KEY: storage and retrieval ──

    @Test
    @DisplayName("Given METADATA_KEY, when withOption is called, should store metadata in CallOptions")
    void given_metadataKey_when_withOption_should_storeMetadata() {
        // Arrange
        Metadata metadata = new Metadata();
        Metadata.Key<String> key = Metadata.Key.of("x-user-id", Metadata.ASCII_STRING_MARSHALLER);
        metadata.put(key, "user-123");

        // Act
        CallOptions opts = CallOptions.DEFAULT.withOption(GrpcCallContext.METADATA_KEY, metadata);

        // Assert
        assertThat(opts.getOption(GrpcCallContext.METADATA_KEY)).isNotNull();
        assertThat(opts.getOption(GrpcCallContext.METADATA_KEY)).isSameAs(metadata);
    }

    @Test
    @DisplayName("Given METADATA_KEY with null default, when getOption on DEFAULT CallOptions, should return null")
    void given_metadataKeyWithNullDefault_when_getOption_should_returnNull() {
        Metadata metadata = CallOptions.DEFAULT.getOption(GrpcCallContext.METADATA_KEY);

        assertThat(metadata).isNull();
    }

    // ── INTERCEPTOR: null metadata handling ──

    @Test
    @DisplayName(
            "Given null metadata in CallOptions, when INTERCEPTOR.interceptCall, should return original call unwrapped")
    void given_nullMetadata_when_interceptCall_should_returnOriginalCallUnwrapped() {
        // Arrange
        mockChannel = mock(Channel.class);
        mockMethod = mock(MethodDescriptor.class);
        mockCall = mock(ClientCall.class);
        when(mockChannel.newCall(mockMethod, CallOptions.DEFAULT)).thenReturn(mockCall);
        CallOptions opts = CallOptions.DEFAULT; // null metadata by default

        // Act
        ClientCall<Object, Object> result = GrpcCallContext.INTERCEPTOR.interceptCall(mockMethod, opts, mockChannel);

        // Assert
        assertThat(result).isSameAs(mockCall);
        assertThat(result).isNotInstanceOf(ForwardingClientCall.class);
    }

    // ── INTERCEPTOR: empty metadata handling ──

    @Test
    @DisplayName(
            "Given empty Metadata (no keys) in CallOptions, when INTERCEPTOR.interceptCall, should return original call unwrapped")
    void given_emptyMetadata_when_interceptCall_should_returnOriginalCallUnwrapped() {
        // Arrange
        mockChannel = mock(Channel.class);
        mockMethod = mock(MethodDescriptor.class);
        mockCall = mock(ClientCall.class);

        Metadata emptyMetadata = new Metadata();
        CallOptions opts = CallOptions.DEFAULT.withOption(GrpcCallContext.METADATA_KEY, emptyMetadata);

        when(mockChannel.newCall(mockMethod, opts)).thenReturn(mockCall);

        // Act
        ClientCall<Object, Object> result = GrpcCallContext.INTERCEPTOR.interceptCall(mockMethod, opts, mockChannel);

        // Assert: verify channel.newCall was called (proving interceptor passed through)
        verify(mockChannel).newCall(mockMethod, opts);
        // And that we got back the original call without wrapping
        assertThat(result).isSameAs(mockCall);
    }

    // ── INTERCEPTOR: non-empty metadata wrapping ──

    @Test
    @DisplayName(
            "Given non-empty Metadata in CallOptions, when INTERCEPTOR.interceptCall, should return ForwardingClientCall")
    void given_nonEmptyMetadata_when_interceptCall_should_returnForwardingClientCall() {
        // Arrange
        mockChannel = mock(Channel.class);
        mockMethod = mock(MethodDescriptor.class);
        mockCall = mock(ClientCall.class);

        Metadata metadata = new Metadata();
        Metadata.Key<String> key = Metadata.Key.of("x-request-id", Metadata.ASCII_STRING_MARSHALLER);
        metadata.put(key, "req-999");
        CallOptions opts = CallOptions.DEFAULT.withOption(GrpcCallContext.METADATA_KEY, metadata);

        when(mockChannel.newCall(mockMethod, opts)).thenReturn(mockCall);

        // Act
        ClientCall<Object, Object> result = GrpcCallContext.INTERCEPTOR.interceptCall(mockMethod, opts, mockChannel);

        // Assert
        assertThat(result).isInstanceOf(ForwardingClientCall.class);
    }

    // ── ForwardingClientCall.start(): single key merge ──

    @Test
    @DisplayName(
            "Given ForwardingClientCall with one metadata key, when start is called, should merge key into headers")
    void given_forwardingCallWithOneKey_when_start_should_mergeKeyIntoHeaders() {
        // Arrange
        mockChannel = mock(Channel.class);
        mockMethod = mock(MethodDescriptor.class);
        mockCall = mock(ClientCall.class);

        Metadata metadata = new Metadata();
        Metadata.Key<String> extraKey = Metadata.Key.of("x-user-id", Metadata.ASCII_STRING_MARSHALLER);
        metadata.put(extraKey, "user-456");
        CallOptions opts = CallOptions.DEFAULT.withOption(GrpcCallContext.METADATA_KEY, metadata);

        when(mockChannel.newCall(mockMethod, opts)).thenReturn(mockCall);

        ClientCall<Object, Object> wrappedCall =
                GrpcCallContext.INTERCEPTOR.interceptCall(mockMethod, opts, mockChannel);

        // Verify we got a wrapper
        assertThat(wrappedCall).isNotSameAs(mockCall);

        ClientCall.Listener<Object> listener = mock(ClientCall.Listener.class);
        Metadata headers = new Metadata();

        // Act - calling start should trigger the merge
        wrappedCall.start(listener, headers);

        // Assert - the headers object passed to the delegate should now contain the merged metadata
        verify(mockCall).start(listener, headers);
        assertThat(headers.get(extraKey)).isEqualTo("user-456");
    }

    // ── ForwardingClientCall.start(): multiple keys merge ──

    @Test
    @DisplayName(
            "Given ForwardingClientCall with two metadata keys, when start is called, should merge both keys into headers")
    void given_forwardingCallWithTwoKeys_when_start_should_mergeBothKeysIntoHeaders() {
        // Arrange
        mockChannel = mock(Channel.class);
        mockMethod = mock(MethodDescriptor.class);
        mockCall = mock(ClientCall.class);

        Metadata metadata = new Metadata();
        Metadata.Key<String> key1 = Metadata.Key.of("x-user-id", Metadata.ASCII_STRING_MARSHALLER);
        Metadata.Key<String> key2 = Metadata.Key.of("x-tenant-id", Metadata.ASCII_STRING_MARSHALLER);
        metadata.put(key1, "user-789");
        metadata.put(key2, "tenant-001");
        CallOptions opts = CallOptions.DEFAULT.withOption(GrpcCallContext.METADATA_KEY, metadata);

        when(mockChannel.newCall(mockMethod, opts)).thenReturn(mockCall);

        ClientCall<Object, Object> wrappedCall =
                GrpcCallContext.INTERCEPTOR.interceptCall(mockMethod, opts, mockChannel);

        ClientCall.Listener<Object> listener = mock(ClientCall.Listener.class);
        Metadata headers = new Metadata();

        // Act
        wrappedCall.start(listener, headers);

        // Assert
        verify(mockCall).start(listener, headers);
        assertThat(headers.get(key1)).isEqualTo("user-789");
        assertThat(headers.get(key2)).isEqualTo("tenant-001");
    }

    // ── ForwardingClientCall.start(): preserve existing headers ──

    @Test
    @DisplayName(
            "Given headers with existing key, when merged with extra metadata, should not overwrite existing header")
    void given_headersWithExistingKey_when_mergeExtra_should_notOverwriteExisting() {
        // Arrange
        mockChannel = mock(Channel.class);
        mockMethod = mock(MethodDescriptor.class);
        mockCall = mock(ClientCall.class);

        Metadata metadata = new Metadata();
        Metadata.Key<String> extraKey = Metadata.Key.of("x-override", Metadata.ASCII_STRING_MARSHALLER);
        metadata.put(extraKey, "new-value");
        CallOptions opts = CallOptions.DEFAULT.withOption(GrpcCallContext.METADATA_KEY, metadata);

        when(mockChannel.newCall(mockMethod, opts)).thenReturn(mockCall);

        ClientCall<Object, Object> wrappedCall =
                GrpcCallContext.INTERCEPTOR.interceptCall(mockMethod, opts, mockChannel);

        Metadata headers = new Metadata();
        Metadata.Key<String> existingKey = Metadata.Key.of("x-existing", Metadata.ASCII_STRING_MARSHALLER);
        headers.put(existingKey, "old-value");

        ClientCall.Listener<Object> listener = mock(ClientCall.Listener.class);

        // Act
        wrappedCall.start(listener, headers);

        // Assert - metadata.merge() in gRPC combines metadata, both keys should be present
        verify(mockCall).start(listener, headers);
        assertThat(headers.get(existingKey)).isEqualTo("old-value");
        assertThat(headers.get(extraKey)).isEqualTo("new-value");
    }

    // ── INTERCEPTOR: listener forwarding ──

    @Test
    @DisplayName("Given ForwardingClientCall, when start is called, should forward the listener to delegate")
    void given_forwardingCall_when_start_should_forwardListenerToDelegate() {
        // Arrange
        mockChannel = mock(Channel.class);
        mockMethod = mock(MethodDescriptor.class);
        mockCall = mock(ClientCall.class);

        Metadata metadata = new Metadata();
        Metadata.Key<String> key = Metadata.Key.of("x-id", Metadata.ASCII_STRING_MARSHALLER);
        metadata.put(key, "id-123");
        CallOptions opts = CallOptions.DEFAULT.withOption(GrpcCallContext.METADATA_KEY, metadata);

        when(mockChannel.newCall(mockMethod, opts)).thenReturn(mockCall);

        ClientCall<Object, Object> wrappedCall =
                GrpcCallContext.INTERCEPTOR.interceptCall(mockMethod, opts, mockChannel);

        ClientCall.Listener<Object> listener = mock(ClientCall.Listener.class);
        Metadata headers = new Metadata();

        // Act
        wrappedCall.start(listener, headers);

        // Assert - the listener should be passed through unchanged
        verify(mockCall).start(listener, headers);
    }

    // ── INTERCEPTOR singleton instance ──

    @Test
    @DisplayName("Given INTERCEPTOR, when accessed multiple times, should return the same singleton instance")
    void given_interceptor_when_accessedMultipleTimes_should_returnSameSingletonInstance() {
        // Act
        ClientInterceptor first = GrpcCallContext.INTERCEPTOR;
        ClientInterceptor second = GrpcCallContext.INTERCEPTOR;

        // Assert
        assertThat(first).isSameAs(second);
    }
}
