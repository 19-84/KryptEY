package com.amnesica.kryptey.inputmethod.signalprotocol.encoding;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.amnesica.kryptey.inputmethod.signalprotocol.MessageEnvelope;
import com.amnesica.kryptey.inputmethod.signalprotocol.SignalProtocolMain;
import com.amnesica.kryptey.inputmethod.signalprotocol.prekey.PreKeyResponseItem;
import com.amnesica.kryptey.inputmethod.signalprotocol.util.JsonUtil;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * The binary wire format that replaces base64(minified JSON).
 *
 * <p>Emphasis is on two things the JSON form got wrong — size and plaintext metadata — and on
 * hostile input, because {@code decode} parses attacker-supplied bytes straight off the clipboard.
 */
public class BinaryEnvelopeTest {

  private static MessageEnvelope bundleEnvelope() {
    SignalProtocolMain.testIsRunning = true;
    SignalProtocolMain.initialize(null);
    final MessageEnvelope envelope = SignalProtocolMain.getPreKeyResponseMessage();
    assertNotNull(envelope);
    assertNotNull(envelope.getPreKeyResponse());
    return envelope;
  }

  private static MessageEnvelope messageEnvelope() {
    return new MessageEnvelope(new byte[] {9, 8, 7, 6, 5}, 3, "sender-uuid", 42);
  }

  // ------------------------------------------------------------- round trip

  @Test
  public void aPlainMessageRoundTrips() throws Exception {
    final MessageEnvelope original = messageEnvelope();
    final MessageEnvelope restored = BinaryEnvelope.decode(BinaryEnvelope.encode(original));

    assertArrayEquals(original.getCiphertextMessage(), restored.getCiphertextMessage());
    assertEquals(original.getCiphertextType(), restored.getCiphertextType());
    assertEquals(original.getSignalProtocolAddressName(), restored.getSignalProtocolAddressName());
    assertEquals(original.getDeviceId(), restored.getDeviceId());
    assertNull(restored.getPreKeyResponse());
  }

  @Test
  public void aPqxdhBundleRoundTripsWithEveryKeyIntact() throws Exception {
    final MessageEnvelope original = bundleEnvelope();
    final MessageEnvelope restored = BinaryEnvelope.decode(BinaryEnvelope.encode(original));

    final PreKeyResponseItem before = original.getPreKeyResponse().getDevices().get(0);
    final PreKeyResponseItem after = restored.getPreKeyResponse().getDevices().get(0);

    assertArrayEquals("identity key changed",
        original.getPreKeyResponse().getIdentityKey().serialize(),
        restored.getPreKeyResponse().getIdentityKey().serialize());
    assertEquals(before.getDeviceId(), after.getDeviceId());
    assertEquals(before.getRegistrationId(), after.getRegistrationId());

    assertEquals(before.getSignedPreKey().getKeyId(), after.getSignedPreKey().getKeyId());
    assertArrayEquals(before.getSignedPreKey().getPublicKey().serialize(),
        after.getSignedPreKey().getPublicKey().serialize());
    assertArrayEquals(before.getSignedPreKey().getSignature(),
        after.getSignedPreKey().getSignature());

    assertEquals(before.getPreKey().getKeyId(), after.getPreKey().getKeyId());
    assertArrayEquals(before.getPreKey().getPublicKey().serialize(),
        after.getPreKey().getPublicKey().serialize());

    // The Kyber key is 1569 bytes and needs a u16 length prefix; a u8 would silently truncate it.
    assertEquals(before.getKyberPreKey().getKeyId(), after.getKyberPreKey().getKeyId());
    // Mutation testing showed the three id assertions here were 0 == 0 - every id in a freshly
    // generated bundle happens to be zero, so zeroing them on the wire was undetectable. Assert
    // they are actually carried by round-tripping a bundle with distinct non-zero ids.
    assertArrayEquals("kyber public key did not survive",
        before.getKyberPreKey().getPublicKey().serialize(),
        after.getKyberPreKey().getPublicKey().serialize());
    assertArrayEquals("kyber signature did not survive",
        before.getKyberPreKey().getSignature(), after.getKyberPreKey().getSignature());
  }

