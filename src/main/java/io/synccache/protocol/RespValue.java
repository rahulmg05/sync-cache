package io.synccache.protocol;

import java.util.List;

/**
 * Sealed interface representing a RESP2 value.
 * Each variant corresponds to one of the five RESP2 types.
 */
public sealed interface RespValue
    permits RespValue.SimpleString,
            RespValue.ErrorValue,
            RespValue.IntegerValue,
            RespValue.BulkString,
            RespValue.RespArray {

  /** A simple string value ('+' prefix in RESP2). */
  record SimpleString(String value) implements RespValue {}

  /** An error value ('-' prefix in RESP2). */
  record ErrorValue(String message) implements RespValue {}

  /** An integer value (':' prefix in RESP2). */
  record IntegerValue(long value) implements RespValue {}

  /**
   * A bulk string value ('$' prefix in RESP2).
   * A null {@code value} represents the null bulk string ($-1\r\n).
   */
  record BulkString(byte[] value) implements RespValue {}

  /**
   * An array value ('*' prefix in RESP2).
   * A null {@code elements} list represents the null array (*-1\r\n).
   */
  record RespArray(List<RespValue> elements) implements RespValue {}
}
