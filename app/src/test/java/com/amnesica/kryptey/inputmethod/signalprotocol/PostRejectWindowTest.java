package com.amnesica.kryptey.inputmethod.signalprotocol;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.amnesica.kryptey.inputmethod.signalprotocol.chat.Contact;
import com.amnesica.kryptey.inputmethod.signalprotocol.encoding.EnvelopeCodec;
import com.amnesica.kryptey.inputmethod.signalprotocol.stores.SignalProtocolStoreImpl;
import com.amnesica.kryptey.inputmethod.signalprotocol.util.JsonUtil;
import com.amnesica.kryptey.inputmethod.signalprotocol.util.ProtocolAddresses;

import org.junit.Before;
import org.junit.Test;
import org.signal.libsignal.protocol.SignalProtocolAddress;

import java.util.ArrayList;

/**
 * The window a rejection opens, and the record that closes it.
 *
 * <p>{@code rejectContactKey} un-pins a key so a user who found a safety-number mismatch can act.
 * But {@code removeIdentity} wiped every trace of the address, leaving it indistinguishable from
 * one never seen — so the next bundle to arrive was a clean trust-on-first-use with no warning
 * anywhere, <b>including the forged bundle that provoked the rejection</b>. That is the same
 * substitution window contact deletion was reverted for, entered through a different door.
 *
 * <p>Worse, the design argued this was safe because "an attacker cannot deliver the user to the
 * verify screen, and cannot make the comparison fail for a genuine peer". Both are false. The
 * identity-change warning tells the user in as many words to go and compare, and safety numbers are
 * computed over the peer-supplied address name, which nothing signs — so a messenger can rewrite it
 * and manufacture a mismatch between two entirely honest peers. An attacker can therefore arrange
 * for a careful user to reject a <em>correct</em> pin, and then supply the replacement.
 *
 * <p>So the rejection is remembered even though the key is not.
 */
public class PostRejectWindowTest {

  private Account victim;
  private Account realPeer;
  private Account attacker;
  private SignalProtocolAddress peerAddress;

  @Before
  public void setUp() throws Exception {
    SignalProtocolMain.testIsRunning = true;
    SignalProtocolMain.initialize(null);
    victim = SignalProtocolMain.getInstance().getAccount();
    SignalProtocolMain.initialize(null);
    realPeer = SignalProtocolMain.getInstance().getAccount();
    SignalProtocolMain.initialize(null);
    attacker = SignalProtocolMain.getInstance().getAccount();

    peerAddress = ProtocolAddresses.of(realPeer.getSignalProtocolAddress().getName(),
        realPeer.getDeviceId());

    activate(realPeer);
    final String genuine = SignalProtocolMain.exportOwnKeyBundle();
    activate(victim);
    assertTrue(SignalProtocolMain.processPreKeyResponseMessage(
        EnvelopeCodec.fromWire(genuine), peerAddress));
  }

  private void activate(final Account a) {
    SignalProtocolMain.getInstance().setAccount(a);
  }

  private Contact storedContact() {
    final Contact c = new Contact("Real", "Peer", peerAddress.getName(),
        peerAddress.getDeviceId(), false);
    final ArrayList<Contact> list = new ArrayList<>();
    list.add(c);
    victim.setContactList(list);
    return c;
  }

  private String attackerBundle() throws Exception {
    activate(attacker);
    final String b = SignalProtocolMain.exportOwnKeyBundle();
    activate(victim);
    return b;
  }

  @Test
  public void aRejectionIsRememberedEvenThoughTheKeyIsNot() throws Exception {
    final Contact contact = storedContact();
    assertFalse(SignalProtocolMain.wasKeyRejected(peerAddress));

    SignalProtocolMain.rejectContactKey(contact);

    assertNull("the key must be gone",
        victim.getSignalProtocolStore().getIdentityKeyStore().getIdentity(peerAddress));
    assertTrue("but the rejection must not be gone with it",
        SignalProtocolMain.wasKeyRejected(peerAddress));
  }

  /**
   * The attack the record exists to make visible: re-deliver the forged bundle straight after the
   * rejection. It still gets pinned — nothing can stop that, the address is genuinely unpinned —
   * but it must no longer be a <em>silent</em> first sighting.
   */
  @Test
  public void reDeliveringTheForgedBundleAfterARejectIsNotSilent() throws Exception {
    final Contact contact = storedContact();
    final String forged = attackerBundle();

    SignalProtocolMain.rejectContactKey(contact);
    assertTrue(SignalProtocolMain.processPreKeyResponseMessage(
        EnvelopeCodec.fromWire(forged), peerAddress));

    assertTrue("a pin at a rejected address must be a warned event, not a first sighting",
        SignalProtocolMain.wasKeyRejected(peerAddress));
    assertFalse("and it must not be trusted",
        SignalProtocolMain.isContactKeyTrustworthy(victim.getContactList().get(0)));
  }

