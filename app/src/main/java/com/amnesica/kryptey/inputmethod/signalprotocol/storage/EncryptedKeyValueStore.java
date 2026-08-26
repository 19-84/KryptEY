package com.amnesica.kryptey.inputmethod.signalprotocol.storage;

import com.amnesica.kryptey.inputmethod.signalprotocol.util.Base64;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Wraps a {@link KeyValueStore} so every value is stored as a {@link CryptoBox} envelope, and owns
 * the one-time migration of a KryptEY 0.1.5 install whose protocol store — identity private key
 * included — sits in SharedPreferences as cleartext JSON.
 *
 * <p>Two properties this class must hold, both learned from getting them wrong:
 *
 * <ul>
 *   <li><b>Never launder cleartext into ciphertext.</b> The schema marker is an unauthenticated
 *       cleartext row. An attacker with filesystem write access could delete it and substitute
 *       attacker-authored JSON — say an identity store pinning their own key — and a purely
 *       marker-driven migration would seal that under the legitimate Keystore key, after which the
 *       app reads it back as authentic. So cleartext is accepted only when nothing in the store
 *       actually decrypts.
 *   <li><b>Never skip a value we cannot read.</b> Deciding "already converted" from the shape of the
 *       bytes is a guess. An envelope sealed under a master key that has since been lost looks
 *       exactly like one sealed under the current key; skipping it and then marking the store clean
 *       orphans the identity permanently. Membership is decided by trial decryption.
 * </ul>
 */
public final class EncryptedKeyValueStore {

  static final String SCHEMA_KEY = "__kryptey_storage_schema";
  static final String SCHEMA_ENCRYPTED = "2";

  /**
   * Marker payloads. Both are stored <em>sealed</em>, so their presence is unforgeable: an attacker
   * can delete a marker but cannot manufacture one, and deletion fails safe.
   *
   * <p>Three states, which is what lets a legitimate interrupted migration be told apart from an
   * attacker who substituted cleartext into an already-migrated store. Structurally those look
   * identical — some envelopes, some cleartext — so the distinction has to be recorded, and
   * recorded in a way that cannot be faked.
   *
   * <ul>
   *   <li>no marker → legacy: every value is 0.1.5 cleartext
   *   <li>{@code MIGRATING} → conversion started; a mix of envelopes and cleartext is expected
   *   <li>{@code COMPLETE} → conversion finished; cleartext here is never legitimate
   * </ul>
   */
  private static final String MARKER_MIGRATING = "kryptey-storage-migrating-v" + SCHEMA_ENCRYPTED;
  private static final String MARKER_COMPLETE = "kryptey-storage-complete-v" + SCHEMA_ENCRYPTED;

  private final KeyValueStore delegate;
  private final CryptoBox cryptoBox;

  public EncryptedKeyValueStore(final KeyValueStore delegate, final CryptoBox cryptoBox) {
    this.delegate = delegate;
    this.cryptoBox = cryptoBox;
  }

  /**
   * Whether {@code store} carries a schema marker at all.
   *
   * <p>Cannot verify it without the master key, so this only answers "has this store ever been
   * touched by the encrypted schema". It is the marker half of {@link #hasEncryptedData(
   * KeyValueStore)} and not a substitute for it: a store whose marker never reached disk, or whose
   * marker an attacker deleted, still holds the user's identity as ciphertext and answers false
   * here. Callers deciding whether data is at stake must call {@code hasEncryptedData}.
   */
  public static boolean isEncrypted(final KeyValueStore store) {
    return store.get(SCHEMA_KEY) != null;
  }

  /**
   * True when the store holds anything a freshly generated master key could not read.
   *
   * <p>Looks for envelope-shaped values as well as the marker: an interrupted migration leaves real
   * ciphertext with the marker deliberately unset, and concluding "nothing at stake" there would let
   * a Keystore-backed box mint a replacement key and orphan what had already been converted.
   */
  public boolean hasEncryptedData() {
    return hasEncryptedData(delegate);
  }

