package com.amnesica.kryptey.inputmethod.signalprotocol.encoding;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.amnesica.kryptey.inputmethod.latin.e2ee.E2EEStrip;
import com.amnesica.kryptey.inputmethod.signalprotocol.MessageEnvelope;
import com.amnesica.kryptey.inputmethod.signalprotocol.util.Base64;

import org.junit.Test;

import java.io.IOException;
import com.amnesica.kryptey.inputmethod.signalprotocol.SignalProtocolMain;

/**
 * Regression tests for envelopes that decoded cleanly and then killed the IME further downstream.
 *
 * <p>The codec survived 600k fuzzed inputs without throwing unchecked, which is necessary but not
 * sufficient: it was also accepting field values that libsignal rejects with <em>unchecked</em>
 * {@code IllegalArgumentException}, and no caller on the clipboard path catches those. Validation
 * belongs in the parser, because everything after it takes these values on trust.
 */
public class HostileEnvelopeTest {

  private static String wire(final byte[] raw) {
    return Base64.encodeBytes(raw);
  }

  /** version, flags=ciphertext, name "x", deviceId, type, len, body */
  private static byte[] messageWithDeviceId(final int deviceId) {
    return new byte[] {1, 0x02, 1, 'x', (byte) deviceId, 3, 0, 2, 9, 9};
  }

  @Test
  public void anOutOfRangeTopLevelDeviceIdIsRejectedAtParseTime() {
    for (final int bad : new int[] {0, 128, 200, 255}) {
      final IOException e = assertThrows("deviceId " + bad + " should be refused",
          IOException.class, () -> BinaryEnvelope.decode(messageWithDeviceId(bad)));
      assertTrue(e.getMessage().contains("device id out of libsignal's range"));
    }
  }

  @Test
  public void validDeviceIdsStillDecode() throws Exception {
    for (final int ok : new int[] {1, 42, 127}) {
      // Discarding the result would let this pass even if decode returned an envelope naming a
      // different device - the bounds check is only half of what matters here.
      assertEquals("decoded the wrong device id", ok,
          BinaryEnvelope.decode(messageWithDeviceId(ok)).getDeviceId());
    }
  }

  /** encode must refuse what decode refuses, or we emit envelopes we cannot read back. */
  @Test
  public void encodeRefusesAnOutOfRangeDeviceId() {
    assertThrows(IOException.class,
        () -> BinaryEnvelope.encode(new MessageEnvelope(new byte[] {1}, 2, "sender", 0)));
    assertThrows(IOException.class,
        () -> BinaryEnvelope.encode(new MessageEnvelope(new byte[] {1}, 2, "sender", 9999)));
  }

  /** An envelope carrying nothing: encode() refuses to emit it, so decode() must refuse it too. */
  @Test
  public void anEmptyFlagsEnvelopeIsRejected() {
    final IOException e = assertThrows(IOException.class,
        () -> BinaryEnvelope.decode(new byte[] {1, 0x00, 1, 'x', 42}));
    assertTrue(e.getMessage().contains("carries nothing"));
  }

  @Test
  public void aZeroLengthCiphertextIsRejected() {
    final IOException e = assertThrows(IOException.class,
        () -> BinaryEnvelope.decode(new byte[] {1, 0x02, 1, 'x', 42, 3, 0, 0}));
    assertTrue(e.getMessage().contains("length is zero"));
  }

  // ------------------------------------------------------------- size limits

  /**
   * The FairyTale path feeds an unbounded inflater: ~390 KB of clipboard text expanded to 64 MB on
   * the IME main thread. OutOfMemoryError is an Error, so nothing downstream could catch it.
   */
  @Test
  public void oversizedWireTextIsRefusedBeforeAnyExpansion() {
    final StringBuilder huge = new StringBuilder();
    for (int i = 0; i < EnvelopeCodec.MAX_WIRE_CHARS + 100; i++) huge.append('A');

    final IOException e =
        assertThrows(IOException.class, () -> EnvelopeCodec.fromWire(huge.toString()));
    assertTrue(e.getMessage().contains("too large"));
  }

  /**
   * A REAL bundle, not two constants.
   *
   * <p>This compared {@code MAX_WIRE_CHARS} with {@code CHAR_THRESHOLD_PRE_KEY_RESPONSE} - 8192
   * against 4096 - and constructed no bundle at all, so a bundle that outgrew the cap left it
   * green. The send-side threshold is not what the cap has to clear; the actual serialised bundle
   * is, and PQXDH is what made that a real question.
   */
  @Test
  public void theWireCapComfortablyExceedsARealBundle() throws Exception {
    SignalProtocolMain.testIsRunning = true;
    SignalProtocolMain.initialize(null);
    final String bundle = SignalProtocolMain.exportOwnKeyBundle();

    assertNotNull("a real bundle must serialise", bundle);
    assertTrue("a real PQXDH bundle is " + bundle.length() + " characters against a cap of "
            + EnvelopeCodec.MAX_WIRE_CHARS + " - the cap must not reject legitimate key bundles",
        bundle.length() < EnvelopeCodec.MAX_WIRE_CHARS);
    assertTrue("and it must round-trip at that size",
        EnvelopeCodec.fromWire(bundle).getPreKeyResponse() != null);
  }
}
