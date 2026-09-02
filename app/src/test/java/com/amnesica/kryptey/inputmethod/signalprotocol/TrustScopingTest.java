package com.amnesica.kryptey.inputmethod.signalprotocol;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.amnesica.kryptey.inputmethod.signalprotocol.chat.Contact;
import com.amnesica.kryptey.inputmethod.signalprotocol.encoding.EnvelopeCodec;
import com.amnesica.kryptey.inputmethod.signalprotocol.stores.IdentityKeyStoreImpl;
import com.amnesica.kryptey.inputmethod.signalprotocol.util.KeyUtil;
import com.amnesica.kryptey.inputmethod.signalprotocol.util.ProtocolAddresses;

import org.junit.Before;
import org.junit.Test;
import org.signal.libsignal.protocol.IdentityKey;
import org.signal.libsignal.protocol.IdentityKeyPair;
import org.signal.libsignal.protocol.SignalProtocolAddress;
import org.signal.libsignal.protocol.fingerprint.Fingerprint;

/**
 * Scoping and content of the trust state.
 *
 * <p>Mutation testing found these unguarded: accepting one contact's identity change could clear
 * every contact's; the change flag ignored the device id; and the safety number the user compares
 * could be computed over the wrong key entirely — a fingerprint of your own identity — because the
 * only assertion was {@code assertNotNull}.
 *
 * <p>This used to claim it also caught "the halves swapped, or at the wrong protocol version".
 * Neither is observable in libsignal 0.86.5, and saying so implied coverage that cannot exist:
 * measured, the generator ignores the version argument entirely (0, 1, 2, 3 and 99 give identical
 * digits) and the fingerprint is symmetric under swapping the two halves — necessarily so, since
 * both sides must arrive at the same number. Only the own-identity case is actually covered.
 *
 * <p>Everything here uses more than one address or asserts on actual content, which is exactly what
 * the single-address, non-null-only tests could not do.
 */
public class TrustScopingTest {

  private static final SignalProtocolAddress ALICE = ProtocolAddresses.of("alice-uuid", 11);
  private static final SignalProtocolAddress BOB = ProtocolAddresses.of("bob-uuid", 22);
  /** Same name as ALICE, different device — the shape a folded legacy id produces. */
  private static final SignalProtocolAddress ALICE_OTHER_DEVICE =
      ProtocolAddresses.of("alice-uuid", 33);

  private IdentityKeyStoreImpl store;

  @Before
  public void setUp() {
    SignalProtocolMain.testIsRunning = true;
    final IdentityKeyPair own = KeyUtil.generateIdentityKeyPair();
    store = new IdentityKeyStoreImpl(own, KeyUtil.generateRegistrationId());
  }

  private static IdentityKey someIdentity() {
    return KeyUtil.generateIdentityKeyPair().getPublicKey();
  }

  // -------------------------------------------------- one address, one rejection

  /**
   * Marking the same address rejected twice must leave ONE entry, or the user can never clear it.
   *
   * <p>{@code rejectedAddresses} is a {@code List}, and {@code clearRejection} answers it with
   * {@code List.remove(Object)} - which removes the first occurrence and stops. So the dedupe on
   * the way in is not tidiness; it is the only thing making the removal on the way out complete.
   * A mutation sweep deleted the {@code contains} check and the whole suite stayed green.
   *
   * <p>Two entries at one address are reachable without any attacker: {@code markKeyRejected} is
   * called from {@code rejectContactKey}, behind a button the user can press more than once, and
   * again from {@code verifyContact}'s failed-write rollback.
   *
   * <p>What the second entry costs: the user compares the safety number, it matches, the app
   * records the comparison - and the warning does not come down. {@code isContactKeyTrustworthy}
   * refuses the badge for as long as a rejection stands, so it is a warning nobody can answer.
   * Measured with the guard deleted: after two rejections and one successful clear,
   * {@code wasKeyRejected} is still true.
   */
  @Test
  public void rejectingTwiceStillClearsInOne() {
    store.markKeyRejected(ALICE);
    store.markKeyRejected(ALICE);
    assertTrue("precondition: the address must read as rejected", store.wasKeyRejected(ALICE));

    assertTrue("precondition: the clear must report that it removed something - otherwise this "
        + "case would pass on a store that never recorded the rejection at all",
        store.clearRejection(ALICE));

    assertFalse("one comparison that matched must clear the rejection outright. A second stored "
            + "entry survives it, and the user is left with a warning they have already answered "
            + "and cannot answer again",
        store.wasKeyRejected(ALICE));
  }

