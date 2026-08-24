package com.amnesica.kryptey.inputmethod.signalprotocol;

import static org.junit.Assert.assertEquals;
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
 * What the safety number is a function of — the single fact the whole trust model rests on.
 *
 * <p>The number used to be computed over the two <em>address names</em>, mirroring Signal, where
 * that identifier is a server-attested ACI. KryptEY has no server and nothing attests anything: the
 * remote name is whatever the peer wrote in the envelope, a plaintext field covered by neither the
 * bundle signatures nor the message MAC.
 *
 * <p>So a messenger that rewrote that one field, consistently in both directions, left every key
 * genuine and every message decrypting while the two sides displayed different numbers. It could
 * not forge a <em>match</em> — the keys were always in the hash — but it could manufacture
 * unlimited <em>mismatches</em> between two entirely honest peers, on demand and undetectably.
 *
 * <p>That is the input every other control trusts. A mismatch drops the badge and is the trigger
 * for {@code rejectContactKey}, which discards a pin — so an adversary able to produce mismatches
 * could walk a careful user into throwing away a correct key, and users who saw enough of them
 * would stop believing the number at all.
 *
 * <p>The number is now a pure function of the two identity keys. These tests pin that.
 */
public class FingerprintBindingTest {

  private Account alice;
  private Account bob;
  private Account mallory;

  @Before
  public void setUp() {
    SignalProtocolMain.testIsRunning = true;
    SignalProtocolMain.initialize(null);
    alice = SignalProtocolMain.getInstance().getAccount();
    SignalProtocolMain.initialize(null);
    bob = SignalProtocolMain.getInstance().getAccount();
    SignalProtocolMain.initialize(null);
    mallory = SignalProtocolMain.getInstance().getAccount();
  }

  private void activate(final Account a) {
    SignalProtocolMain.getInstance().setAccount(a);
  }

  private static SignalProtocolAddress addressOf(final Account a) {
    return ProtocolAddresses.of(a.getSignalProtocolAddress().getName(), a.getDeviceId());
  }

  private static Contact contactAt(final SignalProtocolAddress address) {
    return new Contact("Peer", "Account", address.getName(), address.getDeviceId(), false);
  }

  /** Pins {@code peer}'s bundle into {@code viewer}'s store under {@code underAddress}. */
  private void pin(final Account viewer, final Account peer,
      final SignalProtocolAddress underAddress) throws Exception {
    activate(peer);
    final String bundle = SignalProtocolMain.exportOwnKeyBundle();
    activate(viewer);
    assertTrue(SignalProtocolMain.processPreKeyResponseMessage(
        EnvelopeCodec.fromWire(bundle), underAddress));
  }

  private String numberFor(final SignalProtocolAddress address) {
    final var fingerprint = SignalProtocolMain.getFingerprint(contactAt(address));
    assertNotNull(fingerprint);
    return fingerprint.getDisplayableFingerprint().getDisplayText();
  }

  /**
   * The attack this closes. Mallory relays honestly but rewrites the declared address name in
   * transit — every key genuine, nothing else touched.
   */
  @Test
  public void rewritingTheAddressNameCannotChangeTheNumber() throws Exception {
    final SignalProtocolAddress real = addressOf(alice);
    final SignalProtocolAddress rewritten =
        ProtocolAddresses.of("rewritten-by-the-messenger", alice.getDeviceId());

    pin(bob, alice, real);
    final String underRealName = numberFor(real);

    pin(bob, alice, rewritten);
    final String underRewrittenName = numberFor(rewritten);

    assertEquals("the same identity key under a rewritten address name must read out the same "
            + "number, or a messenger can manufacture a mismatch between honest peers",
        underRealName, underRewrittenName);
  }

  /** Including when the device id is what was rewritten. */
  @Test
  public void rewritingTheDeviceIdCannotChangeTheNumber() throws Exception {
    final SignalProtocolAddress asSent = ProtocolAddresses.of("alice-uuid", 11);
    final SignalProtocolAddress asRewritten = ProtocolAddresses.of("alice-uuid", 77);

    pin(bob, alice, asSent);
    pin(bob, alice, asRewritten);

    assertEquals(numberFor(asSent), numberFor(asRewritten));
  }

  /** Both honest sides must still agree — the property the comparison exists for. */
  @Test
  public void bothHonestSidesStillComputeTheSameNumber() throws Exception {
    pin(bob, alice, addressOf(alice));
    activate(bob);
    final String bobsView = numberFor(addressOf(alice));

    pin(alice, bob, addressOf(bob));
    activate(alice);
    final String alicesView = numberFor(addressOf(bob));

    assertEquals("the two sides must read out the same number, or verification is useless",
        bobsView, alicesView);
  }

  /**
   * And they must still agree when the messenger has rewritten the names each side sees — which is
   * the whole point: the rewrite becomes invisible instead of becoming a false alarm.
   */
  @Test
  public void honestSidesAgreeEvenWhenTheMessengerRewritesBothNames() throws Exception {
    pin(bob, alice, ProtocolAddresses.of("alice-as-bob-sees-her", 5));
    activate(bob);
    final String bobsView = numberFor(ProtocolAddresses.of("alice-as-bob-sees-her", 5));

    pin(alice, bob, ProtocolAddresses.of("bob-as-alice-sees-him", 9));
    activate(alice);
    final String alicesView = numberFor(ProtocolAddresses.of("bob-as-alice-sees-him", 9));

    assertEquals("rewritten names must not split two honest peers", bobsView, alicesView);
  }

  /**
   * The number must still do its actual job: a different key must read differently. Without this
   * the tests above are satisfied by a constant.
   */
  @Test
  public void adifferentIdentityKeyStillChangesTheNumber() throws Exception {
    final SignalProtocolAddress address = ProtocolAddresses.of("same-address-uuid", 21);

    pin(bob, alice, address);
    final String withAlicesKey = numberFor(address);

    // Mallory substitutes at the same address; force the pin so the number is recomputed.
    activate(bob);
    bob.getSignalProtocolStore().getIdentityKeyStore().removeIdentity(address);
    bob.getSignalProtocolStore().getSessionStore().deleteSession(address);
    pin(bob, mallory, address);
    final String withMallorysKey = numberFor(address);

    assertNotEquals("a substituted key MUST change the number - that is what it is for",
        withAlicesKey, withMallorysKey);
  }

  /** And the local half matters too: the same peer seen by two different users differs. */
  @Test
  public void theViewersOwnKeyIsPartOfTheNumber() throws Exception {
    pin(bob, alice, addressOf(alice));
    activate(bob);
    final String bobsView = numberFor(addressOf(alice));

    pin(mallory, alice, addressOf(alice));
    activate(mallory);
    final String mallorysView = numberFor(addressOf(alice));

    assertNotEquals("two different viewers of one peer must not share a number",
        bobsView, mallorysView);
  }
}