  /** Only a fresh comparison retires the warning. */
  @Test
  public void onlyComparingAgainClearsTheWarning() throws Exception {
    final Contact contact = storedContact();
    SignalProtocolMain.rejectContactKey(contact);

    activate(realPeer);
    final String genuine = SignalProtocolMain.exportOwnKeyBundle();
    activate(victim);
    SignalProtocolMain.processPreKeyResponseMessage(
        EnvelopeCodec.fromWire(genuine), peerAddress);
    assertTrue("still warned until the user actually compares",
        SignalProtocolMain.wasKeyRejected(peerAddress));

    assertTrue(SignalProtocolMain.verifyContact(victim.getContactList().get(0)));

    assertFalse("a fresh comparison retires it", SignalProtocolMain.wasKeyRejected(peerAddress));
  }

  /** Nothing an attacker can do clears it - not another bundle, not a second rejection. */
  @Test
  public void anAttackerCannotClearTheWarning() throws Exception {
    final Contact contact = storedContact();
    SignalProtocolMain.rejectContactKey(contact);

    for (int attempt = 0; attempt < 3; attempt++) {
      SignalProtocolMain.processPreKeyResponseMessage(
          EnvelopeCodec.fromWire(attackerBundle()), peerAddress);
      assertTrue("attempt " + attempt + " cleared the warning",
          SignalProtocolMain.wasKeyRejected(peerAddress));
    }

    SignalProtocolMain.rejectContactKey(victim.getContactList().get(0));
    assertTrue("rejecting again must not clear it either",
        SignalProtocolMain.wasKeyRejected(peerAddress));
  }

  @Test
  public void theWarningIsScopedToOneAddress() throws Exception {
    final SignalProtocolAddress other = ProtocolAddresses.of("other-uuid", 9);
    SignalProtocolMain.rejectContactKey(storedContact());

    assertTrue(SignalProtocolMain.wasKeyRejected(peerAddress));
    assertFalse("a rejection must not tar an unrelated address",
        SignalProtocolMain.wasKeyRejected(other));
    assertFalse(SignalProtocolMain.wasKeyRejected(null));
  }

  /**
   * The record must outlive {@code removeIdentity} itself, not merely outlive
   * {@code rejectContactKey}.
   *
   * <p>Those are different claims, and only the weaker one was tested. {@code rejectContactKey}
   * calls {@code removeIdentity} and then {@code markKeyRejected}, so a {@code removeIdentity} that
   * wiped the rejection would be invisible through that path — the mark is simply re-applied a line
   * later. Any other caller would silently reopen the window. Driving the store directly is what
   * pins the contract the javadoc actually states.
   */
  @Test
  public void removeIdentityMustNotForgetARejection() {
    final var store = victim.getSignalProtocolStore().getIdentityKeyStore();
    store.markKeyRejected(peerAddress);

    store.removeIdentity(peerAddress);

    assertTrue("removeIdentity must forget the key and keep the rejection",
        store.wasKeyRejected(peerAddress));
    assertNull(store.getIdentity(peerAddress));
  }

  /** It has to survive a restart, or the window reopens on the next app launch. */
  @Test
  public void theWarningSurvivesSerialization() throws Exception {
    SignalProtocolMain.rejectContactKey(storedContact());

    final SignalProtocolStoreImpl reloaded = JsonUtil.fromJson(
        JsonUtil.toJson(victim.getSignalProtocolStore()), SignalProtocolStoreImpl.class);

    assertTrue("a rejection lost across a restart reopens the window",
        reloaded.getIdentityKeyStore().wasKeyRejected(peerAddress));
  }

  // ------------------------------------------------------- the address tag (S3)

  /**
   * The tag must not collide for an address the attacker chooses.
   *
   * <p>The first version returned the first six characters of the address name plus the device id.
   * The address name is written by the peer and the messenger knows every contact's, so an impostor
   * only had to pick a name agreeing in its first six characters and reuse the device id — no
   * grinding at all, and both rows rendered identically. A tag an adversary can make collide is
   * worse than no tag, because it looks like a check.
   */
  @Test
  public void theAddressTagDoesNotCollideForAChosenPrefix() {
    final String victimName = "aaaaaa-1111-2222-3333-444444444444";
    final String chosenName = "aaaaaa-9999-8888-7777-666666666666";

    final Contact genuine = new Contact("Alice", "Smith", victimName, 42, false);
    final Contact impostor = new Contact("Alice", "Smith", chosenName, 42, false);

    assertNotEquals("an address chosen to share the first six characters must not share the tag",
        genuine.getAddressTag(), impostor.getAddressTag());
  }

