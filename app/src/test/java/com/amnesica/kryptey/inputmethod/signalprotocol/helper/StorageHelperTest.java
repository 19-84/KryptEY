package com.amnesica.kryptey.inputmethod.signalprotocol.helper;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;

import com.amnesica.kryptey.inputmethod.signalprotocol.Account;
import com.amnesica.kryptey.inputmethod.signalprotocol.ProtocolIdentifier;
import com.amnesica.kryptey.inputmethod.signalprotocol.storage.CryptoBox;
import com.amnesica.kryptey.inputmethod.signalprotocol.storage.GcmCryptoBox;
import com.amnesica.kryptey.inputmethod.signalprotocol.storage.StorageCryptoException;
import com.amnesica.kryptey.inputmethod.signalprotocol.stores.PreKeyMetadataStoreImpl;
import com.amnesica.kryptey.inputmethod.signalprotocol.stores.SignalProtocolStoreImpl;
import com.amnesica.kryptey.inputmethod.signalprotocol.util.JsonUtil;
import com.amnesica.kryptey.inputmethod.signalprotocol.util.KeyUtil;
import com.amnesica.kryptey.inputmethod.signalprotocol.util.ProtocolAddresses;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.signal.libsignal.protocol.IdentityKeyPair;
import org.signal.libsignal.protocol.SignalProtocolAddress;

import java.security.NoSuchAlgorithmException;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import com.amnesica.kryptey.inputmethod.signalprotocol.chat.Contact;

/**
 * Covers the class that decides whether a user still has an identity.
 *
 * <p>Every failure mode here is one that previously presented as either an NPE thrown out of
 * {@code LatinIME.setInputView()} — crashing the keyboard on every raise — or as a silent overwrite
 * of the user's keys. None of it was under test before, because the class hardcoded an Android
 * Keystore dependency; it now takes a {@code CryptoBoxFactory} so the key source can be swapped.
 */
@RunWith(RobolectricTestRunner.class)
public class StorageHelperTest {

  private Context context;
  private SharedPreferences preferences;
  private SecretKey key;

  /** A working in-process box, standing in for the Keystore-backed one. */
  private StorageHelper.CryptoBoxFactory workingBox() {
    return (ctx, hasExistingData) -> new GcmCryptoBox() {
      @Override
      protected SecretKey key() {
        return key;
      }
    };
  }

  /** A box that refuses everything, standing in for a lost or unusable Keystore key. */
  private StorageHelper.CryptoBoxFactory brokenBox() {
    return (ctx, hasExistingData) -> new CryptoBox() {
      @Override
      public byte[] seal(byte[] plaintext, byte[] aad) throws StorageCryptoException {
        throw new StorageCryptoException("keystore unavailable");
      }

      @Override
      public byte[] open(byte[] envelope, byte[] aad) throws StorageCryptoException {
        throw new StorageCryptoException("keystore unavailable");
      }
    };
  }

  private static SecretKey newKey() {
    try {
      final KeyGenerator g = KeyGenerator.getInstance("AES");
      g.init(256);
      return g.generateKey();
    } catch (NoSuchAlgorithmException e) {
      throw new AssertionError(e);
    }
  }

  @Before
  public void setUp() {
    context = RuntimeEnvironment.getApplication();
    preferences = context.getSharedPreferences("protocol", Context.MODE_PRIVATE);
    preferences.edit().clear().commit();
    key = newKey();
  }

  private Account newAccount() {
    final IdentityKeyPair identityKeyPair = KeyUtil.generateIdentityKeyPair();
    final int registrationId = KeyUtil.generateRegistrationId();
    final PreKeyMetadataStoreImpl metadata = new PreKeyMetadataStoreImpl();
    final SignalProtocolStoreImpl protocolStore =
        new SignalProtocolStoreImpl(identityKeyPair, registrationId);
    KeyUtil.generateAndStoreOneTimePreKeys(protocolStore, metadata);
    final var signedPreKey = KeyUtil.generateAndStoreSignedPreKey(protocolStore, metadata);
    metadata.setActiveSignedPreKeyId(signedPreKey.getId());
    metadata.setSignedPreKeyRegistered(true);
    // libsignal 0.86 rejects device ids outside [1, 127].
    final int deviceId = ProtocolAddresses.generateDeviceId();
    return new Account("alice", deviceId, identityKeyPair, metadata, protocolStore,
        new SignalProtocolAddress("alice", deviceId));
  }

