package io.synccache.store;

import io.synccache.config.ServerConfig;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StoreTest {

  private static final int MAX_KEYS = 5;
  private static final int MAX_KEY_SIZE = 10;
  private static final int MAX_VALUE_SIZE = 20;
  private static final long MAX_VALUE_SIZE_LONG = MAX_VALUE_SIZE;

  private Store store;

  @BeforeEach
  void setUp() {
    store = new Store(new ServerConfig(6379, MAX_KEYS, MAX_KEY_SIZE, MAX_VALUE_SIZE_LONG));
  }

  @Test
  void set_whenKeyAndValueAreValid_storesValue() {
    store.set("key", new StringValue("hello".getBytes(StandardCharsets.UTF_8), StringValue.NO_EXPIRY));
    Optional<TypedValue> result = store.get("key");
    assertThat(result).isPresent();
    assertThat(result.get()).isInstanceOf(StringValue.class);
    assertThat(((StringValue) result.get()).data()).isEqualTo("hello".getBytes(StandardCharsets.UTF_8));
  }

  @Test
  void get_whenKeyDoesNotExist_returnsEmpty() {
    Optional<TypedValue> result = store.get("missing");
    assertThat(result).isEmpty();
  }

  @Test
  void set_whenKeyExceedsMaxKeySize_throwsStoreLimitException() {
    String longKey = "this-is-too-long-key";
    assertThatThrownBy(() ->
        store.set(longKey, new StringValue("v".getBytes(StandardCharsets.UTF_8), StringValue.NO_EXPIRY))
    ).isInstanceOf(StoreLimitException.class);
  }

  @Test
  void set_whenValueExceedsMaxValueSize_throwsStoreLimitException() {
    byte[] bigValue = new byte[MAX_VALUE_SIZE + 1];
    assertThatThrownBy(() ->
        store.set("key", new StringValue(bigValue, StringValue.NO_EXPIRY))
    ).isInstanceOf(StoreLimitException.class);
  }

  @Test
  void set_whenMaxKeysReached_throwsStoreLimitException() {
    for (int i = 0; i < MAX_KEYS; i++) {
      store.set("k" + i, new StringValue("v".getBytes(StandardCharsets.UTF_8), StringValue.NO_EXPIRY));
    }
    assertThatThrownBy(() ->
        store.set("overflow", new StringValue("v".getBytes(StandardCharsets.UTF_8), StringValue.NO_EXPIRY))
    ).isInstanceOf(StoreLimitException.class);
  }

  @Test
  void delete_whenKeyExists_removesKeyAndReturnsTrue() {
    store.set("key", new StringValue("v".getBytes(StandardCharsets.UTF_8), StringValue.NO_EXPIRY));
    boolean deleted = store.delete("key");
    assertThat(deleted).isTrue();
    assertThat(store.exists("key")).isFalse();
  }

  @Test
  void delete_whenKeyDoesNotExist_returnsFalse() {
    assertThat(store.delete("missing")).isFalse();
  }

  @Test
  void exists_whenKeyPresent_returnsTrue() {
    store.set("key", new StringValue("v".getBytes(StandardCharsets.UTF_8), StringValue.NO_EXPIRY));
    assertThat(store.exists("key")).isTrue();
  }

  @Test
  void exists_whenKeyAbsent_returnsFalse() {
    assertThat(store.exists("missing")).isFalse();
  }

  @Test
  void size_reflectsActualKeyCount() {
    assertThat(store.size()).isEqualTo(0);
    store.set("a", new StringValue("1".getBytes(StandardCharsets.UTF_8), StringValue.NO_EXPIRY));
    store.set("b", new StringValue("2".getBytes(StandardCharsets.UTF_8), StringValue.NO_EXPIRY));
    assertThat(store.size()).isEqualTo(2);
  }

  @Test
  void flush_removesAllKeys() {
    store.set("a", new StringValue("1".getBytes(StandardCharsets.UTF_8), StringValue.NO_EXPIRY));
    store.set("b", new StringValue("2".getBytes(StandardCharsets.UTF_8), StringValue.NO_EXPIRY));
    store.flush();
    assertThat(store.size()).isEqualTo(0);
  }

  @Test
  void type_whenKeyHoldsStringValue_returnsString() {
    store.set("key", new StringValue("v".getBytes(StandardCharsets.UTF_8), StringValue.NO_EXPIRY));
    assertThat(store.type("key")).isEqualTo("string");
  }

  @Test
  void type_whenKeyAbsent_returnsNone() {
    assertThat(store.type("missing")).isEqualTo("none");
  }

  @Test
  void set_whenUpdatingExistingKey_doesNotCountAgainstMaxKeys() {
    for (int i = 0; i < MAX_KEYS; i++) {
      store.set("k" + i, new StringValue("v".getBytes(StandardCharsets.UTF_8), StringValue.NO_EXPIRY));
    }
    // Updating an existing key must not throw even at max capacity
    store.set("k0", new StringValue("updated".getBytes(StandardCharsets.UTF_8), StringValue.NO_EXPIRY));
    assertThat(store.get("k0")).isPresent();
  }
}
