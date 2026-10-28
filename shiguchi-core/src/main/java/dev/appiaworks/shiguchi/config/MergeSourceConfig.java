package dev.appiaworks.shiguchi.config;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One backend contributing to a {@link MergeRouteConfig}: which service and method to call, and
 * how its results line up with the other sources'.
 */
@Data
@NoArgsConstructor
public class MergeSourceConfig {
    /**
     * The registered service name (under {@code shiguchi.services}) this source lives in.
     * Optional, but required when several registrations expose the same proto service — the
     * sharded case, where each backend serves an identical .proto and only the address differs.
     * Without it those sources cannot be told apart and the merge reads one backend twice.
     */
    private String endpoint = "";

    /** Proto service FQN, e.g. {@code orders.OrderService}. */
    private String service;

    /** RPC name on that service. */
    private String rpc;

    /**
     * Whether this backend returns its rows already ordered by the merge key.
     *
     * <p>Defaults to false, and that default is deliberate: the gateway can send
     * {@code sort_fields} but cannot check the backend honoured it. A source wrongly declared
     * pre-sorted does not fail — it returns silently misordered pages. Set true only for a
     * backend you have confirmed sorts its output.
     */
    private boolean presorted = false;
}
