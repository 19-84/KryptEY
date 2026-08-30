package com.amnesica.kryptey.inputmethod.latin.e2ee;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.view.ContextThemeWrapper;
import android.widget.EditText;
import android.widget.TextView;

import com.amnesica.kryptey.inputmethod.R;
import com.amnesica.kryptey.inputmethod.signalprotocol.Account;
import com.amnesica.kryptey.inputmethod.signalprotocol.MessageEnvelope;
import com.amnesica.kryptey.inputmethod.signalprotocol.SignalProtocolMain;
import com.amnesica.kryptey.inputmethod.signalprotocol.chat.Contact;
import com.amnesica.kryptey.inputmethod.signalprotocol.encoding.EnvelopeCodec;
import com.amnesica.kryptey.inputmethod.signalprotocol.helper.StorageHelper;
import com.amnesica.kryptey.inputmethod.signalprotocol.util.ProtocolAddresses;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.shadows.ShadowToast;
import org.signal.libsignal.protocol.SignalProtocolAddress;

import java.util.ArrayList;

/**
 * Which duplicate-name wording the user gets when both facts are true at once.
 *
 * <p>Two records can match a name: a live contact holding it at another address, and a retirement
 * left by a deleted one. They say different things, and the app has a separate sentence for each -
 * deliberately, because the retired one exists precisely so a warning is not "provably wrong where
 * it fires". The live sentence ends "Both now appear in your list, tagged by address", which is the
 * only part telling the user there are now two rows and which thing distinguishes them.
 *
 * <p>{@code duplicateNameMessage} computed {@code live} as "a name is known AND it is not retired",
 * so a retirement suppressed the live wording. When both hold - the attacker has already been
 * deleted from this list once under this name and is trying again from a fresh address - the user
 * was told about the deletion and never told that two live rows now share the name. That is the
 * half of the warning the address tag exists to be read with, dropped in the case where the attack
 * is furthest along.
 */
@RunWith(RobolectricTestRunner.class)
public class DuplicateNameWordingTest {

  private E2EEStripView strip;
  private Account victim;
  private SignalProtocolAddress peerAddress;
  private String attackerBundle;
  private String attackerBundleAgain;
  /**
   * A SECOND invite from the same peer: same identity key, a fresh one-time pre-key.
   *
   * <p>Needed because the first is consumed by the session this fixture builds in setUp, and a
   * bundle whose one-time pre-key is already spent pins nothing - so a row built from it has no key,
   * and a test about two rows sharing a key would be measuring two rows where only one has one.
   */
  private String peerBundleAgain;

  @Before
  public void setUp() throws Exception {
    SignalProtocolMain.resetForTest();
    SignalProtocolMain.testIsRunning = true;

    SignalProtocolMain.initialize(null);
    final Account peer = SignalProtocolMain.getInstance().getAccount();
    peerAddress = ProtocolAddresses.of(peer.getSignalProtocolAddress().getName(),
        peer.getDeviceId());
    final String peerBundle = SignalProtocolMain.exportOwnKeyBundle();
    peerBundleAgain = SignalProtocolMain.exportOwnKeyBundle();

    SignalProtocolMain.initialize(null);
    attackerBundle = SignalProtocolMain.exportOwnKeyBundle();
    attackerBundleAgain = SignalProtocolMain.exportOwnKeyBundle();

    SignalProtocolMain.initialize(null);
    victim = SignalProtocolMain.getInstance().getAccount();
    assertTrue(SignalProtocolMain.processPreKeyResponseMessage(
        EnvelopeCodec.fromWire(peerBundle), peerAddress));

    final Contact genuine = new Contact("Bob", "Jones", peerAddress.getName(),
        peerAddress.getDeviceId(), false);
    final ArrayList<Contact> contacts = new ArrayList<>();
    contacts.add(genuine);
    victim.setContactList(contacts);

    strip = new E2EEStripView(new ContextThemeWrapper(RuntimeEnvironment.getApplication(),
        R.style.KeyboardTheme_LXX_Pure_Day), null);
    strip.setListener(new E2EEStripView.Listener() {
      @Override public void onTextInput(final String rawText) { }
      @Override public void onSensitiveContentVisibilityChanged(final boolean sensitive) { }
    }, strip);
    SignalProtocolMain.setStorageStateForTest(StorageHelper.StorageState.READABLE);
    // Writes land here so the banner carries no appended storage caution. It is NOT what makes the
    // pins work: buildSession writes trustedKeys in memory and a failed account write rolls nothing
    // back, so a failing store still pins. What stops a pin in this fixture is an unsigned
    // relabelling - see acceptInviteFrom.
    com.amnesica.kryptey.inputmethod.signalprotocol.storage.TestStores.writesLand();
    ShadowToast.reset();
  }

