package com.amnesica.kryptey.inputmethod.signalprotocol.encoding;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.amnesica.kryptey.inputmethod.latin.e2ee.E2EEStrip;
import com.amnesica.kryptey.inputmethod.signalprotocol.MessageEnvelope;
import com.amnesica.kryptey.inputmethod.signalprotocol.SignalProtocolMain;

import org.junit.Test;

import java.io.IOException;

/**
 * The single seam between a {@link MessageEnvelope} and the text that goes into a messenger.
 *
 * <p>{@link EnvelopeCodec#fromWire} parses clipboard content, so most of what it ever sees is
 * ordinary text that is not an envelope at all. Every one of those must come back as a checked
 * {@code IOException}: an unchecked exception here reaches {@code LatinIME.setInputView()} and
 * kills the input-method process, which is this codebase's most persistent crash mode.
 */
public class EnvelopeCodecTest {

  private static MessageEnvelope bundle() {
    SignalProtocolMain.testIsRunning = true;
    SignalProtocolMain.initialize(null);
    final MessageEnvelope envelope = SignalProtocolMain.getPreKeyResponseMessage();
    assertNotNull(envelope);
    return envelope;
  }

  @Test
  public void aBundleSurvivesTheFullTextRoundTrip() throws Exception {
    final MessageEnvelope original = bundle();
    final MessageEnvelope restored = EnvelopeCodec.fromWire(EnvelopeCodec.toWire(original));

    assertArrayEquals(original.getPreKeyResponse().getIdentityKey().serialize(),
        restored.getPreKeyResponse().getIdentityKey().serialize());
    assertArrayEquals(
        original.getPreKeyResponse().getDevices().get(0).getKyberPreKey().getPublicKey().serialize(),
        restored.getPreKeyResponse().getDevices().get(0).getKyberPreKey().getPublicKey().serialize());
  }

  @Test
  public void aMessageSurvivesTheFullTextRoundTrip() throws Exception {
    final MessageEnvelope original = new MessageEnvelope(new byte[] {4, 5, 6}, 2, "sender", 9);
    final MessageEnvelope restored = EnvelopeCodec.fromWire(EnvelopeCodec.toWire(original));

    assertArrayEquals(original.getCiphertextMessage(), restored.getCiphertextMessage());
    assertEquals(original.getSignalProtocolAddressName(),
        restored.getSignalProtocolAddressName());
    assertEquals(original.getDeviceId(), restored.getDeviceId());
  }

  @Test
  public void surroundingWhitespaceIsTolerated() throws Exception {
    // Copying from a messenger routinely picks up a trailing newline.
    final String wire = EnvelopeCodec.toWire(bundle());
    assertNotNull(EnvelopeCodec.fromWire("  " + wire + "\n"));
  }

  /** The realistic case: the clipboard holds something that is not an envelope. */
  @Test
  public void ordinaryClipboardTextIsRejectedAsCheckedFailure() {
    final String[] notEnvelopes = {
        "", "   ", "hello", "https://example.com/some/path",
        "{\"preKeyResponse\":null}",                 // the old JSON format
        "Lorem ipsum dolor sit amet, consectetur.",
        "1234567890", "🔐🔐🔐", "-----BEGIN PGP MESSAGE-----",
    };
    for (final String text : notEnvelopes) {
      try {
        EnvelopeCodec.fromWire(text);
        throw new AssertionError("accepted non-envelope text: " + text);
      } catch (IOException expected) {
        // correct
      } catch (Throwable unchecked) {
        throw new AssertionError("unchecked exception for clipboard text \"" + text + "\": "
            + unchecked, unchecked);
      }
    }
  }

  @Test
  public void nullInputIsACheckedFailure() {
    assertThrows(IOException.class, () -> EnvelopeCodec.fromWire(null));
  }


  /**
   * The bundle produced through the live path must fit the limit the UI enforces. This is the
   * assertion that would have caught the invite being ~5x over the cap, and it now measures the
   * binary format rather than a JSON encoding the app no longer uses.
   */
  @Test
  public void aBundleOnTheRealWireFitsTheInviteLimit() throws Exception {
    final String wire = EnvelopeCodec.toWire(bundle());
    System.out.println("WIRE live-path chars = " + wire.length()
        + " limit = " + E2EEStrip.CHAR_THRESHOLD_PRE_KEY_RESPONSE);

    assertTrue("a key bundle is " + wire.length() + " characters but the invite limit is "
            + E2EEStrip.CHAR_THRESHOLD_PRE_KEY_RESPONSE,
        wire.length() <= E2EEStrip.CHAR_THRESHOLD_PRE_KEY_RESPONSE);
  }

  /** Corrupting the transmitted text must never produce an unchecked exception. */
  @Test
  public void corruptedWireTextNeverThrowsUnchecked() throws Exception {
    final String wire = EnvelopeCodec.toWire(bundle());
    final java.util.Random random = new java.util.Random(4242L);

    for (int trial = 0; trial < 300; trial++) {
      final char[] chars = wire.toCharArray();
      for (int flip = 0; flip < 5; flip++) {
        chars[random.nextInt(chars.length)] = (char) ('A' + random.nextInt(26));
      }
      try {
        EnvelopeCodec.fromWire(new String(chars));
      } catch (IOException expected) {
        // correct
      } catch (Throwable unchecked) {
        throw new AssertionError("corrupted wire text escaped as " + unchecked, unchecked);
      }
    }
  }

  @Test
  public void truncatedWireTextNeverThrowsUnchecked() throws Exception {
    final String wire = EnvelopeCodec.toWire(bundle());
    for (int len = 0; len < wire.length(); len += 7) {
      try {
        EnvelopeCodec.fromWire(wire.substring(0, len));
      } catch (IOException expected) {
        // correct
      } catch (Throwable unchecked) {
        throw new AssertionError("truncation at " + len + " escaped as " + unchecked,
            unchecked);
      }
    }
  }
}