  /** And a one-character change anywhere in the address must change it. */
  @Test
  public void everyPartOfTheAddressFeedsTheTag() {
    final String base = "aaaaaa-1111-2222-3333-444444444444";
    final String tail = "aaaaaa-1111-2222-3333-444444444445";

    assertNotEquals("a change in the last character must show",
        new Contact("A", "B", base, 42, false).getAddressTag(),
        new Contact("A", "B", tail, 42, false).getAddressTag());
    assertNotEquals("a change of device id must show",
        new Contact("A", "B", base, 42, false).getAddressTag(),
        new Contact("A", "B", base, 43, false).getAddressTag());
  }

  // -------------------------------------------------- display-name folding (S5)

  /**
   * Exact string equality let the duplicate check be dodged for free — and the natural way to fill
   * the name field is to copy it out of the invite message, which the attacker wrote.
   */
  @Test
  public void displayNameComparisonFoldsWhitespaceAndCase() {
    final ArrayList<Contact> list = new ArrayList<>();
    list.add(new Contact("Alice", "Smith", peerAddress.getName(), peerAddress.getDeviceId(),
        false));
    victim.setContactList(list);

    final SignalProtocolAddress elsewhere = ProtocolAddresses.of("attacker-uuid", 7);
    for (final String[] dodge : new String[][] {
        {"Alice ", "Smith"}, {"alice", "smith"}, {"  ALICE  ", " Smith "}}) {
      assertTrue("\"" + dodge[0] + "|" + dodge[1] + "\" dodged the duplicate warning",
          SignalProtocolMain.hasContactWithSameDisplayName(dodge[0], dodge[1], elsewhere));
    }
  }

  @Test
  public void genuinelyDifferentNamesStillDoNotMatch() {
    final ArrayList<Contact> list = new ArrayList<>();
    list.add(new Contact("Alice", "Smith", peerAddress.getName(), peerAddress.getDeviceId(),
        false));
    victim.setContactList(list);

    final SignalProtocolAddress elsewhere = ProtocolAddresses.of("attacker-uuid", 7);
    assertFalse(SignalProtocolMain.hasContactWithSameDisplayName("Bob", "Smith", elsewhere));
    assertFalse(SignalProtocolMain.hasContactWithSameDisplayName("Alice", "Jones", elsewhere));
  }

  // -------------------------------------------------- guards with no account

  /**
   * The trust queries must be safe with no account loaded, on both arms of their guards.
   *
   * <p>These are written {@code address == null || account == null}, and every test loads an account
   * and passes a real address — so only the both-present path ran and the {@code ||} could be
   * weakened to {@code &&} unnoticed. With {@code &&}, a query made before the account has loaded
   * dereferences null. That state is ordinary, not exotic: {@code setInputView} can run before
   * {@code reloadAccount} completes, and the E2EE strip queries trust state as soon as it is drawn.
   */
  @Test
  public void trustQueriesAreSafeWithNoAccountLoaded() {
    SignalProtocolMain.getInstance().setAccount(null);

    assertFalse(SignalProtocolMain.hasUnacceptedIdentityChange(peerAddress));
    assertFalse(SignalProtocolMain.hasUnacceptedIdentityChange(null));
    assertFalse(SignalProtocolMain.dismissIdentityChange(peerAddress));
    assertFalse(SignalProtocolMain.dismissIdentityChange(null));
    assertFalse(SignalProtocolMain.wasKeyRejected(peerAddress));
    assertFalse(SignalProtocolMain.rejectContactKey(storedContactDetached()));
    assertFalse(SignalProtocolMain.acceptIdentityChange(peerAddress, null));
    org.junit.Assert.assertNull(SignalProtocolMain.getPendingIdentity(peerAddress));
    assertEquals("a tag needs an account to be keyed with", "",
        SignalProtocolMain.displayTagFor(storedContactDetached()));
    assertEquals(0, SignalProtocolMain.contactCount());
  }

  /** And with an account but no address, which is the other arm. */
  @Test
  public void trustQueriesAreSafeWithNoAddress() {
    assertFalse(SignalProtocolMain.hasUnacceptedIdentityChange(null));
    assertFalse(SignalProtocolMain.dismissIdentityChange(null));
    assertFalse(SignalProtocolMain.wasKeyRejected(null));
    assertFalse(SignalProtocolMain.rejectContactKey(null));
    assertFalse(SignalProtocolMain.acceptIdentityChange(null, null));
    org.junit.Assert.assertNull(SignalProtocolMain.getPendingIdentity(null));
    assertEquals("", SignalProtocolMain.displayTagFor(null));
  }

  private Contact storedContactDetached() {
    return new Contact("Real", "Peer", peerAddress.getName(), peerAddress.getDeviceId(), false);
  }
}