  // ------------------------------------------------- existing-identity detection

  @Test
  public void reportsNoExistingDataOnAFreshInstall() {
    assertFalse(new StorageHelper(context, workingBox()).hasExistingProtocolData());
  }

  /**
   * The guard that stops {@code initialize()} generating a new identity over an existing one. It
   * must key off the protocol store itself, not off a "first run" flag kept in a different, and
   * independently losable, preferences file.
   */
  @Test
  public void reportsExistingDataOnceAnAccountIsStored() {
    final StorageHelper helper = new StorageHelper(context, workingBox());
    helper.storeAllInformationInSharedPreferences(newAccount());
    assertTrue(helper.hasExistingProtocolData());
  }

  /** Still true when the data cannot be decrypted — an unreadable identity is still an identity. */
  @Test
  public void reportsExistingDataEvenWhenItCannotBeDecrypted() {
    new StorageHelper(context, workingBox()).storeAllInformationInSharedPreferences(newAccount());
    assertTrue("an undecryptable store must still count as existing data",
        new StorageHelper(context, brokenBox()).hasExistingProtocolData());
  }

  // -------------------------------------------------------------- round tripping

  @Test
  public void storesAndReloadsAnAccount() {
    final Account original = newAccount();
    final StorageHelper helper = new StorageHelper(context, workingBox());
    helper.storeAllInformationInSharedPreferences(original);

    final Account reloaded = new StorageHelper(context, workingBox())
        .getAccountFromSharedPreferences();

    assertNotNull(reloaded);
    assertEquals(original.getName(), reloaded.getName());
    assertEquals(original.getSignalProtocolStore().getLocalRegistrationId(),
        reloaded.getSignalProtocolStore().getLocalRegistrationId());
    assertEquals(
        JsonUtil.toJson(original.getSignalProtocolStore().getIdentityKeyPair()),
        JsonUtil.toJson(reloaded.getSignalProtocolStore().getIdentityKeyPair()));
  }

  @Test
  public void nothingCleartextReachesPreferences() {
    new StorageHelper(context, workingBox()).storeAllInformationInSharedPreferences(newAccount());

    for (final String k : preferences.getAll().keySet()) {
      final Object v = preferences.getAll().get(k);
      if (!(v instanceof String)) continue;
      final String s = (String) v;
      assertFalse("cleartext JSON reached preferences under " + k,
          s.startsWith("{") || s.startsWith("[") || s.contains("identityKeyStore"));
    }
  }

  // ------------------------------------------------------------ failure handling

  /**
   * The original crash: a storage read failure became {@code null}, the {@code null} was
   * dereferenced, and the NPE propagated out of {@code setInputView()}.
   */
  @Test
  public void returnsNullRatherThanThrowingWhenNothingIsStored() {
    assertNull(new StorageHelper(context, workingBox()).getAccountFromSharedPreferences());
  }

  @Test
  public void returnsNullRatherThanThrowingWhenTheKeyIsGone() {
    new StorageHelper(context, workingBox()).storeAllInformationInSharedPreferences(newAccount());
    assertNull("an unreadable store must not crash the load",
        new StorageHelper(context, brokenBox()).getAccountFromSharedPreferences());
  }

  @Test
  public void returnsNullRatherThanThrowingOnCorruptedValues() {
    new StorageHelper(context, workingBox()).storeAllInformationInSharedPreferences(newAccount());
    preferences.edit()
        .putString(String.valueOf(ProtocolIdentifier.PROTOCOL_STORE), "!!! not base64 !!!")
        .commit();

    assertNull(new StorageHelper(context, workingBox()).getAccountFromSharedPreferences());
  }

