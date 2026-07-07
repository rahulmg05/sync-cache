package io.synccache.protocol;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RespEncoderTest {

  private String encode(RespValue value) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    new RespEncoder(out).encode(value);
    return out.toString(StandardCharsets.UTF_8);
  }

  @Test
  void encode_simpleString_producesCorrectWireFormat() throws IOException {
    String result = encode(new RespValue.SimpleString("OK"));
    assertThat(result).isEqualTo("+OK\r\n");
  }

  @Test
  void encode_errorValue_producesCorrectWireFormat() throws IOException {
    String result = encode(new RespValue.ErrorValue("ERR bad command"));
    assertThat(result).isEqualTo("-ERR bad command\r\n");
  }

  @Test
  void encode_integerValue_producesCorrectWireFormat() throws IOException {
    String result = encode(new RespValue.IntegerValue(42L));
    assertThat(result).isEqualTo(":42\r\n");
  }

  @Test
  void encode_negativeInteger_producesCorrectWireFormat() throws IOException {
    String result = encode(new RespValue.IntegerValue(-1L));
    assertThat(result).isEqualTo(":-1\r\n");
  }

  @Test
  void encode_bulkString_producesCorrectWireFormat() throws IOException {
    byte[] data = "hello".getBytes(StandardCharsets.UTF_8);
    String result = encode(new RespValue.BulkString(data));
    assertThat(result).isEqualTo("$5\r\nhello\r\n");
  }

  @Test
  void encode_nullBulkString_producesNullMarker() throws IOException {
    String result = encode(new RespValue.BulkString(null));
    assertThat(result).isEqualTo("$-1\r\n");
  }

  @Test
  void encode_emptyArray_producesCorrectWireFormat() throws IOException {
    String result = encode(new RespValue.RespArray(List.of()));
    assertThat(result).isEqualTo("*0\r\n");
  }

  @Test
  void encode_nullArray_producesNullMarker() throws IOException {
    String result = encode(new RespValue.RespArray(null));
    assertThat(result).isEqualTo("*-1\r\n");
  }

  @Test
  void encode_arrayWithElements_producesCorrectWireFormat() throws IOException {
    List<RespValue> elements = List.of(
        new RespValue.SimpleString("foo"),
        new RespValue.IntegerValue(3L)
    );
    String result = encode(new RespValue.RespArray(elements));
    assertThat(result).isEqualTo("*2\r\n+foo\r\n:3\r\n");
  }

  @Test
  void encode_decodeRoundtrip_preservesBulkString() throws IOException {
    byte[] data = "round-trip".getBytes(StandardCharsets.UTF_8);
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    new RespEncoder(out).encode(new RespValue.BulkString(data));
    RespValue decoded = new RespDecoder(
        new java.io.ByteArrayInputStream(out.toByteArray())).decode();
    assertThat(decoded).isInstanceOf(RespValue.BulkString.class);
    assertThat(((RespValue.BulkString) decoded).value()).isEqualTo(data);
  }
}
