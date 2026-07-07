package io.synccache.server;

import io.synccache.config.ServerConfig;
import io.synccache.protocol.RespValue;
import io.synccache.store.Store;
import java.util.List;

/**
 * Interface implemented by each command handler.
 */
public interface CommandHandler {

  /**
   * Handles a command invocation.
   *
   * @param args   the command arguments (first element is the command name)
   * @param store  the in-memory store
   * @param config the server configuration
   * @return the RESP response to send to the client
   */
  RespValue handle(List<RespValue> args, Store store, ServerConfig config);
}
