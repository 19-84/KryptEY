package com.amnesica.kryptey.inputmethod.signalprotocol;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.amnesica.kryptey.inputmethod.signalprotocol.stores.IdentityKeyStoreImpl;
import com.amnesica.kryptey.inputmethod.signalprotocol.util.KeyUtil;
import com.amnesica.kryptey.inputmethod.signalprotocol.util.ProtocolAddresses;

import org.junit.Test;
import org.signal.libsignal.protocol.IdentityKey;
import org.signal.libsignal.protocol.IdentityKeyPair;
import org.signal.libsignal.protocol.SignalProtocolAddress;
import org.signal.libsignal.protocol.state.IdentityKeyStore;

/**
 * Covers {@code saveIdentity}'s return value, which libsignal 0.86 changed from {@code boolean} to
 * {@link IdentityKeyStore.IdentityChange}.
 *
 * <p>That value is how libsignal decides whether to warn a user that a contact's safety number
 * changed — the highest-signal security event the protocol produces. A mutation making it always
 * return {@code NEW_OR_UNCHANGED} survived the whole suite, so nothing was checking it.
 *
 * <p>They also cover the trust decision the store makes on an identity change. {@code trustedKeys}
 * used to append while the lookup returned the first match, so a replaced key was recorded and then
 * permanently ignored — the contact became unreachable with no recovery. It now replaces, and
 * refuses to <em>send</em> to the new key until the user accepts the change, so a substituted
 * identity is neither silently accepted nor permanently fatal.
 */
public class IdentityChangeTest {

  private static final SignalProtocolAddress PEER = ProtocolAddresses.of("peer-uuid", 42);

  private static IdentityKeyStoreImpl newStore() {
    final IdentityKeyPair own = KeyUtil.generateIdentityKeyPair();
    return new IdentityKeyStoreImpl(own, KeyUtil.generateRegistrationId());
  }

  private static IdentityKey someIdentity() {
    return KeyUtil.generateIdentityKeyPair().getPublicKey();
  }

  @Test
  public void aFirstSightingIsNewOrUnchanged() {
    assertEquals(IdentityKeyStore.IdentityChange.NEW_OR_UNCHANGED,
        newStore().saveIdentity(PEER, someIdentity()));
  }

  @Test
  public void reSavingTheSameIdentityIsNewOrUnchanged() {
    final IdentityKeyStoreImpl store = newStore();
    final IdentityKey identity = someIdentity();

    store.saveIdentity(PEER, identity);
    assertEquals("re-saving an unchanged key must not look like a key change",
        IdentityKeyStore.IdentityChange.NEW_OR_UNCHANGED, store.saveIdentity(PEER, identity));
  }

  @Test
  public void aDifferentIdentityForAKnownAddressIsReportedAsReplaced() {
    final IdentityKeyStoreImpl store = newStore();
    store.saveIdentity(PEER, someIdentity());

    assertEquals("a changed identity key must be reported as a replacement",
        IdentityKeyStore.IdentityChange.REPLACED_EXISTING, store.saveIdentity(PEER, someIdentity()));
  }

  @Test
  public void aFirstSightingIsTrusted() {
    final IdentityKeyStoreImpl store = newStore();
    final IdentityKey identity = someIdentity();
    assertTrue(store.isTrustedIdentity(PEER, identity, IdentityKeyStore.Direction.SENDING));
  }

  /** The lookup must return the replacement, not the stale first entry. */
  @Test
  public void aChangedIdentityActuallyReplacesTheStoredOne() {
    final IdentityKeyStoreImpl store = newStore();
    final IdentityKey original = someIdentity();
    final IdentityKey replacement = someIdentity();

    store.saveIdentity(PEER, original);
    assertEquals(IdentityKeyStore.IdentityChange.REPLACED_EXISTING,
        store.saveIdentity(PEER, replacement));

    assertEquals("the store still reports the displaced key", replacement, store.getIdentity(PEER));
  }

