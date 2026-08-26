package com.amnesica.kryptey.inputmethod.latin.e2ee;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.view.ContextThemeWrapper;
import android.view.View;
import android.widget.TextView;

import com.amnesica.kryptey.inputmethod.R;
import com.amnesica.kryptey.inputmethod.signalprotocol.SignalProtocolMain;
import com.amnesica.kryptey.inputmethod.signalprotocol.chat.Contact;
import com.amnesica.kryptey.inputmethod.signalprotocol.helper.StorageHelper;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

/**
 * Tapping a contact row is not a response to a security warning.
 *
 * <p>{@code selectContact} used to clear whatever warning was on the banner, about whichever
 * contact, and then re-assert exactly two of the app's warning states, and only for the contact
 * that was tapped. Every gap in that sentence is reachable in one tap.
 *
 * <p>The one that matters most is the duplicate-name warning, which this codebase calls the only
 * control covering the case the pin cannot: a messenger posting an invite under a name the user
 * already has. That warning's own text ends "Both now appear in your list, tagged by address" — so
 * the user opens the list to look, and tapping one of the two identical rows is what erased the
 * warning about them being identical. Half the time that row is the impostor, and what replaced the
 * warning reads exactly like a healthy contact.
 */
@RunWith(RobolectricTestRunner.class)
public class SelectingAcontactDoesNotEraseAwarningTest {

  private E2EEStripView strip;

  @Before
  public void setUp() {
    SignalProtocolMain.resetForTest();
    SignalProtocolMain.testIsRunning = true;
    SignalProtocolMain.initialize(null);

    strip = new E2EEStripView(new ContextThemeWrapper(RuntimeEnvironment.getApplication(),
        R.style.KeyboardTheme_LXX_Pure_Day), null);
    strip.setListener(new E2EEStripView.Listener() {
      @Override public void onTextInput(final String rawText) { }
      @Override public void onSensitiveContentVisibilityChanged(final boolean sensitive) { }
    }, strip);
    SignalProtocolMain.setStorageStateForTest(StorageHelper.StorageState.READABLE);
  }

  @After
  public void tearDown() {
    SignalProtocolMain.resetForTest();
    SignalProtocolMain.testIsRunning = false;
  }

  private String banner() {
    final TextView view = strip.findViewById(R.id.e2ee_info_text);
    assertNotNull(view);
    return view.getText().toString();
  }

  private static Contact contact(final String first, final String address) {
    return new Contact(first, "Jones", address, 3, false);
  }

  /** The duplicate-name warning survives the very act its own text invites. */
  @Test
  public void theduplicateNameWarningSurvivesTappingOneOfTheTwoRows() {
    final String warning = "You already have a contact called Bob Jones, and this is a different "
        + "one. Both now appear in your list, tagged by address.";
    strip.setWarningMessageForTest(warning);

    strip.selectContact(contact("Bob", "impostor-address"));

    assertEquals("the warning about two contacts being indistinguishable was erased by tapping one "
        + "of them, which is exactly what its own text tells the user to go and do", warning,
        banner());
  }

  /**
   * The storage warning is not about a contact at all, and it takes the action buttons with it.
   *
   * <p>Losing the text is the smaller half. {@code refreshActionButtons} derives button state from
   * the banner, so replacing {@code INFO_STORAGE_UNREADABLE} with "Chosen contact: Bob" turns
   * Encrypt and Decrypt back on for an install whose account cannot be decrypted — while the
   * sentence telling the user not to re-invite anyone is gone. It does not come back on its own.
   */
  @Test
  public void thestorageWarningAndItsDisabledButtonsSurviveSelection() {
    SignalProtocolMain.setStorageStateForTest(StorageHelper.StorageState.UNREADABLE);
    strip.setWarningMessageForTest(E2EEStripView.INFO_STORAGE_UNREADABLE);
    assertTrue("precondition: this banner must be one that disables the action buttons",
        E2EEStripView.disablesActionButtons(banner()));

    strip.selectContact(contact("Bob", "bob-address"));

    assertEquals("a warning that is not about any contact was erased by choosing one",
        E2EEStripView.INFO_STORAGE_UNREADABLE, banner());

    final View encrypt = strip.findViewById(R.id.e2ee_button_encrypt);
    assertNotNull(encrypt);
    assertFalse("Encrypt came back on for an install whose account cannot be decrypted - the "
        + "button state is derived from the banner, so erasing the warning re-enabled it",
        encrypt.isEnabled());
  }
}