  /**
   * The same question, asked of a raw store before any {@link CryptoBox} exists.
   *
   * <p>Needs no key: the marker check is a presence test and {@link #looksLikeEnvelope} is
   * structural. That is what lets {@code StorageHelper} ask it at the one point where the answer
   * matters - it has to decide what to tell the Keystore box <em>while constructing</em> the box,
   * so an instance method on a store that already holds one is too late.
   */
  public static boolean hasEncryptedData(final KeyValueStore store) {
    if (isEncrypted(store)) return true;
    for (final String key : payloadKeys(store)) {
      final String value = store.get(key);
      if (value != null && looksLikeEnvelope(value)) return true;
    }
    return false;
  }

  /** True when the store still holds unencrypted 0.1.5-era values. */
  public boolean needsMigration() {
    if (MARKER_COMPLETE.equals(readMarker())) return false;
    return !payloadKeys().isEmpty();
  }

  /** The sealed marker payload, or null if absent or unreadable under the current key. */
  private String readMarker() {
    final String raw = delegate.get(SCHEMA_KEY);
    if (raw == null) return null;
    try {
      return decode(SCHEMA_KEY, raw);
    } catch (StorageCryptoException e) {
      return null;
    }
  }

  private void writeMarker(final String payload) throws StorageCryptoException {
    delegate.put(SCHEMA_KEY, encode(SCHEMA_KEY, payload));
  }

  /**
   * Converts every legacy cleartext value to an envelope.
   *
   * <p>Classifies the whole store before writing anything, so an inconsistent state aborts instead
   * of being half-committed. Values are then sealed one at a time and read back, and only once all
   * of them are confirmed on disk is the schema marker written — a crash before that leaves an
   * unmarked, resumable store rather than a marked, unreadable one.
   */
  public void migrateToEncrypted() throws StorageCryptoException {
    try {
      migrateToEncryptedInternal();
    } catch (RuntimeException e) {
      // The delegate signals a failed durable write with an unchecked exception. Converting it here
      // keeps it inside the checked type callers already handle, instead of propagating out through
      // StorageHelper and LatinIME.setInputView() and crashing the keyboard on every raise.
      throw new StorageCryptoException("storage write failed during migration", e);
    }
  }

  /**
   * Prepares a store that has never legitimately held cleartext, refusing anything that has.
   *
   * <p>For a store created after encryption existed, the cleartext migration is not a no-op that
   * happens to be harmless — it is an oracle. It seals whatever key names it finds under the real
   * master key, and the AAD binds the format version and the key NAME, not which file the value
   * came from. So an attacker with write access to the app's data directory could put a
   * cleartext {@code PROTOCOL_STORE} of their own authorship into such a file, let this method seal
   * it, then copy the sealed value into the account's file, where it opens correctly and loads as
   * the user's identity. The anti-laundering check in {@code migrateToEncryptedInternal} does not
   * stop it: that fires only when the SAME file also holds a decryptable envelope, and a file
   * holding one payload key is one the attacker can simply empty first.
   *
   * <p>So a store like that never runs the migration. It only ever has to survive the one gap a
   * legitimate write can leave — {@code put} writes the value and then the marker as two commits,
   * so a kill between them leaves an envelope with no marker, which {@code get} already reads
   * correctly. Everything else is refused.
   *
   * @param permittedKeys the only payload keys this store is allowed to contain.
   */
  public void requireEncryptedOnly(final Set<String> permittedKeys) throws StorageCryptoException {
    for (final String key : payloadKeys()) {
      if (!permittedKeys.contains(key)) {
        throw new StorageCryptoException("unexpected key '" + key + "' in a store that may hold "
            + "only " + permittedKeys + "; refusing to touch it");
      }
      final String raw = delegate.get(key);
      if (raw == null) continue;
      if (!looksLikeEnvelope(raw)) {
        throw new StorageCryptoException("'" + key + "' is cleartext in a store that has never "
            + "legitimately held any; refusing to seal it");
      }
      if (!canDecrypt(key, raw)) {
        throw new StorageCryptoException("'" + key + "' cannot be decrypted with the current "
            + "master key; refusing to migrate around it");
      }
    }
  }

