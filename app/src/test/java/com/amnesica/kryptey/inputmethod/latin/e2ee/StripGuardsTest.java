package com.amnesica.kryptey.inputmethod.latin.e2ee;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.view.ContextThemeWrapper;
import android.widget.TextView;

import com.amnesica.kryptey.inputmethod.R;
import com.amnesica.kryptey.inputmethod.signalprotocol.Account;
import com.amnesica.kryptey.inputmethod.signalprotocol.SignalProtocolMain;
import com.amnesica.kryptey.inputmethod.signalprotocol.chat.Contact;
import com.amnesica.kryptey.inputmethod.signalprotocol.encoding.EnvelopeCodec;
import com.amnesica.kryptey.inputmethod.signalprotocol.util.ProtocolAddresses;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.signal.libsignal.protocol.SignalProtocolAddress;

import java.util.ArrayList;

/**
 * Guards inside {@code E2EEStripView} that no test could reach.
 *
 * <p>A review of this file found that SEVEN mutations survive the whole suite, including deleting
 * the substitution warning outright. The cause was not subtlety - it was that nothing had ever
 * inflated the view, so every guard in the 1300 lines holding the user's decisions was defended by
 * nothing. These drive the real methods.
 */
@RunWith(RobolectricTestRunner.class)
public class StripGuardsTest {

  private E2EEStripView strip;
  private Account victim;
  private SignalProtocolAddress peerAddress;
  private String attackerBundle;