  /**
   * A failed write must not fall back to cleartext. Losing a write is recoverable; writing the
   * identity private key to disk unencrypted is not.
   */
  @Test
  public void aFailedSealWritesNothingRatherThanCleartext() {
    new StorageHelper(context, brokenBox()).storeAllInformationInSharedPreferences(newAccount());

    // The "writes nothing" half, which nothing asserted.
    //
    // putAll seals the whole batch before touching the delegate, so a broken box means the loop
    // below runs over an EMPTY set of values - it can no longer catch a cleartext write because
    // there is nothing to inspect. The test kept its name and lost its content when the write path
    // became atomic.
    final Set<String> written = new HashSet<>();
    for (final String key : preferences.getAll().keySet()) {
      if (!key.startsWith("__kryptey")) written.add(key);
    }
    assertTrue("a failed seal must write nothing at all, and wrote " + written, written.isEmpty());

    for (final String k : preferences.getAll().keySet()) {
      final Object v = preferences.getAll().get(k);
      if (!(v instanceof String)) continue;
      assertFalse("cleartext written after a seal failure under " + k,
          ((String) v).contains("identityKeyStore"));
    }
  }

  /**
   * A single unreadable contacts or messages value must not turn into the literal string "null"
   * being written back over the user's entire history.
   */
  @Test
  public void aFailedContactsReadDoesNotEraseTheAccount() {
    final Account original = newAccount();
    final StorageHelper helper = new StorageHelper(context, workingBox());
    helper.storeAllInformationInSharedPreferences(original);

    preferences.edit()
        .putString(String.valueOf(ProtocolIdentifier.CONTACTS), "!!! not base64 !!!")
        .commit();

    final Account reloaded = new StorageHelper(context, workingBox())
        .getAccountFromSharedPreferences();

    assertNotNull("a bad contacts value must not sink the whole account", reloaded);
    assertNotNull("contacts must fall back to empty, never null", reloaded.getContactList());
    assertTrue(reloaded.getContactList().isEmpty());

    // Writing back must not persist the string "null" over the stored history.
    new StorageHelper(context, workingBox()).storeAllInformationInSharedPreferences(reloaded);
    final String stored = preferences.getString(String.valueOf(ProtocolIdentifier.CONTACTS), null);
    assertNotNull(stored);
    assertFalse("the literal string \"null\" was persisted", "null".equals(stored));
  }

  // ------------------------------------------------------------------- migration

  /** A 0.1.5 preferences file is converted in place on first access. */
  @Test
  public void migratesLegacyCleartextOnFirstAccess() {
    final Account original = newAccount();
    // Write the 0.1.5 way: cleartext JSON, no schema marker.
    preferences.edit()
        .putString(String.valueOf(ProtocolIdentifier.UNIQUE_USER_ID),
            JsonUtil.toJson(original.getName()))
        .putString(String.valueOf(ProtocolIdentifier.PROTOCOL_STORE),
            JsonUtil.toJson(original.getSignalProtocolStore()))
        .putString(String.valueOf(ProtocolIdentifier.METADATA_STORE),
            JsonUtil.toJson(original.getMetadataStore()))
        .putString(String.valueOf(ProtocolIdentifier.PROTOCOL_ADDRESS),
            JsonUtil.toJson(original.getSignalProtocolAddress()))
        .putString(String.valueOf(ProtocolIdentifier.CONTACTS), "[]")
        .putString(String.valueOf(ProtocolIdentifier.UNENCRYPTED_MESSAGES), "[]")
        .commit();

    final Account reloaded = new StorageHelper(context, workingBox())
        .getAccountFromSharedPreferences();

    assertNotNull("legacy account failed to load", reloaded);
    assertEquals(original.getName(), reloaded.getName());
    assertEquals(
        JsonUtil.toJson(original.getSignalProtocolStore().getIdentityKeyPair()),
        JsonUtil.toJson(reloaded.getSignalProtocolStore().getIdentityKeyPair()));

    final String onDisk = preferences.getString(
        String.valueOf(ProtocolIdentifier.PROTOCOL_STORE), null);
    assertFalse("legacy cleartext survived the migration", onDisk.startsWith("{"));
  }

