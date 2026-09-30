package com.amnesica.kryptey.inputmethod.signalprotocol;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.amnesica.kryptey.inputmethod.signalprotocol.chat.Contact;
import com.amnesica.kryptey.inputmethod.signalprotocol.encoding.EnvelopeCodec;
import com.amnesica.kryptey.inputmethod.signalprotocol.stores.PreKeyMetadataStoreImpl;
import com.amnesica.kryptey.inputmethod.signalprotocol.stores.SignalProtocolStoreImpl;
import com.amnesica.kryptey.inputmethod.signalprotocol.util.Base64;
import com.amnesica.kryptey.inputmethod.signalprotocol.util.JsonUtil;
import com.amnesica.kryptey.inputmethod.signalprotocol.util.ProtocolAddresses;

import com.fasterxml.jackson.core.type.TypeReference;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.signal.libsignal.protocol.SignalProtocolAddress;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * A user who upgrades keeps their conversations.
 *
 * <p>Everything here was written by libsignal 0.86.5, through this app's own entry points and its
 * own JSON persistence, and committed before the move off it (see {@link UpgradeFixtureGenerator}).
 * This opens those stores with the libsignal the build now pins and carries on. Between the two
 * versions libsignal rewrote its session layer, made the post-quantum ratchet (SPQR) mandatory for
 * every session, and began binding both parties' addresses into each new session's opening
 * messages - three changes
 * any one of which could leave an upgraded user unable to read a single message from a contact they
 * already had, with nothing to do about it but re-invite everyone.
 *
 * <p>Every store is re-read from the fixture for every test, so no case depends on another's
 * leftovers and each exercises state exactly as 0.86.5 left it.
 */
public class StoresFromLibsignal0865SurviveTest {

  private static final String DIR = "fixtures/libsignal-0.86.5/";

  private Map<String, String> wire;
  private Map<String, String> expect;

  private static String resource(final String name) throws IOException {
    try (InputStream in = StoresFromLibsignal0865SurviveTest.class.getClassLoader()
        .getResourceAsStream(name)) {
      assertNotNull("missing fixture " + name, in);
      final ByteArrayOutputStream out = new ByteArrayOutputStream();
      in.transferTo(out);
      return out.toString(StandardCharsets.UTF_8.name());
    }
  }

  private static Account load(final String who) throws IOException {
    final SignalProtocolStoreImpl store = JsonUtil.fromJson(
        resource(DIR + who + ".protocol-store.json"), SignalProtocolStoreImpl.class);
    final PreKeyMetadataStoreImpl metadata = JsonUtil.fromJson(
        resource(DIR + who + ".metadata-store.json"), PreKeyMetadataStoreImpl.class);
    final SignalProtocolAddress address = JsonUtil.fromJson(
        resource(DIR + who + ".protocol-address.json"), SignalProtocolAddress.class);
    // The fixture's rotation clocks read 2026-10-30. Past that date every first send would rotate
    // the signed pre-key and attach a bundle, and the receiver would archive the 0.86.5 session and
    // decrypt through libsignal's previous-state fallback - so which path these cases exercised
    // would depend on the calendar. Held off here; the rotation path has its own case below.
    metadata.setNextSignedPreKeyRefreshTime(Long.MAX_VALUE);
    metadata.setOldSignedPreKeyDeletionTime(Long.MAX_VALUE);
    return new Account(resource(DIR + who + ".name.txt"), address.getDeviceId(),
        store.getIdentityKeyPair(), metadata, store, address);
  }

  private static SignalProtocolAddress addressOf(final Account a) {
    return ProtocolAddresses.of(a.getName(), a.getDeviceId());
  }

  private static void activate(final Account a) {
    SignalProtocolMain.getInstance().setAccount(a);
  }

  private static String send(final Account from, final Account to, final String text)
      throws Exception {
    activate(from);
    final MessageEnvelope envelope = SignalProtocolMain.encryptMessage(text, addressOf(to));
    assertNotNull("nothing was encrypted for \"" + text + "\"", envelope);
    return EnvelopeCodec.toWire(envelope);
  }

  private static String receive(final Account at, final Account from, final String wireText)
      throws Exception {
    activate(at);
    return SignalProtocolMain.decryptMessage(EnvelopeCodec.fromWire(wireText), addressOf(from));
  }

  private static void talk(final Account a, final Account b, final String label) throws Exception {
    for (int round = 1; round <= 3; round++) {
      final String ab = label + ": a to b, " + round;
      assertEquals(ab, receive(b, a, send(a, b, ab)));
      final String ba = label + ": b to a, " + round;
      assertEquals(ba, receive(a, b, send(b, a, ba)));
    }
  }

  @Before
  public void setUp() throws IOException {
    SignalProtocolMain.testIsRunning = true;
    SignalProtocolMain.initialize(null);
    wire = JsonUtil.fromJson(resource(DIR + "wire.json"), new TypeReference<Map<String, String>>() {});
    expect = JsonUtil.fromJson(resource(DIR + "expect.json"),
        new TypeReference<Map<String, String>>() {});
  }

