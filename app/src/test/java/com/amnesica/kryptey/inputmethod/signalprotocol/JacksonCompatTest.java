package com.amnesica.kryptey.inputmethod.signalprotocol;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.amnesica.kryptey.inputmethod.signalprotocol.chat.Contact;
import com.amnesica.kryptey.inputmethod.signalprotocol.chat.StorageMessage;
import com.amnesica.kryptey.inputmethod.signalprotocol.stores.PreKeyMetadataStoreImpl;
import com.amnesica.kryptey.inputmethod.signalprotocol.stores.SignalProtocolStoreImpl;
import com.amnesica.kryptey.inputmethod.signalprotocol.util.Base64;
import com.amnesica.kryptey.inputmethod.signalprotocol.util.JsonUtil;

import com.fasterxml.jackson.core.type.TypeReference;

import org.junit.Test;
import org.signal.libsignal.protocol.SignalProtocolAddress;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;

/**
 * Guards the on-device upgrade path.
 *
 * <p>KryptEY persists the whole Signal protocol store — identity key pair, pre-keys, signed
 * pre-keys, sessions — as Jackson JSON in SharedPreferences. An installed app therefore holds JSON
 * written by whatever Jackson version shipped in the previous release. If a Jackson upgrade changes
 * how any of that is read, {@code StorageHelper.getClassFromSharedPreferences} catches the
 * IOException and returns {@code null}, and {@code StorageHelper.getAccountFromSharedPreferences}
 * then dereferences it — an NPE thrown out of {@code LatinIME.setInputView()}, i.e. the keyboard
 * crashes every time it is raised, with no in-app recovery.
 *
 * <p>The fixtures under {@code src/test/resources/fixtures} were produced by <b>Jackson 2.14.1</b>
 * (the version shipped in KryptEY 0.1.5) via {@link FixtureGenerator}. This test reads them with
 * whatever Jackson is current. A round-trip test cannot substitute for this: round-tripping only
 * shows that a Jackson version can read its own output.
 *
 * <p>When Jackson is upgraded again, do not regenerate these fixtures — that would silently delete
 * the coverage. Add a new set alongside them.
 */
public class JacksonCompatTest {

  private static String fixture(final String name) throws IOException {
    try (InputStream in =
        JacksonCompatTest.class.getClassLoader().getResourceAsStream("fixtures/" + name)) {
      assertNotNull("missing fixture: fixtures/" + name, in);
      final ByteArrayOutputStream out = new ByteArrayOutputStream();
      final byte[] buf = new byte[4096];
      int n;
      while ((n = in.read(buf)) != -1) {
        out.write(buf, 0, n);
      }
      return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }
  }

  @Test
  public void identityKeyPairSurvivesJacksonUpgradeByteForByte() throws IOException {
    final SignalProtocolStoreImpl store =
        JsonUtil.fromJson(fixture("protocol-store.json"), SignalProtocolStoreImpl.class);
    assertNotNull("protocol store deserialized to null - upgrade would NPE in StorageHelper", store);

    final byte[] expected = Base64.decodeWithoutPadding(fixture("identity-key-pair.b64"));
    final byte[] actual = store.getIdentityKeyPair().serialize();

    // Byte-for-byte, not just "it parsed": a silently truncated or re-encoded private key would
    // still produce a non-null IdentityKeyPair but would break every existing session.
    assertArrayEquals("identity key pair changed across the Jackson upgrade", expected, actual);
  }

  @Test
  public void registrationIdSurvivesJacksonUpgrade() throws IOException {
    final SignalProtocolStoreImpl store =
        JsonUtil.fromJson(fixture("protocol-store.json"), SignalProtocolStoreImpl.class);
    assertEquals(Integer.parseInt(fixture("registration-id.txt").trim()),
        store.getLocalRegistrationId());
  }

  @Test
  public void preKeysSurviveJacksonUpgrade()
      throws IOException, org.signal.libsignal.protocol.InvalidKeyIdException {
    final SignalProtocolStoreImpl store =
        JsonUtil.fromJson(fixture("protocol-store.json"), SignalProtocolStoreImpl.class);

    // The fixture was generated with pre-keys present; if the map silently deserialized empty the
    // app would look fine until the first inbound PreKeySignalMessage failed to decrypt.
    assertTrue("pre-key store deserialized empty", store.getPreKeyStore().getSize() > 0);
    assertTrue("pre-key 0 missing after upgrade", store.containsPreKey(0));
    assertNotNull("signed pre-key 0 missing after upgrade", store.loadSignedPreKey(0));
  }

  @Test
  public void metadataStoreSurvivesJacksonUpgrade() throws IOException {
    final PreKeyMetadataStoreImpl meta =
        JsonUtil.fromJson(fixture("metadata-store.json"), PreKeyMetadataStoreImpl.class);
    assertNotNull(meta);
    assertTrue("signed pre-key should be marked registered", meta.isSignedPreKeyRegistered());
    // Non-zero refresh times prove the long fields parsed rather than defaulting to 0, which would
    // make the app think the signed pre-key was overdue and rotate it on every launch.
    assertTrue(meta.getNextSignedPreKeyRefreshTime() > 0);
    assertTrue(meta.getOldSignedPreKeyDeletionTime() > 0);
  }