  /**
   * And the same for the out-of-band list, which dedupes for a different reason.
   *
   * <p>{@code outOfBandAddresses} records provenance rather than a user decision, so a duplicate
   * does not strand a warning. It is pinned because the mutation survives and the fix is one line:
   * {@code removeIdentity} and {@code acceptIdentityChange} both drop provenance with a single
   * {@code remove}, so a second entry would silently keep an out-of-band claim alive across a key
   * the user has already replaced.
   */
  @Test
  public void markingOutOfBandTwiceStillDropsInOne() {
    final IdentityKey key = someIdentity();
    store.saveIdentity(ALICE, key);
    store.markKeyOutOfBand(ALICE);
    store.markKeyOutOfBand(ALICE);
    assertTrue("precondition: the key must read as out-of-band",
        store.isKeyOutOfBand(ALICE));

    store.removeIdentity(ALICE);

    assertFalse("forgetting the key must drop its provenance completely; a surviving duplicate "
            + "keeps an out-of-band claim attached to an address whose key is gone",
        store.isKeyOutOfBand(ALICE));
  }

  /**
   * Provenance belongs to the key it was established for, on all three paths that replace one.
   *
   * <p>{@code acceptIdentityChange} and {@code removeIdentity} both drop the out-of-band record
   * when the pinned key goes, and the first states the reason: a new key did not arrive through the
   * channel the old one did. {@code saveIdentity}'s {@code REPLACED_EXISTING} arm replaces a pinned
   * key too and did not drop it - an inconsistency rather than a decision, and one a review found
   * by comparing the three rather than by anything failing.
   *
   * <p>Inert today, and the test says so rather than implying a live defect: libsignal's
   * {@code isTrustedIdentity} refuses a displaced key before {@code saveIdentity} is reached, so
   * this arm does not run in production. It is pinned because the alternative is that whoever makes
   * it reachable has to notice this on their own, from a store whose other two replacers already
   * behave the other way.
   */
  @Test
  public void replacingApinnedKeyDropsTheOutOfBandClaimWithIt() {
    final IdentityKey original = someIdentity();
    store.saveIdentity(ALICE, original);
    store.markKeyOutOfBand(ALICE);
    assertTrue("precondition: the original key must be recorded as out-of-band",
        store.isKeyOutOfBand(ALICE));

    final IdentityKey replacement = someIdentity();
    assertEquals("precondition: this must be the replacing arm, not a first sighting",
        com.amnesica.kryptey.inputmethod.signalprotocol.stores.IdentityKeyStoreImpl
            .IdentityChange.REPLACED_EXISTING,
        store.saveIdentity(ALICE, replacement));

    assertFalse("the out-of-band claim was made about the key that has just been replaced. Left "
            + "standing it says the NEW key arrived through a trusted channel, which is exactly "
            + "what did not happen - and it is what the other two replacers already avoid",
        store.isKeyOutOfBand(ALICE));
  }

  // ------------------------------------------------------------- scoping

  /** Accepting one contact's change must not silently clear another's warning. */
  @Test
  public void acceptingOneContactsChangeLeavesOthersPending() {
    final IdentityKey aliceNew = someIdentity();
    final IdentityKey bobNew = someIdentity();

    store.saveIdentity(ALICE, someIdentity());
    store.saveIdentity(BOB, someIdentity());
    store.recordIdentityChange(ALICE, aliceNew);
    store.recordIdentityChange(BOB, bobNew);

    assertTrue(store.hasUnacceptedIdentityChange(ALICE));
    assertTrue(store.hasUnacceptedIdentityChange(BOB));

    assertTrue(store.acceptIdentityChange(ALICE, aliceNew));

    assertFalse(store.hasUnacceptedIdentityChange(ALICE));
    assertTrue("accepting Alice's change cleared Bob's warning too",
        store.hasUnacceptedIdentityChange(BOB));
  }

