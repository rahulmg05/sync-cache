package io.synccache.store;

/**
 * A string (byte array) value with an optional expiry time.
 *
 * @param data          the raw byte content
 * @param expiryEpochMs epoch milliseconds at which this value expires, or -1 for no expiry
 */
public record StringValue(byte[] data, long expiryEpochMs) implements TypedValue {

  /** Sentinel value indicating no expiry. */
  public static final long NO_EXPIRY = -1L;

  /**
   * Returns true if this value has expired relative to the given current time.
   *
   * @param nowMs current epoch milliseconds
   * @return true if expired
   */
  public boolean isExpired(long nowMs) {
    return expiryEpochMs != NO_EXPIRY && nowMs >= expiryEpochMs;
  }
}