  @After
  public void tearDown() {
    SignalProtocolMain.resetForTest();
    SignalProtocolMain.testIsRunning = false;
  }

  private String infoText() {
    final TextView view = strip.findViewById(R.id.e2ee_info_text);
    assertNotNull(view);
    return view.getText().toString();
  }

  /** Accepts an invite from {@code address} under the given name, through the real Add button. */
  private void acceptInviteAs(final String first, final String last,
                              final SignalProtocolAddress address) throws Exception {
    acceptInviteFrom(attackerBundle, first, last, address);
  }

  /** The same, from a chosen bundle, so a row can be given the PEER's key rather than a new one. */
  private void acceptInviteFrom(final String bundle, final String first, final String last,
                                final SignalProtocolAddress address) throws Exception {
    final MessageEnvelope original = EnvelopeCodec.fromWire(bundle);
    // Through asEditedInTransit, which relabels the sender while leaving the bundle and its issuing
    // signature intact. Rebuilding the envelope with the plain constructor drops the signature, so
    // requireTheBundleWasIssuedAsOneUnit refuses it and NOTHING IS PINNED - the row is still created,
    // holding no key, which looks identical on the banner and makes every test about which key a row
    // holds vacuous. Two of the tests below were written that way and passed against the defect they
    // were meant to catch.
    final MessageEnvelope relabelled =
        com.amnesica.kryptey.inputmethod.signalprotocol.BundleSigning.asEditedInTransit(
            original, new MessageEnvelope(original.getPreKeyResponse(),
                address.getName(), address.getDeviceId()));
    ((EditText) strip.findViewById(R.id.e2ee_add_contact_first_name_input_field)).setText(first);
    ((EditText) strip.findViewById(R.id.e2ee_add_contact_last_name_input_field)).setText(last);
    strip.addContactForTest(relabelled);
  }

  /**
   * A live duplicate must be described as one, even when the name was also once deleted.
   */
  @Test
  public void aliveDuplicateIsNamedEvenWhenTheNameWasAlsoRetired() throws Exception {
    // The attacker tried this name once before from another address and was deleted.
    victim.retireDisplayName("Bob", "Jones", "an-earlier-attacker-address.1");
    assertTrue("precondition: the retirement matches",
        SignalProtocolMain.hasRetiredDisplayName("Bob", "Jones"));
    assertTrue("precondition: and a LIVE contact of that name is in the list",
        SignalProtocolMain.hasContactWithSameDisplayName("Bob", "Jones",
            ProtocolAddresses.of("attacker-two", 3)));

    acceptInviteAs("Bob", "Jones", ProtocolAddresses.of("attacker-two", 3));

    final String shown = infoText();
    assertTrue("with two live rows now sharing this name, the user must be told that - it is the "
            + "half of the warning the address tag exists to be read with. Shown instead: " + shown,
        shown.contains("Both now appear in your list"));
  }

  /**
   * Control, in the other direction: with no live contact of that name, the deleted-contact
   * wording is still the one used - it must not become "you already have a contact called X" when
   * the user can see that there is no such row.
   */
  @Test
  public void aretiredNameAloneStillGetsTheDeletedContactWording() throws Exception {
    victim.setContactList(new ArrayList<>());
    victim.retireDisplayName("Carol", "Smith", "a-deleted-address.1");

    acceptInviteAs("Carol", "Smith", ProtocolAddresses.of("attacker-two", 3));

    final String shown = infoText();
    assertTrue("nothing of that name is in the list, so the live wording would be provably wrong "
        + "where it fires: " + shown, shown.contains("You deleted a contact called"));
  }

