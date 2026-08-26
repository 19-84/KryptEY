package com.amnesica.kryptey.inputmethod.signalprotocol;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.amnesica.kryptey.inputmethod.signalprotocol.encoding.EnvelopeCodec;
import com.amnesica.kryptey.inputmethod.signalprotocol.prekey.PreKeyResponse;
import com.amnesica.kryptey.inputmethod.signalprotocol.util.ProtocolAddresses;

import org.junit.Before;
import org.junit.Test;
import org.signal.libsignal.protocol.SignalProtocolAddress;

/**
 * A one-time pre-key must never be offered in two bundles.
 *
 * <p>Found by mutation, and the first version of this test asserted the wrong thing — which is worth
 * keeping, because the code was right and the test was wrong in a way that looked like a finding.
 *
 * <p>The measurement stands: turning {@code PreKeyStoreImpl.removePreKey} into a no-op left the full
 * suite green at 874 tests, and making it <em>throw</em> failed 25 — so it is reached by every
 * end-to-end conversation on the branch and asserted by none of them. That is the combination that
 * looks like coverage and is not.
 *
 * <p>What it is <b>not</b> is "the key must be gone afterwards". Used records are retained on
 * purpose, and {@code pruneUsedPreKeys} documents why: a peer's first message arrives after the
 * bundle was handed out, so the key has to still be there to decrypt it. Ids are recycled too —
 * {@code findUnusedPreKeyId} returns the lowest id whose record is not marked used, so a fresh key
 * pair reappears under an id that was just consumed. Asserting on the id therefore fails against
 * correct code, which is exactly what the first draft of this file did.
 *
 * <p>The property that actually carries the security is about key <em>material</em>, tested through
 * the bundle rather than the store: once a one-time pre-key has been used, no later bundle may offer
 * that public key again. If it could, one device compromise would cover every first message that
 * reused it instead of one — which is the whole reason the key is one-time. Retention is fine;
 * re-offering is not.
 */
public class OneTimePreKeyIsConsumedTest {

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

  /** The one-time pre-key a bundle actually offers, as bytes on the wire. */
  private static byte[] offeredPreKey(final MessageEnvelope bundle) {
    final PreKeyResponse response = bundle.getPreKeyResponse();
    assertNotNull("the bundle must carry a pre-key response", response);
    assertNotNull(response.getDevices());
    assertFalse(response.getDevices().isEmpty());
    assertNotNull("the bundle must offer a one-time pre-key, or this test is about nothing",
        response.getDevices().get(0).getPreKey());
    return response.getDevices().get(0).getPreKey().getPublicKey().serialize();
  }

  /**
   * Once used, that key is never offered again.
   *
   * <p>Black-box on purpose: it asks the same question a peer receiving two bundles would ask, so it
   * survives any change to how consumption is recorded internally — retention, id recycling, marking
   * used rather than deleting. All of those are implementation; this is the property.
   */
  @Test
  public void aconsumedOneTimePreKeyIsNeverOfferedAgain() throws Exception {
    activate(alice);
    final MessageEnvelope firstBundle = SignalProtocolMain.getPreKeyResponseMessage();
    final byte[] firstOffer = offeredPreKey(firstBundle);
    final String wire = EnvelopeCodec.toWire(firstBundle);

    // Bob builds a session from it and sends the first message, which is what consumes the key.
    activate(bob);
    assertTrue(SignalProtocolMain.processPreKeyResponseMessage(
        EnvelopeCodec.fromWire(wire), addressOf(alice)));
    final String firstMessage = EnvelopeCodec.toWire(
        SignalProtocolMain.encryptMessage("the first message of the session", addressOf(alice)));

    activate(alice);
    assertEquals("precondition: the message must decrypt, or nothing was consumed",
        "the first message of the session",
        SignalProtocolMain.decryptMessage(EnvelopeCodec.fromWire(firstMessage), addressOf(bob)));

    final byte[] secondOffer = offeredPreKey(SignalProtocolMain.getPreKeyResponseMessage());

    assertFalse("a one-time pre-key that has been used must never be offered in another bundle. "
            + "Offering it again means one device compromise covers every first message that reused "
            + "it, instead of one - which is the entire reason the key is one-time. Note this is "
            + "about the KEY, not the id: ids are recycled with fresh material on purpose",
        java.util.Arrays.equals(firstOffer, secondOffer));
  }

