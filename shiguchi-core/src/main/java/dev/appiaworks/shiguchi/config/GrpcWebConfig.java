package dev.appiaworks.shiguchi.config;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * gRPC-Web ({@code shiguchi.grpc-web.*}): lets browser clients — grpc-web, Connect's
 * gRPC-Web transport, or anything speaking the
 * <a href="https://github.com/grpc/grpc/blob/master/doc/PROTOCOL-WEB.md">gRPC-Web protocol</a>
 * — call the loaded gRPC services over plain HTTP/1.1 or HTTP/2 on the gateway's HTTP port,
 * without a separate proxy.
 *
 * <p>Requests are recognised by their {@code Content-Type} ({@code application/grpc-web},
 * {@code +proto}, and the base64 {@code application/grpc-web-text} variants) and addressed as
 * {@code POST <path-prefix>/<package.Service>/<Method>}. Unary and server-streaming methods are
 * supported; client and bidirectional streaming cannot be expressed in gRPC-Web.
 */
@Data
@NoArgsConstructor
public class GrpcWebConfig {

    /** Serve gRPC-Web. Off by default: it makes every loaded method callable from a browser. */
    private boolean enabled = false;

    /**
     * Prefix the gRPC-Web paths sit under, e.g. {@code /grpc-web} for
     * {@code POST /grpc-web/acme.Library/GetBook}. Empty (the default) serves them at the plain
     * gRPC path, as gRPC-Web clients expect; a client's base URL then carries the prefix if one is set.
     */
    private String pathPrefix = "";
}