  /** The flag must be keyed by the full address, not the name alone. */
  @Test
  public void theChangeFlagDistinguishesDevicesOfTheSameName() {
    store.saveIdentity(ALICE, someIdentity());
    store.saveIdentity(ALICE_OTHER_DEVICE, someIdentity());
    store.recordIdentityChange(ALICE, someIdentity());

    assertTrue(store.hasUnacceptedIdentityChange(ALICE));
    assertFalse("a change on device 11 must not flag device 33",
        store.hasUnacceptedIdentityChange(ALICE_OTHER_DEVICE));
  }

  @Test
  public void identityPinsDistinguishDevicesOfTheSameName() {
    final IdentityKey first = someIdentity();
    final IdentityKey second = someIdentity();

    store.saveIdentity(ALICE, first);
    store.saveIdentity(ALICE_OTHER_DEVICE, second);

    assertEquals(first, store.getIdentity(ALICE));
    assertEquals(second, store.getIdentity(ALICE_OTHER_DEVICE));
    assertNotEquals(first, second);
  }

  @Test
  public void forgettingOneContactLeavesTheOtherIntact() {
    final IdentityKey bobKey = someIdentity();
    store.saveIdentity(ALICE, someIdentity());
    store.saveIdentity(BOB, bobKey);

    store.removeIdentity(ALICE);

    assertNull(store.getIdentity(ALICE));
    assertEquals("removing Alice must not touch Bob", bobKey, store.getIdentity(BOB));
  }

  @Test
  public void outOfBandProvenanceIsPerAddress() {
    store.saveIdentity(ALICE, someIdentity());
    store.saveIdentity(BOB, someIdentity());
    store.markKeyOutOfBand(ALICE);

    assertTrue(store.isKeyOutOfBand(ALICE));
    assertFalse("provenance leaked to another contact", store.isKeyOutOfBand(BOB));
    assertFalse(store.isKeyOutOfBand(ALICE_OTHER_DEVICE));
  }

  /** A key accepted after a change did not come through the original trusted channel. */
  @Test
  public void provenanceDoesNotCarryOverToAnAcceptedNewKey() {
    final IdentityKey replacement = someIdentity();
    store.saveIdentity(ALICE, someIdentity());
    store.markKeyOutOfBand(ALICE);
    store.recordIdentityChange(ALICE, replacement);

    assertTrue(store.acceptIdentityChange(ALICE, replacement));

    assertFalse("out-of-band provenance must not transfer to a replacement key",
        store.isKeyOutOfBand(ALICE));
  }

  // --------------------------------------------------------- fingerprints

  /**
   * The safety number must actually be over the peer's key, and must match what the peer computes.
   *
   * <p>Previously the only assertion anywhere was {@code assertNotNull}, so a fingerprint over the
   * user's own identity passed. Swapping the halves and changing the version are NOT caught here
   * and cannot be: measured against libsignal 0.86.5, the version argument is ignored and the
   * fingerprint is symmetric in its two halves.
   */
  @Test
  public void bothSidesComputeTheSameSafetyNumber() throws Exception {
    SignalProtocolMain.initialize(null);
    final Account alice = SignalProtocolMain.getInstance().getAccount();
    SignalProtocolMain.initialize(null);
    final Account bob = SignalProtocolMain.getInstance().getAccount();

    final SignalProtocolAddress aliceAddress =
        ProtocolAddresses.of(alice.getSignalProtocolAddress().getName(), alice.getDeviceId());
    final SignalProtocolAddress bobAddress =
        ProtocolAddresses.of(bob.getSignalProtocolAddress().getName(), bob.getDeviceId());

    // Each side learns the other's key.
    SignalProtocolMain.getInstance().setAccount(alice);
    final String aliceBundle = SignalProtocolMain.exportOwnKeyBundle();
    SignalProtocolMain.getInstance().setAccount(bob);
    final String bobBundle = SignalProtocolMain.exportOwnKeyBundle();

    SignalProtocolMain.getInstance().setAccount(bob);
    assertTrue(SignalProtocolMain.processPreKeyResponseMessage(
        EnvelopeCodec.fromWire(aliceBundle), aliceAddress));
    final Fingerprint bobsView = SignalProtocolMain.getFingerprint(
        new Contact("A", "L", aliceAddress.getName(), aliceAddress.getDeviceId(), false));

    SignalProtocolMain.getInstance().setAccount(alice);
    assertTrue(SignalProtocolMain.processPreKeyResponseMessage(
        EnvelopeCodec.fromWire(bobBundle), bobAddress));
    final Fingerprint alicesView = SignalProtocolMain.getFingerprint(
        new Contact("B", "O", bobAddress.getName(), bobAddress.getDeviceId(), false));

    assertNotNull(bobsView);
    assertNotNull(alicesView);
    assertEquals("the two sides must read out the same safety number, or verification is useless",
        bobsView.getDisplayableFingerprint().getDisplayText(),
        alicesView.getDisplayableFingerprint().getDisplayText());
  }