  // ------------------------------------------------- partial-read failure modes

  /**
   * A partial read must abort the load, not produce a half-built account.
   *
   * <p>The guard is {@code metadataStore == null || address == null}. Weakening the {@code ||} to
   * {@code &&} survived the whole suite, because every existing test either stores everything or
   * stores nothing — the case where exactly one of the two is missing was never constructed. With
   * {@code &&} the load proceeds carrying a null field, which surfaces later as an NPE out of
   * {@code setInputView()}, i.e. a keyboard that crashes on every raise.
   */
  @Test
  public void aMissingMetadataStoreAloneAbortsTheLoad() {
    final StorageHelper helper = new StorageHelper(context, workingBox());
    helper.storeAllInformationInSharedPreferences(newAccount());
    preferences.edit().remove(ProtocolIdentifier.METADATA_STORE.toString()).commit();

    assertNull("a load missing only the metadata store must abort, not half-build an account",
        new StorageHelper(context, workingBox()).getAccountFromSharedPreferences());
  }

  @Test
  public void aMissingProtocolAddressAloneAbortsTheLoad() {
    final StorageHelper helper = new StorageHelper(context, workingBox());
    helper.storeAllInformationInSharedPreferences(newAccount());
    preferences.edit().remove(ProtocolIdentifier.PROTOCOL_ADDRESS.toString()).commit();

    assertNull("a load missing only the protocol address must abort",
        new StorageHelper(context, workingBox()).getAccountFromSharedPreferences());
  }

  /**
   * A successful read of the message history must be kept.
   *
   * <p>Inverting the null check replaces a perfectly good list with an empty one, and the very next
   * write-back persists that — silently erasing the user's entire message history. The existing
   * tests only ever round-trip an account with no messages, so an empty list came back either way
   * and the mutation was invisible.
   */
  @Test
  public void aStoredMessageHistorySurvivesReloading() {
    final Account original = newAccount();
    final String peer = "peer-uuid";
    original.addUnencryptedMessage(
        new com.amnesica.kryptey.inputmethod.signalprotocol.chat.Contact(
            "Real", "Peer", peer, 7, false),
        new com.amnesica.kryptey.inputmethod.signalprotocol.chat.StorageMessage(
            // The modern key. A bare name here would be a PRE-upgrade entry, and the load-time
            // migration drops one it cannot attribute - which is correct, and a different test.
            com.amnesica.kryptey.inputmethod.signalprotocol.chat.StorageMessage.chatLogKey(peer, 7),
            peer, original.getSignalProtocolAddress().getName(),
            java.time.Instant.ofEpochSecond(1_700_000_000L), "hello there"));
    new StorageHelper(context, workingBox()).storeAllInformationInSharedPreferences(original);

    final Account reloaded =
        new StorageHelper(context, workingBox()).getAccountFromSharedPreferences();

    assertNotNull(reloaded);
    assertEquals("the stored message history must survive a reload, not be replaced by an "
        + "empty list that the next write-back then persists",
        1, reloaded.getUnencryptedMessages().size());
  }

  /** And the same for the contact list, which shares the fallback. */
  @Test
  public void aStoredContactListSurvivesReloading() {
    final Account original = newAccount();
    final java.util.ArrayList<com.amnesica.kryptey.inputmethod.signalprotocol.chat.Contact> list =
        new java.util.ArrayList<>();
    list.add(new com.amnesica.kryptey.inputmethod.signalprotocol.chat.Contact(
        "Real", "Peer", "peer-uuid", 7, true));
    original.setContactList(list);
    new StorageHelper(context, workingBox()).storeAllInformationInSharedPreferences(original);

    final Account reloaded =
        new StorageHelper(context, workingBox()).getAccountFromSharedPreferences();

    assertNotNull(reloaded);
    assertEquals("the stored contact list must survive a reload",
        1, reloaded.getContactList().size());
    assertEquals("Real", reloaded.getContactList().get(0).getFirstName());
  }

