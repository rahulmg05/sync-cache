package io.synccache.store;

import io.synccache.config.ServerConfig;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe in-memory key-value store.
 * Reads may be performed from any thread; mutating methods must only be called
 * from the command engine's single writer thread.
 */
public final class Store {

  private final ConcurrentHashMap<String, TypedValue> map = new ConcurrentHashMap<>();
  private final ServerConfig config;

  /**
   * Creates a new Store with the given configuration.
   *
   * @param config the server configuration providing limit parameters
   */
  public Store(ServerConfig config) {
    this.config = config;
  }

  /**
   * Retrieves the value for the given key.
   *
   * @param key the key to look up
   * @return an Optional containing the value, or empty if not present
   */
  public Optional<TypedValue> get(String key) {
    return Optional.ofNullable(map.get(key));
  }

  /**
   * Stores a value under the given key, enforcing size and count limits.
   * Must only be called from the writer thread.
   *
   * @param key   the key to store
   * @param value the value to store
   * @throws StoreLimitException if key size, value size, or max-key count is exceeded
   */
  public void set(String key, TypedValue value) {
    byte[] keyBytes = key.getBytes(StandardCharsets.UTF_8);
    if (keyBytes.length > config.maxKeySize()) {
      throw new StoreLimitException(
          "Key size " + keyBytes.length + " exceeds limit " + config.maxKeySize());
    }
    if (value instanceof StringValue sv) {
      if (sv.data() != null && sv.data().length > config.maxValueSize()) {
        throw new StoreLimitException(
            "Value size " + sv.data().length + " exceeds limit " + config.maxValueSize());
      }
    }
    boolean isNew = !map.containsKey(key);
    if (isNew && map.size() >= config.maxKeys()) {
      throw new StoreLimitException(
          "Max key count " + config.maxKeys() + " reached");
    }
    map.put(key, value);
  }

  /**
   * Deletes the value associated with the given key.
   * Must only be called from the writer thread.
   *
   * @param key the key to delete
   * @return true if the key existed and was deleted
   */
  public boolean delete(String key) {
    return map.remove(key) != null;
  }

  /**
   * Checks whether the given key exists in the store.
   *
   * @param key the key to check
   * @return true if the key is present
   */
  public boolean exists(String key) {
    return map.containsKey(key);
  }

  /**
   * Returns the number of keys currently in the store.
   *
   * @return the key count
   */
  public int size() {
    return map.size();
  }

  /**
   * Deletes all keys from the store.
   * Must only be called from the writer thread.
   */
  public void flush() {
    map.clear();
  }

  /**
   * Returns the type name of the value associated with the given key.
   *
   * @param key the key to check
   * @return "string" if the key holds a StringValue, or "none" if absent
   */
  public String type(String key) {
    TypedValue val = map.get(key);
    if (val == null) {
      return "none";
    }
    if (val instanceof StringValue) {
      return "string";
    }
    return "unknown";
  }
}
