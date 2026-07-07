package io.synccache.commands;

import io.synccache.config.ServerConfig;
import io.synccache.protocol.RespValue;
import io.synccache.server.CommandHandler;
import io.synccache.store.Store;
import java.util.List;

/**
 * Handles the ECHO command.
 * Returns the given message as a bulk string.
 */
public final class EchoCommand implements CommandHandler {

  @Override
  public RespValue handle(List<RespValue> args, Store store, ServerConfig config) {
    if (args.size() != 2) {
      return new RespValue.ErrorValue("ERR wrong number of arguments for 'echo' command");
    }
    if (args.get(1) instanceof RespValue.BulkString bs) {
      return new RespValue.BulkString(bs.value());
    }
    return new RespValue.ErrorValue("ERR wrong number of arguments for 'echo' command");
  }
}
