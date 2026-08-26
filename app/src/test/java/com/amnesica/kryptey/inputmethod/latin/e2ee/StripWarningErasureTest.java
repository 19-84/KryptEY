package com.amnesica.kryptey.inputmethod.latin.e2ee;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.view.ContextThemeWrapper;
import android.widget.EditText;
import android.widget.TextView;

import com.amnesica.kryptey.inputmethod.R;
import com.amnesica.kryptey.inputmethod.signalprotocol.Account;
import com.amnesica.kryptey.inputmethod.signalprotocol.SignalProtocolMain;
import com.amnesica.kryptey.inputmethod.signalprotocol.chat.Contact;
import com.amnesica.kryptey.inputmethod.signalprotocol.encoding.EnvelopeCodec;
import com.amnesica.kryptey.inputmethod.signalprotocol.helper.StorageHelper;
import com.amnesica.kryptey.inputmethod.signalprotocol.util.ProtocolAddresses;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.signal.libsignal.protocol.SignalProtocolAddress;

import java.util.ArrayList;

/**
 * Adversarial round: remaining routes that erase or wedge a standing warning.
 *
 * <p>Same fixture as {@code StripGuardsTest}, plus a THIRD innocent identity ("Carol") so an
 * ordinary second invite can be accepted while a warning about somebody else is standing.
 */
@RunWith(RobolectricTestRunner.class)
public class StripWarningErasureTest {

  private E2EEStripView strip;
  private Account victim;
  private SignalProtocolAddress peerAddress;
  private String attackerBundle;
  private String carolBundle;

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
    carolBundle = SignalProtocolMain.exportOwnKeyBundle();

    SignalProtocolMain.initialize(null);
    victim = SignalProtocolMain.getInstance().getAccount();
    assertTrue(SignalProtocolMain.processPreKeyResponseMessage(
        EnvelopeCodec.fromWire(peerBundle), peerAddress));

    final Contact contact = new Contact("Bob", "Jones", peerAddress.getName(),
        peerAddress.getDeviceId(), false);
    final ArrayList<Contact> contacts = new ArrayList<>();
    contacts.add(contact);
    victim.setContactList(contacts);