  @Before
  public void setUp() throws Exception {
    SignalProtocolMain.resetForTest();
    SignalProtocolMain.testIsRunning = true;

    // Peer and attacker minted before the victim, so initialize(null) still creates fresh
    // identities rather than reloading.
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

  /**
   * The substitution warning must actually be shown.
   *
   * <p>Making {@code warnIfIdentityChanged} return false unconditionally - deleting the entire
   * warning - survived all 624 tests before this. The trust model spends most of its effort making
   * sure a substituted key is refused AND that the user is told; the second half had no coverage at
   * the point where it reaches a screen.
   */
  @Test
  public void asubstitutionIsWarnedAboutOnScreen() {
    SignalProtocolMain.importOutOfBandKeyBundle(attackerBundle, peerAddress);
    assertTrue("precondition: a change must be pending",
        SignalProtocolMain.hasUnacceptedIdentityChange(peerAddress));

    assertTrue("warnIfIdentityChanged must report that it warned", strip.warnIfIdentityChanged(bob()));

    final String shown = infoField().getText().toString();
    assertTrue("the banner must name the contact and say the key was refused: " + shown,
        shown.contains("Bob") && shown.toLowerCase().contains("refused"));
  }

  /** And it must NOT warn when nothing is pending - a warning that always fires teaches dismissal. */
  @Test
  public void nowarningWhenNothingIsPending() {
    assertFalse("nothing is pending, so nothing may be claimed",
        strip.warnIfIdentityChanged(bob()));
    assertEquals("and the banner must be untouched",
        E2EEStripView.INFO_NO_CONTACT_CHOSEN_TEXT, infoField().getText().toString());
  }

  /**
   * A name imitating an address tag must be refused by the real validation path.
   *
   * <p>{@code displayNameImitatesATag} was tested directly; the call to it from
   * {@code providedContactInformationIsValid} was not, so deleting the call survived. A predicate
   * nothing invokes is not a control - the tag exists precisely so a name cannot impersonate one.
   */
  @Test
  public void anameImitatingAtagIsRefused() {
    assertFalse("a first name shaped like an address tag must be refused",
        strip.providedContactInformationIsValid("#ab12-cd34", "Jones"));
    assertFalse("and a last name",
        strip.providedContactInformationIsValid("Bob", "#ab12-cd34"));
    assertTrue("while an ordinary name is accepted",
        strip.providedContactInformationIsValid("Bob", "Jones"));
  }

  private android.view.View verifyButton() {
    return strip.findViewById(R.id.e2ee_verify_contact_verify_button);
  }

  /**
   * The verify screen must not show one contact's digits under another contact's name.
   *
   * <p>Deleting {@code clearFingerprintViews()} from the no-fingerprint branch reverted a
   * documented fix and survived the whole suite: the previous contact's safety number stayed on
   * screen while the header named a contact it does not belong to. A user comparing those digits by
   * voice would be confirming the wrong key - and confirming is exactly what that screen is for.
   */
  @Test
  public void theverifyScreenBlanksWhenThereIsNoFingerprint() {
    // A pinned contact: real digits appear.
    strip.verifyContact(bob());
    final String pinnedDigits = firstCodeSegment();
    assertFalse("precondition: a pinned contact must show digits", pinnedDigits.isEmpty());

    // A contact with nothing pinned: getFingerprint returns null.
    final Contact stranger = new Contact("Never", "Seen", "never-pinned-uuid", 11, false);
    final ArrayList<Contact> contacts = new ArrayList<>(victim.getContactList());
    contacts.add(stranger);
    victim.setContactList(contacts);

    strip.verifyContact(stranger);

    assertEquals("the previous contact's digits must not remain under this contact's name",
        "", firstCodeSegment());
  }

  /**
   * And confirmation must be impossible while there are no digits to have compared.
   *
   * <p>Inverting {@code clearFingerprintViews} to ENABLE the buttons also survived. That is the one
   * runtime call standing between a blank verify screen and a user pressing verify over nothing -
   * the layout declares no {@code android:enabled}, so the default is enabled.
   */
  @Test
  public void confirmationIsDisabledWhenThereAreNoDigits() {
    final Contact stranger = new Contact("Never", "Seen", "never-pinned-uuid", 11, false);
    final ArrayList<Contact> contacts = new ArrayList<>(victim.getContactList());
    contacts.add(stranger);
    victim.setContactList(contacts);

    strip.verifyContact(stranger);

    assertNotNull("the verify button must exist", verifyButton());
    assertFalse("verify must be disabled with no safety number on screen",
        verifyButton().isEnabled());
  }

  /** And enabled again for a contact that does have one, or the screen is simply broken. */
  @Test
  public void confirmationIsEnabledWhenDigitsAreShown() {
    strip.verifyContact(bob());

    assertTrue("verify must be available once there is a number to compare",
        verifyButton().isEnabled());
  }

  private String firstCodeSegment() {
    final TextView code = strip.findViewById(R.id.code_first);
    assertNotNull("the verify screen must have digit views", code);
    return code.getText().toString();
  }

  /**
   * The contact banner must never be written over a standing warning.
   *
   * <p>Making the caller's {@code if (!mIdentityWarningStanding)} unconditional survived the whole
   * suite, and that guard is a documented fix: without it the identity-change warning was
   * overwritten one frame after being set, on the clipboard path - the only path an attacker's
   * envelope takes - leaving a three-and-a-half-second toast as the entire signal over a screen
   * that otherwise looked like an ordinary success.
   *
   * <p>The caller's flag is cleared immediately after that check, so it protects exactly one frame.
   * This asserts the property at the place that has to hold for every frame after it.
   */
  @Test
  public void thecontactBannerNeverOverwritesAstandingWarning() {
    strip.setWarningMessageForTest("Someone offered a different key for Bob.");

    strip.showChosenContactInMainInfoField();

    assertEquals("a warning must outlive the routine banner that follows it",
        "Someone offered a different key for Bob.", infoField().getText().toString());
  }

  /** And once the user has acted, the routine banner appears normally. */
  @Test
  public void thecontactBannerAppearsOnceTheWarningIsCleared() {
    strip.setWarningMessageForTest("Someone offered a different key for Bob.");
    strip.selectContact(bob());

    strip.showChosenContactInMainInfoField();

    assertTrue("after a deliberate action the ordinary banner must return: "
            + infoField().getText(),
        infoField().getText().toString().contains("Bob"));
  }
}
