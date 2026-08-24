package com.amnesica.kryptey.inputmethod.latin.e2ee;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;

import com.amnesica.kryptey.inputmethod.signalprotocol.Account;
import com.amnesica.kryptey.inputmethod.signalprotocol.SignalProtocolMain;
import com.amnesica.kryptey.inputmethod.signalprotocol.encoding.Encoder;
import com.amnesica.kryptey.inputmethod.signalprotocol.encoding.EnvelopeCodec;
import com.amnesica.kryptey.inputmethod.signalprotocol.encoding.FairyTaleEncoder;
import com.amnesica.kryptey.inputmethod.signalprotocol.exceptions.TooManyCharsException;
import com.amnesica.kryptey.inputmethod.signalprotocol.util.ProtocolAddresses;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.signal.libsignal.protocol.SignalProtocolAddress;

import java.io.IOException;

/**
 * Whatever the send path emits, the receive path must accept - or the send must fail loudly.
 *
 * <p>The test this replaces asserted the wrong thing in two ways, and both are worth recording
 * because they are the failure mode of a test written from the constants rather than from the code.
 *
 * <p>First, its model of the send path was wrong. Its javadoc said the send side "counts UTF-8
 * bytes of the plaintext wire text"; {@code checkMessageLengthForEncodingMethod} counts the USER'S
 * plaintext, which for a 500-byte message becomes 3068 wire characters - and 5500 when a signed
 * pre-key rotation falls due and a full PQXDH bundle rides along. It asserted something six to
 * eleven times smaller than reality.
 *
 * <p>Second, it could not fail for any constant it named. Deleting the send caps outright left it
 * green; it only fired if a threshold was RAISED. A test that passes when the thing it guards is
 * removed is not a guard.
 *
 * <p>So this drives the real path end to end and asserts the property that actually matters:
 * anything {@code encode} hands back is decodable, and anything that would not be is refused with a
 * message the user can act on.
 */
@RunWith(RobolectricTestRunner.class)
public class SendableIsDecodableTest {

  private E2EEStrip strip;
  private Context context;
  private Account alice;
  private SignalProtocolAddress bobAddress;

  @Before
  public void buildARealSession() throws Exception {
    context = RuntimeEnvironment.getApplication();
    strip = new E2EEStrip(context);
    FairyTaleEncoder.initForTest(
        "Once upon a time there was a keyboard that kept its own counsel and read nobody's mail.",
        "The miller had a daughter who spun straw, and the straw was never gold, only ever straw.");

    SignalProtocolMain.testIsRunning = true;
    SignalProtocolMain.initialize(null);
    final Account bob = SignalProtocolMain.getInstance().getAccount();
    SignalProtocolMain.initialize(null);
    alice = SignalProtocolMain.getInstance().getAccount();

    bobAddress = ProtocolAddresses.of(bob.getSignalProtocolAddress().getName(), bob.getDeviceId());

    SignalProtocolMain.getInstance().setAccount(bob);
    final String bobBundle = SignalProtocolMain.exportOwnKeyBundle();
    SignalProtocolMain.getInstance().setAccount(alice);
    assertTrue(SignalProtocolMain.processPreKeyResponseMessage(
        EnvelopeCodec.fromWire(bobBundle), bobAddress));
  }

  private static String plaintext(final int bytes) {
    return incompressible(bytes);
  }

  /**
   * Text that will not compress, which is the worst case for every bound here. The encoder deflates
   * before it expands, so repeated characters understate the encoded size by orders of magnitude.
   */
  private static String incompressible(final int length) {
    final String alphabet =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    final java.util.Random random = new java.util.Random(20260824L);
    final StringBuilder builder = new StringBuilder(length);
    while (builder.length() < length) {
      builder.append(alphabet.charAt(random.nextInt(alphabet.length())));
    }
    return builder.toString();
  }

  /**
   * The core property, over both encoders and a spread of sizes up to the send cap.
   *
   * <p>Either the send refuses - and the user is told - or what comes out fits through the
   * recipient's door. There is no third acceptable outcome, and the third outcome is what shipped.
   */
  @Test
  public void anythingTheSendPathEmitsIsDecodable() throws Exception {
    for (final Encoder encoder : new Encoder[] {Encoder.RAW, Encoder.FAIRYTALE}) {
      for (final int size : new int[] {1, 50, 200, 300, 400, 499, 500}) {
        assertSendableOrRefused(plaintext(size), encoder, "no rotation due");
      }
    }
  }