  /**
   * The ids in a freshly generated bundle are all zero, which made three assertions in the test
   * above tautological. This one uses distinct non-zero ids so a dropped or zeroed id is caught.
   */
  @Test
  public void distinctPreKeyIdsAreCarriedOnTheWire() throws Exception {
    final MessageEnvelope original = bundleEnvelope();
    final PreKeyResponseItem device = original.getPreKeyResponse().getDevices().get(0);

    final com.amnesica.kryptey.inputmethod.signalprotocol.prekey.PreKeyResponseItem rebuilt =
        new com.amnesica.kryptey.inputmethod.signalprotocol.prekey.PreKeyResponseItem(
            device.getDeviceId(), 0x11223344,
            new com.amnesica.kryptey.inputmethod.signalprotocol.prekey.SignedPreKeyEntity(
                7001, device.getSignedPreKey().getPublicKey(),
                device.getSignedPreKey().getSignature()),
            new com.amnesica.kryptey.inputmethod.signalprotocol.prekey.PreKeyEntity(
                7002, device.getPreKey().getPublicKey()),
            new com.amnesica.kryptey.inputmethod.signalprotocol.prekey.KyberPreKeyEntity(
                7003, device.getKyberPreKey().getPublicKey(),
                device.getKyberPreKey().getSignature()));

    final java.util.List<PreKeyResponseItem> devices = new java.util.LinkedList<>();
    devices.add(rebuilt);
    final MessageEnvelope envelope = new MessageEnvelope(
        new com.amnesica.kryptey.inputmethod.signalprotocol.prekey.PreKeyResponse(
            original.getPreKeyResponse().getIdentityKey(), devices),
        original.getSignalProtocolAddressName(), original.getDeviceId());

    final PreKeyResponseItem after =
        BinaryEnvelope.decode(BinaryEnvelope.encode(envelope)).getPreKeyResponse()
            .getDevices().get(0);

    assertEquals("registrationId lost", 0x11223344, after.getRegistrationId());
    assertEquals("signedPreKeyId lost", 7001, after.getSignedPreKey().getKeyId());
    assertEquals("preKeyId lost", 7002, after.getPreKey().getKeyId());
    assertEquals("kyberPreKeyId lost", 7003, after.getKyberPreKey().getKeyId());
  }

  /** M13: the presence bytes could be dropped entirely and nothing noticed. */
  @Test
  public void anAbsentPreKeyAndKyberKeyRoundTripAsAbsent() throws Exception {
    final MessageEnvelope original = bundleEnvelope();
    final PreKeyResponseItem device = original.getPreKeyResponse().getDevices().get(0);

    final java.util.List<PreKeyResponseItem> devices = new java.util.LinkedList<>();
    devices.add(new PreKeyResponseItem(device.getDeviceId(), device.getRegistrationId(),
        device.getSignedPreKey(), null, null));

    final MessageEnvelope envelope = new MessageEnvelope(
        new com.amnesica.kryptey.inputmethod.signalprotocol.prekey.PreKeyResponse(
            original.getPreKeyResponse().getIdentityKey(), devices),
        original.getSignalProtocolAddressName(), original.getDeviceId());

    final PreKeyResponseItem after =
        BinaryEnvelope.decode(BinaryEnvelope.encode(envelope)).getPreKeyResponse()
            .getDevices().get(0);

    assertNull("an absent pre key came back present", after.getPreKey());
    assertNull("an absent kyber key came back present", after.getKyberPreKey());
    assertNotNull("the signed pre key should survive", after.getSignedPreKey());
  }

  /** M24: every device after the first could be silently dropped. */
  @Test
  public void everyDeviceInABundleIsCarried() throws Exception {
    final MessageEnvelope original = bundleEnvelope();
    final PreKeyResponseItem device = original.getPreKeyResponse().getDevices().get(0);

    final java.util.List<PreKeyResponseItem> devices = new java.util.LinkedList<>();
    devices.add(new PreKeyResponseItem(11, 101, device.getSignedPreKey(), device.getPreKey(),
        device.getKyberPreKey()));
    devices.add(new PreKeyResponseItem(22, 202, device.getSignedPreKey(), device.getPreKey(),
        device.getKyberPreKey()));

    final MessageEnvelope envelope = new MessageEnvelope(
        new com.amnesica.kryptey.inputmethod.signalprotocol.prekey.PreKeyResponse(
            original.getPreKeyResponse().getIdentityKey(), devices),
        original.getSignalProtocolAddressName(), original.getDeviceId());

    final java.util.List<PreKeyResponseItem> after =
        BinaryEnvelope.decode(BinaryEnvelope.encode(envelope)).getPreKeyResponse().getDevices();

    assertEquals("devices were dropped", 2, after.size());
    assertEquals(11, after.get(0).getDeviceId());
    assertEquals(22, after.get(1).getDeviceId());
    assertEquals(202, after.get(1).getRegistrationId());
  }

  @Test
  public void aBundleWithAnAttachedCiphertextRoundTrips() throws Exception {
    final MessageEnvelope original = bundleEnvelope();
    original.setCiphertextMessage(new byte[] {1, 2, 3, 4});
    original.setCiphertextType(3);

    final MessageEnvelope restored = BinaryEnvelope.decode(BinaryEnvelope.encode(original));

    assertNotNull(restored.getPreKeyResponse());
    assertArrayEquals(new byte[] {1, 2, 3, 4}, restored.getCiphertextMessage());
    assertEquals(3, restored.getCiphertextType());
  }