  /** Different peers must not share a safety number. */
  @Test
  public void differentPeersHaveDifferentSafetyNumbers() throws Exception {
    SignalProtocolMain.initialize(null);
    final Account me = SignalProtocolMain.getInstance().getAccount();
    SignalProtocolMain.initialize(null);
    final Account peerOne = SignalProtocolMain.getInstance().getAccount();
    SignalProtocolMain.initialize(null);
    final Account peerTwo = SignalProtocolMain.getInstance().getAccount();

    SignalProtocolMain.getInstance().setAccount(peerOne);
    final String oneBundle = SignalProtocolMain.exportOwnKeyBundle();
    SignalProtocolMain.getInstance().setAccount(peerTwo);
    final String twoBundle = SignalProtocolMain.exportOwnKeyBundle();

    SignalProtocolMain.getInstance().setAccount(me);
    final SignalProtocolAddress oneAddr = ProtocolAddresses.of(
        peerOne.getSignalProtocolAddress().getName(), peerOne.getDeviceId());
    final SignalProtocolAddress twoAddr = ProtocolAddresses.of(
        peerTwo.getSignalProtocolAddress().getName(), peerTwo.getDeviceId());
    SignalProtocolMain.processPreKeyResponseMessage(EnvelopeCodec.fromWire(oneBundle), oneAddr);
    SignalProtocolMain.processPreKeyResponseMessage(EnvelopeCodec.fromWire(twoBundle), twoAddr);

    final String a = SignalProtocolMain.getFingerprint(
        new Contact("P", "1", oneAddr.getName(), oneAddr.getDeviceId(), false))
        .getDisplayableFingerprint().getDisplayText();
    final String b = SignalProtocolMain.getFingerprint(
        new Contact("P", "2", twoAddr.getName(), twoAddr.getDeviceId(), false))
        .getDisplayableFingerprint().getDisplayText();

    assertNotEquals("two different peers produced the same safety number", a, b);
  }

  /**
   * The fallback: a pinned identity with no session must still yield a safety number.
   *
   * <p>Every other fingerprint test goes through a path that establishes a session and a pin
   * together, so the identity-store fallback never executes and could be deleted unnoticed — which
   * is exactly what mutation testing found. Deleting the session while keeping the pin is the state
   * the fallback exists for.
   */
  @Test
  public void aFingerprintIsAvailableFromThePinAloneWithNoSession() throws Exception {
    SignalProtocolMain.initialize(null);
    final Account me = SignalProtocolMain.getInstance().getAccount();
    SignalProtocolMain.initialize(null);
    final Account peer = SignalProtocolMain.getInstance().getAccount();

    SignalProtocolMain.getInstance().setAccount(peer);
    final String peerBundle = SignalProtocolMain.exportOwnKeyBundle();

    SignalProtocolMain.getInstance().setAccount(me);
    final SignalProtocolAddress peerAddress = ProtocolAddresses.of(
        peer.getSignalProtocolAddress().getName(), peer.getDeviceId());
    assertTrue(SignalProtocolMain.processPreKeyResponseMessage(
        EnvelopeCodec.fromWire(peerBundle), peerAddress));

    // Drop the session, keep the pinned identity.
    me.getSignalProtocolStore().getSessionStore().deleteSession(peerAddress);
    assertFalse(me.getSignalProtocolStore().getSessionStore().containsSession(peerAddress));
    assertNotNull("the pin must survive session deletion",
        me.getSignalProtocolStore().getIdentityKeyStore().getIdentity(peerAddress));

    assertNotNull("a safety number must still be derivable from the pinned key alone",
        SignalProtocolMain.getFingerprint(new Contact("P", "R", peerAddress.getName(),
            peerAddress.getDeviceId(), false)));
  }

