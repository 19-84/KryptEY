package com.amnesica.kryptey.inputmethod.latin.e2ee;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.widget.EditText;

import com.amnesica.kryptey.inputmethod.R;
import com.amnesica.kryptey.inputmethod.signalprotocol.Account;
import com.amnesica.kryptey.inputmethod.signalprotocol.SignalProtocolMain;
import com.amnesica.kryptey.inputmethod.signalprotocol.chat.Contact;
import com.amnesica.kryptey.inputmethod.signalprotocol.encoding.RawEncoder;
import com.amnesica.kryptey.inputmethod.signalprotocol.helper.StorageHelper;
import com.amnesica.kryptey.inputmethod.signalprotocol.util.ProtocolAddresses;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.signal.libsignal.protocol.SignalProtocolAddress;

import java.util.ArrayList;
import java.util.List;

/**
 * What {@code onKeyboardHidden()} still leaves behind.
 *
 * <p>The dismissal path claims to leave "no plaintext, no screen, no recipient". Two of those are
 * enumerations, and both enumerations are short by one. {@code isShowingSensitiveContent()} - the
 * predicate {@code clearDecryptedContent} consults to decide whether to leave the screen - lists
 * four screens and the strip has six; the add-contact screen is one of the two it omits, and
 * {@code ScreenLifetimeTest}'s own class javadoc names it as one of the three that must not
 * survive. And the FLAG_SECURE notification is edge-triggered on view switches alone, so the
 * dismissal path can end with the flag stuck on.
 *
 * <p>Same fixture shape as {@code StripWarningErasureTest}: a victim, a pinned peer ("Bob") and a
 * stranger whose bundle arrives from an address no contact holds, so it opens the add screen the
 * way a real invite does.
 */
@RunWith(RobolectricTestRunner.class)
public class DismissalResidueTest {

  private E2EEStripView strip;
  private Account victim;
  private SignalProtocolAddress peerAddress;
  private String strangerBundle;
  private String secondStrangerBundle;

  /** Every FLAG_SECURE decision the strip has handed the IME, in order. */
  private final List<Boolean> sensitiveNotifications = new ArrayList<>();

  @Before
  public void setUp() throws Exception {
    SignalProtocolMain.resetForTest();
    SignalProtocolMain.testIsRunning = true;

    SignalProtocolMain.initialize(null);
    final Account peer = SignalProtocolMain.getInstance().getAccount();
    peerAddress = ProtocolAddresses.of(peer.getSignalProtocolAddress().getName(),
        peer.getDeviceId());

    SignalProtocolMain.initialize(null);
    strangerBundle = SignalProtocolMain.exportOwnKeyBundle();

    SignalProtocolMain.initialize(null);
    secondStrangerBundle = SignalProtocolMain.exportOwnKeyBundle();

    SignalProtocolMain.initialize(null);
    victim = SignalProtocolMain.getInstance().getAccount();

    final Contact contact = new Contact("Bob", "Jones", peerAddress.getName(),
        peerAddress.getDeviceId(), false);
    final ArrayList<Contact> contacts = new ArrayList<>();
    contacts.add(contact);
    victim.setContactList(contacts);

    strip = new E2EEStripView(new ContextThemeWrapper(RuntimeEnvironment.getApplication(),
        R.style.KeyboardTheme_LXX_Pure_Day), null);

    // The strip must be able to report FLAG_SECURE decisions, exactly as LatinIME wires it. The
    // strip is passed as its own input view: setListener only looks up the main keyboard view in
    // it, which is absent here and is not what this test is about.
    strip.setListener(new E2EEStripView.Listener() {
      @Override
      public void onTextInput(final String rawText) { }

      @Override
      public void onSensitiveContentVisibilityChanged(final boolean sensitive) {
        sensitiveNotifications.add(sensitive);
      }
    }, strip);

    // An unreadable store suppresses banner writes and the clipboard path on its own, independently
    // of the standing flag - which has already made two tests in this suite vacuous. Pin it
    // readable so nothing below can pass for that reason.
    SignalProtocolMain.setStorageStateForTest(StorageHelper.StorageState.READABLE);
  }

  private View wrapper(final int id) {
    final View view = strip.findViewById(id);
    assertNotNull(view);
    return view;
  }

  private EditText field(final int id) {
    return strip.findViewById(id);
  }

  private String firstName() {
    return field(R.id.e2ee_add_contact_first_name_input_field).getText().toString();
  }

  /** Puts a KryptEY-shaped invite on the clipboard, as the messenger's copy button does. */
  private void copyInviteToClipboard(final String wireBundle) {
    final ClipboardManager clipboard = (ClipboardManager) RuntimeEnvironment.getApplication()
        .getSystemService(Context.CLIPBOARD_SERVICE);
    clipboard.setPrimaryClip(ClipData.newPlainText("", RawEncoder.encode(wireBundle)));
  }

  /**
   * The real route to the add-contact screen: an invite from an address no contact holds, pasted
   * and decrypted. Nothing here is a test door - this is the Decrypt button.
   */
  private void openTheAddScreenForAnInvite(final String wireBundle) {
    copyInviteToClipboard(wireBundle);
    strip.findViewById(R.id.e2ee_button_decrypt).performClick();
    assertEquals("precondition: an invite from an unknown address must open the add screen",
        View.VISIBLE, wrapper(R.id.e2ee_add_contact_wrapper).getVisibility());
  }

  private boolean lastSensitiveNotification() {
    assertFalse("precondition: the strip must have told the IME something",
        sensitiveNotifications.isEmpty());
    return sensitiveNotifications.get(sensitiveNotifications.size() - 1);
  }

