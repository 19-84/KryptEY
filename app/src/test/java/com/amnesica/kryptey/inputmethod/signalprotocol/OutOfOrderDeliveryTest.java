package com.amnesica.kryptey.inputmethod.signalprotocol;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.amnesica.kryptey.inputmethod.signalprotocol.encoding.EnvelopeCodec;
import com.amnesica.kryptey.inputmethod.signalprotocol.util.ProtocolAddresses;

import org.junit.Before;
import org.junit.Test;
import org.signal.libsignal.protocol.DuplicateMessageException;
import org.signal.libsignal.protocol.SignalProtocolAddress;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Messages arriving out of order, which for this keyboard is the ordinary case.
 *
 * <p>Every other conversation test sends a message and immediately decrypts it. That is not how
 * KryptEY is used: ciphertext travels as text the user copies and pastes by hand, so they paste
 * what is in front of them - scrolling back to an older message, skipping one they cannot be
 * bothered with, pasting the same thing twice because they lost track. The double ratchet is built
 * for exactly this, but nothing here checked that KryptEY's wrapping of it preserves the property.
 *
 * <p>What is being pinned is libsignal's behaviour as KryptEY exposes it, so a future change to
 * session handling - archiving, store pruning, the bundle-attached-to-a-message path - shows up as
 * a failure rather than as a user quietly unable to read an old message.
 */
public class OutOfOrderDeliveryTest {

  private Account alice;
  private Account bob;
  private SignalProtocolAddress aliceAddress;
  private SignalProtocolAddress bobAddress;

  @Before
  public void buildASession() throws Exception {
    SignalProtocolMain.resetForTest();
    SignalProtocolMain.testIsRunning = true;

    SignalProtocolMain.initialize(null);
    alice = SignalProtocolMain.getInstance().getAccount();
    SignalProtocolMain.initialize(null);
    bob = SignalProtocolMain.getInstance().getAccount();

    aliceAddress = ProtocolAddresses.of(alice.getSignalProtocolAddress().getName(),
        alice.getDeviceId());
    bobAddress = ProtocolAddresses.of(bob.getSignalProtocolAddress().getName(), bob.getDeviceId());

    activate(alice);
    final String aliceBundle = SignalProtocolMain.exportOwnKeyBundle();
    activate(bob);
    assertTrue(SignalProtocolMain.processPreKeyResponseMessage(
        EnvelopeCodec.fromWire(aliceBundle), aliceAddress));
  }

  private void activate(final Account account) {
    SignalProtocolMain.getInstance().setAccount(account);
  }

  /** Bob encrypts a run of messages to Alice, as wire text, without Alice reading any of them. */
  private List<String> bobSends(final int count) throws Exception {
    activate(bob);
    final List<String> wire = new ArrayList<>();
    for (int i = 0; i < count; i++) {
      final MessageEnvelope envelope =
          SignalProtocolMain.encryptMessage("message " + i, aliceAddress);
      assertNotNull("message " + i + " did not encrypt", envelope);
      wire.add(EnvelopeCodec.toWire(envelope));
    }
    return wire;
  }

  private String aliceReads(final String wireText) throws Exception {
    activate(alice);
    return SignalProtocolMain.decryptMessage(EnvelopeCodec.fromWire(wireText), bobAddress);
  }

  @Test
  public void messagesPastedInReverseOrderAllDecrypt() throws Exception {
    final List<String> wire = bobSends(8);
    Collections.reverse(wire);

    for (int i = 0; i < wire.size(); i++) {
      assertEquals("the message pasted at position " + i + " did not decrypt",
          "message " + (wire.size() - 1 - i), aliceReads(wire.get(i)));
    }
  }

  @Test
  public void messagesPastedInAshuffledOrderAllDecrypt() throws Exception {
    final List<String> wire = bobSends(12);
    // Fixed seed: a failure has to be reproducible, and "shuffled" is otherwise a different test
    // every run.
    Collections.shuffle(wire, new java.util.Random(20260824L));

    int decoded = 0;
    for (final String text : wire) {
      final String plaintext = aliceReads(text);
      assertNotNull("a shuffled message did not decrypt", plaintext);
      assertTrue(plaintext.startsWith("message "));
      decoded++;
    }
    assertEquals("every message must decrypt regardless of order", 12, decoded);
  }