  /**
   * Replacing is not trusting. A man in the middle who substitutes their own identity must not be
   * accepted silently, so sending is refused until the user acknowledges the change.
   */
  /**
   * A displaced key is refused in BOTH directions.
   *
   * <p>An earlier version allowed RECEIVING, intending to show the message with a warning. That was
   * unworkable: libsignal calls isTrustedIdentity before saveIdentity, so refusing in either
   * direction means saveIdentity never runs — which made the whole pending-change mechanism dead
   * code. The change is now recorded from the exception path instead.
   */
  @Test
  public void aDisplacedKeyIsRefusedInBothDirections() {
    final IdentityKeyStoreImpl store = newStore();
    final IdentityKey original = someIdentity();
    final IdentityKey attacker = someIdentity();
    store.saveIdentity(PEER, original);

    assertFalse(store.isTrustedIdentity(PEER, attacker, IdentityKeyStore.Direction.SENDING));
    assertFalse(store.isTrustedIdentity(PEER, attacker, IdentityKeyStore.Direction.RECEIVING));
    assertTrue("the pinned key itself must stay trusted",
        store.isTrustedIdentity(PEER, original, IdentityKeyStore.Direction.SENDING));
  }

  @Test
  public void recordingAChangeDoesNotGrantTrust() {
    final IdentityKeyStoreImpl store = newStore();
    final IdentityKey original = someIdentity();
    final IdentityKey attacker = someIdentity();
    store.saveIdentity(PEER, original);

    store.recordIdentityChange(PEER, attacker);

    assertTrue(store.hasUnacceptedIdentityChange(PEER));
    assertEquals("the pin must not move until the user accepts", original, store.getIdentity(PEER));
    assertFalse("recording must not make the offered key usable",
        store.isTrustedIdentity(PEER, attacker, IdentityKeyStore.Direction.SENDING));
    assertEquals(attacker, store.getPendingIdentity(PEER));
  }

  @Test
  public void acceptingReplacesThePinOnlyForTheKeyTheUserWasShown() {
    final IdentityKeyStoreImpl store = newStore();
    final IdentityKey original = someIdentity();
    final IdentityKey offered = someIdentity();
    store.saveIdentity(PEER, original);
    store.recordIdentityChange(PEER, offered);

    assertFalse("accepting some other key must be refused",
        store.acceptIdentityChange(PEER, someIdentity()));
    assertEquals(original, store.getIdentity(PEER));

    assertTrue(store.acceptIdentityChange(PEER, offered));
    assertEquals(offered, store.getIdentity(PEER));
    assertFalse(store.hasUnacceptedIdentityChange(PEER));
    assertTrue(store.isTrustedIdentity(PEER, offered, IdentityKeyStore.Direction.SENDING));
  }

  @Test
  public void recordingIsIgnoredWhenNothingIsActuallyDisplaced() {
    final IdentityKeyStoreImpl store = newStore();
    final IdentityKey key = someIdentity();

    store.recordIdentityChange(PEER, key); // nothing pinned yet
    assertFalse(store.hasUnacceptedIdentityChange(PEER));

    store.saveIdentity(PEER, key);
    store.recordIdentityChange(PEER, key); // same key
    assertFalse("re-offering the pinned key is not a change",
        store.hasUnacceptedIdentityChange(PEER));
  }

  @Test
  public void aFirstSightingIsNotFlaggedAsAChange() {
    final IdentityKeyStoreImpl store = newStore();
    store.saveIdentity(PEER, someIdentity());
    assertFalse("a first sighting must not be treated as an identity change",
        store.hasUnacceptedIdentityChange(PEER));
  }