  // -------------------------------------------------------- display-tag secret

  /**
   * The tag secret must survive a store/reload cycle.
   *
   * <p>It did not. The field existed and was documented as "per-install", but nothing wrote it and
   * the {@code Account} constructor mints a fresh one on every load — and {@code reloadAccount}
   * runs on every {@code setInputView}, so every contact tag changed on rotation, on a theme flip,
   * and on every IME restart. A tag is only useful because it is the same next time you look.
   *
   * <p>The test that was supposed to cover this asserted the tag <em>changes</em> after
   * re-initialising, which passed <em>because of</em> the bug. Nothing round-tripped an account
   * through storage, which is the only way to see it.
   */
  @Test
  public void theDisplayTagSecretSurvivesAStoreAndReload() {
    final Account original = newAccount();
    final byte[] secret = original.getDisplayTagSecret();
    assertNotNull(secret);
    assertEquals("the secret must be 32 bytes", 32, secret.length);

    new StorageHelper(context, workingBox()).storeAllInformationInSharedPreferences(original);
    final Account reloaded =
        new StorageHelper(context, workingBox()).getAccountFromSharedPreferences();

    assertNotNull(reloaded);
    org.junit.Assert.assertArrayEquals(
        "a reload must keep the secret, or every contact tag changes",
        secret, reloaded.getDisplayTagSecret());
  }

  /**
   * The secret must survive <em>repeated</em> load/store cycles, not just one.
   *
   * <p>This is the shape of the real failure. {@code reloadAccount} loads the account and then
   * writes it straight back, and it runs on every {@code setInputView} — so on every rotation, every
   * theme change and every IME restart. A single round-trip assertion would pass even if the write
   * back stored a freshly minted secret each time, because it only ever looks once. Cycling proves
   * the loop is a fixed point rather than a slow drift.
   */
  @Test
  public void theDisplayTagSecretSurvivesRepeatedLoadStoreCycles() {
    final Account original = newAccount();
    final byte[] secret = original.getDisplayTagSecret();
    new StorageHelper(context, workingBox()).storeAllInformationInSharedPreferences(original);

    Account current = null;
    for (int cycle = 0; cycle < 5; cycle++) {
      final StorageHelper helper = new StorageHelper(context, workingBox());
      current = helper.getAccountFromSharedPreferences();
      assertNotNull("cycle " + cycle + " failed to load", current);
      org.junit.Assert.assertArrayEquals("the secret drifted on cycle " + cycle,
          secret, current.getDisplayTagSecret());
      helper.storeAllInformationInSharedPreferences(current);
    }
  }

  /** Two separate accounts must not share one - that is what makes a tag unpredictable. */
  @Test
  public void twoAccountsGetDifferentSecrets() {
    assertFalse("two accounts must not share a tag secret",
        java.util.Arrays.equals(newAccount().getDisplayTagSecret(),
            newAccount().getDisplayTagSecret()));
  }

  /** A store written before the field existed must still load, minting a secret rather than failing. */
  @Test
  public void aStoreWithoutASecretStillLoads() {
    final StorageHelper helper = new StorageHelper(context, workingBox());
    helper.storeAllInformationInSharedPreferences(newAccount());
    preferences.edit().remove(ProtocolIdentifier.DISPLAY_TAG_SECRET.toString()).commit();

    final Account reloaded =
        new StorageHelper(context, workingBox()).getAccountFromSharedPreferences();

    assertNotNull("an upgrade from a store predating the field must not fail", reloaded);
    assertEquals(32, reloaded.getDisplayTagSecret().length);
  }

