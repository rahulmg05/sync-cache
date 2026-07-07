package io.synccache.config;

/**
 * Immutable configuration record for sync-cache server.
 */
public record ServerConfig(int port, int maxKeys, int maxKeySize, long maxValueSize) {
}
