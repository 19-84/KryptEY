package com.amnesica.kryptey.inputmethod.signalprotocol.encoding;

import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.amnesica.kryptey.inputmethod.signalprotocol.MessageEnvelope;
import com.amnesica.kryptey.inputmethod.signalprotocol.SignalProtocolMain;
import com.amnesica.kryptey.inputmethod.signalprotocol.prekey.KyberPreKeyEntity;
import com.amnesica.kryptey.inputmethod.signalprotocol.prekey.PreKeyResponse;
import com.amnesica.kryptey.inputmethod.signalprotocol.prekey.PreKeyResponseItem;
import com.amnesica.kryptey.inputmethod.signalprotocol.prekey.SignedPreKeyEntity;

import org.junit.Test;

import java.io.IOException;
import java.util.LinkedList;
import java.util.List;

/**
 * The encoder's range and sanity guards.
 *
 * <p>Mutation testing showed every one of these could be deleted with zero test failures — nine
 * clauses of dead defensive code. Two of them are load-bearing rather than decorative:
 *
 * <ul>
 *   <li>{@code writeVarU8}'s length check is the only thing that turns a Kyber-key regression into
 *       a loud failure instead of silent truncation to 33 bytes.
 *   <li>{@code writeU8}'s range check is the only thing stopping a device id above 127 from being
 *       truncated into a different, valid-looking value.
 * </ul>
 *
 * <p>A guard nothing exercises is a guard that can be removed during a refactor without anyone
 * noticing, which is exactly when it stops protecting anything.
 */
public class EncoderGuardTest {

  private static MessageEnvelope bundleEnvelope() {
    SignalProtocolMain.testIsRunning = true;
    SignalProtocolMain.initialize(null);
    return SignalProtocolMain.getPreKeyResponseMessage();
  }

  private static MessageEnvelope withDevices(final List<PreKeyResponseItem> devices) {
    final MessageEnvelope original = bundleEnvelope();
    return new MessageEnvelope(
        new PreKeyResponse(original.getPreKeyResponse().getIdentityKey(), devices),
        original.getSignalProtocolAddressName(), original.getDeviceId());
  }

  @Test
  public void aNullEnvelopeIsRefused() {
    assertThrows(IOException.class, () -> BinaryEnvelope.encode(null));
  }

  @Test
  public void aBundleWithNoDevicesIsRefused() {
    final IOException e = assertThrows(IOException.class,
        () -> BinaryEnvelope.encode(withDevices(new LinkedList<>())));
    assertTrue(e.getMessage().contains("no devices"));
  }

  /**
   * A bundle declaring zero devices must be refused BY THE DEVICE COUNT.
   *
   * <p>This was two kinds of vacuous. It hard-coded version 1, so after the format bumped it failed
   * on the version byte; and even before that its {@code identityKeyLen} was zero, so
   * {@code new IdentityKey(new byte[0], 0)} threw "malformed identity key" before the device count
   * was ever read. The assertion was a bare {@code assertThrows(IOException)}, which cannot tell
   * those apart - so the decoder-side zero-device refusal had no test at all while looking like it
   * had one. The encoder-side one is covered above; this is the other half.
   */
  @Test
  public void aDecodedBundleClaimingZeroDevicesIsRefused() throws Exception {
    final byte[] identity =
        org.signal.libsignal.protocol.IdentityKeyPair.generate().getPublicKey().serialize();

    final java.io.ByteArrayOutputStream frame = new java.io.ByteArrayOutputStream();
    frame.write(BinaryEnvelope.VERSION);
    frame.write(0x01);            // flags: bundle present
    frame.write(1);               // name length
    frame.write('x');
    frame.write(42);              // device id
    frame.write(identity.length); // a REAL identity key, so parsing reaches the device count
    frame.write(identity, 0, identity.length);
    frame.write(0);               // deviceCount = 0

    final IOException refused = assertThrows(IOException.class,
        () -> BinaryEnvelope.decode(frame.toByteArray()));
    assertTrue("the refusal must be about the device count, not about something the fixture got "
        + "wrong earlier in the frame: " + refused.getMessage(),
        refused.getMessage().contains("no devices"));
  }

