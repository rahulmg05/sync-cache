package io.synccache.commands;

import io.synccache.config.ServerConfig;
import io.synccache.protocol.RespValue;
import io.synccache.server.CommandHandler;
import io.synccache.store.Store;
import java.util.List;

/**
 * Handles the DBSIZE command.
 * Returns the number of keys in the store.
 */
public final class DbSizeCommand implements CommandHandler {

  @Override
  public RespValue handle(List<RespValue> args, Store store, ServerConfig config) {
    return new RespValue.IntegerValue(store.size());
  }
}
