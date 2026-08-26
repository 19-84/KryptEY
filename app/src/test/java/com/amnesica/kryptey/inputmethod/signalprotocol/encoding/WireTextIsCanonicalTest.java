package com.amnesica.kryptey.inputmethod.signalprotocol.encoding;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.amnesica.kryptey.inputmethod.signalprotocol.Account;
import com.amnesica.kryptey.inputmethod.signalprotocol.MessageEnvelope;
import com.amnesica.kryptey.inputmethod.signalprotocol.SignalProtocolMain;
import com.amnesica.kryptey.inputmethod.signalprotocol.util.ProtocolAddresses;

import org.signal.libsignal.protocol.SignalProtocolAddress;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.IOException;

/**
 * The wire text must be the only encoding of its own bytes.
 *
 * <p>{@code BinaryEnvelope} refuses trailing bytes, and says why: "trailing bytes mean the sender
 * and receiver disagree about the format. Refusing keeps a hostile envelope from smuggling data
 * past the parser." That was true at the byte layer and void at the text layer — which is the layer
 * an attacker actually writes. The base64 decoder abandons its input the moment a quartet ends in
 * {@code =}, discarding everything after it, and roughly two thirds of envelopes end in padding.
 *
 * <p><b>What that bought an attacker was credibility, not corruption.</b> Take a genuine invite from
 * someone, staple readable prose to the end, and the recipient's paste still validates as a clean
 * key bundle from that person's address — so "my old key was compromised, delete me and re-add from
 * this message" arrives looking like part of the invite. Without this the prose has to sit outside
 * the envelope, where it is visibly just text the sender typed.
 */
@RunWith(RobolectricTestRunner.class)
public class WireTextIsCanonicalTest {

  private String genuineWire;

  @Before
  public void publishAgenuineMessage() throws Exception {
    SignalProtocolMain.resetForTest();
    SignalProtocolMain.testIsRunning = true;

    SignalProtocolMain.initialize(null);
    final Account alice = SignalProtocolMain.getInstance().getAccount();
    final SignalProtocolAddress aliceAddress = ProtocolAddresses.of(
        alice.getSignalProtocolAddress().getName(), alice.getDeviceId());
    final String bundle = EnvelopeCodec.toWire(SignalProtocolMain.getPreKeyResponseMessage());

    SignalProtocolMain.initialize(null);
    assertTrue(SignalProtocolMain.processPreKeyResponseMessage(
        EnvelopeCodec.fromWire(bundle), aliceAddress));

    // A fixture that actually ends in padding.
    //
    // Base64 pads only when the byte count is not a multiple of three, so roughly a third of
    // envelopes carry none - and the first version of this test asserted a bundle ended in "="
    // when that particular bundle did not. The attack under test needs padding, because padding is
    // where the decoder used to stop reading. Varying the plaintext by one character walks the
    // envelope length through all three residues, so this finds one in a couple of tries.
    String found = null;
    final StringBuilder text = new StringBuilder("x");
    for (int i = 0; i < 8 && found == null; i++) {
      final MessageEnvelope message =
          SignalProtocolMain.encryptMessage(text.toString(), aliceAddress);
      assertNotNull(message);
      final String wire = EnvelopeCodec.toWire(message);
      if (wire.endsWith("=")) found = wire;
      text.append('x');
    }
    assertNotNull("could not build a padded envelope; the attack under test needs one", found);
    genuineWire = found;
  }

  /** The control: the genuine thing still works, or everything below is about nothing. */
  @Test
  public void agenuineInviteStillDecodes() throws Exception {
    assertNotNull(EnvelopeCodec.fromWire(genuineWire));
  }