  /**
   * The user reads the newest message first and only later scrolls back. The ratchet has to have
   * kept the keys for everything it skipped over.
   */
  @Test
  public void readingTheNewestFirstStillLeavesTheOlderOnesReadable() throws Exception {
    final List<String> wire = bobSends(10);

    assertEquals("message 9", aliceReads(wire.get(9)));
    for (int i = 0; i < 9; i++) {
      assertEquals("an older message became unreadable after reading a newer one",
          "message " + i, aliceReads(wire.get(i)));
    }
  }

  /** A message the user never pastes must not block the ones after it. */
  @Test
  public void amessageThatIsNeverPastedDoesNotBlockTheRest() throws Exception {
    final List<String> wire = bobSends(6);

    for (int i = 0; i < wire.size(); i++) {
      if (i == 2) continue;   // never pasted
      assertEquals("message " + i, aliceReads(wire.get(i)));
    }
  }

  /**
   * Pasting the same message twice must be refused, not decrypted again.
   *
   * <p>This is the property that makes replay inert, and it is also the exception a hostile
   * messenger can induce at will - which is why the app's failure advice must never tell the user
   * to delete a contact.
   */
  @Test
  public void pastingTheSameMessageTwiceIsRefused() throws Exception {
    final List<String> wire = bobSends(3);

    assertEquals("message 0", aliceReads(wire.get(0)));
    assertThrows("a replayed message must not decrypt a second time",
        DuplicateMessageException.class, () -> aliceReads(wire.get(0)));

    assertEquals("and the session must be undamaged by the attempt",
        "message 1", aliceReads(wire.get(1)));
  }

  /**
   * How far back the ratchet actually reaches, pinned rather than assumed.
   *
   * <p>libsignal keeps a bounded number of skipped message keys per chain. Past that bound an old
   * message is gone for good - which for this keyboard means a user who reads the newest message
   * and later scrolls a long way back finds nothing. The number is libsignal's, not KryptEY's; what
   * this records is that KryptEY does not narrow it, and what the real limit is.
   */
  @Test
  public void theRatchetReachesExactlyTwoThousandMessagesBack() throws Exception {
    final List<String> wire = bobSends(2001);

    assertEquals("message 2000", aliceReads(wire.get(2000)));
    assertEquals("skipping 2000 messages must still leave the oldest readable",
        "message 0", aliceReads(wire.get(0)));
  }

  /**
   * One past the window, and the message reports as a DUPLICATE rather than as unreadable.
   *
   * <p>That is the part worth knowing. libsignal keeps 2000 skipped message keys per chain and
   * evicts the oldest; asking for an evicted key is indistinguishable from asking for one already
   * used, so it raises {@code DuplicateMessageException}. I assumed
   * {@code InvalidMessageException} and measured otherwise.
   *
   * <p>The consequence reaches the user: the app's failure text says "Most often this means it was
   * already decrypted once - each message can only be opened a single time." For a message more
   * than 2000 behind, that explanation is wrong. It is wrong in a harmless direction - the message
   * really is unrecoverable either way - but a user scrolling far back in a long conversation is
   * told they have already read something they have not.
   *
   * <p>Pinned rather than fixed: distinguishing the two cases needs a counter libsignal does not
   * expose, and the alternative wording would have to be vague enough to cover both.
   */
  @Test
  public void oneMessagePastTheWindowReportsAsAduplicate() throws Exception {
    final List<String> wire = bobSends(2002);

    assertEquals("message 2001", aliceReads(wire.get(2001)));
    assertThrows("2000 skipped keys is the bound, and past it libsignal cannot tell an evicted key "
            + "from a used one", DuplicateMessageException.class, () -> aliceReads(wire.get(0)));

    assertEquals("and messages inside the window must still be readable",
        "message 1500", aliceReads(wire.get(1500)));
  }
}
