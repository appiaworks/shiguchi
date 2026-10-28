package dev.appiaworks.shiguchi.grpc;

import dev.appiaworks.protobuf.extras.api.ApiStatus;
import io.grpc.BindableService;

/**
 * A gRPC service the gateway itself implements and serves on its native gRPC port, beside the
 * pass-through proxy: a bean of this type is added to the proxy server as a service of its own,
 * so calls to it are answered in place rather than routed through a channel. The push module's
 * {@code PushService} is one. A plain {@link BindableService} bean is deliberately not picked up:
 * applications declare those for other reasons (STATIC-mode stub discovery, for one).
 */
@ApiStatus(value = ApiStatus.Status.EXPERIMENTAL, since = "0.12.0")
public interface GatewayHostedService extends BindableService {}
