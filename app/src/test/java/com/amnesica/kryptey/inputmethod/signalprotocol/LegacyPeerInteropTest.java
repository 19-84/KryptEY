package com.amnesica.kryptey.inputmethod.signalprotocol;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.amnesica.kryptey.inputmethod.signalprotocol.util.ProtocolAddresses;

import org.junit.After;
import org.junit.Before;
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

  /** Somebody else, in the contact list, so "resolves to nobody" is not trivially true. */
  private static final String SOMEBODY_ELSE = "9c1f0d84-1111-2222-3333-444455556666";

  /**
   * An account holding one contact who is NOT the peer these envelopes name.
   *
   * <p>Without this the null assertions below hold for the wrong reason: no account means no
   * contact list means null for every input, and a lookup that matched the first row it found
   * would pass. With one row present, "resolved to nobody" is a claim about the lookup.
   */
  @Before
  public void setUp() {
    SignalProtocolMain.resetForTest();
    SignalProtocolMain.testIsRunning = true;
    SignalProtocolMain.initialize(null);
    final java.util.ArrayList<com.amnesica.kryptey.inputmethod.signalprotocol.chat.Contact> list =
        new java.util.ArrayList<>();
    list.add(new com.amnesica.kryptey.inputmethod.signalprotocol.chat.Contact(
        "Someone", "Else", SOMEBODY_ELSE, 7, false));
    SignalProtocolMain.getInstance().getAccount().setContactList(list);
  }

  @After
  public void tearDown() {
    SignalProtocolMain.resetForTest();
    SignalProtocolMain.testIsRunning = false;
  }

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

  /**
   * The same question over every device id a 0.1.5 peer could have generated.
   *
   * <p>This asserted nothing. It called the method 271 times and discarded every result, so it
   * proved only that no id throws — which is half the property, and the half the test beside it had
   * already been corrected for: "contact == null || contact instanceof Object" was true for every
   * possible value, and it was replaced with the real question. The loop in the same file kept the
   * weaker form. A lookup that started matching the wrong contact for some ids would have passed
   * here forever.
   */
  @Test
  public void everyLegacyEnvelopeDeviceIdIsSurvivable() {
    int examined = 0;
    for (int deviceId = 0; deviceId < 10_000; deviceId += 37) {
      final MessageEnvelope envelope =
          new MessageEnvelope(new byte[] {1}, 3, PEER, deviceId);
      // Must not throw for any id - before the fix an unchecked IllegalArgumentException escaped
      // the click listener and killed the input-method process...
      final Object resolved = SignalProtocolMain.extractContactFromMessageEnvelope(envelope);
      // ...and must not resolve either. PEER is in nobody's contact list, so every one of these is
      // an envelope from an unknown sender; anything non-null means the lookup matched something it
      // should not have, which on this path decides who a message is attributed to.
      assertNull("device id " + deviceId + " resolved an envelope from an unknown peer to a "
          + "contact", resolved);
      examined++;
    }
    assertEquals("the sweep must actually run; a changed bound that empties it would leave this "
        + "test passing while checking nothing", 271, examined);
    assertNotNull("and the contact list must be non-empty, or 'resolved to nobody' is true because "
            + "there is nobody rather than because the lookup was right",
        SignalProtocolMain.getInstance().getAccount().getContactList());
    assertEquals("one row, belonging to somebody else", 1,
        SignalProtocolMain.getInstance().getAccount().getContactList().size());
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