  @After
  public void tearDown() {
    SignalProtocolMain.resetForTest();
  }

  /**
   * The fixture predates address binding, read from the bytes rather than from a label.
   *
   * <p>From libsignal 0.91 every PreKey message between two UUID-named parties carries both
   * addresses as field 6 of its inner SignalMessage. The fixture's opening message to Carol has no
   * field 6, so no build from 0.91 on wrote it. WRITTEN-BY.txt cannot say that: the generator writes
   * it as a literal whatever libsignal is running, so a regeneration under the current version would
   * keep the label and silently turn every case here into a self-consistency check.
   *
   * <p>The same parser is run on an opening message the current build makes, which must have
   * field 6, or this check is measuring nothing.
   */
  @Test
  public void theFixtureReallyCameFromBeforeAddressBinding() throws Exception {
    assertTrue(resource(DIR + "WRITTEN-BY.txt").startsWith("libsignal-android 0.86.5"));
    assertFalse("the fixture's opening message carries addresses, so a build from libsignal 0.91 "
            + "on wrote it - regenerated, and every case here now proves only self-consistency",
        innerSignalMessageHasAddresses(EnvelopeCodec.fromWire(wire.get("alice-to-carol-first"))));

    SignalProtocolMain.initialize(null);
    final Account x = SignalProtocolMain.getInstance().getAccount();
    SignalProtocolMain.initialize(null);
    final Account y = SignalProtocolMain.getInstance().getAccount();
    activate(y);
    final String invite = SignalProtocolMain.exportOwnKeyBundle();
    activate(x);
    assertTrue(SignalProtocolMain.processPreKeyResponseMessage(
        EnvelopeCodec.fromWire(invite), addressOf(y)));
    final MessageEnvelope fresh = SignalProtocolMain.encryptMessage("now", addressOf(y));
    assertNotNull(fresh);
    assertTrue("anti-vacuity: an opening message from the current build must carry addresses",
        innerSignalMessageHasAddresses(fresh));
  }

  /** PreKeySignalMessage: version byte, then protobuf; field 4 is the inner SignalMessage. */
  private static boolean innerSignalMessageHasAddresses(final MessageEnvelope envelope) {
    assertEquals("expected a PreKey message", 3, envelope.getCiphertextType());
    final byte[] outer = envelope.getCiphertextMessage();
    final byte[] inner = lengthDelimitedField(outer, 1, outer.length, 4);
    assertNotNull("no inner SignalMessage", inner);
    // SignalMessage: version byte, protobuf, 8-byte MAC.
    return lengthDelimitedField(inner, 1, inner.length - 8, 6) != null;
  }

  private static byte[] lengthDelimitedField(final byte[] b, int i, final int end, final int want) {
    while (i < end) {
      long key = 0;
      int shift = 0;
      int c;
      do {
        c = b[i++] & 0xff;
        key |= (long) (c & 0x7f) << shift;
        shift += 7;
      } while ((c & 0x80) != 0);
      final int field = (int) (key >>> 3);
      final int wireType = (int) (key & 7);
      if (wireType == 0) {
        while ((b[i++] & 0x80) != 0) { /* varint */ }
      } else if (wireType == 2) {
        long len = 0;
        shift = 0;
        do {
          c = b[i++] & 0xff;
          len |= (long) (c & 0x7f) << shift;
          shift += 7;
        } while ((c & 0x80) != 0);
        if (field == want) return java.util.Arrays.copyOfRange(b, i, i + (int) len);
        i += (int) len;
      } else {
        throw new AssertionError("unexpected protobuf wire type " + wireType);
      }
    }
    return null;
  }

  @Test
  public void identityKeysAndTheSafetyNumberAreUnchanged() throws Exception {
    final Account alice = load("alice");
    final Account bob = load("bob");
    assertArrayEquals(Base64.decodeWithoutPadding(resource(DIR + "alice.identity-key-pair.b64")),
        alice.getSignalProtocolStore().getIdentityKeyPair().serialize());

    // A changed safety number is exactly what a key substitution looks like, so an upgrade that
    // moved it would teach every user to dismiss the one warning that matters.
    activate(alice);
    assertEquals(expect.get("safety-alice-bob"), SignalProtocolMain.getFingerprint(
            new Contact("Peer", "Fixture", bob.getName(), bob.getDeviceId(), false))
        .getDisplayableFingerprint().getDisplayText());
  }

  @Test
  public void messagesInFlightAtTheUpgradeAreRead() throws Exception {
    final Account alice = load("alice");
    final Account bob = load("bob");
    assertEquals(expect.get("alice-to-bob-inflight"),
        receive(bob, alice, wire.get("alice-to-bob-inflight")));
    assertEquals(expect.get("bob-to-alice-inflight"),
        receive(alice, bob, wire.get("bob-to-alice-inflight")));
  }

