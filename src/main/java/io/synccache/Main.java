package io.synccache;

import io.synccache.commands.ConfigGetCommand;
import io.synccache.commands.DbSizeCommand;
import io.synccache.commands.DelCommand;
import io.synccache.commands.EchoCommand;
import io.synccache.commands.ExistsCommand;
import io.synccache.commands.FlushDbCommand;
import io.synccache.commands.GetCommand;
import io.synccache.commands.PingCommand;
import io.synccache.commands.SetCommand;
import io.synccache.commands.TypeCommand;
import io.synccache.config.ConfigLoader;
import io.synccache.config.ServerConfig;
import io.synccache.server.CommandEngine;
import io.synccache.server.CommandHandler;
import io.synccache.server.TcpServer;
import io.synccache.store.Store;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * Entry point for the sync-cache daemon.
 */
public final class Main {

  private static final System.Logger LOGGER = System.getLogger(Main.class.getName());

  private Main() {}

  /**
   * Application entry point.
   *
   * @param args command-line arguments; accepts {@code --config path}
   */
  public static void main(String[] args) {
    String configPath = "config.yml";
    for (int i = 0; i < args.length - 1; i++) {
      if ("--config".equals(args[i])) {
        configPath = args[i + 1];
      }
    }
    ServerConfig config = ConfigLoader.load(configPath);
    Store store = new Store(config);
    Map<String, CommandHandler> handlers = buildHandlers();
    CommandEngine engine = new CommandEngine(handlers, store, config);
    TcpServer server = new TcpServer(config, engine);
    Runtime.getRuntime().addShutdownHook(new Thread(() -> {
      LOGGER.log(System.Logger.Level.INFO, "Shutting down sync-cache...");
      server.stop();
      engine.stop();
    }, "shutdown-hook"));
    engine.start();
    try {
      server.start();
    } catch (IOException e) {
      LOGGER.log(System.Logger.Level.ERROR, "Failed to start server", e);
      System.exit(1);
    }
  }

  private static Map<String, CommandHandler> buildHandlers() {
    Map<String, CommandHandler> handlers = new HashMap<>();
    handlers.put("PING", new PingCommand());
    handlers.put("ECHO", new EchoCommand());
    handlers.put("SET", new SetCommand());
    handlers.put("GET", new GetCommand());
    handlers.put("DEL", new DelCommand());
    handlers.put("EXISTS", new ExistsCommand());
    handlers.put("TYPE", new TypeCommand());
    handlers.put("DBSIZE", new DbSizeCommand());
    handlers.put("FLUSHDB", new FlushDbCommand());
    handlers.put("CONFIG", new ConfigGetCommand());
    return handlers;
  }
}