  /** Control: a plain live duplicate, with no retirement anywhere, is unchanged. */
  @Test
  public void aplainLiveDuplicateIsUnchanged() throws Exception {
    acceptInviteAs("Bob", "Jones", ProtocolAddresses.of("attacker-two", 3));

    final String shown = infoText();
    assertTrue("a live duplicate must still get the live wording: " + shown,
        shown.contains("Both now appear in your list"));
  }

  /**
   * A third party pinning this key must not turn off the warning that exposes an impostor.
   *
   * <p>The same-key wording was selected by two questions asked separately - "does some live row
   * share this name" and "does SOME address pin this key" - and nothing tied the answers to the same
   * people, while the sentence they select asserts something about the two <em>same-named</em> rows.
   *
   * <p>Here they are about different people: two "Bob Jones" rows holding DIFFERENT keys, and one
   * unrelated contact who happens to pin the second Bob's key. Both halves of the old predicate are
   * satisfied and neither is about the other.
   *
   * <p>The user must still be told to compare the number against each row, because here that WORKS -
   * the keys differ, so the digits differ, and the peer confirms only their own. Telling them
   * instead that comparing cannot distinguish the rows is false, and it removes the only control
   * this app has for the case the pin cannot cover.
   *
   * <p>Asserted after a SELECTION, not after the add. {@code duplicateNameMessage} runs on the add
   * path before {@code createSessionWithContact} has pinned anything, so at that moment the
   * intersection is false for a fresh address whatever it is asked - and the banner is then
   * overwritten by the arrival wording, which shares a phrase with the sentence under test. The
   * re-derivation on selection is the only place the intersection decides anything.
   */
  @Test
  public void anunrelatedContactPinningThisKeyDoesNotSuppressTheImpostorWording() throws Exception {
    final SignalProtocolAddress unrelated = ProtocolAddresses.of("carols-address", 1);
    final SignalProtocolAddress secondBob = ProtocolAddresses.of("second-bobs-address", 1);

    acceptInviteFrom(attackerBundle, "Bob", "Jones", secondBob);
    acceptInviteFrom(attackerBundleAgain, "Carol", "Smith", unrelated);

    assertNotNull("precondition: the second Bob must hold a pinned key",
        victim.getSignalProtocolStore().getIdentityKeyStore().getIdentity(secondBob));
    assertNotNull("precondition: and the unrelated row must hold one too, or nothing pins this key "
            + "elsewhere and the old predicate would have been false anyway",
        victim.getSignalProtocolStore().getIdentityKeyStore().getIdentity(unrelated));
    assertEquals("precondition: the two rows must hold the SAME key, or this is not the state that "
            + "fooled the old predicate",
        victim.getSignalProtocolStore().getIdentityKeyStore().getIdentity(secondBob),
        victim.getSignalProtocolStore().getIdentityKeyStore().getIdentity(unrelated));

    strip.selectContact(rowAt(secondBob));

    assertTrue("with two same-named rows holding DIFFERENT keys, the user must be told to compare "
            + "the number against each - that is what tells an impostor from the real contact here. "
            + "An unrelated third row pinning one of those keys says nothing about the two Bobs. "
            + "Banner: " + infoText(),
        infoText().contains("the one they confirm is theirs"));
  }

