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

  @Before
  public void setUp() throws Exception {
    SignalProtocolMain.resetForTest();
    SignalProtocolMain.testIsRunning = true;

    SignalProtocolMain.initialize(null);
    final Account peer = SignalProtocolMain.getInstance().getAccount();
    peerAddress = ProtocolAddresses.of(peer.getSignalProtocolAddress().getName(),
        peer.getDeviceId());
    final String peerBundle = SignalProtocolMain.exportOwnKeyBundle();

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
    final MessageEnvelope original = EnvelopeCodec.fromWire(attackerBundle);
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
}