  /**
   * The case that actually failed: a signed pre-key rotation falling due while the session is still
   * pending-prekey, so a full PQXDH bundle rides along with an ordinary chat message.
   *
   * <p>Measured before the fix: wire 5500 characters, FairyTale-encoded to 8398, against a cap of
   * 8192 - sent successfully, undecodable on arrival. Reachable by any account older than 30 days
   * sending its first sizable message to a newly imported contact.
   */
  @Test
  public void arotationDueMessageIsRefusedRatherThanSentUndecodable() throws Exception {
    alice.getMetadataStore().setNextSignedPreKeyRefreshTime(1L);   // long overdue

    for (final int size : new int[] {200, 300, 400, 500}) {
      assertSendableOrRefused(plaintext(size), Encoder.FAIRYTALE, "rotation due");
      // Rotation is consumed by the first send, so put it back for the next size.
      alice.getMetadataStore().setNextSignedPreKeyRefreshTime(1L);
    }
  }

  private void assertSendableOrRefused(final String message, final Encoder encoder,
                                       final String situation) throws Exception {
    final CharSequence encoded;
    try {
      encoded = strip.encryptMessage(message, bobAddress, encoder);
    } catch (TooManyCharsException refused) {
      assertTrue("a refusal must tell the user what to do about it: " + refused.getMessage(),
          refused.getMessage() != null && refused.getMessage().contains("recipient"));
      return;
    }

    assertNotNull(situation + ", " + encoder + ", " + message.length() + " bytes: nothing encoded",
        encoded);
    assertTrue(situation + ", " + encoder + ", " + message.length() + " bytes: encoded to "
            + encoded.length() + " characters, past the " + E2EEStrip.MAX_DECODABLE_CHARS
            + " the recipient will accept - it would send and fail on their device",
        encoded.length() <= E2EEStrip.MAX_DECODABLE_CHARS);
  }

  /**
   * And the refusal must be reachable at all, or the test above passes by never exercising it. A
   * plaintext far past the cap has to produce one.
   */
  @Test
  public void therefusalPathIsActuallyReachable() {
    try {
      // Straight through encode, bypassing the plaintext cap, with something that cannot fit.
      //
      // Incompressible, and that is not a detail: the encoder deflates before it expands, so
      // "y".repeat(20000) encodes to a few hundred characters and sails through. A test using
      // repeated input here would have passed while proving nothing.
      strip.encode(incompressible(20000), Encoder.FAIRYTALE);
      fail("encoding 20000 characters must be refused, not handed back undecodable");
    } catch (TooManyCharsException expected) {
      assertTrue(expected.getMessage().contains("recipient"));
    } catch (IOException e) {
      fail("expected a TooManyCharsException, got " + e);
    }
  }

  /** The raw encoder is a pass-through, so its own cap is what keeps it inside the door. */
  @Test
  public void therawPathNeedsNoExpansionHeadroom() {
    assertTrue("the raw send cap exceeds the decode cap",
        E2EEStrip.CHAR_THRESHOLD_RAW <= E2EEStrip.MAX_DECODABLE_CHARS);
  }

  /** A real key bundle - the other send path - must also survive its own encoder. */
  @Test
  public void arealInviteIsDecodableAfterEncoding() throws Exception {
    final String wire = SignalProtocolMain.exportOwnKeyBundle();
    assertTrue("precondition: the bundle must pass the send cap",
        wire.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
            <= E2EEStrip.CHAR_THRESHOLD_PRE_KEY_RESPONSE);

    final String encoded = strip.encode(wire, Encoder.FAIRYTALE);
    assertEquals("an invite must survive its own encoder and decode back unchanged",
        wire, FairyTaleEncoder.decode(encoded));
  }

  /**
   * The product limit on a chat message, which nothing asserted.
   *
   * <p>Deleting the FAIRYTALE plaintext cap left the whole suite green even after the encode-time
   * check was added, because the encode-time check catches only what would be undecodable. The
   * 500-byte limit is a separate, deliberate choice about message size, and a limit no test
   * mentions is a limit that quietly stops existing.
   */
  @Test
  public void aplaintextPastTheProductLimitIsRefused() {
    for (final Encoder encoder : new Encoder[] {Encoder.RAW, Encoder.FAIRYTALE}) {
      try {
        strip.encryptMessage(plaintext(E2EEStrip.CHAR_THRESHOLD_RAW + 1), bobAddress, encoder);
        fail(encoder + ": a message past the documented limit must be refused");
      } catch (TooManyCharsException expected) {
        assertTrue(expected.getMessage().contains("Too many characters"));
      } catch (Exception e) {
        fail(encoder + ": expected TooManyCharsException, got " + e);
      }
    }
  }
}
