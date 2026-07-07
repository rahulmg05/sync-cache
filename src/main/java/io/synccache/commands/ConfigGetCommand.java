package io.synccache.commands;

import io.synccache.config.ServerConfig;
import io.synccache.protocol.RespValue;
import io.synccache.server.CommandHandler;
import io.synccache.store.Store;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Handles the CONFIG GET command.
 * Supports parameters: port, max-keys, max-key-size, max-value-size.
 * Supports glob {@code *} to return all parameters.
 */
public final class ConfigGetCommand implements CommandHandler {

  private static final String PARAM_PORT = "port";
  private static final String PARAM_MAX_KEYS = "max-keys";
  private static final String PARAM_MAX_KEY_SIZE = "max-key-size";
  private static final String PARAM_MAX_VALUE_SIZE = "max-value-size";

  @Override
  public RespValue handle(List<RespValue> args, Store store, ServerConfig config) {
    if (args.size() != 3) {
      return new RespValue.ErrorValue("ERR wrong number of arguments for 'config|get' command");
    }
    if (!(args.get(2) instanceof RespValue.BulkString paramBs) || paramBs.value() == null) {
      return new RespValue.ErrorValue("ERR invalid parameter");
    }
    String param = new String(paramBs.value(), StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
    List<RespValue> result = new ArrayList<>();
    if ("*".equals(param)) {
      appendParam(result, PARAM_PORT, String.valueOf(config.port()));
      appendParam(result, PARAM_MAX_KEYS, String.valueOf(config.maxKeys()));
      appendParam(result, PARAM_MAX_KEY_SIZE, String.valueOf(config.maxKeySize()));
      appendParam(result, PARAM_MAX_VALUE_SIZE, String.valueOf(config.maxValueSize()));
    } else {
      switch (param) {
        case PARAM_PORT -> appendParam(result, PARAM_PORT, String.valueOf(config.port()));
        case PARAM_MAX_KEYS -> appendParam(result, PARAM_MAX_KEYS, String.valueOf(config.maxKeys()));
        case PARAM_MAX_KEY_SIZE ->
            appendParam(result, PARAM_MAX_KEY_SIZE, String.valueOf(config.maxKeySize()));
        case PARAM_MAX_VALUE_SIZE ->
            appendParam(result, PARAM_MAX_VALUE_SIZE, String.valueOf(config.maxValueSize()));
        default -> { }
      }
    }
    return new RespValue.RespArray(result);
  }

  private void appendParam(List<RespValue> result, String name, String value) {
    result.add(new RespValue.BulkString(name.getBytes(StandardCharsets.UTF_8)));
    result.add(new RespValue.BulkString(value.getBytes(StandardCharsets.UTF_8)));
  }
}
