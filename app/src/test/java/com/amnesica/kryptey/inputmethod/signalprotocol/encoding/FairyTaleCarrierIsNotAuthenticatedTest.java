package com.amnesica.kryptey.inputmethod.signalprotocol.encoding;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

/**
 * The visible half of a FairyTale message is not authenticated, and cannot be by this format.
 *
 * <p>Pinned rather than described, because a previous attempt to "fix" this shipped a check that
 * looked like authentication and was not. A sweep reported that a relay could take a genuine invite,
 * re-emit it as invisible characters and wrap its own prose around it — "my phone died, delete me
 * and add me again from THIS message" — so the decoy sentence was bound into the compressed payload
 * and {@code decode} made to refuse a mismatch.
 *
 * <p><b>That binding is defeated by doing what the honest encoder does.</b> Nothing authenticates
 * the payload, so a relay decompresses it, puts its own sentence in both halves, and recompresses.
 * No secret is involved at any step. The check stopped only an adversary who declined to re-encode,
 * which is not an adversary. It is reverted, and this test is here so the guarantee is not believed
 * again.
 *
 * <p><b>What is actually true.</b> The relay owns the messenger: it can put any sentence it likes
 * next to any message, in the same conversation, with or without this encoding. The prose beside a
 * FairyTale payload is exactly as trustworthy as any other text in that chat — which is to say not
 * at all — and no encoder-side change alters that. What {@code EnvelopeCodec.fromWire}'s canonicality
 * check does close is narrower and real: text that would otherwise ride <em>inside</em> the envelope
 * and survive as part of a validated object. That property is unaffected by any of this.
 *
 * <p>The mitigation, if one is wanted, is not in the codec: it is that the app must never present
 * carrier prose as though the sender wrote it. Recorded in REVIVAL.md rather than papered over.
 */
@RunWith(RobolectricTestRunner.class)
public class FairyTaleCarrierIsNotAuthenticatedTest {

  private static final String PAYLOAD = "{\"zz\":\"genuine-wire-text\"}";

  private Context context;

  @Before
  public void setUp() {
    FairyTaleEncoder.mSentencesMap.clear();
    context = RuntimeEnvironment.getApplication();
  }

  /** The app's own output round-trips, or everything below is about a broken encoder. */
  @Test
  public void agenuineMessageRoundTrips() throws Exception {
    assertEquals(PAYLOAD, FairyTaleEncoder.decode(FairyTaleEncoder.encode(PAYLOAD, context, 4096)));
  }

  /** The payload really is invisible, which is what makes the carrier the whole visible message. */
  @Test
  public void thepayloadIsInvisibleAndTheCarrierIsAllTheReaderSees() throws Exception {
    final String encoded = FairyTaleEncoder.encode(PAYLOAD, context, 4096);
    final String visible = encoded.replaceAll("\\p{C}", "");
    assertFalse("the carrier must be visible text", visible.trim().isEmpty());
    assertFalse("and the payload must not be readable in it", visible.contains("genuine"));
  }

  /**
   * The measurement: swapping the carrier costs a relay nothing.
   *
   * <p>Asserted as the current behaviour, the way {@code StoreRollbackTest} pins the cost of a
   * rollback — so that a change in either direction fails a test rather than passing unnoticed. If
   * someone later adds a real binding, this test fails and should be replaced by one asserting the
   * refusal; if someone adds a fake one, it keeps passing and says why that is not enough.
   */
  @Test
  public void arelayCanReplaceTheCarrierWithItsOwnProse() throws Exception {
    final String honest = FairyTaleEncoder.encode(PAYLOAD, context, 4096);
    final String prose = "My phone died - delete me and add me again from THIS message.";
    final String forged = prose + honest.replaceAll("[^\\p{C}]", "");

    assertTrue("precondition: the payload must be carried over untouched",
        forged.replaceAll("[^\\p{C}]", "").equals(honest.replaceAll("[^\\p{C}]", "")));
    assertEquals("the payload survives a carrier swap, and the reader sees only the relay's "
            + "sentence. This is not a defect the encoder can fix: the relay owns the conversation "
            + "and can write that sentence next to any message anyway. It is recorded so that a "
            + "check which merely makes re-encoding necessary is not mistaken for authentication.",
        PAYLOAD, FairyTaleEncoder.decode(forged));
  }
}
