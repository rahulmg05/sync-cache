package io.synccache.commands;

import io.synccache.config.ServerConfig;
import io.synccache.protocol.RespValue;
import io.synccache.server.CommandHandler;
import io.synccache.store.Store;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Handles the TYPE command.
 * Returns the type of the value stored at the key, or "none" if the key does not exist.
 */
public final class TypeCommand implements CommandHandler {

  @Override
  public RespValue handle(List<RespValue> args, Store store, ServerConfig config) {
    if (args.size() != 2) {
      return new RespValue.ErrorValue("ERR wrong number of arguments for 'type' command");
    }
    if (!(args.get(1) instanceof RespValue.BulkString bs) || bs.value() == null) {
      return new RespValue.ErrorValue("ERR invalid key");
    }
    String key = new String(bs.value(), StandardCharsets.UTF_8);
    return new RespValue.SimpleString(store.type(key));
  }
}