  /** Recovery: forgetting a contact must forget the pinned identity, or re-invite cannot work. */
  @Test
  public void removingAnIdentityRestoresFirstUseBehaviour() {
    final IdentityKeyStoreImpl store = newStore();
    store.saveIdentity(PEER, someIdentity());
    store.recordIdentityChange(PEER, someIdentity());
    assertTrue(store.hasUnacceptedIdentityChange(PEER));

    store.removeIdentity(PEER);

    assertNull("identity survived removal", store.getIdentity(PEER));
    assertFalse(store.hasUnacceptedIdentityChange(PEER));

    final IdentityKey fresh = someIdentity();
    assertEquals("after removal the next key should be a clean first sighting",
        IdentityKeyStore.IdentityChange.NEW_OR_UNCHANGED, store.saveIdentity(PEER, fresh));
    assertTrue(store.isTrustedIdentity(PEER, fresh, IdentityKeyStore.Direction.SENDING));
  }

  /** The change flag must survive a keyboard restart, or the warning is silently lost. */
  @Test
  public void anUnacceptedChangeSurvivesSerialization() throws Exception {
    final IdentityKeyStoreImpl store = newStore();
    store.saveIdentity(PEER, someIdentity());
    store.recordIdentityChange(PEER, someIdentity());

    final IdentityKeyStoreImpl reloaded = com.amnesica.kryptey.inputmethod.signalprotocol.util.JsonUtil
        .fromJson(com.amnesica.kryptey.inputmethod.signalprotocol.util.JsonUtil.toJson(store),
            IdentityKeyStoreImpl.class);

    assertTrue("the identity-change warning was lost across a restart",
        reloaded.hasUnacceptedIdentityChange(PEER));
  }

  @Test
  public void onlyOneEntryIsKeptPerAddress() {
    final IdentityKeyStoreImpl store = newStore();
    for (int i = 0; i < 20; i++) store.saveIdentity(PEER, someIdentity());
    final IdentityKey last = someIdentity();
    store.saveIdentity(PEER, last);

    // Unbounded growth would also bloat the store, which is re-serialized on every message.
    assertEquals(last, store.getIdentity(PEER));
  }

  // ------------------------------------------------- the UI-facing passthroughs

  /**
   * These are called from the keyboard before an account necessarily exists (and with whatever
   * address a peer supplied), so they must never throw — an unchecked exception on this path
   * reaches setInputView() and kills the IME.
   */
  @Test
  public void theQueryIsSafeBeforeAnAccountExists() {
    assertFalse(SignalProtocolMain.hasUnacceptedIdentityChange(null));
    assertFalse(SignalProtocolMain.acceptIdentityChange(null, null)); // must not throw
  }

  @Test
  public void theQueryReflectsTheStoreForALiveAccount() {
    SignalProtocolMain.testIsRunning = true;
    SignalProtocolMain.initialize(null);
    final IdentityKeyStoreImpl store =
        SignalProtocolMain.getInstance().getAccount().getSignalProtocolStore().getIdentityKeyStore();

    assertFalse("a peer we have never seen has no pending change",
        SignalProtocolMain.hasUnacceptedIdentityChange(PEER));

    store.saveIdentity(PEER, someIdentity());
    assertFalse("a first sighting is not a change",
        SignalProtocolMain.hasUnacceptedIdentityChange(PEER));

    final IdentityKey offered = someIdentity();
    store.recordIdentityChange(PEER, offered);
    assertTrue("a displaced identity must be reported to the UI",
        SignalProtocolMain.hasUnacceptedIdentityChange(PEER));
    assertEquals(offered, SignalProtocolMain.getPendingIdentity(PEER));

    assertTrue(SignalProtocolMain.acceptIdentityChange(PEER, offered));
    assertFalse("accepting must clear the flag",
        SignalProtocolMain.hasUnacceptedIdentityChange(PEER));
  }

  @Test
  public void identitiesAreScopedPerAddress() {
    final IdentityKeyStoreImpl store = newStore();
    final SignalProtocolAddress other = ProtocolAddresses.of("other-uuid", 42);
    final IdentityKey first = someIdentity();

    store.saveIdentity(PEER, first);

    assertEquals("a second address must be an independent first sighting",
        IdentityKeyStore.IdentityChange.NEW_OR_UNCHANGED, store.saveIdentity(other, someIdentity()));
    assertEquals(first, store.getIdentity(PEER));
  }
}
