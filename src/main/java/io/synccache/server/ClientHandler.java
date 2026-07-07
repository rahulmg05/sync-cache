package io.synccache.server;

import io.synccache.protocol.RespDecoder;
import io.synccache.protocol.RespEncoder;
import io.synccache.protocol.RespValue;
import java.io.IOException;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Handles a single client connection: reads RESP commands, dispatches to the engine,
 * and writes responses. Runs on a virtual thread.
 */
public final class ClientHandler implements Runnable {

  private static final System.Logger LOGGER = System.getLogger(ClientHandler.class.getName());

  private final Socket socket;
  private final CommandEngine engine;

  /**
   * Creates a new client handler for the given socket.
   *
   * @param socket the accepted client socket
   * @param engine the command engine to dispatch commands to
   */
  public ClientHandler(Socket socket, CommandEngine engine) {
    this.socket = socket;
    this.engine = engine;
  }

  @Override
  public void run() {
    try (socket) {
      RespDecoder decoder = new RespDecoder(socket.getInputStream());
      RespEncoder encoder = new RespEncoder(socket.getOutputStream());
      while (!socket.isClosed()) {
        RespValue frame = decoder.decode();
        if (!(frame instanceof RespValue.RespArray array) || array.elements() == null) {
          encoder.encode(new RespValue.ErrorValue("ERR expected array command"));
          continue;
        }
        List<RespValue> args = array.elements();
        CompletableFuture<RespValue> future = new CompletableFuture<>();
        engine.submit(new CommandRequest(args, future));
        RespValue response = future.join();
        encoder.encode(response);
      }
    } catch (IOException e) {
      LOGGER.log(System.Logger.Level.DEBUG, "Client disconnected: {0}", e.getMessage());
    } catch (Exception e) {
      LOGGER.log(System.Logger.Level.WARNING, "Unexpected error handling client", e);
    }
  }
}