  @Test
  public void aLargeCiphertextRoundTrips() throws Exception {
    final byte[] big = new byte[40_000]; // beyond a u8/u16 boundary mistake
    for (int i = 0; i < big.length; i++) big[i] = (byte) i;

    final MessageEnvelope restored = BinaryEnvelope.decode(
        BinaryEnvelope.encode(new MessageEnvelope(big, 2, "sender", 7)));
    assertArrayEquals(big, restored.getCiphertextMessage());
  }

  /**
   * Names are UUIDs in this app, and the field is attacker-supplied and rendered in the contact
   * list, so non-ASCII is refused rather than round-tripped — bidi overrides in particular can make
   * one contact display as another.
   */
  @Test
  public void aUuidSenderNameRoundTrips() throws Exception {
    final String name = java.util.UUID.randomUUID().toString();
    final MessageEnvelope restored = BinaryEnvelope.decode(
        BinaryEnvelope.encode(new MessageEnvelope(new byte[] {1}, 2, name, 5)));
    assertEquals(name, restored.getSignalProtocolAddressName());
  }

  @Test
  public void displayUnsafeSenderNamesAreRefused() {
    final String[] hostile = {
        "alice\u202Ebob",      // right-to-left override: renders as "alicebob" reversed
        "alice\u0000bob",      // NUL
        "alice\nbob",          // newline breaks the info banner
        "日本語",                // non-ASCII homoglyph surface
        "",                     // empty
    };
    for (final String name : hostile) {
      assertThrows("accepted display-unsafe name: " + name.replace("\u202E", "<RLO>"),
          IOException.class,
          () -> BinaryEnvelope.encode(new MessageEnvelope(new byte[] {1}, 2, name, 5)));
    }
  }

  // ------------------------------------------------------------------- size

  /**
   * Records the real size relationship, which is NOT the reason to prefer this format.
   *
   * <p>An earlier version of this test compared against {@code Base64Encoder.encode(json)} — a
   * double encoding the app never performs, since {@code Base64Encoder} has no callers in main and
   * {@code RawEncoder} is the identity function. Measured against what actually goes on the wire,
   * binary-plus-base64 (2484) versus raw JSON (2517) is a ~1% difference, not the 26% that
   * comparison implied. Base64's 4/3 expansion gives back almost exactly what dropping the JSON
   * structure saves.
   *
   * <p>The format earns its place on metadata and parsing, not bytes: no plaintext field names, no
   * wall-clock timestamp, strict bounds-checked parsing of hostile input, and a byte string rather
   * than a JSON document to build on when the envelope is eventually masked.
   */
  @Test
  public void theBinaryFormIsNoLargerThanTheJsonForm() throws Exception {
    final MessageEnvelope envelope = bundleEnvelope();

    final int jsonChars = JsonUtil.toJson(envelope).length();
    final byte[] binary = BinaryEnvelope.encode(envelope);
    final int binaryChars = com.amnesica.kryptey.inputmethod.signalprotocol.util.Base64
        .encodeBytes(binary).length();

    System.out.println("WIRE json=" + jsonChars + " binary-b64=" + binaryChars
        + " binary-raw=" + binary.length);

    assertTrue("binary form (" + binaryChars + ") must not be larger than JSON (" + jsonChars + ")",
        binaryChars <= jsonChars);
    assertTrue("raw binary should be meaningfully smaller than the JSON text",
        binary.length < jsonChars * 0.8);
  }

  @Test
  public void theWallClockTimestampIsNotOnTheWireAtAll() throws Exception {
    final MessageEnvelope original = messageEnvelope();
    final byte[] encoded = BinaryEnvelope.encode(original);

    // The old JSON envelope carried System.currentTimeMillis() in the clear on every message, which
    // the receiver never read. Prove no byte sequence of the current time appears.
    final long now = original.getTimestamp();
    final byte[] beBytes = new byte[8];
    for (int i = 0; i < 8; i++) beBytes[i] = (byte) (now >>> (8 * (7 - i)));

    assertFalse("a wall-clock timestamp is still being transmitted",
        indexOf(encoded, beBytes) >= 0);
    // ...and the decoded envelope's timestamp is locally generated, not attacker-controlled.
    assertNotNull(BinaryEnvelope.decode(encoded));
  }

  private static int indexOf(final byte[] haystack, final byte[] needle) {
    outer:
    for (int i = 0; i + needle.length <= haystack.length; i++) {
      for (int j = 0; j < needle.length; j++) if (haystack[i + j] != needle[j]) continue outer;
      return i;
    }
    return -1;
  }

  // --------------------------------------------------------- hostile input

