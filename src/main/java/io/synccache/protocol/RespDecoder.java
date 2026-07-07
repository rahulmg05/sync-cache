package io.synccache.protocol;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Decodes RESP2 frames from an InputStream using the accumulator pattern.
 * Blocking reads are acceptable because each connection runs on a virtual thread.
 */
public final class RespDecoder {

  private final InputStream in;

  /**
   * Creates a new decoder reading from the given stream.
   *
   * @param in the input stream to read from
   */
  public RespDecoder(InputStream in) {
    this.in = in;
  }

  /**
   * Reads and decodes the next RESP2 value from the stream.
   *
   * @return the decoded RespValue
   * @throws IOException if an I/O error occurs or the stream ends unexpectedly
   */
  public RespValue decode() throws IOException {
    int typeByte = in.read();
    if (typeByte == -1) {
      throw new IOException("Connection closed by client");
    }
    char type = (char) typeByte;
    return switch (type) {
      case '+' -> new RespValue.SimpleString(readLine());
      case '-' -> new RespValue.ErrorValue(readLine());
      case ':' -> new RespValue.IntegerValue(Long.parseLong(readLine()));
      case '$' -> decodeBulkString();
      case '*' -> decodeArray();
      default -> throw new IOException("Unknown RESP type byte: " + typeByte);
    };
  }

  private RespValue.BulkString decodeBulkString() throws IOException {
    int length = Integer.parseInt(readLine());
    if (length == -1) {
      return new RespValue.BulkString(null);
    }
    byte[] data = readExact(length);
    readCrlf();
    return new RespValue.BulkString(data);
  }

  private RespValue.RespArray decodeArray() throws IOException {
    int count = Integer.parseInt(readLine());
    if (count == -1) {
      return new RespValue.RespArray(null);
    }
    List<RespValue> elements = new ArrayList<>(count);
    for (int i = 0; i < count; i++) {
      elements.add(decode());
    }
    return new RespValue.RespArray(elements);
  }

  private String readLine() throws IOException {
    StringBuilder sb = new StringBuilder();
    int prev = -1;
    while (true) {
      int b = in.read();
      if (b == -1) {
        throw new IOException("Stream ended while reading line");
      }
      if (prev == '\r' && b == '\n') {
        sb.deleteCharAt(sb.length() - 1);
        return sb.toString();
      }
      sb.append((char) b);
      prev = b;
    }
  }

  private byte[] readExact(int length) throws IOException {
    byte[] buf = new byte[length];
    int read = 0;
    while (read < length) {
      int n = in.read(buf, read, length - read);
      if (n == -1) {
        throw new IOException("Stream ended while reading bulk data");
      }
      read += n;
    }
    return buf;
  }

  private void readCrlf() throws IOException {
    int cr = in.read();
    int lf = in.read();
    if (cr != '\r' || lf != '\n') {
      throw new IOException("Expected CRLF but got: " + cr + ", " + lf);
    }
  }
}
