package com.amnesica.kryptey.inputmethod.signalprotocol;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.amnesica.kryptey.inputmethod.signalprotocol.encoding.EncodeHelper;
import com.amnesica.kryptey.inputmethod.signalprotocol.encoding.EnvelopeCodec;
import com.amnesica.kryptey.inputmethod.signalprotocol.util.ProtocolAddresses;

import org.junit.Before;
import org.junit.Test;
import org.signal.libsignal.protocol.SignalProtocolAddress;

/**
 * How many characters a send actually costs, pinned as a range.
 *
 * <p>This app is a channel on top of somebody else's messenger, and every one of those imposes a
 * per-message character limit - 280 on a free X post, 300 on Bluesky, 500 on Mastodon, 2000 on
 * Discord and Signal, 4096 on Telegram. Whether KryptEY can be used on a given platform at all is
 * decided by one number: how many characters it emits. That number appears in this project's
 * documentation and in the reasoning behind {@code MAX_DECODABLE_CHARS}, and until this test it was
 * measured by hand and then trusted - the failure mode the rest of this suite exists to prevent.
 *
 * <p>The cliff is the part worth pinning. A session that is merely PENDING carries the pre-key
 * material on EVERY message, so a one-character message costs about 2400 characters. Once the peer
 * has replied once, the same message costs about 200. That is a factor of twelve, it is invisible
 * to the user, and it decides whether a platform works: at 2000 characters, Discord and Signal can
 * carry an established conversation comfortably and cannot carry the handshake at all.
 *
 * <p>Ranges rather than exact figures, deliberately. The envelope carries a timestamp and key
 * material whose base64 length moves by a character or two between runs, so an equality assertion
 * would be flaky for a reason that has nothing to do with what is being asserted. The bounds are
 * wide enough not to flake and tight enough that a change in the wire format - a field added, a
 * bundle attached where none was - fails them.
 */
public class HowManyCharactersAsendActuallyCostsTest {

  private Account alice;
  private Account bob;

  @Before
  public void twoParties() {
    SignalProtocolMain.testIsRunning = true;
    SignalProtocolMain.initialize(null);
    alice = SignalProtocolMain.getInstance().getAccount();
    SignalProtocolMain.initialize(null);
    bob = SignalProtocolMain.getInstance().getAccount();
  }

  private void activate(final Account a) {
    SignalProtocolMain.getInstance().setAccount(a);
  }

  private static SignalProtocolAddress addr(final Account a) {
    return ProtocolAddresses.of(a.getSignalProtocolAddress().getName(), a.getDeviceId());
  }

  private String send(final Account from, final Account to, final String plaintext)
      throws Exception {
    activate(from);
    return EnvelopeCodec.toWire(SignalProtocolMain.encryptMessage(plaintext, addr(to)));
  }

  private void read(final Account by, final Account from, final String wire) throws Exception {
    activate(by);
    SignalProtocolMain.decryptMessage(EnvelopeCodec.fromWire(wire), addr(from));
  }

  private static void within(final String what, final int low, final int high, final int actual) {
    assertTrue(what + " is " + actual + " characters, outside the expected " + low + ".." + high
        + ". If the wire format changed on purpose, re-measure and update BACKLOG.md's platform "
        + "table with it - the whole table is derived from these numbers.", actual >= low && actual <= high);
  }

  @Test
  public void aninviteIsAboutTwoAndAHalfThousandCharacters() throws Exception {
    activate(alice);
    within("an invite", 2300, 2800, SignalProtocolMain.exportOwnKeyBundle().length());
  }

  @Test
  public void apendingSessionCostsAboutTwoAndAHalfThousandCharactersPerMessage() throws Exception {
    activate(alice);
    final String invite = SignalProtocolMain.exportOwnKeyBundle();
    activate(bob);
    assertTrue(SignalProtocolMain.processPreKeyResponseMessage(
        EnvelopeCodec.fromWire(invite), addr(alice)));

    within("a one-character message on a pending session", 2200, 2700, send(bob, alice, "x").length());
    within("a 500-character message on a pending session", 2900, 3300,
        send(bob, alice, "x".repeat(500)).length());
  }

  /**
   * The cliff. Everything about which platforms are usable turns on this being a large factor.
   */
  @Test
  public void anestablishedSessionCostsAnOrderOfMagnitudeLess() throws Exception {
    activate(alice);
    final String invite = SignalProtocolMain.exportOwnKeyBundle();
    activate(bob);
    assertTrue(SignalProtocolMain.processPreKeyResponseMessage(
        EnvelopeCodec.fromWire(invite), addr(alice)));

    // Bob speaks, Alice reads and replies. That reply is what completes the handshake.
    final String pending = send(bob, alice, "x");
    read(alice, bob, pending);
    final String reply = send(alice, bob, "ok");
    read(bob, alice, reply);

    final int established = send(bob, alice, "x").length();
    within("a one-character message on an established session", 150, 300, established);
    within("a 500-character message on an established session", 700, 1000,
        send(bob, alice, "x".repeat(500)).length());

    assertTrue("the pending-to-established drop is the property the platform analysis rests on, and "
            + "it has collapsed: pending " + pending.length() + " vs established " + established,
        pending.length() > established * 5);
  }

  /**
   * An invite too large for a platform can be sent in parts - but only rejoined with a SPACE.
   *
   * <p>This is the only route onto a platform whose limit is below the invite size, so whether it
   * works is worth a test rather than an assumption. It rests on two behaviours in two different
   * classes: {@code EnvelopeCodec.fromWire} strips all whitespace before decoding, and
   * {@code E2EEStrip.decodeMessage} routes anything containing a {@code \p{C}} character to the
   * FairyTale decoder instead of the raw one. A newline is {@code \p{C}}; a space is not.
   *
   * <p>So rejoining with a space works and rejoining with a newline does not - which is exactly the
   * thing a user pasting two chat messages would get wrong, since a paste of two messages is
   * newline-separated. Nothing in the UI offers this or explains it.
   */
  @Test
  public void aninviteSplitInTwoCanBeRejoinedWithAspaceButNotAnewline() throws Exception {
    activate(alice);
    final String invite = SignalProtocolMain.exportOwnKeyBundle();
    final int half = invite.length() / 2;

    final String spaced = invite.substring(0, half) + " " + invite.substring(half);
    assertEquals("a space must not route the paste to the FairyTale decoder",
        false, EncodeHelper.encodedTextContainsInvisibleCharacters(spaced));
    assertEquals("the codec must recover the same envelope from the space-joined halves",
        invite, EnvelopeCodec.toWire(EnvelopeCodec.fromWire(spaced)));

    final String newlined = invite.substring(0, half) + "\n" + invite.substring(half);
    assertEquals("a newline must still route to the FairyTale decoder - this is what makes pasting "
            + "two chat messages fail, and the split-invite advice depends on knowing it",
        true, EncodeHelper.encodedTextContainsInvisibleCharacters(newlined));
  }
}