  /**
   * The guard that keeps a Kyber-key regression loud. A Kyber-1024 public key is 1569 bytes; if it
   * were ever written with a u8 length it would truncate to 33 and produce an envelope that decodes
   * into a wrong key rather than failing.
   */
  @Test
  public void aFieldTooLongForItsU8LengthPrefixIsRefused() throws Exception {
    final MessageEnvelope original = bundleEnvelope();
    final PreKeyResponseItem device = original.getPreKeyResponse().getDevices().get(0);

    // A signature field is u8-prefixed; 300 bytes cannot be represented.
    final List<PreKeyResponseItem> devices = new LinkedList<>();
    devices.add(new PreKeyResponseItem(device.getDeviceId(), device.getRegistrationId(),
        new SignedPreKeyEntity(1, device.getSignedPreKey().getPublicKey(), new byte[300]),
        device.getPreKey(), device.getKyberPreKey()));

    final IOException e =
        assertThrows(IOException.class, () -> BinaryEnvelope.encode(withDevices(devices)));
    assertTrue("wrong guard fired: " + e.getMessage(),
        e.getMessage().contains("too long for u8 length"));
  }

  /** A Kyber key of a legitimate size must still encode — the guard must not be over-tight. */
  @Test
  public void aRealKyberKeyIsWellWithinItsU16LengthPrefix() throws Exception {
    final MessageEnvelope original = bundleEnvelope();
    final KyberPreKeyEntity kyber = original.getPreKeyResponse().getDevices().get(0)
        .getKyberPreKey();

    final int length = kyber.getPublicKey().serialize().length;
    assertTrue("a Kyber key should not fit a u8 prefix - that is why it uses u16", length > 255);
    assertTrue("a Kyber key must fit the u16 prefix", length <= 65535);

    BinaryEnvelope.encode(original); // must not throw
  }

  @Test
  public void aNullIdentityKeyIsRefused() {
    final MessageEnvelope original = bundleEnvelope();
    final MessageEnvelope broken = new MessageEnvelope(
        new PreKeyResponse(null, original.getPreKeyResponse().getDevices()),
        original.getSignalProtocolAddressName(), original.getDeviceId());

    final IOException e = assertThrows(IOException.class, () -> BinaryEnvelope.encode(broken));
    assertTrue(e.getMessage().contains("identityKey"));
  }

  @Test
  public void aNullSignedPreKeyIsRefused() {
    final MessageEnvelope original = bundleEnvelope();
    final PreKeyResponseItem device = original.getPreKeyResponse().getDevices().get(0);

    final List<PreKeyResponseItem> devices = new LinkedList<>();
    devices.add(new PreKeyResponseItem(device.getDeviceId(), device.getRegistrationId(),
        null, device.getPreKey(), device.getKyberPreKey()));

    final IOException e =
        assertThrows(IOException.class, () -> BinaryEnvelope.encode(withDevices(devices)));
    assertTrue(e.getMessage().contains("signedPreKey"));
  }

  @Test
  public void aNullSenderNameIsRefused() {
    assertThrows(IOException.class,
        () -> BinaryEnvelope.encode(new MessageEnvelope(new byte[] {1}, 2, null, 42)));
  }

  /** More devices than a u8 count can express must fail loudly rather than wrap. */
  @Test
  public void tooManyDevicesIsRefused() {
    final MessageEnvelope original = bundleEnvelope();
    final PreKeyResponseItem device = original.getPreKeyResponse().getDevices().get(0);

    final List<PreKeyResponseItem> devices = new LinkedList<>();
    for (int i = 0; i < 300; i++) {
      devices.add(new PreKeyResponseItem(device.getDeviceId(), device.getRegistrationId(),
          device.getSignedPreKey(), device.getPreKey(), device.getKyberPreKey()));
    }

    final IOException e =
        assertThrows(IOException.class, () -> BinaryEnvelope.encode(withDevices(devices)));
    assertTrue(e.getMessage().contains("too many devices"));
  }
}