  /**
   * ...and the row that really does share the key still gets the wording written for it.
   *
   * <p>The control, and the reason the fix is an intersection rather than a removal. The second row
   * holds the GENUINE peer's key at an address the relay chose: the digits match, the peer confirms
   * both, and telling the user to pick the one they confirm is advice that cannot be followed.
   *
   * <p>Asserts a phrase unique to the shared-name wording rather than one it shares with the arrival
   * wording. "both show the same number" appears in both sentences, so reading it proves only that
   * one of them is on the banner - and the arrival one is written last on the add path, so the
   * assertion passed without the selection wording ever being chosen.
   */
  @Test
  public void therowThatActuallySharesTheKeyStillGetsTheSameKeyWording() throws Exception {
    final SignalProtocolAddress relayed = ProtocolAddresses.of("an-address-the-relay-picked", 1);

    acceptInviteFrom(peerBundleAgain, "Bob", "Jones", relayed);

    assertNotNull("precondition: the relayed row must actually hold a pinned key",
        victim.getSignalProtocolStore().getIdentityKeyStore().getIdentity(relayed));

    strip.selectContact(rowAt(relayed));

    assertTrue("when the two same-named rows really do hold one key, the selection must re-derive "
            + "the wording that says comparing numbers cannot tell them apart. Banner: " + infoText(),
        infoText().contains("holds the SAME key"));
  }

  /** The live contact row at an address, as the list holds it. */
  private Contact rowAt(final SignalProtocolAddress address) {
    for (final Contact candidate : victim.getContactList()) {
      if (String.valueOf(candidate.getSignalProtocolAddress()).equals(String.valueOf(address))) {
        return candidate;
      }
    }
    throw new IllegalStateException("no contact row at " + address);
  }

  /**
   * A deleted name whose key turns up again must not be sent to a comparison that is already
   * decided.
   *
   * <p>{@code INFO_RETIRED_CONTACT_NAME} offers the safety number as the test of the reinstall
   * story. Here the key has already settled it: {@code initializeProtocol} mints the UUID, the
   * device id and the identity key together, so a reinstall arrives with a NEW key at a NEW address,
   * and the same key at a different address cannot be one. It is the user's own old invite delivered
   * a second time.
   *
   * <p>The comparison would nevertheless pass — the digits are a function of the two identity keys,
   * so they match by construction and the peer confirms their own key — and the user would read that
   * match as confirming the story the key refutes.
   */
  @Test
  public void adeletedNameWhoseKeyComesBackIsNotSentToTheNumber() throws Exception {
    final SignalProtocolAddress secondAddress = ProtocolAddresses.of("a-second-address", 1);

    // The row is gone; the pin deliberately is not.
    victim.setContactList(new ArrayList<>());
    victim.retireDisplayName("Bob", "Jones", String.valueOf(peerAddress));

    acceptInviteFrom(peerBundleAgain, "Bob", "Jones", secondAddress);

    assertNotNull("precondition: the new row must hold a pinned key",
        victim.getSignalProtocolStore().getIdentityKeyStore().getIdentity(secondAddress));
    assertNotNull("precondition: and the deleted contact's pin must have survived the deletion, or "
            + "there is no second address holding this key and nothing to detect",
        victim.getSignalProtocolStore().getIdentityKeyStore().getIdentity(peerAddress));

    strip.selectContact(rowAt(secondAddress));

    assertTrue("the wording must say the key is the one the deleted contact had, not ask the user "
            + "to check the number - that check passes here whatever the truth is. Banner: "
            + infoText(),
        infoText().contains("holds the SAME key that one had"));
  }

  /**
   * ...and an ordinary deleted-name case still gets the ordinary deleted-name wording.
   *
   * <p>The control. A different key at the new address is exactly the state the retired sentence was
   * written for: the reinstall story is possible, the numbers differ, and comparing by voice is the
   * test that settles it. If this went red the fix would be a suppression rather than a distinction.
   */
  @Test
  public void adeletedNameWithAdifferentKeyStillGetsTheOrdinaryWording() throws Exception {
    final SignalProtocolAddress secondAddress = ProtocolAddresses.of("a-second-address", 1);

    victim.setContactList(new ArrayList<>());
    victim.retireDisplayName("Bob", "Jones", String.valueOf(peerAddress));

    acceptInviteFrom(attackerBundle, "Bob", "Jones", secondAddress);

    strip.selectContact(rowAt(secondAddress));

    assertTrue("a different key leaves the reinstall story open, and the number is the test that "
            + "settles it. Banner: " + infoText(),
        infoText().contains("check the security number with them by voice"));
  }
}
