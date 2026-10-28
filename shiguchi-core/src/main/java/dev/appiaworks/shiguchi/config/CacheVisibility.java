package dev.appiaworks.shiguchi.config;

/**
 * Who may store a cacheable REST response: the {@code private} / {@code public} directive of its
 * {@code Cache-Control} header, and whether the gateway's own response cache keys it per caller.
 */
public enum CacheVisibility {
    /**
     * {@code private} when the request identifies its caller (an authenticated principal, an
     * {@code Authorization} or a {@code Cookie} header), no directive otherwise. The response
     * cache keys every entry by the caller's forwarded identity. The safe choice for a response
     * that may depend on who asks, and the default.
     */
    AUTO,
    /** Always {@code private}: browsers may keep it, shared caches (CDNs, proxies) may not. */
    PRIVATE,
    /**
     * Always {@code public}: any cache may store it and hand it to anyone, credentials or not.
     * The response cache shares one entry between every caller. Only for responses that are the
     * same for everybody.
     */
    PUBLIC
}
