package com.amnesica.kryptey.inputmethod.signalprotocol.storage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;


import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * An account save must be one write, not eight.
 *
 * <p>{@code storeAllInformationInSharedPreferences} used to call eight single-value setters in
 * turn, each a durable {@code commit()}. Two costs, and the second is the one that matters:
 *
 * <ul>
 *   <li>Eight fsyncs on the IME main thread, per save.
 *   <li>Eight places to stop half way. A process death or an I/O failure after the fourth left a
 *       store holding a new protocol store beside an old contact list. Reload cannot detect that -
 *       both halves are individually well-formed and parse cleanly - so the account came back
 *       subtly wrong rather than obviously broken, which is the worse of the two.
 * </ul>
 *
 * <p>These test the store layer directly rather than through {@code StorageHelper}, because the
 * property is about what reaches the delegate and how often, and that is invisible from above.
 */
public class AtomicAccountWriteTest {

  /** A delegate that records every write and how many separate batches it was handed. */
  private static final class RecordingStore implements KeyValueStore {
    final Map<String, String> written = new LinkedHashMap<>();
    int singleWrites;
    int batches;
    /** When non-zero, the Nth single write throws - standing in for an I/O failure mid-save. */
    int failSingleWriteNumber;

    @Override
    public String get(final String key) {
      return written.get(key);
    }

    @Override
    public void put(final String key, final String value) {
      singleWrites++;
      if (failSingleWriteNumber != 0 && singleWrites == failSingleWriteNumber) {
        throw new IllegalStateException("simulated I/O failure on write " + singleWrites);
      }
      written.put(key, value);
    }

    @Override
    public void putAll(final Map<String, String> entries) {
      batches++;
      written.putAll(entries);
    }

    @Override
    public void remove(final String key) {
      written.remove(key);
    }

    @Override
    public boolean contains(final String key) {
      return written.containsKey(key);
    }

    @Override
    public Set<String> keys() {
      return new HashSet<>(written.keySet());
    }
  }

  /** A box that seals by passing bytes through, and can be told to fail on a chosen value. */
  private static final class SelectiveBox implements CryptoBox {
    String failOnValueContaining;

    @Override
    public byte[] seal(final byte[] plaintext, final byte[] aad) throws StorageCryptoException {
      final String text = new String(plaintext, java.nio.charset.StandardCharsets.UTF_8);
      if (failOnValueContaining != null && text.contains(failOnValueContaining)) {
        throw new StorageCryptoException("keystore refused '" + failOnValueContaining + "'");
      }
      return plaintext;
    }

    @Override
    public byte[] open(final byte[] envelope, final byte[] aad) {
      return envelope;
    }
  }

  private RecordingStore delegate;
  private SelectiveBox box;
  private EncryptedKeyValueStore store;

  @Before
  public void setUp() {
    delegate = new RecordingStore();
    box = new SelectiveBox();
    store = new EncryptedKeyValueStore(delegate, box);
  }

  private static Map<String, String> eightValues() {
    final Map<String, String> batch = new LinkedHashMap<>();
    batch.put("METADATA_STORE", "{\"metadata\":1}");
    batch.put("UNIQUE_USER_ID", "\"alice\"");
    batch.put("PROTOCOL_STORE", "{\"identityKeyPair\":\"secret\"}");
    batch.put("PROTOCOL_ADDRESS", "{\"name\":\"alice\"}");
    batch.put("DEVICE_ID", "7");
    batch.put("UNENCRYPTED_MESSAGES", "[]");
    batch.put("CONTACTS", "[{\"first\":\"Bob\"}]");
    batch.put("DISPLAY_TAG_SECRET", "\"c2VjcmV0\"");
    return batch;
  }

  @Test
  public void awholeAccountReachesTheDelegateAsOneBatch() throws Exception {
    store.putAll(eightValues());

    assertEquals("the batch must reach the delegate as a single write", 1, delegate.batches);
    // Exactly one single write is expected and correct: the schema marker, which is a property of
    // the store rather than of the account. Asserting "zero single writes" would have been wrong,
    // and asserting "at most a few" would have hidden a regression, so it is named.
    assertEquals("only the schema marker may be written outside the batch, and it was written "
            + delegate.singleWrites + " times", 1, delegate.singleWrites);
    assertTrue("the one single write must be the schema marker, not an account value",
        delegate.written.containsKey(EncryptedKeyValueStore.SCHEMA_KEY));
    assertEquals("every account value must be present", 8, accountKeys(delegate).size());
  }