  /**
   * A box that works except on one value - standing in for a Keystore key that has been
   * invalidated part-way through a save, or a value that will not serialise.
   */
  private StorageHelper.CryptoBoxFactory boxFailingOn(final String marker) {
    return (ctx, hasExistingData) -> new GcmCryptoBox() {
      @Override
      protected SecretKey key() {
        return key;
      }

      @Override
      public byte[] seal(final byte[] plaintext, final byte[] aad)
          throws StorageCryptoException {
        if (new String(plaintext, java.nio.charset.StandardCharsets.UTF_8).contains(marker)) {
          throw new StorageCryptoException("keystore refused a value containing " + marker);
        }
        return super.seal(plaintext, aad);
      }
    };
  }

  /**
   * An account save is all or nothing.
   *
   * <p>The save used to be eight independent durable commits, so a failure on the eighth left the
   * first seven written. Reload cannot detect that - every value is individually well-formed and
   * parses cleanly - so the account came back holding a new protocol store beside an old contact
   * list, which is worse than coming back broken.
   *
   * <p>This fails the LAST value specifically. Failing the first would pass on the old code too.
   */
  @Test
  public void afailureOnTheLastValueLeavesTheWholeSaveUnwritten() {
    final Account account = newAccount();
    account.setContactList(new ArrayList<>(List.of(
        new Contact("Bob", "Jones", "peer-uuid", 7, false))));

    // DISPLAY_TAG_SECRET is written last; fail on the contact list, which is written just before.
    new StorageHelper(context, boxFailingOn("Jones")).storeAllInformationInSharedPreferences(account);

    // Everything except the store's own bookkeeping. Filtering by prefix rather than importing
    // SCHEMA_KEY, which is package-private on purpose - a test is not a reason to widen it.
    final Set<String> written = new HashSet<>();
    for (final String key : preferences.getAll().keySet()) {
      if (!key.startsWith("__kryptey")) written.add(key);
    }

    assertTrue("a save that could not seal one value left " + written
            + " behind - a torn account that reload cannot detect", written.isEmpty());
  }

  /** And the same save with a working box writes everything and reloads intact. */
  @Test
  public void thesameSaveWithAWorkingBoxIsComplete() {
    final Account account = newAccount();
    account.setContactList(new ArrayList<>(List.of(
        new Contact("Bob", "Jones", "peer-uuid", 7, false))));

    final StorageHelper helper = new StorageHelper(context, workingBox());
    helper.storeAllInformationInSharedPreferences(account);

    final Account reloaded = helper.getAccountFromSharedPreferences();
    assertNotNull("the control must actually store an account, or the test above proves nothing",
        reloaded);
    assertEquals("Jones", reloaded.getContactList().get(0).getLastName());
  }

  // ------------------------------------------------- telling apart the two empty states

  /**
   * A fresh install is NONE, and the right response to it is to generate an identity.
   */
  @Test
  public void afreshInstallReportsNoStorage() {
    assertEquals(StorageHelper.StorageState.NONE,
        new StorageHelper(context, workingBox()).storageState());
  }

  /** A healthy account is READABLE. */
  @Test
  public void ahealthyAccountReportsReadable() {
    final StorageHelper helper = new StorageHelper(context, workingBox());
    helper.storeAllInformationInSharedPreferences(newAccount());

    assertEquals(StorageHelper.StorageState.READABLE, helper.storageState());
  }

  /**
   * The case the app could not previously tell from a fresh install: the identity is still on disk
   * and the key that protects it is gone.
   *
   * <p>Presenting this as a fresh install invites the user to re-invite every contact, which
   * silently discards every pin they had already verified - reopening the trust-on-first-use window
   * for all of them, at a moment when whoever caused the key loss knows exactly when to strike.
   */
  @Test
  public void alostKeystoreKeyReportsUnreadableRatherThanEmpty() {
    new StorageHelper(context, workingBox()).storeAllInformationInSharedPreferences(newAccount());

    // Same stored bytes, different key: exactly what a lost Keystore alias looks like.
    key = newKey();

    assertEquals("a lost key must not look like a fresh install",
        StorageHelper.StorageState.UNREADABLE,
        new StorageHelper(context, workingBox()).storageState());
  }

