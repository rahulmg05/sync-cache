package io.synccache.server;

import io.synccache.config.ServerConfig;
import io.synccache.protocol.RespValue;
import io.synccache.store.Store;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

/**
 * Single-writer command engine that processes commands from a blocking queue.
 * All store mutations happen exclusively on the writer thread owned by this engine.
 */
public final class CommandEngine {

  private static final int QUEUE_CAPACITY = 65536;
  private static final System.Logger LOGGER = System.getLogger(CommandEngine.class.getName());

  private final BlockingQueue<CommandRequest> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
  private final Map<String, CommandHandler> handlers;
  private final Store store;
  private final ServerConfig config;
  private volatile boolean running = false;
  private Thread writerThread;

  /**
   * Creates a new command engine.
   *
   * @param handlers map of uppercase command names to their handlers
   * @param store    the in-memory store
   * @param config   the server configuration
   */
  public CommandEngine(Map<String, CommandHandler> handlers, Store store, ServerConfig config) {
    this.handlers = handlers;
    this.store = store;
    this.config = config;
  }

  /**
   * Starts the writer thread. Must be called before submitting commands.
   */
  public void start() {
    running = true;
    writerThread = new Thread(this::runLoop, "command-engine-writer");
    writerThread.setDaemon(false);
    writerThread.start();
  }

  /**
   * Stops the writer thread gracefully.
   */
  public void stop() {
    running = false;
    if (writerThread != null) {
      writerThread.interrupt();
    }
  }

  /**
   * Submits a command request to the queue.
   *
   * @param request the command request to enqueue
   */
  public void submit(CommandRequest request) {
    try {
      queue.put(request);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      request.result().completeExceptionally(e);
    }
  }

  private void runLoop() {
    while (running) {
      try {
        CommandRequest request = queue.take();
        processRequest(request);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        break;
      }
    }
    // Drain remaining requests
    CommandRequest request;
    while ((request = queue.poll()) != null) {
      processRequest(request);
    }
  }

  private void processRequest(CommandRequest request) {
    try {
      RespValue response = dispatch(request.args());
      request.result().complete(response);
    } catch (Exception e) {
      LOGGER.log(System.Logger.Level.ERROR, "Error processing command", e);
      request.result().complete(
          new RespValue.ErrorValue("ERR internal server error: " + e.getMessage()));
    }
  }

  private RespValue dispatch(java.util.List<RespValue> args) {
    if (args == null || args.isEmpty()) {
      return new RespValue.ErrorValue("ERR empty command");
    }
    if (!(args.get(0) instanceof RespValue.BulkString bs) || bs.value() == null) {
      return new RespValue.ErrorValue("ERR invalid command format");
    }
    String commandName = new String(bs.value(), java.nio.charset.StandardCharsets.UTF_8)
        .toUpperCase(java.util.Locale.ROOT);
    CommandHandler handler = handlers.get(commandName);
    if (handler == null) {
      return new RespValue.ErrorValue("ERR unknown command '" + commandName + "'");
    }
    return handler.handle(args, store, config);
  }
}
