package dev.appiaworks.shiguchi.graphql;

/**
 * Wraps the failure of a batch load that had more failed keys than the per-request error cap
 * ({@value GrpcDataLoaderRegistryFactory#MAX_ERRORS_PER_LOADER}) allows to be listed one by one:
 * it stands for those keys, and {@link GrpcGraphQlExceptionHandler} adds their count to the error.
 */
final class BatchFailureSummary extends RuntimeException {

    /** Resolves to null in the relationship fetcher: a failed key whose error is not listed. */
    static final Object SUPPRESSED = new Object();

    private final String type;
    private final int omitted;

    BatchFailureSummary(String type, int omitted, Throwable cause) {
        super(cause.getMessage(), cause);
        this.type = type;
        this.omitted = omitted;
    }

    String type() {
        return type;
    }

    int omitted() {
        return omitted;
    }
}
