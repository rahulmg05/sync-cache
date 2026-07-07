package io.synccache.store;

/**
 * Thrown when a store operation violates a configured limit
 * (max key size, max value size, or max keys count).
 */
public final class StoreLimitException extends RuntimeException {

  /**
   * Constructs a new StoreLimitException with the given message.
   *
   * @param message description of the violated limit
   */
  public StoreLimitException(String message) {
    super(message);
  }
}
