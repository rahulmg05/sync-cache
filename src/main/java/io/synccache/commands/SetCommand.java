package io.synccache.commands;

import io.synccache.config.ServerConfig;
import io.synccache.protocol.RespValue;
import io.synccache.server.CommandHandler;
import io.synccache.store.StoreLimitException;
import io.synccache.store.Store;
import io.synccache.store.StringValue;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

/**
 * Handles the SET command.
 * {@code SET key value [EX seconds]} stores a string value with optional TTL.
 */
public final class SetCommand implements CommandHandler {

  @Override
  public RespValue handle(List<RespValue> args, Store store, ServerConfig config) {
    if (args.size() < 3) {
      return new RespValue.ErrorValue("ERR wrong number of arguments for 'set' command");
    }
    String key = extractString(args.get(1));
    if (key == null) {
      return new RespValue.ErrorValue("ERR invalid key");
    }
    if (!(args.get(2) instanceof RespValue.BulkString valueBs)) {
      return new RespValue.ErrorValue("ERR invalid value");
    }
    byte[] valueData = valueBs.value();
    long expiryMs = StringValue.NO_EXPIRY;
    int i = 3;
    while (i < args.size()) {
      String option = extractString(args.get(i));
      if (option == null) {
        return new RespValue.ErrorValue("ERR invalid option");
      }
      if ("EX".equals(option.toUpperCase(Locale.ROOT))) {
        if (i + 1 >= args.size()) {
          return new RespValue.ErrorValue("ERR syntax error");
        }
        String secStr = extractString(args.get(i + 1));
        if (secStr == null) {
          return new RespValue.ErrorValue("ERR invalid expire time");
        }
        long seconds;
        try {
          seconds = Long.parseLong(secStr);
        } catch (NumberFormatException e) {
          return new RespValue.ErrorValue("ERR invalid expire time in 'set' command");
        }
        if (seconds <= 0) {
          return new RespValue.ErrorValue("ERR invalid expire time in 'set' command");
        }
        expiryMs = System.currentTimeMillis() + seconds * 1000L;
        i += 2;
      } else {
        return new RespValue.ErrorValue("ERR syntax error");
      }
    }
    try {
      store.set(key, new StringValue(valueData, expiryMs));
    } catch (StoreLimitException e) {
      return new RespValue.ErrorValue("ERR " + e.getMessage());
    }
    return new RespValue.SimpleString("OK");
  }

  private String extractString(RespValue val) {
    if (val instanceof RespValue.BulkString bs && bs.value() != null) {
      return new String(bs.value(), StandardCharsets.UTF_8);
    }
    return null;
  }
}