  @Test
  public void nullAndEmptyInputAreRejected() {
    assertThrows(IOException.class, () -> BinaryEnvelope.decode(null));
    assertThrows(IOException.class, () -> BinaryEnvelope.decode(new byte[0]));
    assertThrows(IOException.class, () -> BinaryEnvelope.encode(null));
  }

  @Test
  public void anUnknownVersionIsRejected() throws Exception {
    final byte[] encoded = BinaryEnvelope.encode(messageEnvelope());
    encoded[0] = 0x7F;
    final IOException e = assertThrows(IOException.class, () -> BinaryEnvelope.decode(encoded));
    assertTrue(e.getMessage().contains("unsupported envelope version"));
  }

  /**
   * Uses a single unknown bit, not 0xFF. Setting every bit also sets both <em>known</em> flags, so
   * decode failed on a truncated read rather than on the flags check — the test passed while the
   * check it names could be deleted entirely.
   */
  @Test
  public void unknownFlagBitsAreRejected() throws Exception {
    final byte[] encoded = BinaryEnvelope.encode(messageEnvelope());
    encoded[1] |= 0x04;
    final IOException e = assertThrows(IOException.class, () -> BinaryEnvelope.decode(encoded));
    assertTrue("failed for the wrong reason: " + e.getMessage(),
        e.getMessage().contains("unknown envelope flags"));
  }

  /**
   * Every truncation must raise IOException. An AIOOBE here would be unchecked and would escape the
   * clipboard-decrypt path, killing the IME process — the failure mode this project keeps hitting.
   */
  @Test
  public void everyTruncationIsRejectedCleanly() throws Exception {
    final byte[] full = BinaryEnvelope.encode(bundleEnvelope());
    for (int len = 0; len < full.length; len++) {
      final byte[] truncated = Arrays.copyOf(full, len);
      try {
        BinaryEnvelope.decode(truncated);
        throw new AssertionError("accepted a truncated envelope of length " + len);
      } catch (IOException expected) {
        // correct
      } catch (Throwable unchecked) {
        throw new AssertionError(
            "truncation at length " + len + " escaped as " + unchecked, unchecked);
      }
    }
  }

  @Test
  public void trailingBytesAreRejected() throws Exception {
    final byte[] full = BinaryEnvelope.encode(messageEnvelope());
    final byte[] padded = Arrays.copyOf(full, full.length + 4);
    final IOException e = assertThrows(IOException.class, () -> BinaryEnvelope.decode(padded));
    assertTrue(e.getMessage().contains("trailing bytes"));
  }

  /** Random and corrupted input must never produce an unchecked exception. */
  @Test
  public void randomAndCorruptedInputNeverThrowsUnchecked() throws Exception {
    final byte[] full = BinaryEnvelope.encode(bundleEnvelope());
    final java.util.Random random = new java.util.Random(20260823L);

    for (int trial = 0; trial < 400; trial++) {
      final byte[] corrupted = full.clone();
      for (int flip = 0; flip < 6; flip++) {
        corrupted[random.nextInt(corrupted.length)] = (byte) random.nextInt(256);
      }
      try {
        BinaryEnvelope.decode(corrupted);
      } catch (IOException expected) {
        // correct
      } catch (Throwable unchecked) {
        throw new AssertionError("corrupted envelope escaped as " + unchecked, unchecked);
      }
    }

    for (int trial = 0; trial < 400; trial++) {
      final byte[] noise = new byte[random.nextInt(300)];
      random.nextBytes(noise);
      try {
        BinaryEnvelope.decode(noise);
      } catch (IOException expected) {
        // correct
      } catch (Throwable unchecked) {
        throw new AssertionError("random input escaped as " + unchecked, unchecked);
      }
    }
  }

  @Test
  public void aDeclaredLengthLargerThanTheBufferIsRejected() throws Exception {
    final byte[] encoded = BinaryEnvelope.encode(messageEnvelope());
    // Sender-name length sits immediately after version+flags. Derived rather than hard-coded: a
    // literal offset silently starts poking a different field the moment the layout changes.
    final int nameLenOffset = 2;
    assertEquals("layout changed; this test is no longer poking the name length",
        messageEnvelope().getSignalProtocolAddressName().length(), encoded[nameLenOffset]);
    encoded[nameLenOffset] = (byte) 0xFF;
    assertThrows(IOException.class, () -> BinaryEnvelope.decode(encoded));
  }

  @Test
  public void anEmptyEnvelopeIsRefusedRatherThanEncoded() {
    final MessageEnvelope empty = new MessageEnvelope(null, 0, "sender", 1);
    empty.setCiphertextMessage(null);
    assertThrows(IOException.class, () -> BinaryEnvelope.encode(empty));
  }
}
