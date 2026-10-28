package dev.appiaworks.protobuf.extras.api;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * What a consumer of this library may rely on, and what may change under them.
 *
 * <p>The gateway is mostly configuration, but a handful of types exist to be implemented or
 * called by applications — the extension points listed in
 * <a href="https://github.com/appiaworks/shiguchi/blob/main/docs/configuration.md">the
 * configuration reference</a>. This annotation says which of those carry a compatibility promise.
 * Everything not annotated is internal: it is public only because Java has no finer visibility
 * across packages, and it may change in any release.
 *
 * <p>The promise for {@link Status#STABLE} types is enforced, not merely stated: the build
 * compares every published module against the previous release and fails on a change that would
 * break a consumer. See {@code docs/api-compatibility.md}.
 *
 * <p>Retained at runtime so the compatibility gate can read it back off the published jars, and
 * so an application can inspect what it is depending on.
 *
 * <p>Applied to a type, it covers that type's public members.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.CONSTRUCTOR, ElementType.FIELD, ElementType.PACKAGE})
public @interface ApiStatus {

    /** The promise attached to the annotated element. */
    Status value();

    /** The release the element reached its current status in, e.g. {@code "0.5.0"}. Optional. */
    String since() default "";

    /** Levels of promise, from strongest to weakest. */
    enum Status {
        /**
         * Safe to build on. Within a major version this type keeps binary compatibility: no
         * removed or renamed public member, no changed signature, no narrowed visibility. New
         * methods may be added to a class, and to an interface only with a default implementation.
         *
         * <p>A breaking change to a stable type needs a major version and a migration note.
         */
        STABLE,

        /**
         * Usable, but still settling. The shape may change in a minor release when experience
         * shows it is wrong, with the change called out in the changelog. Suitable for
         * applications that can absorb an occasional adjustment on upgrade, not for libraries
         * that re-expose it to their own users.
         */
        EXPERIMENTAL,

        /**
         * Scheduled for removal. Still works and still tested, but a replacement exists — named
         * in the {@code @deprecated} Javadoc it is paired with — and it will be gone in a later
         * major version.
         */
        DEPRECATED,

        /**
         * Not for consumers. Public for the gateway's own modules to use across package
         * boundaries; it may change or disappear in any release, including a patch.
         */
        INTERNAL
    }
}
