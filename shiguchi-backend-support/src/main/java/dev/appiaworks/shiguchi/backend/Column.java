package dev.appiaworks.shiguchi.backend;

import dev.appiaworks.protobuf.extras.api.ApiStatus;

/**
 * A field of the response message paired with the property that supplies it.
 *
 * @param field the field on the response message, as declared to {@link MaskSpec}
 * @param property the entity property, column or path to select
 */
@ApiStatus(value = ApiStatus.Status.EXPERIMENTAL, since = "0.21.0")
public record Column(String field, String property) {}
