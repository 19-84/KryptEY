package com.amnesica.kryptey.inputmethod.latin.e2ee;

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
    // Writes have to land here. Pinning a key goes through the account write, so with the default
    // failing store a row is created holding NO key - which is invisible to the tests above, since
    // they only read the wording, and fatal to the ones below, which are about which key a row
    // holds.
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
    final MessageEnvelope relabelled = new MessageEnvelope(original.getPreKeyResponse(),
        address.getName(), address.getDeviceId());
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
   * people. The sentence they select asserts that the two <em>same-named</em> rows hold one key.
   *
   * <p>Here they are about different people, which needs no attacker beyond the one already in the
   * fixture: two genuine "Bob Jones" rows holding DIFFERENT keys, and one unrelated contact who
   * happens to pin the second Bob's key. Both halves are satisfied and neither is about the other.
   *
   * <p>The user must still be told to compare the number against each row, because here that WORKS:
   * the keys differ, so the digits differ, and the peer confirms only their own. Telling them
   * instead that comparing cannot distinguish the rows is false, and it removes the only control
   * this app has for the case the pin cannot cover.
   */
  @Test
  public void anunrelatedContactPinningThisKeyDoesNotSuppressTheImpostorWording() throws Exception {
    final SignalProtocolAddress unrelated = ProtocolAddresses.of("carols-address", 1);
    final SignalProtocolAddress secondBob = ProtocolAddresses.of("second-bobs-address", 1);

    // Carol pins the attacker's key. Nothing about her shares a name with anybody.
    acceptInviteAs("Carol", "Smith", unrelated);
    // A second "Bob Jones" whose key is the attacker's - a different key from the genuine Bob's, so
    // the two rows show different safety numbers and comparing them is what exposes this.
    acceptInviteAs("Bob", "Jones", secondBob);

    assertTrue("with two same-named rows holding DIFFERENT keys, the user must be told to compare "
            + "the number against each - that is what tells an impostor from the real contact here. "
            + "An unrelated third row pinning one of those keys says nothing about the two Bobs, and "
            + "must not replace this with a claim that both show the same number. Banner: "
            + infoText(),
        infoText().contains("the one they confirm is theirs"));
  }

  /**
   * ...and the row that really does share the key still gets the wording written for it.
   *
   * <p>The control for the test above, and the reason the fix is an intersection rather than a
   * removal. Here the second row holds the GENUINE peer's key at an address the relay chose, which
   * is the attack the same-key wording exists for: the digits match, the peer confirms both, and
   * telling the user to pick the one they confirm is advice that cannot be followed.
   */
  @Test
  public void therowThatActuallySharesTheKeyStillGetsTheSameKeyWording() throws Exception {
    final SignalProtocolAddress relayed = ProtocolAddresses.of("an-address-the-relay-picked", 1);

    // Through BundleSigning.asEditedInTransit, which is how the relay's move is modelled
    // everywhere else in the suite: it relabels the envelope's sender while leaving the bundle and
    // its signature intact, which is precisely what makes the re-delivery verify. Rebuilding the
    // envelope with the plain constructor instead drops whatever binds the two, and the bundle is
    // then refused - a row is still created, holding no key, which is a different state entirely.
    final MessageEnvelope original = EnvelopeCodec.fromWire(peerBundleAgain);
    final MessageEnvelope relabelled =
        com.amnesica.kryptey.inputmethod.signalprotocol.BundleSigning.asEditedInTransit(
            original,
            new MessageEnvelope(original.getPreKeyResponse(), relayed.getName(),
                relayed.getDeviceId()));
    ((EditText) strip.findViewById(R.id.e2ee_add_contact_first_name_input_field)).setText("Bob");
    ((EditText) strip.findViewById(R.id.e2ee_add_contact_last_name_input_field)).setText("Jones");
    strip.addContactForTest(relabelled);

    assertNotNull("precondition: the relayed row must actually hold a pinned key, or this measures "
            + "two rows of which only one has one. rows=" + victim.getContactList().size()
            + " addresses=" + java.util.Arrays.toString(victim.getContactList().stream()
                .map(c -> String.valueOf(c.getSignalProtocolAddress())).toArray())
            + " relayed=" + relayed + " banner=" + infoText(),
        victim.getSignalProtocolStore().getIdentityKeyStore().getIdentity(relayed));

    assertTrue("when the two same-named rows really do hold one key, the user must be told that "
            + "comparing numbers cannot tell them apart - both show the same number, because it is "
            + "the same key. Banner: " + infoText(),
        infoText().contains("both show the same number"));
  }
}
