package com.amnesica.kryptey.inputmethod.signalprotocol;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.amnesica.kryptey.inputmethod.signalprotocol.util.ProtocolAddresses;

import org.junit.Test;
import org.signal.libsignal.protocol.SignalProtocolAddress;

/**
 * Guards the paths that consume a <em>peer-supplied</em> device id.
 *
 * <p>A KryptEY 0.1.5 peer generated its device id with {@code new Random().nextInt(10000)}, so
 * ~99% of them fall outside the {@code [1,127]} range libsignal 0.86 accepts. The raw constructor
 * throws an unchecked {@code IllegalArgumentException}, and nothing on the clipboard-decrypt path
 * catches unchecked exceptions — so every one of these sites was an IME process kill triggered by
 * an ordinary message from a legacy contact.
 *
 * <p>Sanitising the values the app generates itself was not enough: the dangerous ids are the ones
 * that arrive over the wire.
 */
public class LegacyPeerInteropTest {

  private static final String PEER = "2aecc0a1-67ef-42c5-b2db-60f0bfdf2f33";

  /** The behaviour that makes all of this necessary. */
  @Test
  public void aLegacyPeerDeviceIdIsRejectedByTheRawConstructor() {
    assertThrows("libsignal should reject a 0.1.5-era device id",
        IllegalArgumentException.class, () -> new SignalProtocolAddress(PEER, 7296));
  }

  /**
   * {@code extractContactFromMessageEnvelope} builds an address from the envelope's device id.
   * This is reached from the clipboard-decrypt path on every inbound message.
   */
  @Test
  public void extractingAContactFromALegacyEnvelopeDoesNotThrow() {
    final MessageEnvelope envelope =
        new MessageEnvelope(new byte[] {1, 2, 3}, 3, PEER, 7296);

    // Must not raise: before the fix this propagated an unchecked IllegalArgumentException out of
    // the click listener and killed the input-method process.
    final Object contact = SignalProtocolMain.extractContactFromMessageEnvelope(envelope);
    // "contact == null || contact instanceof Object" was the assertion here, which is true for
    // every possible value - the test proved only that the call returned. A null result is the
    // correct outcome for an envelope naming a peer that is not in the contact list; anything else
    // means the lookup matched something it should not have.
    assertNull("an envelope from an unknown peer must not resolve to a contact", contact);
  }

  @Test
  public void everyLegacyEnvelopeDeviceIdIsSurvivable() {
    for (int deviceId = 0; deviceId < 10_000; deviceId += 37) {
      final MessageEnvelope envelope =
          new MessageEnvelope(new byte[] {1}, 3, PEER, deviceId);
      // The call must not throw for any id a 0.1.5 peer could have generated.
      SignalProtocolMain.extractContactFromMessageEnvelope(envelope);
    }
  }

  /**
   * The address a legacy envelope maps to must be constructible and stable — the same peer has to
   * resolve to the same address every time or contacts and sessions stop matching.
   */
  @Test
  public void aLegacyEnvelopeMapsToAStableConstructibleAddress() {
    final SignalProtocolAddress first = ProtocolAddresses.of(PEER, 7296);
    final SignalProtocolAddress second = ProtocolAddresses.of(PEER, 7296);

    assertNotNull(first);
    assertTrue(ProtocolAddresses.isValidDeviceId(first.getDeviceId()));
    assertTrue("the same peer must map to the same address", first.equals(second));
  }
}