  /** No session and no pinned key means no fingerprint — and no crash. */
  @Test
  public void anUnknownContactYieldsNoFingerprintRatherThanCrashing() {
    SignalProtocolMain.initialize(null);
    assertNull(SignalProtocolMain.getFingerprint(
        new Contact("Nobody", "Here", "unknown-uuid", 42, false)));
  }

  // ------------------------------------------------------------- null guards

  /**
   * Both arms of the identity-store null guards.
   *
   * <p>These are written {@code a == null || b == null}, and every existing test passes two real
   * values — so only the both-non-null path ever ran and weakening the {@code ||} to {@code &&}
   * survived. With {@code &&}, one null reaches {@code addressKey(null)} or an {@code equals} on a
   * null pin: an NPE out of the decrypt path, which on this codebase means a keyboard that dies
   * mid-message rather than a refusal.
   */
  @Test
  public void recordingAChangeIsSafeWithEitherArgumentMissing() {
    final IdentityKey key = someIdentity();
    store.saveIdentity(ALICE, someIdentity());

    store.recordIdentityChange(null, key);      // must not throw
    store.recordIdentityChange(ALICE, null);    // must not throw

    assertFalse("a guarded call must not have recorded anything",
        store.hasUnacceptedIdentityChange(ALICE));
  }

  @Test
  public void acceptingAChangeIsSafeWithEitherArgumentMissing() {
    final IdentityKey offered = someIdentity();
    store.saveIdentity(ALICE, someIdentity());
    store.recordIdentityChange(ALICE, offered);

    assertFalse("a null address must be refused, not dereferenced",
        store.acceptIdentityChange(null, offered));
    assertFalse("a null key must be refused", store.acceptIdentityChange(ALICE, null));
    assertTrue("and the pending change must survive both refusals",
        store.hasUnacceptedIdentityChange(ALICE));
  }

  @Test
  public void theQueriesAreSafeWithNoAddress() {
    assertFalse(store.hasUnacceptedIdentityChange(null));
    assertFalse(store.wasKeyRejected(null));
    assertFalse(store.isKeyOutOfBand(null));
    assertNull(store.getPendingIdentity(null));
    store.removeIdentity(null);      // must not throw
    store.markKeyRejected(null);     // must not throw
    assertFalse(store.dismissIdentityChange(null));
  }

  // ------------------------------------------------- equals completeness

  /**
   * Every field of the identity store must participate in {@code equals}.
   *
   * <p>Nothing in production compares two stores — but the serialisation tests do, to prove the
   * store survives a JSON round trip. An {@code equals} that ignores a field makes that assertion
   * vacuous for the field it ignores, so the round trip could silently drop the pinned keys, the
   * pending changes, or the rejection records and no test would notice. A mutation sweep of this
   * class killed everything behavioural and left only these.
   */
  @Test
  public void everyFieldOfTheStoreParticipatesInEquals() {
    final IdentityKeyPair own = KeyUtil.generateIdentityKeyPair();
    final int registrationId = KeyUtil.generateRegistrationId();

    final IdentityKeyStoreImpl a = new IdentityKeyStoreImpl(own, registrationId);
    final IdentityKeyStoreImpl b = new IdentityKeyStoreImpl(own, registrationId);
    assertEquals("two identically built stores must be equal", a, b);

    // The account's OWN key pair, which this test claimed to cover and did not: a and b were both
    // built from the same `own`, so the sixth field was never varied and dropping it from equals
    // left the suite green. By this test's own argument that made every round-trip assertion
    // vacuous for the one key the whole store is anchored on.
    assertNotEquals("a store with a different identity key pair must not be equal",
        a, new IdentityKeyStoreImpl(KeyUtil.generateIdentityKeyPair(), registrationId));

    // registration id
    assertNotEquals(a, new IdentityKeyStoreImpl(own, registrationId + 1));

    // pinned keys
    final IdentityKey pin = someIdentity();
    a.saveIdentity(ALICE, pin);
    assertNotEquals("a pinned key must count", a, b);
    b.saveIdentity(ALICE, pin);
    assertEquals(a, b);

    // pending changes
    a.recordIdentityChange(ALICE, someIdentity());
    assertNotEquals("a pending identity change must count", a, b);
    a.dismissIdentityChange(ALICE);
    assertEquals(a, b);

    // out-of-band provenance
    a.markKeyOutOfBand(ALICE);
    assertNotEquals("out-of-band provenance must count", a, b);
    b.markKeyOutOfBand(ALICE);
    assertEquals(a, b);

    // rejection records - the one thing that must outlive removeIdentity
    a.markKeyRejected(ALICE);
    assertNotEquals("a rejection record must count", a, b);
  }