  // ---------------------------------------------------------------------------------------------
  // 1. The add-contact screen rides through the dismissal.
  // ---------------------------------------------------------------------------------------------

  /**
   * The add-contact screen must not still be up when the keyboard rises in the next app.
   *
   * <p>{@code clearDecryptedContent} leaves the current screen only {@code if
   * (isShowingSensitiveContent())}, and that predicate enumerates the messages list, the verify
   * screen, the contact list and a non-empty main view. The add-contact screen is not in it, so the
   * one screen the user reaches by acting on the messenger's own payload is the one that survives
   * the app switch - open in whatever app the keyboard next serves, with the Add button still bound
   * to the attacker's envelope.
   *
   * <p>Hiding the keyboard is not a user decision the attacker has to wait for: any app may hide it
   * whenever it likes, which is the same primitive {@code forgetChosenRecipient} was written for.
   */
  @Test
  public void theAddContactScreenDoesNotSurviveTheKeyboardBeingHidden() {
    openTheAddScreenForAnInvite(strangerBundle);

    strip.onKeyboardHidden();

    assertEquals("the add-contact screen must not be on screen in the next app",
        View.GONE, wrapper(R.id.e2ee_add_contact_wrapper).getVisibility());
    assertEquals("and the strip must be back on the main view", View.VISIBLE,
        wrapper(R.id.e2ee_main_wrapper).getVisibility());
  }

  /**
   * And the name typed for an abandoned invite must not be waiting on the next invite's screen.
   *
   * <p>This is precisely the defect 6354d93 fixed on the Cancel button - "a user who declines one
   * invite and accepts the next without re-reading the field names a new address after the old
   * contact" - left open on the dismissal path, which unlike Cancel is a path the messenger can
   * trigger itself.
   *
   * <p>Driven end to end: an invite arrives, the user types the name they expect, the keyboard is
   * dismissed, a SECOND invite arrives from a different address, and the screen it opens is still
   * carrying the first name. Pressing Add there creates "Alice Baker" at the second stranger's
   * address.
   */
  @Test
  public void theNameTypedForAnInviteDoesNotSurviveTheKeyboardBeingHidden() {
    openTheAddScreenForAnInvite(strangerBundle);
    field(R.id.e2ee_add_contact_first_name_input_field).setText("Alice");
    field(R.id.e2ee_add_contact_last_name_input_field).setText("Baker");

    strip.onKeyboardHidden();
    openTheAddScreenForAnInvite(secondStrangerBundle);

    assertEquals("the next invite's screen must not open pre-filled with the last one's name",
        "", firstName());
  }

  // ---------------------------------------------------------------------------------------------
  // 2. FLAG_SECURE and the dismissal path.
  // ---------------------------------------------------------------------------------------------

  /**
   * Dismissing the keyboard must not leave FLAG_SECURE applied for the rest of the process.
   *
   * <p>The flag is "applied only while such a screen is up, rather than for the keyboard's whole
   * life, so ordinary typing in other apps still screenshots normally" - a product judgement the
   * code states explicitly. But {@code notifySensitiveVisibility()} is reachable from exactly one
   * place, the {@code finally} of {@code showOnlyUIView}, so the only events that revise the flag
   * are screen switches.
   *
   * <p>{@code clearDecryptedContent} empties the compose field BEFORE consulting {@code
   * isShowingSensitiveContent()}, so on the main view the predicate has already gone false by the
   * time it is asked, no screen switch happens, and no notification is sent. The flag raised while
   * the draft was on screen is never lowered: every app the user types in afterwards is
   * unscreenshotable until the process restarts.
   *
   * <p>Set up through production routes only - type a message, open Help, come back - because
   * coming back from Help is one of the ordinary ways the main view is re-shown with content in it.
   */
  @Test
  public void dismissingTheKeyboardLowersFlagSecureAgain() {
    field(R.id.e2ee_input_field).setText("meet me at the safe house");
    strip.findViewById(R.id.e2ee_button_show_help).performClick();
    strip.findViewById(R.id.e2ee_help_list_return_button).performClick();
    assertTrue("precondition: a draft on the main view must have raised the flag",
        lastSensitiveNotification());

    strip.onKeyboardHidden();

    assertFalse("the keyboard was dismissed and nothing sensitive is left, so the flag must come "
            + "down - otherwise it is on for the keyboard's whole life, which the design "
            + "explicitly rejects", lastSensitiveNotification());
  }

  /**
   * And plaintext arriving in the compose field must raise the flag in the first place.
   *
   * <p>Same root cause from the other side. {@code isShowingSensitiveContent()} counts "a decrypted
   * message in the field" as exactly what must not be captured, and {@code
   * StripGuardsTest#themainViewCountsOnlyWhileItHoldsSomething} asserts that it does - but that
   * test asks the predicate directly. Nothing in the strip ever asks it when the field's content
   * changes, because the only caller is a screen switch and putting text into the field is not one.
   * So the window is capturable for the whole time the message is on screen, which is the one case
   * the FLAG_SECURE work leads with.
   */
  @Test
  public void plaintextArrivingInTheComposeFieldRaisesFlagSecure() {
    strip.showMainViewForTest();
    sensitiveNotifications.clear();

    field(R.id.e2ee_input_field).setText("meet me at the safe house");

    assertTrue("a message rendered in the compose field must be marked not-for-capture; the "
            + "predicate says it is sensitive and nothing ever asks",
        sensitiveNotifications.contains(Boolean.TRUE));
  }
}
