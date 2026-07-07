package io.synccache.server;

import io.synccache.config.ServerConfig;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;

/**
 * TCP server that accepts client connections and dispatches each to a virtual thread
 * running a ClientHandler.
 */
public final class TcpServer {

  private static final System.Logger LOGGER = System.getLogger(TcpServer.class.getName());

  private final ServerConfig config;
  private final CommandEngine engine;
  private ServerSocket serverSocket;
  private Thread acceptThread;
  private volatile boolean running = false;

  /**
   * Creates a new TCP server.
   *
   * @param config the server configuration (provides the port)
   * @param engine the command engine to dispatch commands to
   */
  public TcpServer(ServerConfig config, CommandEngine engine) {
    this.config = config;
    this.engine = engine;
  }

  /**
   * Starts the server, binding to the configured port.
   *
   * @throws IOException if the server socket cannot be opened
   */
  public void start() throws IOException {
    serverSocket = new ServerSocket(config.port());
    running = true;
    LOGGER.log(System.Logger.Level.INFO, "sync-cache listening on port {0}", getPort());
    acceptThread = new Thread(this::acceptLoop, "tcp-accept");
    acceptThread.setDaemon(false);
    acceptThread.start();
  }

  /**
   * Returns the actual port the server is bound to.
   * Useful when the configured port is 0 (random port).
   *
   * @return the bound port number
   */
  public int getPort() {
    return serverSocket.getLocalPort();
  }

  /**
   * Stops the server, closing the server socket and interrupting the accept thread.
   */
  public void stop() {
    running = false;
    try {
      if (serverSocket != null && !serverSocket.isClosed()) {
        serverSocket.close();
      }
    } catch (IOException e) {
      LOGGER.log(System.Logger.Level.WARNING, "Error closing server socket", e);
    }
    if (acceptThread != null) {
      acceptThread.interrupt();
    }
  }

  private void acceptLoop() {
    while (running) {
      try {
        Socket clientSocket = serverSocket.accept();
        Thread.startVirtualThread(new ClientHandler(clientSocket, engine));
      } catch (IOException e) {
        if (running) {
          LOGGER.log(System.Logger.Level.WARNING, "Error accepting connection", e);
        }
      }
    }
  }
}
