package com.amnesica.kryptey.inputmethod.latin.e2ee;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.widget.TextView;

import com.amnesica.kryptey.inputmethod.R;
import com.amnesica.kryptey.inputmethod.latin.e2ee.adapter.ListAdapterContacts;
import com.amnesica.kryptey.inputmethod.signalprotocol.Account;
import com.amnesica.kryptey.inputmethod.signalprotocol.MessageEnvelope;
import com.amnesica.kryptey.inputmethod.signalprotocol.SignalProtocolMain;
import com.amnesica.kryptey.inputmethod.signalprotocol.chat.Contact;
import com.amnesica.kryptey.inputmethod.signalprotocol.encoding.EnvelopeCodec;
import com.amnesica.kryptey.inputmethod.signalprotocol.encoding.RawEncoder;
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
 * The post-rejection warning must survive the gesture its own text asks the user to make.
 *
 * <p>{@code PostRejectWarningReachTest} closed the previous half of this: a bundle re-delivered to
 * a rejected address is now warned about on the arm the attacker actually takes. The warning it
 * posts ends "Compare the number by voice before sending anything", and the way a user compares is
 * to open the contact list and tap the contact. That tap is {@code selectContact}, which begins by
 * clearing the standing flag.
 *
 * <p>{@code selectContact} already knows that some warnings must not be dismissed by being chosen:
 * it re-asserts {@code hasUnacceptedIdentityChange}, on the reasoning - written in the method - that
 * a pending change "is a state, not a notice that has been read", and
 * {@code StripGuardsTest#choosingTheContactDoesNotDismissAPendingIdentityChange} pins it. A standing
 * rejection is the same kind of thing by the same argument, and is stated to be so everywhere else
 * in the codebase: {@code rejectedAddresses} is "cleared only by a fresh comparison ... never by
 * anything an attacker can trigger", and {@code isContactKeyTrustworthy} makes it outrank a verified
 * badge. Only this one route treats it as a notice.
 *
 * <p>Measured before this file existed: after the warned re-pin, one tap on the contact row left the
 * banner reading "Chosen contact: Bob Jones #6646-2750b6" - byte-identical to a healthy contact -
 * while {@code wasKeyRejected} was still true and the pinned key was still the attacker's.
 */
@RunWith(RobolectricTestRunner.class)
public class PostRejectWarningSurvivesSelectionTest {

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
    assertTrue("precondition: a key must be pinned for this address first",
        SignalProtocolMain.processPreKeyResponseMessage(
            EnvelopeCodec.fromWire(peerBundle), peerAddress));

    final Contact contact = new Contact("Bob", "Jones", peerAddress.getName(),
        peerAddress.getDeviceId(), false);
    final ArrayList<Contact> contacts = new ArrayList<>();
    contacts.add(contact);
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

  private Contact bob() {
    return victim.getContactList().get(0);
  }

  private String infoText() {
    final TextView view = strip.findViewById(R.id.e2ee_info_text);
    assertNotNull(view);
    return view.getText().toString();
  }

  /** Relabels the envelope with the peer's address, as the messenger presents it. */
  private void pasteAsIfFromThePeer(final String wire) throws Exception {
    final MessageEnvelope original = EnvelopeCodec.fromWire(wire);
    // Only the ADDRESS is relabelled, which the issuing signature deliberately does not cover:
    // the address is an unsigned header and this file's whole subject is what arrives at one.
    final MessageEnvelope relabelled =
        com.amnesica.kryptey.inputmethod.signalprotocol.BundleSigning.asEditedInTransit(original,
            new MessageEnvelope(original.getPreKeyResponse(),
                peerAddress.getName(), peerAddress.getDeviceId()));
    final ClipboardManager clipboard = (ClipboardManager) RuntimeEnvironment.getApplication()
        .getSystemService(Context.CLIPBOARD_SERVICE);
    clipboard.setPrimaryClip(ClipData.newPlainText("",
        RawEncoder.encode(EnvelopeCodec.toWire(relabelled))));
  }

  /** One contact row through the real adapter, wired to the strip exactly as the real list is. */
  private View rowFor(final Contact contact) {
    final ArrayList<Object> items = new ArrayList<>();
    items.add(contact);
    final ListAdapterContacts adapter = new ListAdapterContacts(
        new ContextThemeWrapper(RuntimeEnvironment.getApplication(),
            R.style.KeyboardTheme_LXX_Pure_Day),
        R.layout.e2ee_contact_list_element_view, items);
    adapter.setListener(strip);
    final View row = adapter.getView(0, null, null);
    assertNotNull("the adapter must render a row", row);
    return row;
  }

  /** Drives the whole attack up to the moment the warning is standing. */
  private void rejectThenLetTheAttackerRedeliver() throws Exception {
    assertTrue("precondition: a pinned key must have been forgotten",
        SignalProtocolMain.rejectContactKey(bob()));
    pasteAsIfFromThePeer(attackerBundle);
    strip.findViewById(R.id.e2ee_button_decrypt).performClick();
    assertTrue("precondition: the post-rejection warning must be on screen to begin with: "
        + infoText(), infoText().contains("not to trust keys arriving"));
    assertTrue("precondition: the rejection must still be on record",
        SignalProtocolMain.wasKeyRejected(peerAddress));
  }

  /**
   * Tapping the contact is how the user does what the warning told them to do.
   *
   * <p>The row's name, surname and address tag all carry {@code selectContact}; this drives the
   * name, through the real adapter, with the strip as the real listener.
   */
  @Test
  public void tappingThecontactDoesNotDismissAstandingRejection() throws Exception {
    rejectThenLetTheAttackerRedeliver();

    rowFor(bob()).findViewById(R.id.e2ee_contact_first_name_element).performClick();

    final String shown = infoText();
    assertTrue("the rejection is still on record after the tap - only a fresh comparison retires "
        + "it", SignalProtocolMain.wasKeyRejected(peerAddress));
    assertFalse("choosing the contact must not be what makes the warning go away: the banner now "
            + "reads \"" + shown + "\", which is byte-identical to a healthy contact, over a key "
            + "pinned at an address the user told this app was wrong",
        shown.startsWith("Chosen contact"));
    assertTrue("the post-rejection warning must be re-asserted, as a pending identity change "
            + "already is on this same route: " + shown,
        shown.contains("not to trust keys arriving"));
  }

  /** Re-asserted once and then wiped by the messenger's next post is no better. */
  @Test
  public void andItMustStillBeStandingAfterwards() throws Exception {
    rejectThenLetTheAttackerRedeliver();

    rowFor(bob()).findViewById(R.id.e2ee_contact_first_name_element).performClick();
    final String shown = infoText();

    strip.onClipboardChangedForTest();
    assertTrue("the flag must be up too, or one ordinary copy erases it: " + infoText(),
        shown.equals(infoText()));
  }

  /**
   * The control: a contact with no standing rejection still gets the ordinary banner.
   *
   * <p>Without this, re-asserting unconditionally - or never writing the chosen-contact line at
   * all - would pass the two tests above while making the banner useless.
   */
  @Test
  public void aContactWithNothingAgainstItStillGetsTheOrdinaryBanner() {
    assertFalse("precondition: nothing rejected here",
        SignalProtocolMain.wasKeyRejected(peerAddress));

    rowFor(bob()).findViewById(R.id.e2ee_contact_first_name_element).performClick();

    assertTrue("choosing an ordinary contact must still say who it is: " + infoText(),
        infoText().startsWith("Chosen contact"));
  }
}
