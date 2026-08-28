package com.amnesica.kryptey.inputmethod.latin.e2ee;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertThrows;
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
 * plaintext, which for a 500-byte message becomes 3068 wire characters - and 5584 when a signed
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
   *
   * <p>Re-measured after bundles gained an issuing signature, because the numbers above are what
   * this file argues from: the rotation-due wire envelope is 5584 characters now, eighty-four more,
   * and a 200-byte message encodes to 7904 against the same 8192 cap. So the headroom on that path
   * is about 288 characters rather than about 370, and the refusal boundary moved down by roughly
   * sixty bytes of plaintext. Nobody is sent an undecodable message by it - that is the property
   * below, and it still holds - but a message that used to just fit is now refused, which is a cost
   * worth writing down rather than discovering.
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

  /**
   * The product limits are literal values, not whatever the constants happen to say.
   *
   * <p>{@code aplaintextPastTheProductLimitIsRefused} reads {@code CHAR_THRESHOLD_RAW + 1}, so it
   * moves with the constant: raising the cap from 500 to 5000 left it green while every RAW message
   * over about 4000 bytes would then be refused downstream by {@code encode}. A test that follows
   * the value it guards is not guarding it.
   */
  @Test
  public void theproductLimitsAreWhatTheyAreDocumentedToBe() {
    assertEquals("the raw message limit is a product decision, not an incidental constant",
        500, E2EEStrip.CHAR_THRESHOLD_RAW);
    assertEquals(500, E2EEStrip.CHAR_THRESHOLD_FAIRYTALE);
    assertEquals(4096, E2EEStrip.CHAR_THRESHOLD_PRE_KEY_RESPONSE);
    assertEquals(8192, E2EEStrip.MAX_DECODABLE_CHARS);
  }

  /**
   * What the RAW path actually emits, which is not what I previously asserted.
   *
   * <p>The test this replaces compared {@code CHAR_THRESHOLD_RAW} (500) against
   * {@code MAX_DECODABLE_CHARS} and its comment said "the raw encoder is a pass-through, so its own
   * cap is what keeps it inside the door". False: RAW passes through the WIRE ENVELOPE, not the
   * plaintext - measured 3068 characters for a 500-byte message and 5584 with a rotation-attached
   * bundle. Six to eleven times the number it was comparing. That is exactly the category error the
   * commit which wrote it says it is fixing, reintroduced in the replacement.
   */
  @Test
  public void therawPathEmitsTheWireEnvelopeAndItStillFits() throws Exception {
    final CharSequence encoded = strip.encryptMessage(plaintext(500), bobAddress, Encoder.RAW);

    assertNotNull(encoded);
    assertTrue("RAW emits the wire envelope, so it must be far larger than the 500-byte plaintext "
            + "- measured " + encoded.length() + " characters", encoded.length() > 2000);
    assertTrue("and it must still fit through the decode cap", 
        encoded.length() <= E2EEStrip.MAX_DECODABLE_CHARS);
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

  private int chatLogSize() {
    final java.util.List<?> messages = alice.getUnencryptedMessages();
    return messages == null ? 0 : messages.size();
  }

  /**
   * A refused send must leave no trace in the chat log.
   *
   * <p>{@code SignalProtocolMain.encryptMessage} writes the plaintext into the user's history and
   * persists it BEFORE returning, and the encoder that runs afterwards can still refuse. So the
   * refused attempt used to leave a history entry for a message nobody received - and because the
   * refusal consumes the pending signed pre-key rotation, pressing send again succeeded and added a
   * second entry. One message sent, two in the log. A user who instead obeys the toast and shortens
   * the message keeps a history entry for something that was never delivered.
   */
  @Test
  public void arefusedSendLeavesNothingInTheChatLog() throws Exception {
    alice.getMetadataStore().setNextSignedPreKeyRefreshTime(1L);

    final int before = chatLogSize();
    boolean refused = false;
    try {
      strip.encryptMessage(plaintext(500), bobAddress, Encoder.FAIRYTALE);
    } catch (TooManyCharsException expected) {
      refused = true;
    }

    // No "if (!refused) return" here. That is a silent-vacuity switch: the day a 500-byte
    // rotation-due message stops refusing, the test stops asserting and stays green, and the
    // rollback it guards becomes uncovered without anything failing.
    assertTrue("a 500-byte message with a rotation attached must exceed the cap - if that has "
        + "changed, this test needs a new size, not a quiet exit", refused);
    assertEquals("a refused send left the plaintext in the user's history", before, chatLogSize());
  }

  /**
   * The rollback must remove the REFUSED message, not merely one message.
   *
   * <p>The test this replaces asserted {@code chatLogSize() - before <= 1}, which every possible
   * outcome satisfies: rollback works, rollback deleted, refusal never reached. Worse, its first
   * call succeeded, so the refusal it did observe came from a bare {@code encode} that never
   * records anything - the test named for the rollback never exercised it.
   *
   * <p>What it should have caught: a rollback that scans forward and drops the recipient's OLDEST
   * message instead of the matching one silently deletes a real, previously delivered message from
   * the user's history. Counting entries cannot see that. This checks the contents.
   */
  @Test
  public void therollbackRemovesTheRefusedMessageAndNotAnEarlierOne() throws Exception {
    final String earlier = "an earlier message that was really sent";
    assertNotNull(strip.encryptMessage(earlier, bobAddress, Encoder.RAW));
    final int afterEarlier = chatLogSize();

    alice.getMetadataStore().setNextSignedPreKeyRefreshTime(1L);
    boolean refused = false;
    final String doomed = plaintext(500);
    try {
      strip.encryptMessage(doomed, bobAddress, Encoder.FAIRYTALE);
    } catch (TooManyCharsException expected) {
      refused = true;
    }

    assertTrue("this configuration must refuse, or the test proves nothing - if the wire size or "
        + "the cap has moved, re-derive the size rather than deleting the assertion", refused);
    assertEquals("the rollback must leave exactly the messages that were there before",
        afterEarlier, chatLogSize());

    boolean earlierSurvived = false;
    for (final Object message : alice.getUnencryptedMessages()) {
      final String text = ((com.amnesica.kryptey.inputmethod.signalprotocol.chat.StorageMessage)
          message).getUnencryptedMessage();
      assertNotEquals("the refused message must be gone from the history", doomed, text);
      if (earlier.equals(text)) earlierSurvived = true;
    }
    assertTrue("and a genuinely delivered message must NOT be the one removed", earlierSurvived);
  }

  /** And a successful send DOES record exactly one entry - or the rollback is indistinguishable. */
  @Test
  public void asuccessfulSendRecordsExactlyOneEntry() throws Exception {
    final int before = chatLogSize();

    assertNotNull(strip.encryptMessage("hello", bobAddress, Encoder.RAW));

    assertEquals("a successful send must record exactly one message", before + 1, chatLogSize());
  }

  /**
   * An encoder this code does not implement must FAIL, not quietly produce nothing.
   *
   * <p>{@code encode} handled FAIRYTALE and RAW and returned null for anything else, so
   * {@code encryptMessage} returned null with no exception - the rollback never fired and the
   * plaintext stayed in the user's history while the view reported "encryption failed". A null
   * encoder was worse: NullPointerException out of a click listener, which neither catch in
   * {@code encryptAndSendInputFieldContent} stops.
   *
   * <p>Unreachable today, since the chosen encoder is only ever RAW or FAIRYTALE. Worth closing
   * anyway because {@code checkMessageLengthForEncodingMethod} already tolerates a null encoder, so
   * the two halves of the send path disagreed about what counts as acceptable input.
   */
  @Test
  public void anunhandledEncoderFailsRatherThanReturningNothing() {
    for (final Encoder encoder : Encoder.values()) {
      if (encoder == Encoder.RAW || encoder == Encoder.FAIRYTALE) continue;

      final int before = chatLogSize();
      try {
        strip.encryptMessage("hello", bobAddress, encoder);
        fail(encoder + " produced no exception - the plaintext stays in the chat log while the "
            + "view reports a failure");
      } catch (Exception expected) {
        assertTrue(encoder + " must fail as an IOException, not an unchecked throwable from a "
                + "click listener: " + expected.getClass().getName(),
            expected instanceof IOException);
      }
      assertEquals(encoder + " left the plaintext in the chat log", before, chatLogSize());
    }
  }

  /** And a null encoder is refused the same way, rather than dereferenced. */
  @Test
  public void anullEncoderIsRefusedRatherThanDereferenced() {
    final int before = chatLogSize();
    try {
      strip.encryptMessage("hello", bobAddress, null);
      fail("a null encoder must be refused");
    } catch (Exception expected) {
      assertTrue("must be an IOException, not an NPE: " + expected.getClass().getName(),
          expected instanceof IOException);
    }
    assertEquals("a null encoder left the plaintext in the chat log", before, chatLogSize());
  }

  private static String asciiOfLength(final int length) {
    final StringBuilder builder = new StringBuilder(length);
    while (builder.length() < length) builder.append('a');
    return builder.toString();
  }

  /**
   * The invite cap refuses, and refuses at the right byte.
   *
   * <p>Measured by mutation, both halves of this were open: deleting the {@code throw} outright, so
   * an oversized invite is emitted with no refusal at all, survived the whole suite - and so did
   * moving the boundary by one. The message limit is the only thing standing between the send path
   * and a bundle the recipient cannot decode, which is a defect this branch has already had once,
   * in a different form ("messages that send but cannot be decoded").
   *
   * <p>Both directions are asserted together because a cap tested only from above is satisfied by
   * refusing everything, and one tested only from below by refusing nothing.
   */
  @Test
  public void theinviteCapRefusesAndDoesSoAtTheDocumentedByte() {
    final E2EEStrip strip = new E2EEStrip(RuntimeEnvironment.getApplication());

    final String atTheLimit = asciiOfLength(E2EEStrip.CHAR_THRESHOLD_PRE_KEY_RESPONSE);
    try {
      strip.checkMessageLengthForEncodingMethod(atTheLimit, Encoder.RAW, true);
    } catch (TooManyCharsException refused) {
      fail("an invite of exactly " + E2EEStrip.CHAR_THRESHOLD_PRE_KEY_RESPONSE
          + " bytes must be allowed: " + refused.getMessage());
    }

    final String oneOver = asciiOfLength(E2EEStrip.CHAR_THRESHOLD_PRE_KEY_RESPONSE + 1);
    assertThrows("one byte past the limit must be refused, or the limit is not the limit",
        TooManyCharsException.class,
        () -> strip.checkMessageLengthForEncodingMethod(oneOver, Encoder.RAW, true));
  }

  /**
   * And an invite far past the cap is refused too, whichever encoder is selected.
   *
   * <p>The invite branch returns before the per-encoder checks, so if its own throw is removed
   * nothing downstream catches an oversized bundle. This is the same property as above stated where
   * an off-by-one cannot mask it.
   */
  @Test
  public void agrosslyOversizedInviteIsRefusedUnderEveryEncoder() {
    final E2EEStrip strip = new E2EEStrip(RuntimeEnvironment.getApplication());
    final String huge = asciiOfLength(E2EEStrip.CHAR_THRESHOLD_PRE_KEY_RESPONSE * 4);

    for (final Encoder encoder : Encoder.values()) {
      assertThrows("an invite of " + huge.length() + " bytes must be refused under " + encoder,
          TooManyCharsException.class,
          () -> strip.checkMessageLengthForEncodingMethod(huge, encoder, true));
    }
  }

  /** The FairyTale limit is enforced as well, not only the raw one. */
  @Test
  public void thefairyTaleCapRefusesAndDoesSoAtTheDocumentedByte() {
    final E2EEStrip strip = new E2EEStrip(RuntimeEnvironment.getApplication());

    try {
      strip.checkMessageLengthForEncodingMethod(
          asciiOfLength(E2EEStrip.CHAR_THRESHOLD_FAIRYTALE), Encoder.FAIRYTALE, false);
    } catch (TooManyCharsException refused) {
      fail("a message of exactly the FairyTale limit must be allowed: " + refused.getMessage());
    }

    final String oneOver = asciiOfLength(E2EEStrip.CHAR_THRESHOLD_FAIRYTALE + 1);
    assertThrows("one byte past the FairyTale limit must be refused", TooManyCharsException.class,
        () -> strip.checkMessageLengthForEncodingMethod(oneOver, Encoder.FAIRYTALE, false));
  }
}