    strip = new E2EEStripView(new ContextThemeWrapper(RuntimeEnvironment.getApplication(),
        R.style.KeyboardTheme_LXX_Pure_Day), null);
  }

  private TextView infoField() {
    final TextView view = strip.findViewById(R.id.e2ee_info_text);
    assertNotNull(view);
    return view;
  }

  private Contact bob() {
    return victim.getContactList().get(0);
  }

  /** Raises the app's most serious warning through the real production writer. */
  private String standingSubstitutionWarning() {
    SignalProtocolMain.importOutOfBandKeyBundle(attackerBundle, peerAddress);
    assertTrue("precondition: a change must be pending",
        SignalProtocolMain.hasUnacceptedIdentityChange(peerAddress));
    assertTrue("precondition: the warning must have been posted",
        strip.warnIfIdentityChanged(bob()));
    return infoField().getText().toString();
  }

  private void type(final int id, final String text) {
    ((EditText) strip.findViewById(id)).setText(text);
  }

  // ---------------------------------------------------------------------------------------------
  // 1. The password-field banner.
  // ---------------------------------------------------------------------------------------------

  /**
   * Focusing a password field must not erase a standing warning.
   *
   * <p>{@code LatinIME.onStartInputViewInternal} hands the strip the host field's input type on
   * every input session, and the messenger owns the input type of every field it presents. One
   * "your session expired, re-enter your PIN" screen - or any other app with a password box - runs
   * {@code setHostFieldIsPassword(true)}, which writes {@code INFO_PASSWORD_FIELD} straight onto
   * the banner with no standing check at all.
   */
  @Test
  public void apasswordFieldDoesNotEraseAStandingWarning() {
    final String warned = standingSubstitutionWarning();

    strip.setHostFieldIsPassword(true);

    assertEquals("focusing a password field must not erase a security warning",
        warned, infoField().getText().toString());
  }

  /**
   * And the erasure is permanent: the flag is still set, so nothing may write the banner again.
   *
   * <p>After the user goes back to the chat box the strip reads "This is a password field.
   * Encryption and decryption are turned off here" while both actions are in fact back on, and
   * {@code mWarningStanding} is still true, so every passive corrective write refuses. Flag true,
   * text benign and false, buttons live: the wedge the standing flag was supposed to make
   * impossible.
   */
  @Test
  public void apasswordFieldDoesNotWedgeTheStrip() {
    standingSubstitutionWarning();

    strip.setHostFieldIsPassword(true);
    strip.setHostFieldIsPassword(false);   // back to the messenger's compose box

    SignalProtocolMain.setStorageStateForTest(StorageHelper.StorageState.READABLE);
    strip.onClipboardChangedForTest();
    strip.showChosenContactInMainInfoField();

    final String shown = infoField().getText().toString();
    assertFalse("the banner must not be stuck claiming the actions are off while they are on: "
            + shown,
        shown.equals(E2EEStripView.INFO_PASSWORD_FIELD) && strip.actionsAreAvailable());
  }

  // ---------------------------------------------------------------------------------------------
  // 2. The add-contact success banner.
  // ---------------------------------------------------------------------------------------------

  /**
   * Accepting an ordinary invite from somebody ELSE must not erase the warning about Bob.
   *
   * <p>The success line in {@code addContact} - "Contact Carol Smith created. You can send messages
   * now" - is the one banner write on that path with no standing check. The attacker who has just
   * had a substitution refused posts one more perfectly ordinary invite under a fresh name; the
   * user accepts it, because accepting invites is what the app is for, and the warning about the
   * impersonation attempt is gone. The flag stays set, so nothing can put anything there again.
   */
  @Test
  public void anewContactsSuccessBannerDoesNotEraseAStandingWarning() throws Exception {
    final String warned = standingSubstitutionWarning();

    type(R.id.e2ee_add_contact_first_name_input_field, "Carol");
    type(R.id.e2ee_add_contact_last_name_input_field, "Smith");
    strip.addContactForTest(EnvelopeCodec.fromWire(carolBundle));

    final String shown = infoField().getText().toString();
    assertFalse("a success line must not stand in for a security warning: " + shown,
        shown.startsWith("Contact "));
    assertTrue("accepting an unrelated invite must not erase the substitution warning: " + shown,
        shown.contains(warned));
  }

  // ---------------------------------------------------------------------------------------------
  // 3. Backing out of the verify screen.
  // ---------------------------------------------------------------------------------------------

  /**
   * Looking at the safety number and backing out is not comparing it.
   *
   * <p>{@code verifyContact} clears the standing flag on the way in - "going to compare the number
   * is the deliberate response to a warning". But the Return button leaves without comparing
   * anything, and there is no route back to the main view from there that re-asserts: Return goes
   * to the contact list, and the contact list's own Return goes to the main view writing nothing.
   * The pending identity change is still pending, the warning text is still on screen, and the flag
   * is down - so the messenger's next post wipes it, which is exactly what commit 07b21fc fixed for
   * {@code selectContact} and left open one screen away.
   */
  @Test
  public void backingOutOfTheVerifyScreenDoesNotDisarmTheWarning() {
    final String warned = standingSubstitutionWarning();

    strip.showVerifyContactForTest(bob());
    assertTrue(strip.findViewById(R.id.e2ee_verify_contact_return_button).performClick());
    assertTrue(strip.findViewById(R.id.e2ee_contact_list_return_button).performClick());

    assertTrue("precondition: the change is still pending - nothing was compared",
        SignalProtocolMain.hasUnacceptedIdentityChange(peerAddress));

    SignalProtocolMain.setStorageStateForTest(StorageHelper.StorageState.READABLE);
    strip.onClipboardChangedForTest();

    assertTrue("merely opening the verify screen and backing out must not hand the messenger "
        + "the warning's erasure: " + infoField().getText(),
        infoField().getText().toString().contains(warned));
  }

  // ---------------------------------------------------------------------------------------------
  // 4. What clearDecryptedContent leaves on screen.
  // ---------------------------------------------------------------------------------------------

  /**
   * The verify screen must not survive the keyboard being dismissed either.
   *
   * <p>{@code clearDecryptedContent} returns to the main view only when the MESSAGE LIST is up.
   * The verify screen - which the strip's own {@code isShowingSensitiveContent} javadoc calls
   * content the messenger must not have, and which shows the twelve safety-number digits under the
   * contact's name - is left exactly where it was. The IME view is not recreated on an app switch,
   * so it is still there the next time the keyboard rises, in whatever app that is.
   */
  @Test
  public void theverifyScreenDoesNotSurviveTheKeyboardBeingHidden() {
    strip.showVerifyContactForTest(bob());
    assertTrue("precondition: the verify screen must be up",
        strip.isShowingSensitiveContent());

    // Through the dismissal entry point, not one of its three parts. The verify screen sets the
    // chosen contact, and isShowingSensitiveContent is the FLAG_SECURE decision rather than "which
    // screen is up" - with a recipient still chosen the main view's banner names them, which is the
    // same fact this predicate covers the contact list for. forgetChosenRecipient is the part of
    // dismissal that answers it, and onKeyboardHidden is what runs all three.
    strip.onKeyboardHidden();

    assertFalse("nothing sensitive may still be on screen after the keyboard is dismissed",
        strip.isShowingSensitiveContent());
  }

  /** And neither must the contact list, which is who the user talks to. */
  @Test
  public void thecontactListDoesNotSurviveTheKeyboardBeingHidden() {
    strip.showContactListForTest();
    assertTrue("precondition: the contact list must be up", strip.isShowingSensitiveContent());

    strip.clearDecryptedContent();

    assertFalse("the contact list must not still be on screen after the keyboard is dismissed",
        strip.isShowingSensitiveContent());
  }
}
