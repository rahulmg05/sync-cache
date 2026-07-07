package io.synccache.commands;

import io.synccache.config.ServerConfig;
import io.synccache.protocol.RespValue;
import io.synccache.server.CommandHandler;
import io.synccache.store.Store;
import io.synccache.store.StringValue;
import io.synccache.store.TypedValue;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

/**
 * Handles the GET command.
 * Returns the value for a key, or null bulk string if the key does not exist or is expired.
 * Performs lazy TTL expiry on read.
 */
public final class GetCommand implements CommandHandler {

  @Override
  public RespValue handle(List<RespValue> args, Store store, ServerConfig config) {
    if (args.size() != 2) {
      return new RespValue.ErrorValue("ERR wrong number of arguments for 'get' command");
    }
    if (!(args.get(1) instanceof RespValue.BulkString keyBs) || keyBs.value() == null) {
      return new RespValue.ErrorValue("ERR invalid key");
    }
    String key = new String(keyBs.value(), StandardCharsets.UTF_8);
    Optional<TypedValue> opt = store.get(key);
    if (opt.isEmpty()) {
      return new RespValue.BulkString(null);
    }
    TypedValue val = opt.get();
    if (!(val instanceof StringValue sv)) {
      return new RespValue.ErrorValue("WRONGTYPE Operation against a key holding the wrong kind of value");
    }
    if (sv.isExpired(System.currentTimeMillis())) {
      store.delete(key);
      return new RespValue.BulkString(null);
    }
    return new RespValue.BulkString(sv.data());
  }
}
