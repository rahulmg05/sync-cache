package io.synccache.store;

/**
 * Sealed interface representing a typed value stored in the cache.
 * Phase 1 supports only StringValue.
 */
public sealed interface TypedValue permits StringValue {
}
