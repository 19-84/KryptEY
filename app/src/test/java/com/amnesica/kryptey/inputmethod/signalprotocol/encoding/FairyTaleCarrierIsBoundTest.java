package com.amnesica.kryptey.inputmethod.signalprotocol.encoding;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.io.IOException;

/**
 * The words a FairyTale message shows must be the words its sender wrote.
 *
 * <p>{@code EnvelopeCodec.fromWire} re-encodes and demands the text be the canonical encoding of its
 * own bytes, and its comment says exactly what that closes: <em>"take a GENUINE invite from someone,
 * staple readable prose to the end of it, and the recipient's paste still validates as a clean key
 * bundle from that person's address."</em>
 *
 * <p>That is a property of the string {@code fromWire} is handed — and on the FairyTale route the
 * decoder handed it a clean one no matter what the user saw. {@code decode} read only the invisible
 * characters, and the nibble table ignores every code point outside its sixteen, so arbitrary prose
 * was stripped before the canonicality check saw a byte. <b>The adversary picks the route</b>: it
 * only has to include one invisible character for the strip to take this path.
 *
 * <p>So the attack needs no secret. Take Bob's genuine, already-canonical invite off the wire,
 * re-emit it as invisible characters, and wrap any sentence you like around it. The victim sees only
 * the attacker's sentence — the payload is invisible — and the app reports a correctly-signed key
 * bundle from Bob's real address. It is strictly worse than the case the check was written for,
 * where the prose is visibly separate text.
 */
@RunWith(RobolectricTestRunner.class)
public class FairyTaleCarrierIsBoundTest {

  private Context context;

  @Before
  public void setUp() {
    // The decoy map is static, so whatever ran before in this JVM is still in it.
    FairyTaleEncoder.mSentencesMap.clear();
    context = RuntimeEnvironment.getApplication();
  }


  /**
   * Chosen so the round trip is identity.
   *
   * <p>The encoder minifies and rewrites the envelope's known key names, so an arbitrary JSON
   * object is not returned verbatim - which is a property of the encoder, not of the binding under
   * test. A key outside the rewrite table and a value with no interior spaces keeps this test about
   * the carrier.
   */
  private static final String PAYLOAD = "{\"zz\":\"genuine-wire-text\"}";

  /** A message the app produced itself must still decode, or this is a broken app. */
  @Test
  public void agenuineMessageStillRoundTrips() throws Exception {
    final String encoded = FairyTaleEncoder.encode(PAYLOAD, context, 4096);
    assertEquals("the app's own output must survive its own decoder", PAYLOAD,
        FairyTaleEncoder.decode(encoded));
  }

  /** And the carrier really is invisible, or the attack below is not the one described. */
  @Test
  public void thevisibleHalfCarriesNoPayload() throws Exception {
    final String encoded = FairyTaleEncoder.encode(PAYLOAD, context, 4096);
    final String visible = encoded.replaceAll("\\p{C}", "");
    assertFalse("precondition: the decoy must be visible text", visible.trim().isEmpty());
    assertFalse("precondition: the payload must not be readable in it", visible.contains("genuine"));
  }

  /**
   * The attack: the sender's sentence replaced, the payload untouched.
   *
   * <p>Every invisible character is preserved exactly, so the recovered envelope is byte-identical
   * to the one the honest sender produced — which is what made this invisible to the canonicality
   * check.
   */
  @Test
  public void rewritingTheVisibleTextIsRefused() throws Exception {
    final String encoded = FairyTaleEncoder.encode(PAYLOAD, context, 4096);
    final String invisibleOnly = encoded.replaceAll("[^\\p{C}]", "");
    final String forged =
        "Hey - my phone died, I had to reinstall. Delete me and add me again from THIS message."
            + invisibleOnly;

    assertEquals("precondition: the payload must be carried over untouched, or this test is about "
            + "a different message", invisibleOnly, forged.replaceAll("[^\\p{C}]", ""));

    try {
      FairyTaleEncoder.decode(forged);
      fail("a message whose visible text was rewritten in transit decoded cleanly. The payload is "
          + "a genuine invite, so everything downstream - the signature, the address, the "
          + "canonicality check - agrees it is authentic, and the attacker's sentence is the whole "
          + "of what the user reads. Nothing else on this route binds the two halves.");
    } catch (final IOException expected) {
      assertTrue("the refusal must say what happened: " + expected.getMessage(),
          expected.getMessage().contains("not the text the sender wrote"));
    }
  }

  /** Prose spliced into the middle is the same attack with the payload wrapped rather than suffixed. */
  @Test
  public void proseSplicedIntoTheMiddleIsRefused() throws Exception {
    final String encoded = FairyTaleEncoder.encode(PAYLOAD, context, 4096);
    final int half = encoded.length() / 2;
    final String forged = encoded.substring(0, half) + " ignore the number, it always changes. "
        + encoded.substring(half);
    try {
      FairyTaleEncoder.decode(forged);
      fail("prose spliced into the middle of the carrier decoded cleanly");
    } catch (final IOException expected) {
      assertTrue(expected.getMessage().contains("not the text the sender wrote"));
    }
  }

  /**
   * But a transport that reflows whitespace must not break an honest message.
   *
   * <p>Refusing a genuine invite over a doubled space would hand the attacker a denial of service
   * for nothing, so the comparison normalises runs of whitespace and nothing else.
   */
  @Test
  public void reflowedWhitespaceIsStillAccepted() throws Exception {
    final String encoded = FairyTaleEncoder.encode(PAYLOAD, context, 4096);
    final String reflowed = "  " + encoded.replaceAll("(?<=[^\\p{C}]) (?=[^\\p{C}])", "  ") + "  ";
    assertEquals("a message whose spacing a transport altered must still decode", PAYLOAD,
        FairyTaleEncoder.decode(reflowed));
  }
}