  private void migrateToEncryptedInternal() throws StorageCryptoException {
    final String marker = readMarker();
    if (delegate.get(SCHEMA_KEY) != null && marker == null) {
      // A marker exists but will not open: the master key changed under us. Converting anything now
      // would seal it under a key unrelated to the existing envelopes.
      throw new StorageCryptoException(
          "schema marker cannot be decrypted with the current master key; refusing to migrate");
    }

    final Set<String> cleartext = new HashSet<>();
    boolean sawReadableEnvelope = false;

    for (final String key : payloadKeys()) {
      final String value = delegate.get(key);
      if (value == null) continue;

      if (looksLikeEnvelope(value)) {
        if (canDecrypt(key, value)) {
          sawReadableEnvelope = true; // converted by an earlier, interrupted run
        } else {
          // Sealed under a key we no longer hold. Migrating around it and marking the store clean
          // would orphan this value forever, so refuse and leave everything as it is.
          throw new StorageCryptoException("'" + key + "' is an envelope that cannot be decrypted "
              + "with the current master key; refusing to migrate around it");
        }
      } else {
        cleartext.add(key);
      }
    }

    // Asked before either in-file guard, because both of those live in the file the attacker
    // rewrites and a sweep showed both fall to the same move.
    //
    // The guard below refuses cleartext that sits BESIDE decryptable data - so the attacker empties
    // the file first, which costs them nothing: they snapshot it, let one raise seal their row, and
    // restore the snapshot with that row swapped in. The AAD binds the key name, unchanged in both
    // states, so it opens. And MARKER_MIGRATING is durable and invalidated only by a SUCCESSFUL
    // migration, so it can be harvested from a deliberately interrupted run and replayed to disarm
    // the guard without emptying anything at all.
    //
    // This one is answered by the Keystore instead. Once a device has converted a cleartext store
    // once - or started life with no cleartext at all - it never converts one again.
    if (!cleartext.isEmpty() && cryptoBox.legacyMigrationIsSealed()) {
      throw new StorageCryptoException("refusing to encrypt cleartext (" + cleartext
          + "): this device has already completed the one-time conversion, so cleartext here was "
          + "written by something other than an upgrade");
    }

    if (sawReadableEnvelope && !cleartext.isEmpty() && !MARKER_MIGRATING.equals(marker)) {
      // Decryptable envelopes alongside cleartext, with no in-progress marker to explain it. A
      // genuine interrupted migration always leaves that marker behind, and the marker is sealed so
      // it cannot be forged - only deleted, which lands here and fails safe.
      throw new StorageCryptoException("refusing to encrypt cleartext (" + cleartext
          + ") in a store that already contains decryptable data; possible tampering");
    }

    if (!cleartext.isEmpty() && !MARKER_MIGRATING.equals(marker)) {
      // Record that a conversion is under way before touching any value, so that if this dies
      // half-done the next run can tell a legitimate resume from a substitution.
      writeMarker(MARKER_MIGRATING);
    }

    for (final String key : cleartext) {
      final String sealed = encode(key, delegate.get(key));
      delegate.put(key, sealed);

      // Read back before continuing. A storage layer can fail a write without saying so, and
      // marking the store encrypted over surviving cleartext is unrecoverable.
      if (!sealed.equals(delegate.get(key))) {
        throw new StorageCryptoException("migration write for '" + key + "' did not persist; "
            + "refusing to mark the store encrypted while cleartext may remain on disk");
      }
    }

    writeMarker(MARKER_COMPLETE);
    // Outside the file, so restoring an older copy of it cannot un-say this. Also reached on a
    // fresh install, where `cleartext` is empty - which closes the window entirely for anyone who
    // never had a 0.1.5 store to convert.
    cryptoBox.sealLegacyMigration();
  }

  public String get(final String key) throws StorageCryptoException {
    final String raw = delegate.get(key);
    if (raw == null) return null;

    final String marker = readMarker();

    if (MARKER_COMPLETE.equals(marker)) {
      // Migration finished: there is no legitimate cleartext left, so never fall back to returning
      // the raw value. Doing so would hand injected JSON straight to the deserializer.
      return decode(key, raw);
    }

    if (looksLikeEnvelope(raw)) return decode(key, raw);

    // Cleartext. Legitimate only in the legacy state, or mid-migration where the sealed in-progress
    // marker vouches for the mix.
    if (marker == null && containsReadableEnvelope()) {
      throw new StorageCryptoException("'" + key + "' is cleartext in a store that already "
          + "contains decryptable data; refusing to read it");
    }
    return raw;
  }

  public void put(final String key, final String value) throws StorageCryptoException {
    try {
      delegate.put(key, encode(key, value));
      if (!MARKER_COMPLETE.equals(readMarker())) {
        writeMarker(MARKER_COMPLETE);
      }
    } catch (RuntimeException e) {
      throw new StorageCryptoException("storage write failed for '" + key + "'", e);
    }
  }

