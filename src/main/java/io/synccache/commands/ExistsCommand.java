package io.synccache.commands;

import io.synccache.config.ServerConfig;
import io.synccache.protocol.RespValue;
import io.synccache.server.CommandHandler;
import io.synccache.store.Store;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Handles the EXISTS command.
 * Returns the count of existing keys; the same key counts multiple times if repeated.
 */
public final class ExistsCommand implements CommandHandler {

  @Override
  public RespValue handle(List<RespValue> args, Store store, ServerConfig config) {
    if (args.size() < 2) {
      return new RespValue.ErrorValue("ERR wrong number of arguments for 'exists' command");
    }
    long count = 0;
    for (int i = 1; i < args.size(); i++) {
      if (!(args.get(i) instanceof RespValue.BulkString bs) || bs.value() == null) {
        continue;
      }
      String key = new String(bs.value(), StandardCharsets.UTF_8);
      if (store.exists(key)) {
        count++;
      }
    }
    return new RespValue.IntegerValue(count);
  }
}
