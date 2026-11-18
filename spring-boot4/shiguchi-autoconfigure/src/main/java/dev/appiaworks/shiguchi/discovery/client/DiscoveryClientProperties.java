package dev.appiaworks.shiguchi.discovery.client;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings of {@link GrpcPortRegistrationAutoConfiguration}, which runs in a <em>backend</em> app
 * and publishes its gRPC port in its own registry registration, so the gateway's
 * {@code discovery:///} name resolver can dial it. Nothing here is read by the gateway.
 */
@ConfigurationProperties("shiguchi.discovery.client")
public class DiscoveryClientProperties {

    /** Master switch. Off by default: nothing is published and the auto-configuration does not apply. */
    private boolean exposeGrpcPort = false;

    /** The gRPC port to publish. Unset means detect it (a {@link GrpcServerPortSupplier} bean, then an {@code io.grpc.Server} bean). */
    private Integer grpcPort;

    /** The metadata key the port is published under. The gateway reads {@code grpc-port} first. */
    private String metadataKey = "grpc-port";

    /** Also publish {@code <prefix>type=grpc} (default prefix {@code shiguchi.}), so the gateway's auto-registration treats the instance as gRPC. */
    private boolean typeMetadata = true;

    /** Publish the port after startup when it is only known then (a server on port 0), by updating the live registration. */
    private boolean updateOnBind = true;

    /** The {@code shiguchi.type} key prefix used for {@link #typeMetadata}; match the gateway's {@code metadata-prefix}. */
    private String metadataPrefix = "shiguchi.";

    /**
     * Keep the instance {@code STARTING} (out of rotation) until the gRPC port is published, when the port
     * is not known at registration time (a server on port 0). Needs {@link #updateOnBind}. Without it the
     * instance registers {@code UP} before {@code grpc-port} is there and a gateway can dial the HTTP port.
     */
    private boolean holdUntilPublished = true;

    /**
     * How long {@link #holdUntilPublished} may keep the instance out of rotation. When no port was published
     * by then a WARN is logged and the instance goes {@code UP} without {@code grpc-port}.
     */
    private java.time.Duration publishTimeout = java.time.Duration.ofSeconds(60);

    public boolean isHoldUntilPublished() {
        return holdUntilPublished;
    }

    public void setHoldUntilPublished(boolean holdUntilPublished) {
        this.holdUntilPublished = holdUntilPublished;
    }

    public java.time.Duration getPublishTimeout() {
        return publishTimeout;
    }

    public void setPublishTimeout(java.time.Duration publishTimeout) {
        this.publishTimeout = publishTimeout;
    }

    public boolean isExposeGrpcPort() {
        return exposeGrpcPort;
    }

    public void setExposeGrpcPort(boolean exposeGrpcPort) {
        this.exposeGrpcPort = exposeGrpcPort;
    }

    public Integer getGrpcPort() {
        return grpcPort;
    }

    public void setGrpcPort(Integer grpcPort) {
        this.grpcPort = grpcPort;
    }

    public String getMetadataKey() {
        return metadataKey;
    }

    public void setMetadataKey(String metadataKey) {
        this.metadataKey = metadataKey;
    }

    public boolean isTypeMetadata() {
        return typeMetadata;
    }

    public void setTypeMetadata(boolean typeMetadata) {
        this.typeMetadata = typeMetadata;
    }

    public boolean isUpdateOnBind() {
        return updateOnBind;
    }

    public void setUpdateOnBind(boolean updateOnBind) {
        this.updateOnBind = updateOnBind;
    }

    public String getMetadataPrefix() {
        return metadataPrefix;
    }

    public void setMetadataPrefix(String metadataPrefix) {
        this.metadataPrefix = metadataPrefix;
    }
}