  /**
   * Seal everything first, write once.
   *
   * <p>Two separate properties, and both matter. Sealing the whole batch before touching the
   * delegate means a failure part-way - a Keystore key that has been invalidated, a value that
   * would not serialise - writes nothing at all, rather than leaving the first few entries updated
   * beside the rest at their old values. Handing the delegate one map lets it commit once, so
   * process death cannot land in the middle either.
   *
   * <p>Before this, an account save was eight independent durable commits. A tear between them
   * produced a store holding a new protocol store beside an old contact list, with nothing to
   * indicate the two no longer described the same moment.
   */
  public void putAll(final Map<String, String> entries) throws StorageCryptoException {
    final Map<String, String> sealed = new LinkedHashMap<>(entries.size());
    for (final Map.Entry<String, String> entry : entries.entrySet()) {
      // Outside the try below: a failure here must surface as itself, and must happen before any
      // part of the batch has been handed to the delegate.
      sealed.put(entry.getKey(), encode(entry.getKey(), entry.getValue()));
    }

    try {
      delegate.putAll(sealed);
      if (!MARKER_COMPLETE.equals(readMarker())) {
        writeMarker(MARKER_COMPLETE);
      }
    } catch (RuntimeException e) {
      throw new StorageCryptoException("storage write failed for " + entries.size() + " entries", e);
    }
  }

  /**
   * AAD for a value: the format version plus the storage key it belongs to. Binding the key name is
   * what stops a valid envelope being moved between preference keys — {@code CONTACTS} and
   * {@code UNENCRYPTED_MESSAGES} are both plain {@code ArrayList} and Jackson is configured not to
   * fail on unknown properties, so a transplant would otherwise deserialize cleanly and silently
   * erase one of them.
   */
  private static byte[] aad(final String key) {
    return ("kryptey/storage/v" + SCHEMA_ENCRYPTED + "/" + key).getBytes(StandardCharsets.UTF_8);
  }

  private String encode(final String key, final String value) throws StorageCryptoException {
    if (value == null) {
      // JsonUtil.toJson returns null when serialization fails. Without this the resulting NPE would
      // escape through StorageHelper's write path and out of LatinIME.setInputView().
      throw new StorageCryptoException("refusing to store a null value for '" + key + "'");
    }
    return Base64.encodeBytes(cryptoBox.seal(value.getBytes(StandardCharsets.UTF_8), aad(key)));
  }

  private String decode(final String key, final String raw) throws StorageCryptoException {
    final byte[] bytes;
    try {
      bytes = Base64.decode(raw);
    } catch (IOException | RuntimeException e) {
      // Base64.decode throws IllegalArgumentException on short or malformed input, not only
      // IOException; converting here keeps unchecked failures from reaching setInputView().
      throw new StorageCryptoException("stored value is not valid base64", e);
    }
    return new String(cryptoBox.open(bytes, aad(key)), StandardCharsets.UTF_8);
  }

  private boolean canDecrypt(final String key, final String raw) {
    try {
      decode(key, raw);
      return true;
    } catch (StorageCryptoException e) {
      return false;
    }
  }

  private boolean containsReadableEnvelope() {
    for (final String key : payloadKeys()) {
      final String value = delegate.get(key);
      if (value != null && looksLikeEnvelope(value) && canDecrypt(key, value)) return true;
    }
    return false;
  }

  /**
   * Cheap structural pre-filter, used only to decide whether trial decryption is worth attempting.
   * Never load-bearing on its own — a false positive costs one failed decrypt, not a wrong decision.
   */
  private static boolean looksLikeEnvelope(final String raw) {
    try {
      final byte[] bytes = Base64.decode(raw);
      return bytes.length > 1 + GcmCryptoBox.NONCE_BYTES && bytes[0] == GcmCryptoBox.VERSION;
    } catch (IOException | RuntimeException e) {
      return false;
    }
  }

  private Set<String> payloadKeys() {
    return payloadKeys(delegate);
  }

  private static Set<String> payloadKeys(final KeyValueStore store) {
    final Set<String> keys = new HashSet<>(store.keys());
    keys.remove(SCHEMA_KEY);
    return keys;
  }
}
