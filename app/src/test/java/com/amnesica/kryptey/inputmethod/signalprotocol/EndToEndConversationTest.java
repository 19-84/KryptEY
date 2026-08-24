package com.amnesica.kryptey.inputmethod.signalprotocol;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.amnesica.kryptey.inputmethod.signalprotocol.chat.Contact;
import com.amnesica.kryptey.inputmethod.signalprotocol.encoding.EnvelopeCodec;
import com.amnesica.kryptey.inputmethod.signalprotocol.util.ProtocolAddresses;

import org.junit.Before;
import org.junit.Test;
import org.signal.libsignal.protocol.SignalProtocolAddress;

/**
 * Two people actually holding a conversation, through every layer this revival touched.
 *
 * <p>Everything else in the suite is unit- or component-level: the crypto box, the store, the
 * codec, address folding, pre-key rotation — each verified in isolation. None of that proves the
 * pieces fit together, and the most expensive defects found in this project were interaction bugs
 * (a Kyber key handed out but never persisted; an envelope that decoded fine and then crashed in
 * {@code PreKeyBundle}; a bundle 5x over the UI's own size limit). Those are invisible to a unit
 * test and obvious to this one.
 *
 * <p>Covers, in one flow: PQXDH bundle generation → binary wire encoding → transfer as text →
 * decoding → session establishment → encrypt → decrypt, in both directions.
 *
 * <p>{@code SignalProtocolMain} is a singleton with a single active account, so the two parties are
 * simulated by swapping which account is active — the same approach the pre-existing protocol test
 * uses.
 */
public class EndToEndConversationTest {

  private Account alice;
  private Account bob;

  @Before
  public void createBothParties() {
    SignalProtocolMain.testIsRunning = true;

    SignalProtocolMain.initialize(null);
    alice = SignalProtocolMain.getInstance().getAccount();
    assertNotNull(alice);

    SignalProtocolMain.initialize(null);
    bob = SignalProtocolMain.getInstance().getAccount();
    assertNotNull(bob);

    assertNotEquals("the two parties must be distinct identities",
        alice.getSignalProtocolAddress().getName(), bob.getSignalProtocolAddress().getName());
  }

  private void activate(final Account account) {
    SignalProtocolMain.getInstance().setAccount(account);
  }

  private static SignalProtocolAddress addressOf(final Account account) {
    return ProtocolAddresses.of(account.getSignalProtocolAddress().getName(),
        account.getDeviceId());
  }

  private static Contact contactFor(final Account account) {
    return new Contact("Peer", "Account", account.getSignalProtocolAddress().getName(),
        account.getDeviceId(), false);
  }

  /**
   * The whole point. If this passes, a real user can invite someone and exchange a message.
   */
  @Test
  public void twoPeopleCanEstablishASessionAndExchangeMessages() throws Exception {
    // --- Alice publishes her bundle and it travels as text through a messenger ---
    activate(alice);
    final String aliceBundleOnTheWire = EnvelopeCodec.toWire(
        SignalProtocolMain.getPreKeyResponseMessage());
    assertNotNull(aliceBundleOnTheWire);

    // --- Bob receives it, parses it, and builds a session ---
    activate(bob);
    final MessageEnvelope aliceBundle = EnvelopeCodec.fromWire(aliceBundleOnTheWire);
    assertEquals(MessageType.PRE_KEY_RESPONSE_MESSAGE,
        SignalProtocolMain.getMessageType(aliceBundle));

    final SignalProtocolAddress aliceAddress = addressOf(alice);
    assertTrue("Bob could not build a session from Alice's bundle",
        SignalProtocolMain.processPreKeyResponseMessage(aliceBundle, aliceAddress));

    // --- Bob sends Alice a message ---
    final String plaintext = "meet me at the usual place";
    final MessageEnvelope outbound = SignalProtocolMain.encryptMessage(plaintext, aliceAddress);
    assertNotNull("Bob could not encrypt to Alice", outbound);

    final String onTheWire = EnvelopeCodec.toWire(outbound);

    // Look for the plaintext in the CIPHERTEXT BYTES, not in the base64 text.
    //
    // This searched onTheWire for "usual place". The wire alphabet is [A-Za-z0-9+/=], so a string
    // containing a space cannot occur in it under any circumstances - the assertion was false for
    // every possible envelope, including one that carried the plaintext verbatim. Nothing else in
    // the suite asserts that the ciphertext is opaque.
    final byte[] ciphertext = outbound.getCiphertextMessage();
    assertNotNull("there must be ciphertext to inspect", ciphertext);
    final String asLatin1 = new String(ciphertext, java.nio.charset.StandardCharsets.ISO_8859_1);
    assertFalse("the plaintext is visible in the ciphertext", asLatin1.contains("usual place"));
    assertFalse("nor any run of it", asLatin1.contains("meet me"));

    // --- Alice receives and decrypts it ---
    activate(alice);
    final MessageEnvelope inbound = EnvelopeCodec.fromWire(onTheWire);
    final String decrypted =
        SignalProtocolMain.decryptMessage(inbound, addressOf(bob));

    assertEquals("Alice did not recover Bob's message", plaintext, decrypted);
  }