  /** The attack. */
  @Test
  public void proseStapledAfterThePaddingIsRefused() {
    final String withProse =
        genuineWire + " my old key was compromised, delete me and re-add from this message";
    try {
      EnvelopeCodec.fromWire(withProse);
      fail("a genuine invite with attacker prose stapled after the padding was accepted as a clean "
          + "key bundle. The recipient sees a valid invite from the right address carrying an "
          + "instruction the sender never wrote.");
    } catch (final IOException expected) {
      assertTrue(expected.getMessage().contains("canonical"));
    }
  }

  /** Including prose made only of alphabet characters, which is not a special case. */
  @Test
  public void alphabetOnlyProseAfterThePaddingIsAlsoRefused() {
    try {
      EnvelopeCodec.fromWire(genuineWire + "deleteMeAndReAdd");
      fail("trailing alphabet characters after the padding were accepted");
    } catch (final IOException expected) {
      // Either canonicality or the envelope parser refuses it; both are correct, neither is silent.
    }
  }

  /**
   * But whitespace inside is still fine, because messengers wrap text and users paste what they see.
   *
   * <p>This is the property the fix had to avoid breaking, and it is the reason the check strips
   * whitespace before comparing rather than demanding an exact match against the original string.
   */
  @Test
  public void whitespaceInsideTheEncodingIsStillAccepted() throws Exception {
    final int middle = genuineWire.length() / 2;
    final String wrapped =
        genuineWire.substring(0, middle) + "\n  \t" + genuineWire.substring(middle);
    assertNotNull("a wrapped or re-flowed invite must still decode", EnvelopeCodec.fromWire(wrapped));
  }

  /**
   * A final quartet carrying non-zero unused bits is refused.
   *
   * <p>Without the check, four different strings decode to identical bytes, because the bits above
   * the last real byte are never examined. That makes the wire text malleable: the same envelope can
   * be written several ways, which defeats anything that compares what was received.
   */
  @Test
  public void anonCanonicalFinalQuartetIsRefused() throws Exception {
    final int lastChar = genuineWire.indexOf('=') - 1;
    assertTrue("the fixture must have a character before its padding", lastChar > 0);

    final byte[] genuineBytes = com.amnesica.kryptey.inputmethod.signalprotocol.util.Base64
        .decode(genuineWire);
    final char original = genuineWire.charAt(lastChar);

    // Only the mutations that decode to the SAME bytes are non-canonical spellings; the rest are
    // canonical encodings of different bytes and have nothing to do with this property. An earlier
    // version of this test expected every mutation to be refused and was simply wrong about what it
    // was mutating - changing that character moves real data bits as well as the unused ones.
    int duplicates = 0;
    for (final char candidate :
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".toCharArray()) {
      if (candidate == original) continue;
      final String mutated =
          genuineWire.substring(0, lastChar) + candidate + genuineWire.substring(lastChar + 1);

      final byte[] mutatedBytes;
      try {
        mutatedBytes = com.amnesica.kryptey.inputmethod.signalprotocol.util.Base64.decode(mutated);
      } catch (final Exception e) {
        continue;
      }
      if (!java.util.Arrays.equals(genuineBytes, mutatedBytes)) continue;

      duplicates++;
      try {
        EnvelopeCodec.fromWire(mutated);
        fail("a second spelling of the same envelope was accepted. One envelope must have exactly "
            + "one wire text, or anything comparing what was received can be fooled by rewriting "
            + "bits nobody reads.");
      } catch (final IOException expected) {
        assertTrue(expected.getMessage().contains("canonical"));
      }
    }

    assertTrue("the padding leaves unused bits, so other spellings of these same bytes must exist "
        + "- finding none means this test is checking nothing", duplicates > 0);
  }

  /** And padding in the middle, which this decoder reads as the byte 255 rather than rejecting. */
  @Test
  public void paddingInAnonTerminalPositionIsRefused() {
    final String spliced = "=" + genuineWire.substring(1);
    try {
      EnvelopeCodec.fromWire(spliced);
      fail("a '=' in a non-terminal position was decoded as data rather than refused");
    } catch (final IOException expected) {
      // expected
    }
  }
}