  /** And accepting a change requires the key the user was actually shown, in both directions. */
  @Test
  public void acceptingRequiresExactlyTheShownKey() {
    final IdentityKey offered = someIdentity();
    store.saveIdentity(ALICE, someIdentity());
    store.recordIdentityChange(ALICE, offered);

    assertFalse("a different key must not be accepted",
        store.acceptIdentityChange(ALICE, someIdentity()));
    assertTrue("and the change must still be pending", store.hasUnacceptedIdentityChange(ALICE));

    assertFalse("nothing pending at another address means nothing to accept",
        store.acceptIdentityChange(BOB, offered));

    assertTrue("the key actually offered must be accepted",
        store.acceptIdentityChange(ALICE, offered));
    assertEquals(offered, store.getIdentity(ALICE));
  }

  /**
   * What the fingerprint generator's {@code version} argument actually does, which is nothing.
   *
   * <p>Pinned as an interop guard rather than as an assertion about our code. The obvious use for
   * that argument is the one thing it cannot do: bump it to force everyone to re-compare after a
   * derivation change. Measured against libsignal 0.86.5, versions 0, 1, 2, 3 and 99 all produce
   * byte-identical digits.
   *
   * <p>If a future libsignal makes it meaningful, this fails - which is exactly when someone needs
   * to know, because at that moment the constant in {@code createFingerprint} starts deciding what
   * users compare, and two installs on different versions would silently disagree.
   */
  @Test
  public void thefingerprintVersionArgumentIsInertInThisLibsignal() throws Exception {
    SignalProtocolMain.initialize(null);
    final org.signal.libsignal.protocol.IdentityKey alice =
        SignalProtocolMain.getInstance().getAccount()
            .getSignalProtocolStore().getIdentityKeyPair().getPublicKey();
    SignalProtocolMain.initialize(null);
    final org.signal.libsignal.protocol.IdentityKey bob =
        SignalProtocolMain.getInstance().getAccount()
            .getSignalProtocolStore().getIdentityKeyPair().getPublicKey();

    final org.signal.libsignal.protocol.fingerprint.NumericFingerprintGenerator generator =
        new org.signal.libsignal.protocol.fingerprint.NumericFingerprintGenerator(5200);

    String first = null;
    for (final int version : new int[] {0, 1, 2, 3, 99}) {
      final String digits = generator
          .createFor(version, alice.serialize(), alice, bob.serialize(), bob)
          .getDisplayableFingerprint().getDisplayText();
      if (first == null) first = digits;
      assertEquals("libsignal now distinguishes fingerprint version " + version
              + " - the constant in createFingerprint has become load-bearing, and two installs on "
              + "different values would show different numbers for the same pair of keys",
          first, digits);
    }

    // And symmetric in its halves, which is why "swapped halves" cannot be tested either.
    assertEquals("the fingerprint must be symmetric, or the two sides could never agree",
        generator.createFor(2, alice.serialize(), alice, bob.serialize(), bob)
            .getDisplayableFingerprint().getDisplayText(),
        generator.createFor(2, bob.serialize(), bob, alice.serialize(), alice)
            .getDisplayableFingerprint().getDisplayText());
  }
}
