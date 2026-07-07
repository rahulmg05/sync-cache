package io.synccache.server;

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
import io.synccache.config.ServerConfig;
import io.synccache.protocol.RespDecoder;
import io.synccache.protocol.RespEncoder;
import io.synccache.protocol.RespValue;
import io.synccache.store.Store;
import java.io.IOException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class IntegrationTest {

  private static final int MAX_KEY_SIZE = 512;
  private static final int MAX_VALUE_SIZE = 536870912;
  private static final int MAX_KEYS = 100;

  private TcpServer server;
  private CommandEngine engine;
  private int port;

  @BeforeEach
  void setUp() throws IOException {
    ServerConfig config = new ServerConfig(0, MAX_KEYS, MAX_KEY_SIZE, MAX_VALUE_SIZE);
    Store store = new Store(config);
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
    engine = new CommandEngine(handlers, store, config);
    server = new TcpServer(config, engine);
    engine.start();
    server.start();
    port = server.getPort();
  }

  @AfterEach
  void tearDown() {
    server.stop();
    engine.stop();
  }

  private RespValue send(Socket socket, String... parts) throws IOException {
    RespEncoder encoder = new RespEncoder(socket.getOutputStream());
    List<RespValue> args = new ArrayList<>();
    for (String part : parts) {
      args.add(new RespValue.BulkString(part.getBytes(StandardCharsets.UTF_8)));
    }
    encoder.encode(new RespValue.RespArray(args));
    return new RespDecoder(socket.getInputStream()).decode();
  }

  @Test
  void ping_returnsSimpleStringPong() throws IOException {
    try (Socket socket = new Socket("127.0.0.1", port)) {
      RespValue response = send(socket, "PING");
      assertThat(response).isInstanceOf(RespValue.SimpleString.class);
      assertThat(((RespValue.SimpleString) response).value()).isEqualTo("PONG");
    }
  }

  @Test
  void echo_returnsBulkStringWithMessage() throws IOException {
    try (Socket socket = new Socket("127.0.0.1", port)) {
      RespValue response = send(socket, "ECHO", "hello world");
      assertThat(response).isInstanceOf(RespValue.BulkString.class);
      String result = new String(((RespValue.BulkString) response).value(), StandardCharsets.UTF_8);
      assertThat(result).isEqualTo("hello world");
    }
  }

  @Test
  void set_thenGet_roundTrip() throws IOException {
    try (Socket socket = new Socket("127.0.0.1", port)) {
      RespValue setResp = send(socket, "SET", "mykey", "myvalue");
      assertThat(((RespValue.SimpleString) setResp).value()).isEqualTo("OK");
      RespValue getResp = send(socket, "GET", "mykey");
      assertThat(getResp).isInstanceOf(RespValue.BulkString.class);
      String value = new String(((RespValue.BulkString) getResp).value(), StandardCharsets.UTF_8);
      assertThat(value).isEqualTo("myvalue");
    }
  }

  @Test
  void get_whenKeyMissing_returnsNullBulkString() throws IOException {
    try (Socket socket = new Socket("127.0.0.1", port)) {
      RespValue response = send(socket, "GET", "nonexistent");
      assertThat(response).isInstanceOf(RespValue.BulkString.class);
      assertThat(((RespValue.BulkString) response).value()).isNull();
    }
  }

  @Test
  void del_whenKeyExists_deletesAndReturnsOne() throws IOException {
    try (Socket socket = new Socket("127.0.0.1", port)) {
      send(socket, "SET", "delkey", "val");
      RespValue response = send(socket, "DEL", "delkey");
      assertThat(response).isInstanceOf(RespValue.IntegerValue.class);
      assertThat(((RespValue.IntegerValue) response).value()).isEqualTo(1L);
      RespValue getResp = send(socket, "GET", "delkey");
      assertThat(((RespValue.BulkString) getResp).value()).isNull();
    }
  }

  @Test
  void exists_whenKeyPresent_returnsOne() throws IOException {
    try (Socket socket = new Socket("127.0.0.1", port)) {
      send(socket, "SET", "existkey", "v");
      RespValue response = send(socket, "EXISTS", "existkey");
      assertThat(response).isInstanceOf(RespValue.IntegerValue.class);
      assertThat(((RespValue.IntegerValue) response).value()).isEqualTo(1L);
    }
  }

  @Test
  void exists_whenKeyAbsent_returnsZero() throws IOException {
    try (Socket socket = new Socket("127.0.0.1", port)) {
      RespValue response = send(socket, "EXISTS", "nope");
      assertThat(((RespValue.IntegerValue) response).value()).isEqualTo(0L);
    }
  }

  @Test
  void type_whenStringKey_returnsString() throws IOException {
    try (Socket socket = new Socket("127.0.0.1", port)) {
      send(socket, "SET", "typekey", "v");
      RespValue response = send(socket, "TYPE", "typekey");
      assertThat(response).isInstanceOf(RespValue.SimpleString.class);
      assertThat(((RespValue.SimpleString) response).value()).isEqualTo("string");
    }
  }

  @Test
  void type_whenKeyAbsent_returnsNone() throws IOException {
    try (Socket socket = new Socket("127.0.0.1", port)) {
      RespValue response = send(socket, "TYPE", "missing");
      assertThat(((RespValue.SimpleString) response).value()).isEqualTo("none");
    }
  }

  @Test
  void dbsize_returnsCorrectCount() throws IOException {
    try (Socket socket = new Socket("127.0.0.1", port)) {
      send(socket, "FLUSHDB");
      send(socket, "SET", "a", "1");
      send(socket, "SET", "b", "2");
      RespValue response = send(socket, "DBSIZE");
      assertThat(response).isInstanceOf(RespValue.IntegerValue.class);
      assertThat(((RespValue.IntegerValue) response).value()).isEqualTo(2L);
    }
  }

  @Test
  void flushdb_removesAllKeys() throws IOException {
    try (Socket socket = new Socket("127.0.0.1", port)) {
      send(socket, "SET", "flush1", "v");
      send(socket, "SET", "flush2", "v");
      send(socket, "FLUSHDB");
      RespValue response = send(socket, "DBSIZE");
      assertThat(((RespValue.IntegerValue) response).value()).isEqualTo(0L);
    }
  }

  @Test
  void set_withEX_thenGetAfterExpiry_returnsNull() throws IOException, InterruptedException {
    try (Socket socket = new Socket("127.0.0.1", port)) {
      send(socket, "SET", "ttlkey", "value", "EX", "1");
      RespValue before = send(socket, "GET", "ttlkey");
      assertThat(((RespValue.BulkString) before).value()).isNotNull();
      Thread.sleep(1200);
      RespValue after = send(socket, "GET", "ttlkey");
      assertThat(((RespValue.BulkString) after).value()).isNull();
    }
  }

  @Test
  void set_whenKeyTooLong_returnsError() throws IOException {
    try (Socket socket = new Socket("127.0.0.1", port)) {
      String longKey = "x".repeat(MAX_KEY_SIZE + 1);
      RespValue response = send(socket, "SET", longKey, "value");
      assertThat(response).isInstanceOf(RespValue.ErrorValue.class);
      assertThat(((RespValue.ErrorValue) response).message()).startsWith("ERR");
    }
  }

  @Test
  void set_whenValueTooLarge_returnsError() throws IOException {
    try (Socket socket = new Socket("127.0.0.1", port)) {
      ServerConfig smallConfig = new ServerConfig(0, MAX_KEYS, MAX_KEY_SIZE, 10);
      Store smallStore = new Store(smallConfig);
      Map<String, CommandHandler> smallHandlers = new HashMap<>();
      smallHandlers.put("SET", new SetCommand());
      CommandEngine smallEngine = new CommandEngine(smallHandlers, smallStore, smallConfig);
      TcpServer smallServer = new TcpServer(smallConfig, smallEngine);
      smallEngine.start();
      smallServer.start();
      int smallPort = smallServer.getPort();
      try (Socket smallSocket = new Socket("127.0.0.1", smallPort)) {
        RespValue response = send(smallSocket, "SET", "key", "this-value-is-too-large");
        assertThat(response).isInstanceOf(RespValue.ErrorValue.class);
        assertThat(((RespValue.ErrorValue) response).message()).startsWith("ERR");
      } finally {
        smallServer.stop();
        smallEngine.stop();
      }
    }
  }

  @Test
  void unknownCommand_returnsErrorResponse() throws IOException {
    try (Socket socket = new Socket("127.0.0.1", port)) {
      RespValue response = send(socket, "NOTACOMMAND");
      assertThat(response).isInstanceOf(RespValue.ErrorValue.class);
    }
  }
}