  /**
   * The failure that made the old path unsafe. Sealing happens for the whole batch before anything
   * is handed to the delegate, so a Keystore key that has been invalidated - or any value that will
   * not serialise - leaves the previous account entirely intact.
   */
  @Test
  public void afailureToSealAnyValueWritesNoneOfThem() {
    box.failOnValueContaining = "Bob";   // the CONTACTS entry, seventh of eight

    assertThrows(StorageCryptoException.class, () -> store.putAll(eightValues()));

    assertEquals("nothing may reach the delegate when any value fails to seal",
        0, delegate.batches);
    assertTrue("no partial account may be left behind: " + accountKeys(delegate),
        accountKeys(delegate).isEmpty());
  }

  /** And it must not be the ordering that saves us - a failure on the FIRST value behaves alike. */
  @Test
  public void afailureOnTheFirstValueAlsoWritesNothing() {
    box.failOnValueContaining = "metadata";

    assertThrows(StorageCryptoException.class, () -> store.putAll(eightValues()));

    assertEquals(0, delegate.batches);
    assertTrue("no account value may be written", accountKeys(delegate).isEmpty());
  }

  /** Everything the delegate holds that is an account value rather than store bookkeeping. */
  private static Set<String> accountKeys(final RecordingStore store) {
    final Set<String> keys = new HashSet<>(store.written.keySet());
    keys.remove(EncryptedKeyValueStore.SCHEMA_KEY);
    return keys;
  }

  /**
   * What the old path did, demonstrated rather than described: writing the same eight values one at
   * a time leaves seven of them behind when the eighth fails. This is the state reload could not
   * detect.
   */
  @Test
  public void theOldOneAtATimePathLeavesATornAccount() {
    delegate.failSingleWriteNumber = 8;

    final Map<String, String> values = eightValues();
    assertThrows(IllegalStateException.class, () -> {
      for (final Map.Entry<String, String> entry : values.entrySet()) {
        delegate.put(entry.getKey(), entry.getValue());
      }
    });

    assertEquals("seven values survive the failure, and nothing records that the eighth did not",
        7, delegate.written.size());
    assertNull("the contact list is the value that was lost",
        delegate.written.get("DISPLAY_TAG_SECRET"));
  }

  /** A batch must still round-trip through the encryption layer. */
  @Test
  public void thebatchIsReadableAfterwards() throws Exception {
    store.putAll(eightValues());

    assertEquals("\"alice\"", store.get("UNIQUE_USER_ID"));
    assertEquals("{\"identityKeyPair\":\"secret\"}", store.get("PROTOCOL_STORE"));
    assertEquals("[{\"first\":\"Bob\"}]", store.get("CONTACTS"));
  }

  /**
   * The default {@code putAll} on the interface is explicitly NOT atomic - it exists so other
   * implementations keep working. This pins that it still writes everything, so a store that does
   * not override it degrades in throughput rather than in correctness.
   */
  @Test
  public void thedefaultImplementationStillWritesEveryEntry() {
    final KeyValueStore plain = new KeyValueStore() {
      final Map<String, String> map = new HashMap<>();

      @Override
      public String get(String key) {
        return map.get(key);
      }

      @Override
      public void put(String key, String value) {
        map.put(key, value);
      }

      @Override
      public void remove(String key) {
        map.remove(key);
      }

      @Override
      public boolean contains(String key) {
        return map.containsKey(key);
      }

      @Override
      public Set<String> keys() {
        return new HashSet<>(map.keySet());
      }
    };

    plain.putAll(eightValues());

    final List<String> missing = new ArrayList<>();
    for (final String key : eightValues().keySet()) {
      if (!plain.contains(key)) missing.add(key);
    }
    assertTrue("the default putAll dropped " + missing, missing.isEmpty());
  }
}
