package io.synccache.config;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import org.yaml.snakeyaml.Yaml;

/**
 * Loads server configuration from a YAML file.
 */
public final class ConfigLoader {

  private ConfigLoader() {}

  /**
   * Loads and validates the configuration from the given file path.
   *
   * @param path path to the YAML config file
   * @return parsed and validated ServerConfig
   * @throws RuntimeException if the file is missing, unreadable, or contains invalid values
   */
  public static ServerConfig load(String path) {
    Yaml yaml = new Yaml();
    Map<String, Object> raw;
    try (InputStream in = new FileInputStream(path)) {
      raw = yaml.load(in);
    } catch (IOException e) {
      throw new RuntimeException("Failed to read config file: " + path, e);
    }
    if (raw == null) {
      throw new RuntimeException("Config file is empty: " + path);
    }
    int port = requireInt(raw, "port");
    int maxKeys = requireInt(raw, "max-keys");
    int maxKeySize = requireInt(raw, "max-key-size");
    long maxValueSize = requireLong(raw, "max-value-size");
    if (port < 1 || port > 65535) {
      throw new RuntimeException("Config 'port' must be between 1 and 65535, got: " + port);
    }
    if (maxKeys < 1) {
      throw new RuntimeException("Config 'max-keys' must be positive, got: " + maxKeys);
    }
    if (maxKeySize < 1) {
      throw new RuntimeException("Config 'max-key-size' must be positive, got: " + maxKeySize);
    }
    if (maxValueSize < 1) {
      throw new RuntimeException("Config 'max-value-size' must be positive, got: " + maxValueSize);
    }
    return new ServerConfig(port, maxKeys, maxKeySize, maxValueSize);
  }

  private static int requireInt(Map<String, Object> map, String key) {
    Object val = map.get(key);
    if (val == null) {
      throw new RuntimeException("Config key '" + key + "' is required but missing");
    }
    if (!(val instanceof Number)) {
      throw new RuntimeException("Config key '" + key + "' must be a number, got: " + val);
    }
    return ((Number) val).intValue();
  }

  private static long requireLong(Map<String, Object> map, String key) {
    Object val = map.get(key);
    if (val == null) {
      throw new RuntimeException("Config key '" + key + "' is required but missing");
    }
    if (!(val instanceof Number)) {
      throw new RuntimeException("Config key '" + key + "' must be a number, got: " + val);
    }
    return ((Number) val).longValue();
  }
}
