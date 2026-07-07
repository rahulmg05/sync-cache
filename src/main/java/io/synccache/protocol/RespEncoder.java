package io.synccache.protocol;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Encodes RespValue instances to RESP2 wire format and writes them to an OutputStream.
 */
public final class RespEncoder {

  private static final byte[] CRLF = {'\r', '\n'};
  private static final byte[] NULL_BULK = "$-1\r\n".getBytes(StandardCharsets.US_ASCII);
  private static final byte[] NULL_ARRAY = "*-1\r\n".getBytes(StandardCharsets.US_ASCII);

  private final OutputStream out;

  /**
   * Creates a new encoder writing to the given stream.
   *
   * @param out the output stream to write to
   */
  public RespEncoder(OutputStream out) {
    this.out = out;
  }

  /**
   * Encodes and writes a RespValue to the output stream.
   *
   * @param value the value to encode
   * @throws IOException if an I/O error occurs
   */
  public void encode(RespValue value) throws IOException {
    switch (value) {
      case RespValue.SimpleString ss -> writeSimpleString(ss);
      case RespValue.ErrorValue ev -> writeError(ev);
      case RespValue.IntegerValue iv -> writeInteger(iv);
      case RespValue.BulkString bs -> writeBulkString(bs);
      case RespValue.RespArray ra -> writeArray(ra);
    }
    out.flush();
  }

  private void writeSimpleString(RespValue.SimpleString ss) throws IOException {
    out.write('+');
    out.write(ss.value().getBytes(StandardCharsets.UTF_8));
    out.write(CRLF);
  }

  private void writeError(RespValue.ErrorValue ev) throws IOException {
    out.write('-');
    out.write(ev.message().getBytes(StandardCharsets.UTF_8));
    out.write(CRLF);
  }

  private void writeInteger(RespValue.IntegerValue iv) throws IOException {
    out.write(':');
    out.write(Long.toString(iv.value()).getBytes(StandardCharsets.US_ASCII));
    out.write(CRLF);
  }

  private void writeBulkString(RespValue.BulkString bs) throws IOException {
    if (bs.value() == null) {
      out.write(NULL_BULK);
      return;
    }
    out.write('$');
    out.write(Integer.toString(bs.value().length).getBytes(StandardCharsets.US_ASCII));
    out.write(CRLF);
    out.write(bs.value());
    out.write(CRLF);
  }

  private void writeArray(RespValue.RespArray ra) throws IOException {
    if (ra.elements() == null) {
      out.write(NULL_ARRAY);
      return;
    }
    out.write('*');
    out.write(Integer.toString(ra.elements().size()).getBytes(StandardCharsets.US_ASCII));
    out.write(CRLF);
    for (RespValue element : ra.elements()) {
      encode(element);
    }
  }
}