  /** A reply proves the session works in the direction it was not established from. */
  @Test
  public void theConversationWorksInBothDirections() throws Exception {
    activate(alice);
    final String aliceBundle = EnvelopeCodec.toWire(SignalProtocolMain.getPreKeyResponseMessage());

    activate(bob);
    SignalProtocolMain.processPreKeyResponseMessage(
        EnvelopeCodec.fromWire(aliceBundle), addressOf(alice));
    final String first = EnvelopeCodec.toWire(
        SignalProtocolMain.encryptMessage("first", addressOf(alice)));

    activate(alice);
    assertEquals("first",
        SignalProtocolMain.decryptMessage(EnvelopeCodec.fromWire(first), addressOf(bob)));

    // Alice now replies; she never processed a bundle from Bob, only his message.
    final MessageEnvelope reply = SignalProtocolMain.encryptMessage("second", addressOf(bob));
    assertNotNull("Alice could not reply", reply);
    final String replyWire = EnvelopeCodec.toWire(reply);

    activate(bob);
    assertEquals("second",
        SignalProtocolMain.decryptMessage(EnvelopeCodec.fromWire(replyWire), addressOf(alice)));
  }

  /** Ratcheting: several messages in a row must each decrypt, and each must look different. */
  @Test
  public void successiveMessagesRatchetAndRemainDecryptable() throws Exception {
    activate(alice);
    final String aliceBundle = EnvelopeCodec.toWire(SignalProtocolMain.getPreKeyResponseMessage());

    activate(bob);
    SignalProtocolMain.processPreKeyResponseMessage(
        EnvelopeCodec.fromWire(aliceBundle), addressOf(alice));

    final java.util.List<String> wires = new java.util.ArrayList<>();
    for (int i = 0; i < 5; i++) {
      wires.add(EnvelopeCodec.toWire(
          SignalProtocolMain.encryptMessage("message " + i, addressOf(alice))));
    }

    assertEquals("identical ciphertexts across messages - the ratchet is not advancing",
        5, new java.util.HashSet<>(wires).size());

    activate(alice);
    for (int i = 0; i < wires.size(); i++) {
      assertEquals("message " + i,
          SignalProtocolMain.decryptMessage(EnvelopeCodec.fromWire(wires.get(i)), addressOf(bob)));
    }
  }

  /**
   * The session must be PQXDH, not classical X3DH. Version 3 is X3DH; version 4 is PQXDH. This is
   * asserted on a session built through the real wire path rather than a hand-assembled bundle.
   */
  @Test
  public void theEstablishedSessionIsPostQuantum() throws Exception {
    activate(alice);
    final String aliceBundle = EnvelopeCodec.toWire(SignalProtocolMain.getPreKeyResponseMessage());

    activate(bob);
    final SignalProtocolAddress aliceAddress = addressOf(alice);
    SignalProtocolMain.processPreKeyResponseMessage(
        EnvelopeCodec.fromWire(aliceBundle), aliceAddress);

    assertEquals("session is not PQXDH", 4,
        bob.getSignalProtocolStore().loadSession(aliceAddress).getSessionVersion());
  }

  /** Two separate invitations must not collide on one pre-key, which used to lose the first. */
  @Test
  public void invitingASecondContactDoesNotBreakTheFirst() throws Exception {
    activate(alice);
    final String bundleForBob = EnvelopeCodec.toWire(SignalProtocolMain.getPreKeyResponseMessage());
    final String bundleForCarol =
        EnvelopeCodec.toWire(SignalProtocolMain.getPreKeyResponseMessage());

    final MessageEnvelope forBob = EnvelopeCodec.fromWire(bundleForBob);
    final MessageEnvelope forCarol = EnvelopeCodec.fromWire(bundleForCarol);

    final int bobPreKeyId = forBob.getPreKeyResponse().getDevices().get(0).getPreKey().getKeyId();
    final int carolPreKeyId =
        forCarol.getPreKeyResponse().getDevices().get(0).getPreKey().getKeyId();
    assertNotEquals("two invitations reused one pre-key id", bobPreKeyId, carolPreKeyId);

    // Bob, invited first, must still be able to open a session after Carol was invited.
    activate(bob);
    assertTrue("the first invitee's bundle stopped working once a second was issued",
        SignalProtocolMain.processPreKeyResponseMessage(forBob, addressOf(alice)));

    final MessageEnvelope message =
        SignalProtocolMain.encryptMessage("still works", addressOf(alice));
    assertNotNull(message);

    activate(alice);
    assertEquals("still works", SignalProtocolMain.decryptMessage(
        EnvelopeCodec.fromWire(EnvelopeCodec.toWire(message)), addressOf(bob)));
  }

  /** A contact's identity must be verifiable as soon as their bundle is processed. */
  @Test
  public void aContactCanBeVerifiedBeforeAnyMessageIsExchanged() throws Exception {
    activate(alice);
    final String aliceBundle = EnvelopeCodec.toWire(SignalProtocolMain.getPreKeyResponseMessage());

    activate(bob);
    SignalProtocolMain.processPreKeyResponseMessage(
        EnvelopeCodec.fromWire(aliceBundle), addressOf(alice));

    // No message has been sent in either direction yet - this used to NPE inside libsignal.
    assertNotNull("a safety number should be available straight after the handshake",
        SignalProtocolMain.getFingerprint(contactFor(alice)));
  }
}