  @Test
  public void aSkippedMessageKeyKeptByTheOldVersionStillOpensItsMessage() throws Exception {
    final Account alice = load("alice");
    final Account bob = load("bob");
    assertEquals(expect.get("alice-to-bob-skipped"),
        receive(bob, alice, wire.get("alice-to-bob-skipped")));
  }

  @Test
  public void anEstablishedConversationCarriesOnBothWays() throws Exception {
    final Account alice = load("alice");
    final Account bob = load("bob");
    // Drain what was in flight first, as a real upgrade would, then keep talking.
    receive(bob, alice, wire.get("alice-to-bob-inflight"));
    receive(alice, bob, wire.get("bob-to-alice-inflight"));
    talk(alice, bob, "after the upgrade");
  }

  @Test
  public void anEstablishedConversationCarriesOnWithNothingInFlight() throws Exception {
    // The in-flight messages are deliberately NOT drained: new messages must not depend on them.
    talk(load("alice"), load("bob"), "straight on");
  }

  @Test
  public void aConversationCarriesOnWhenTheFirstSendAfterTheUpgradeRotatesTheSignedPreKey()
      throws Exception {
    // What every upgrader meets within the rotation period: the first send attaches a fresh bundle,
    // the receiver processes it over the session 0.86.5 left, and the conversation must survive it.
    final Account alice = load("alice");
    final Account bob = load("bob");
    alice.getMetadataStore().setNextSignedPreKeyRefreshTime(0);
    bob.getMetadataStore().setNextSignedPreKeyRefreshTime(0);

    activate(bob);
    final MessageEnvelope rotating =
        SignalProtocolMain.encryptMessage("bob, rotating", addressOf(alice));
    assertNotNull(rotating);
    assertNotNull("precondition: the send must really have rotated and attached a bundle, or this "
        + "is the plain path again", rotating.getPreKeyResponse());
    assertEquals("bob, rotating", receive(alice, bob, EnvelopeCodec.toWire(rotating)));
    // What was in flight before the upgrade still opens after the rotation.
    assertEquals(expect.get("alice-to-bob-inflight"),
        receive(bob, alice, wire.get("alice-to-bob-inflight")));
    talk(alice, bob, "after the rotation");
  }

  @Test
  public void aSessionStillPendingAtTheUpgradeCompletes() throws Exception {
    final Account alice = load("alice");
    final Account carol = load("carol");
    assertEquals(expect.get("alice-to-carol-first"),
        receive(carol, alice, wire.get("alice-to-carol-first")));
    talk(carol, alice, "carol answers");
  }

  @Test
  public void anInviteMadeBeforeTheUpgradeIsAcceptedAfterIt() throws Exception {
    final Account dave = load("dave");
    SignalProtocolMain.initialize(null);
    final Account eve = SignalProtocolMain.getInstance().getAccount();

    activate(eve);
    assertTrue("an invite written by 0.86.5 did not build a session",
        SignalProtocolMain.processPreKeyResponseMessage(
            EnvelopeCodec.fromWire(wire.get("dave-invite")), addressOf(dave)));
    talk(eve, dave, "eve and dave");
  }

  @Test
  public void aLegacyDeviceIdStillTalksNowThatAddressesAreBoundIntoTheMac() throws Exception {
    // libsignal now binds both (name, device id) pairs into a new session's PreKey messages when the
    // names parse as service IDs, which ours do - and this case starts a new session. A legacy device id outside [1,127] is folded wherever an address
    // is built, so the two sides agree only if the id the sender binds is the id the receiver
    // derives from the envelope.
    SignalProtocolMain.initialize(null);
    final Account fresh = SignalProtocolMain.getInstance().getAccount();
    final Account base = load("bob");
    final int legacyId = 7296;
    assertTrue("the case needs an id libsignal would reject unfolded",
        !ProtocolAddresses.isValidDeviceId(legacyId));
    // Loaded the way StorageHelper loads it: the stored address goes through the app's own
    // deserializer, which folds, and the account's device id is taken from that address. An
    // account holding the raw id is not a reachable state - libsignal refuses to build its bundle.
    final SignalProtocolAddress stored = JsonUtil.fromJson(
        "{\"name\":\"" + base.getName() + "\",\"deviceId\":" + legacyId + "}",
        SignalProtocolAddress.class);
    assertTrue("the stored id must have been folded on load", stored.getDeviceId() != legacyId
        && ProtocolAddresses.isValidDeviceId(stored.getDeviceId()));
    final Account legacy = new Account(base.getName(), stored.getDeviceId(),
        base.getSignalProtocolStore().getIdentityKeyPair(), base.getMetadataStore(),
        base.getSignalProtocolStore(), stored);

    activate(legacy);
    final String invite = SignalProtocolMain.exportOwnKeyBundle();
    activate(fresh);
    assertTrue(SignalProtocolMain.processPreKeyResponseMessage(
        EnvelopeCodec.fromWire(invite), addressOf(legacy)));
    talk(fresh, legacy, "legacy device id");
  }
}
