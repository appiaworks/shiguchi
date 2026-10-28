package dev.appiaworks.shiguchi.graphql;

import com.google.protobuf.Descriptors.Descriptor;
import dev.appiaworks.protobuf.extras.api.ApiStatus;
import graphql.schema.GraphQLCodeRegistry;

/**
 * Adds fields of its own to the generated schema, next to the ones the routes produce: the push
 * module's subscription fields are one. A bean of this type is picked up by the schema builder
 * (for the SDL) and the data fetcher registrar (for the fetchers) on every build and rebuild.
 */
@ApiStatus(value = ApiStatus.Status.EXPERIMENTAL, since = "0.14.0")
public interface GraphQlContributor {

    /** Declares types and fields; called on every schema build, before the SDL is assembled. */
    void contributeSchema(Schema schema);

    /** Registers the data fetchers for the fields declared in {@link #contributeSchema}. */
    void contributeDataFetchers(GraphQLCodeRegistry.Builder registry);

    /** What a contributor may add to the SDL. */
    interface Schema {
        /** One field of {@code type Subscription}, as a declaration line ({@code name(arg: Type): Type}). */
        void subscriptionField(String declaration);

        /**
         * Emits the object type for a protobuf message (and the types it reaches) the way the
         * routes' types are emitted, once, and returns its GraphQL type name; a well-known type
         * answers its scalar.
         */
        String objectType(Descriptor descriptor);

        /** A hand-written type definition, emitted once under its name. */
        void type(String name, String sdl);
    }
}
