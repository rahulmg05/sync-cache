package io.synccache.commands;

import io.synccache.config.ServerConfig;
import io.synccache.protocol.RespValue;
import io.synccache.server.CommandHandler;
import io.synccache.store.Store;
import java.util.List;

/**
 * Handles the FLUSHDB command.
 * Deletes all keys from the store and returns OK.
 */
public final class FlushDbCommand implements CommandHandler {

  @Override
  public RespValue handle(List<RespValue> args, Store store, ServerConfig config) {
    store.flush();
    return new RespValue.SimpleString("OK");
  }
}