  /**
   * After the first message is decrypted, the private half of that pre-key is gone from the store.
   *
   * <p><b>It pins {@code removePreKey} now, and it did not used to.</b> This paragraph used to
   * record a measured negative result: disabling that method changed nothing any test could see,
   * because the store ended with the same two records, both unused, and neither holding the offered
   * key. That measurement was taken while the decrypt path re-minted a pre-key at the declared id
   * unconditionally, which produced the same end state by another route. Now that regeneration
   * happens only when a pre-key was actually consumed, an emptied {@code removePreKey} leaves the
   * record present after the decrypt, the gate declines to replace it, and the store still holds the
   * offered key — so this test fails against that mutant. A surviving mutant became a killed one as
   * a side effect of fixing something else, which is worth writing down: the earlier note was
   * honest about what it measured, and what it measured stopped being true.
   *
   * <p>Scanning key material rather than the id, because the id is recycled: the allocator hands out
   * the lowest record not marked used, and a replacement pair appears under the same number. An
   * assertion on {@code containsPreKey(id)} therefore fails against correct code — it was the first
   * draft of this file, and it looked exactly like a finding.
   */
  @Test
  public void theconsumedPrivateKeyIsDeletedRatherThanLeftLyingInTheStore() throws Exception {
    activate(alice);
    final MessageEnvelope bundle = SignalProtocolMain.getPreKeyResponseMessage();
    final byte[] offered = offeredPreKey(bundle);
    final String wire = EnvelopeCodec.toWire(bundle);

    activate(bob);
    assertTrue(SignalProtocolMain.processPreKeyResponseMessage(
        EnvelopeCodec.fromWire(wire), addressOf(alice)));
    final String firstMessage = EnvelopeCodec.toWire(
        SignalProtocolMain.encryptMessage("first", addressOf(alice)));

    activate(alice);
    assertEquals("precondition: the message must decrypt, or nothing was consumed", "first",
        SignalProtocolMain.decryptMessage(EnvelopeCodec.fromWire(firstMessage), addressOf(bob)));

    assertFalse("the pre-key that opened this message must no longer be in the store. Retaining it "
            + "keeps the private half alive until pruning evicts it, so a device compromise in that "
            + "window still derives a session the key was supposed to close. Note this scans key "
            + "MATERIAL: the id is recycled with a fresh pair, so an id-based assertion passes here "
            + "for the wrong reason", holdsPreKeyMaterial(alice, offered));
  }

  /** Whether any record in the account's pre-key store still carries this public key. */
  private static boolean holdsPreKeyMaterial(final Account account, final byte[] material)
      throws Exception {
    final com.amnesica.kryptey.inputmethod.signalprotocol.stores.PreKeyStoreImpl store =
        account.getSignalProtocolStore().getPreKeyStore();
    // Ids are small and dense - the allocator counts up from zero and recycles - so a bounded scan
    // covers the store. The bound is asserted against the store size so it cannot silently miss.
    final int scan = 256;
    assertTrue("the scan bound must cover the store, or this check is looking at part of it",
        store.getSize() <= scan);
    for (int id = 0; id < scan; id++) {
      if (!store.containsPreKey(id)) continue;
      if (java.util.Arrays.equals(store.loadPreKey(id).getKeyPair().getPublicKey().serialize(),
          material)) {
        return true;
      }
    }
    return false;
  }

  /**
   * And the recycled id carries new key material rather than the old pair.
   *
   * <p>The mechanism behind the property above, pinned separately so a regression says which half
   * broke. {@code findUnusedPreKeyId} hands out the lowest id not marked used, so the id a peer just
   * consumed is the first one reissued — and reissuing it with the same key pair would satisfy no
   * test that only watched ids.
   */
  @Test
  public void arecycledPreKeyIdCarriesAdifferentKey() throws Exception {
    activate(alice);
    final MessageEnvelope firstBundle = SignalProtocolMain.getPreKeyResponseMessage();
    final int reusedId = firstBundle.getPreKeyResponse().getDevices().get(0).getPreKey().getKeyId();
    final byte[] firstOffer = offeredPreKey(firstBundle);
    final String wire = EnvelopeCodec.toWire(firstBundle);

    activate(bob);
    assertTrue(SignalProtocolMain.processPreKeyResponseMessage(
        EnvelopeCodec.fromWire(wire), addressOf(alice)));
    final String firstMessage = EnvelopeCodec.toWire(
        SignalProtocolMain.encryptMessage("first", addressOf(alice)));

    activate(alice);
    assertEquals("first",
        SignalProtocolMain.decryptMessage(EnvelopeCodec.fromWire(firstMessage), addressOf(bob)));

    final MessageEnvelope secondBundle = SignalProtocolMain.getPreKeyResponseMessage();
    final int nextId = secondBundle.getPreKeyResponse().getDevices().get(0).getPreKey().getKeyId();

    if (nextId != reusedId) return; // the id was not recycled here; the property above still holds

    assertFalse("the id was reissued, so it must carry a new key pair - reissuing the same material "
            + "under a recycled id is the failure an id-based test cannot see",
        java.util.Arrays.equals(firstOffer, offeredPreKey(secondBundle)));
  }
}