  @Test
  public void protocolAddressSurvivesJacksonUpgrade() throws IOException {
    final SignalProtocolAddress address =
        JsonUtil.fromJson(fixture("protocol-address.json"), SignalProtocolAddress.class);
    assertNotNull(address);
    assertTrue("address name lost", address.getName().length() > 0);
    assertTrue("device id lost", address.getDeviceId() > 0);

    // And the device id is still a LEGACY one, outside libsignal's [1,127].
    //
    // That is what a 0.1.5 store actually held - ids were minted with nextInt(10000) - and it is
    // the property that makes this fixture a stand-in for one rather than a copy of what today's
    // code would write. FixtureGenerator's procedure says to pin the old Jackson version before
    // regenerating, which protects the serialisation half; nothing protected this half, and
    // regenerating now silently produces a folded id because address construction folds. Measured:
    // 7296 becomes 67.
    //
    // Asserted here rather than described in the generator, because a note in a file someone runs
    // once a year is not a guard.
    final String raw = fixture("protocol-address.json");
    final int storedDeviceId = Integer.parseInt(
        raw.replaceAll(".*\"deviceId\"\\s*:\\s*(\\d+).*", "$1"));

    assertTrue("the fixture on disk must hold a legacy out-of-range device id (found "
            + storedDeviceId + "): regenerating without preserving that turns a "
            + "backward-compatibility fixture into a copy of what today\u0027s code writes",
        storedDeviceId > com.amnesica.kryptey.inputmethod.signalprotocol.util
            .ProtocolAddresses.MAX_DEVICE_ID);
    assertTrue("and reading it must fold that into libsignal's range, which is the behaviour this "
            + "fixture exists to exercise", address.getDeviceId()
            <= com.amnesica.kryptey.inputmethod.signalprotocol.util.ProtocolAddresses.MAX_DEVICE_ID);
  }

  @Test
  public void contactsSurviveJacksonUpgrade() throws IOException {
    final ArrayList<Contact> contacts =
        JsonUtil.fromJson(fixture("contacts.json"), new TypeReference<ArrayList<Contact>>() {});
    assertEquals(1, contacts.size());
    final Contact c = contacts.get(0);
    assertEquals("Ada", c.getFirstName());
    assertEquals("Lovelace", c.getLastName());
    // verified=true must survive: silently resetting it to false would downgrade a user's
    // out-of-band-verified contact back to unverified without telling them.
    assertTrue("verified flag lost across upgrade", c.isVerified());
  }

  /**
   * The highest-risk field in the upgrade. Jackson 2.14.1 wrote Instant as a nanosecond-precision
   * decimal ({@code 1685361429.000000000}); jackson-datatype-jsr310 has changed its default
   * timestamp handling across versions, and a misparse here silently shifts every stored message
   * timestamp by a factor of a billion.
   */
  @Test
  public void instantTimestampSurvivesJacksonUpgrade() throws IOException {
    final ArrayList<StorageMessage> messages = JsonUtil.fromJson(
        fixture("messages.json"), new TypeReference<ArrayList<StorageMessage>>() {});
    assertEquals(1, messages.size());
    final StorageMessage m = messages.get(0);
    assertEquals("fixture message", m.getUnencryptedMessage());
    assertEquals(Instant.ofEpochMilli(1_685_361_429_000L), m.getTimestamp());
  }

  @Test
  public void uniqueUserIdSurvivesJacksonUpgrade() throws IOException {
    final String name = JsonUtil.fromJson(fixture("unique-user-id.json"), String.class);
    assertNotNull(name);
    assertEquals(36, name.length()); // UUID
  }

  /**
   * Reading the old fixture and writing it back out with the current Jackson must produce JSON the
   * current Jackson can also read. This is what actually happens on-device: StorageHelper rewrites
   * the whole store after the first load.
   */
  @Test
  public void reserializingAnUpgradedStoreStaysReadable() throws IOException {
    final SignalProtocolStoreImpl loaded =
        JsonUtil.fromJson(fixture("protocol-store.json"), SignalProtocolStoreImpl.class);
    final String rewritten = JsonUtil.toJson(loaded);
    assertNotNull("re-serialization produced null", rewritten);

    final SignalProtocolStoreImpl reloaded =
        JsonUtil.fromJson(rewritten, SignalProtocolStoreImpl.class);
    assertArrayEquals(loaded.getIdentityKeyPair().serialize(),
        reloaded.getIdentityKeyPair().serialize());
    assertEquals(loaded.getLocalRegistrationId(), reloaded.getLocalRegistrationId());
    assertEquals(loaded.getPreKeyStore().getSize(), reloaded.getPreKeyStore().getSize());
  }
}
