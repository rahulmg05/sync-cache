package io.synccache.protocol;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RespDecoderTest {

  private RespDecoder decoderFor(String input) {
    return new RespDecoder(new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)));
  }

  @Test
  void decode_simpleString_returnsSimpleString() throws IOException {
    RespDecoder decoder = decoderFor("+OK\r\n");
    RespValue value = decoder.decode();
    assertThat(value).isInstanceOf(RespValue.SimpleString.class);
    assertThat(((RespValue.SimpleString) value).value()).isEqualTo("OK");
  }

  @Test
  void decode_errorValue_returnsErrorValue() throws IOException {
    RespDecoder decoder = decoderFor("-ERR something went wrong\r\n");
    RespValue value = decoder.decode();
    assertThat(value).isInstanceOf(RespValue.ErrorValue.class);
    assertThat(((RespValue.ErrorValue) value).message()).isEqualTo("ERR something went wrong");
  }

  @Test
  void decode_integerValue_returnsIntegerValue() throws IOException {
    RespDecoder decoder = decoderFor(":42\r\n");
    RespValue value = decoder.decode();
    assertThat(value).isInstanceOf(RespValue.IntegerValue.class);
    assertThat(((RespValue.IntegerValue) value).value()).isEqualTo(42L);
  }

  @Test
  void decode_bulkString_returnsBulkString() throws IOException {
    RespDecoder decoder = decoderFor("$6\r\nfoobar\r\n");
    RespValue value = decoder.decode();
    assertThat(value).isInstanceOf(RespValue.BulkString.class);
    assertThat(((RespValue.BulkString) value).value()).isEqualTo("foobar".getBytes(StandardCharsets.UTF_8));
  }

  @Test
  void decode_nullBulkString_returnsBulkStringWithNullValue() throws IOException {
    RespDecoder decoder = decoderFor("$-1\r\n");
    RespValue value = decoder.decode();
    assertThat(value).isInstanceOf(RespValue.BulkString.class);
    assertThat(((RespValue.BulkString) value).value()).isNull();
  }

  @Test
  void decode_array_returnsRespArrayWithElements() throws IOException {
    RespDecoder decoder = decoderFor("*2\r\n+hello\r\n:10\r\n");
    RespValue value = decoder.decode();
    assertThat(value).isInstanceOf(RespValue.RespArray.class);
    List<RespValue> elements = ((RespValue.RespArray) value).elements();
    assertThat(elements).hasSize(2);
    assertThat(elements.get(0)).isInstanceOf(RespValue.SimpleString.class);
    assertThat(elements.get(1)).isInstanceOf(RespValue.IntegerValue.class);
  }

  @Test
  void decode_nullArray_returnsRespArrayWithNullElements() throws IOException {
    RespDecoder decoder = decoderFor("*-1\r\n");
    RespValue value = decoder.decode();
    assertThat(value).isInstanceOf(RespValue.RespArray.class);
    assertThat(((RespValue.RespArray) value).elements()).isNull();
  }

  @Test
  void decode_nestedArray_decodesRecursively() throws IOException {
    String input = "*2\r\n*2\r\n+a\r\n+b\r\n$3\r\nfoo\r\n";
    RespDecoder decoder = decoderFor(input);
    RespValue value = decoder.decode();
    assertThat(value).isInstanceOf(RespValue.RespArray.class);
    List<RespValue> outer = ((RespValue.RespArray) value).elements();
    assertThat(outer).hasSize(2);
    assertThat(outer.get(0)).isInstanceOf(RespValue.RespArray.class);
    assertThat(outer.get(1)).isInstanceOf(RespValue.BulkString.class);
  }

  @Test
  void decode_emptyStream_throwsIOException() {
    RespDecoder decoder = decoderFor("");
    assertThatThrownBy(decoder::decode).isInstanceOf(IOException.class);
  }

  @Test
  void decode_negativeLongInteger_returnsIntegerValue() throws IOException {
    RespDecoder decoder = decoderFor(":-1\r\n");
    RespValue value = decoder.decode();
    assertThat(value).isInstanceOf(RespValue.IntegerValue.class);
    assertThat(((RespValue.IntegerValue) value).value()).isEqualTo(-1L);
  }
}
