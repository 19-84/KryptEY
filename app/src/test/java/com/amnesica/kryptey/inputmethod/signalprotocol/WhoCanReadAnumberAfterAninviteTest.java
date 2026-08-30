package com.amnesica.kryptey.inputmethod.signalprotocol;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.amnesica.kryptey.inputmethod.signalprotocol.MessageEnvelope;
import com.amnesica.kryptey.inputmethod.signalprotocol.chat.Contact;
import com.amnesica.kryptey.inputmethod.signalprotocol.encoding.EnvelopeCodec;
import com.amnesica.kryptey.inputmethod.signalprotocol.util.ProtocolAddresses;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.signal.libsignal.protocol.SignalProtocolAddress;

import java.util.ArrayList;

/**
 * Who can actually read a safety number, and when.
 *
 * <p>Every user-facing document says the same thing, and two tests pin it in place: compare the
 * security number by voice <em>before sending anything private</em>. It is described as the only
 * step that tells a chat partner apart from the messenger.
 *
 * <p>A comparison needs both people to have digits. An invite is built from the inviter's own
 * account alone — it carries nothing about the recipient and creates nothing for them — so after one
 * arrives, only the side that <em>received</em> it has a number. The inviter has no contact row for
 * the invitee at all; their first sight of that person is the person's first message, which is
 * exactly what the instruction forbids until after the comparison.
 *
 * <p>So this pins both halves: the one-way state that makes the instruction unfollowable, and the
 * exchange that resolves it. The second is what the help can honestly tell people to do, and it is
 * asserted here rather than assumed, because "swap invites both ways" is only advice worth giving if
 * the resulting pair of sessions actually works.
 */
@RunWith(RobolectricTestRunner.class)
public class WhoCanReadAnumberAfterAninviteTest {

  private Account alice;
  private Account bob;
  private SignalProtocolAddress aliceAddress;
  private SignalProtocolAddress bobAddress;

  @Before
  public void setUp() {
    SignalProtocolMain.resetForTest();
    SignalProtocolMain.testIsRunning = true;

    SignalProtocolMain.initialize(null);
    alice = SignalProtocolMain.getInstance().getAccount();
    alice.setMessageLogLoader(ArrayList::new);
    aliceAddress = ProtocolAddresses.of(alice.getSignalProtocolAddress().getName(),
        alice.getDeviceId());

    SignalProtocolMain.initialize(null);
    bob = SignalProtocolMain.getInstance().getAccount();
    bob.setMessageLogLoader(ArrayList::new);
    bobAddress = ProtocolAddresses.of(bob.getSignalProtocolAddress().getName(), bob.getDeviceId());
  }

  @After
  public void tearDown() {
    SignalProtocolMain.resetForTest();
    SignalProtocolMain.testIsRunning = false;
  }

  private void activate(final Account account) {
    SignalProtocolMain.getInstance().setAccount(account);
  }

  private String numberFor(final SignalProtocolAddress address, final String name) {
    final var fingerprint = SignalProtocolMain.getFingerprint(
        new Contact(name, "Peer", address.getName(), address.getDeviceId(), false));
    return fingerprint == null ? null : fingerprint.getDisplayableFingerprint().getDisplayText();
  }

  @Test
  public void aftrOneInviteOnlyTheReceiverHasAnumber() throws Exception {
    activate(alice);
    final String aliceInvite = SignalProtocolMain.exportOwnKeyBundle();

    activate(bob);
    assertTrue("fixture: Bob accepts Alice's invite",
        SignalProtocolMain.processPreKeyResponseMessage(
            EnvelopeCodec.fromWire(aliceInvite), aliceAddress));
    assertNotNull("the side that received the invite has a number to read", 
        numberFor(aliceAddress, "Alice"));

    activate(alice);
    assertNull("the side that SENT it has nothing to compare: an invite is built from the "
            + "inviter's own account and carries nothing about the recipient, so Alice has no "
            + "pinned key for Bob and no number for him. The instruction every document gives - "
            + "compare by voice before sending anything private - cannot be completed here, and "
            + "the only step that would give Alice a number is the send it forbids",
        numberFor(bobAddress, "Bob"));
    assertTrue("and no contact row either", alice.getContactList() == null
        || alice.getContactList().isEmpty());
  }