  /**
   * The key is fine and the value is corrupt.
   *
   * <p>This is the case that makes the trial decryption in {@code storageState} load-bearing. Both
   * tests above pass without it: when the master key is wrong, {@code secureStore()} fails its
   * migration check and returns null long before any value is read, so removing the decryption
   * still reported UNREADABLE and the mutation survived. Here the store opens perfectly and the one
   * value that matters does not decode - which is what a partial write or a truncated file looks
   * like, and is indistinguishable from a healthy account by any flag.
   */
  @Test
  public void anintactKeyWithACorruptedValueReportsUnreadable() {
    new StorageHelper(context, workingBox()).storeAllInformationInSharedPreferences(newAccount());
    preferences.edit()
        .putString(String.valueOf(ProtocolIdentifier.PROTOCOL_STORE), "!!! not base64 !!!")
        .commit();

    assertEquals("an intact key over a corrupted value must not report READABLE",
        StorageHelper.StorageState.UNREADABLE,
        new StorageHelper(context, workingBox()).storageState());
  }

  /** And a box that refuses everything - an unusable Keystore - is also UNREADABLE, not NONE. */
  @Test
  public void anunusableKeystoreReportsUnreadable() {
    new StorageHelper(context, workingBox()).storeAllInformationInSharedPreferences(newAccount());

    assertEquals(StorageHelper.StorageState.UNREADABLE,
        new StorageHelper(context, brokenBox()).storageState());
  }

  /**
   * The three states must actually be distinguishable from one another. Asserting each one alone
   * would pass on an implementation that always returned UNREADABLE.
   */
  @Test
  public void thethreeStatesAreDistinct() {
    final StorageHelper fresh = new StorageHelper(context, workingBox());
    assertEquals(StorageHelper.StorageState.NONE, fresh.storageState());

    fresh.storeAllInformationInSharedPreferences(newAccount());
    assertEquals(StorageHelper.StorageState.READABLE, fresh.storageState());

    key = newKey();
    assertEquals(StorageHelper.StorageState.UNREADABLE,
        new StorageHelper(context, workingBox()).storageState());
  }

  /**
   * A deleted contact's name must survive a restart, or it is not a defence.
   *
   * <p>{@code reloadAccount} runs on every {@code setInputView}, so an in-memory-only list is empty
   * by the time the attacker's invite arrives - which is after the user has put the keyboard away
   * and picked it up again. The display-tag secret had exactly this failure and it took a review
   * round to notice, because nothing in the suite exercised the persisted half.
   */
  @Test
  public void retiredDisplayNamesSurviveAstoreAndReload() {
    final StorageHelper helper = new StorageHelper(context, workingBox());
    final Account account = newAccount();
    account.retireDisplayName("Bob", "Jones", "bob-uuid");
    account.retireDisplayName("Carol", "Smith", "carol-uuid");

    helper.storeAllInformationInSharedPreferences(account);
    final Account reloaded = helper.getAccountFromSharedPreferences();

    assertNotNull("the account must reload at all", reloaded);
    assertEquals("both retired names must come back", 2,
        reloaded.getRetiredDisplayNames().size());
    assertEquals("Bob", reloaded.getRetiredDisplayNames().get(0)[0]);
    assertEquals("Jones", reloaded.getRetiredDisplayNames().get(0)[1]);
    assertEquals("Smith", reloaded.getRetiredDisplayNames().get(1)[1]);
  }

  /** An account stored before this field existed must still load. */
  @Test
  public void anaccountWithNoRetiredNamesStillLoads() {
    final StorageHelper helper = new StorageHelper(context, workingBox());
    helper.storeAllInformationInSharedPreferences(newAccount());
    preferences.edit()
        .remove(String.valueOf(ProtocolIdentifier.RETIRED_DISPLAY_NAMES))
        .commit();

    final Account reloaded = helper.getAccountFromSharedPreferences();
    assertNotNull("a store predating this field must still load", reloaded);
    assertTrue("and start with an empty list rather than null",
        reloaded.getRetiredDisplayNames().isEmpty());
  }
}
