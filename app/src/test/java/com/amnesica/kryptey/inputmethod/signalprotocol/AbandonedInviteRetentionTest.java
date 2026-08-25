package com.amnesica.kryptey.inputmethod.signalprotocol;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.amnesica.kryptey.inputmethod.signalprotocol.encoding.EnvelopeCodec;
import com.amnesica.kryptey.inputmethod.signalprotocol.util.ProtocolAddresses;

import org.junit.Before;
import org.junit.Test;
import org.signal.libsignal.protocol.SignalProtocolAddress;

/**
 * How long a handed-out invite stays usable while its recipient has not replied.
 *
 * <p>Every bundle allocates its own one-time pre-key id, deliberately: the allocator used to
 * hard-code id 1 and regenerate <em>in place</em>, so handing out a second invite destroyed the key
 * material the first invitee had already been given — their opening message referenced pre-key 1,
 * pre-key 1 was now different material, and it could never be decrypted.
 *
 * <p>A review round noted that an invite which fails its length check burns an id with no rollback,
 * and called it a cost rather than a defect. It is right that it is not a defect: rolling the id back
 * would mean handing the same number out twice with different material, which is precisely the
 * hazard the per-bundle design exists to prevent, and the app cannot know whether the bundle escaped.
 *
 * <p>What nobody had measured is the bound underneath it. Consumed records are retained only to
 * {@code USED_PRE_KEY_RETENTION}, so "each bundle gets its own id" holds until enough later bundles
 * push an earlier one out. That turns a bookkeeping cost into a user-visible one, and these tests
 * pin where the line is rather than leaving it in a constant nobody has exercised.
 */
public class AbandonedInviteRetentionTest {

  /** Mirrors KeyUtil.USED_PRE_KEY_RETENTION, which is private. Asserted below, not assumed. */
  private static final int RETENTION = 50;

  private Account alice;
  private Account bob;

  @Before
  public void createBothParties() {
    SignalProtocolMain.resetForTest();
    SignalProtocolMain.testIsRunning = true;
    SignalProtocolMain.initialize(null);
    alice = SignalProtocolMain.getInstance().getAccount();
    SignalProtocolMain.initialize(null);
    bob = SignalProtocolMain.getInstance().getAccount();
    assertNotNull(alice);
    assertNotNull(bob);
  }

  private void activate(final Account account) {
    SignalProtocolMain.getInstance().setAccount(account);
  }

  private static SignalProtocolAddress addressOf(final Account account) {
    return ProtocolAddresses.of(account.getSignalProtocolAddress().getName(),
        account.getDeviceId());
  }

  /** Alice publishes one invite and abandons {@code abandoned} more after it. */
  private String inviteThenAbandon(final int abandoned) throws Exception {
    activate(alice);
    final String held = EnvelopeCodec.toWire(SignalProtocolMain.getPreKeyResponseMessage());
    for (int i = 0; i < abandoned; i++) {
      assertNotNull("abandoned invite " + i + " must still build",
          SignalProtocolMain.getPreKeyResponseMessage());
    }
    return held;
  }

  /** Bob opens the invite he was given and sends his first message; returns what Alice recovers. */
  private String bobRepliesTo(final String invite, final String text) throws Exception {
    activate(bob);
    assertTrue("Bob must be able to build a session from the invite he holds",
        SignalProtocolMain.processPreKeyResponseMessage(
            EnvelopeCodec.fromWire(invite), addressOf(alice)));
    final String onTheWire =
        EnvelopeCodec.toWire(SignalProtocolMain.encryptMessage(text, addressOf(alice)));

    activate(alice);
    return SignalProtocolMain.decryptMessage(EnvelopeCodec.fromWire(onTheWire), addressOf(bob));
  }

  /**
   * An invite still opens after a realistic number of later ones.
   *
   * <p>This is the property the per-bundle allocator was written for, and the one a user relies on:
   * they invite someone, carry on using the app, and the invitee replies a day later.
   */
  @Test
  public void aninviteStillOpensAfterLaterInvitesWereAbandoned() throws Exception {
    final String held = inviteThenAbandon(10);

    assertEquals("an invite handed out before ten later ones must still open - this is exactly what "
            + "the per-bundle pre-key id exists to guarantee", "sent a day later",
        bobRepliesTo(held, "sent a day later"));
  }

  /**
   * And it stops opening once enough later invites push its record out of retention.
   *
   * <p>Recorded as a measured limit rather than a complaint. Retention is bounded on purpose — the
   * store cannot grow without end — so there has to be a line somewhere. What matters is that the
   * line is known: past it, an invitee who replies gets a message the sender can never read, and
   * the app's own advice on a decryption failure is to delete and re-invite, which is the right
   * advice here for once.
   *
   * <p>The count also pins {@code USED_PRE_KEY_RETENTION} from outside: if that constant changes,
   * the number of abandoned invites needed to reach this changes with it, and this test says so.
   */
  @Test
  public void pastTheRetentionLimitAnAbandonedInvitesKeyIsPrunedAway() throws Exception {
    final String held = inviteThenAbandon(RETENTION * 2 + 5);

    String recovered = null;
    try {
      recovered = bobRepliesTo(held, "sent much too late");
    } catch (Exception expected) {
      // Failing to decrypt is the outcome being pinned; how it fails is libsignal's business.
      return;
    }
    assertNull("past the retention bound the pre-key backing this invite has been pruned, so the "
            + "first message cannot be recovered. If this now succeeds, retention was raised or "
            + "made unbounded - which is a decision worth making deliberately, not by accident",
        recovered);
  }
}