  @Test
  public void swappingInvitesBothWaysGivesBothSidesTheSameNumber() throws Exception {
    activate(alice);
    final String aliceInvite = SignalProtocolMain.exportOwnKeyBundle();
    activate(bob);
    final String bobInvite = SignalProtocolMain.exportOwnKeyBundle();

    activate(bob);
    assertTrue(SignalProtocolMain.processPreKeyResponseMessage(
        EnvelopeCodec.fromWire(aliceInvite), aliceAddress));
    final String bobSees = numberFor(aliceAddress, "Alice");

    activate(alice);
    assertTrue("the second invite must be accepted too - if a simultaneous exchange were refused, "
            + "telling people to swap invites would be advice that does not work",
        SignalProtocolMain.processPreKeyResponseMessage(
            EnvelopeCodec.fromWire(bobInvite), bobAddress));
    final String aliceSees = numberFor(bobAddress, "Bob");

    assertNotNull("both sides must now have digits", aliceSees);
    assertNotNull(bobSees);
    assertEquals("and they must be the SAME digits, or reading them to each other proves nothing. "
            + "This is what makes 'swap invites both ways, then compare' honest advice rather than "
            + "a hope: the number is a function of the two identity keys, so both sides compute it "
            + "from the same pair once each has the other's key",
        bobSees, aliceSees);
  }

  /**
   * And the sessions the swap leaves behind actually carry messages, both ways.
   *
   * <p>The test above stops one assertion short of the claim the advice rests on, and the shortfall
   * is easy to miss: the safety number is a pure function of the two identity keys, so once both
   * sides have pinned each other, <em>equal digits are true by construction</em>. That assertion
   * cannot fail while the pins exist, which means it is not evidence that anything works — the only
   * load-bearing line there is that the second invite was accepted at all.
   *
   * <p>What "send them your own invite too" actually promises is that both people end up able to
   * talk. Two invites crossing is a simultaneous session initiation: each side builds a session from
   * the other's bundle without either having replied yet, which is a real protocol case with a real
   * history of going wrong. So it is exercised rather than assumed, in both directions, because a
   * one-way check would pass on a pair where only the first sender's session survived.
   */
  @Test
  public void bothSidesCanTalkAfterSwappingInvites() throws Exception {
    activate(alice);
    final String aliceInvite = SignalProtocolMain.exportOwnKeyBundle();
    activate(bob);
    final String bobInvite = SignalProtocolMain.exportOwnKeyBundle();

    activate(bob);
    assertTrue("fixture: Bob accepts Alice's invite", SignalProtocolMain.processPreKeyResponseMessage(
        EnvelopeCodec.fromWire(aliceInvite), aliceAddress));
    activate(alice);
    assertTrue("fixture: and Alice accepts Bob's, which is the crossing the advice describes",
        SignalProtocolMain.processPreKeyResponseMessage(
            EnvelopeCodec.fromWire(bobInvite), bobAddress));

    activate(alice);
    final MessageEnvelope toBob = SignalProtocolMain.encryptMessage("the first private thing",
        bobAddress);
    assertNotNull("Alice must be able to encrypt to Bob after the swap", toBob);

    activate(bob);
    assertEquals("Bob must be able to read it - this is the half the advice is actually promising, "
            + "and equal digits do not establish it",
        "the first private thing", SignalProtocolMain.decryptMessage(toBob, aliceAddress));

    activate(bob);
    final MessageEnvelope toAlice = SignalProtocolMain.encryptMessage("and the reply", aliceAddress);
    assertNotNull("Bob must be able to encrypt back", toAlice);

    activate(alice);
    assertEquals("and Alice must be able to read the reply. Checked in both directions on purpose: "
            + "a crossed initiation can leave one side's session usable and the other's not, and a "
            + "one-way test passes on exactly that",
        "and the reply", SignalProtocolMain.decryptMessage(toAlice, bobAddress));
  }
}
