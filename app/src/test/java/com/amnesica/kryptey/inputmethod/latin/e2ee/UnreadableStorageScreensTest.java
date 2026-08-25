package com.amnesica.kryptey.inputmethod.latin.e2ee;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.view.ContextThemeWrapper;
import android.view.View;
import android.widget.TextView;

import com.amnesica.kryptey.inputmethod.R;
import com.amnesica.kryptey.inputmethod.signalprotocol.SignalProtocolMain;
import com.amnesica.kryptey.inputmethod.signalprotocol.helper.StorageHelper;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.shadows.ShadowToast;

/**
 * What the rest of the strip shows while the account cannot be decrypted.
 *
 * <p>{@code UNREADABLE} is a record the app keeps: the identity, every session and every verified
 * contact are still on disk under a Keystore key that no longer exists. {@code StorageHelper}'s own
 * javadoc says why the distinction is not cosmetic - "Presenting that as a fresh install invites the
 * user to re-invite all their contacts, which silently discards every pin they had already verified
 * - the trust-on-first-use window reopens for every one of them, at a moment when an attacker who
 * caused the key loss knows exactly when to strike." {@code INFO_STORAGE_UNREADABLE} is the sentence
 * written for that, and it says "Do NOT re-invite anyone."
 *
 * <p>It reaches exactly one surface: the main info banner, plus the button state derived from that
 * banner's text. The contact list is one tap away, is not disabled, and was measured showing the
 * ordinary line - "Choose your chat partner ... If you want to chat with someone new, invite them
 * via the add button" - over an empty list. That is the fresh-install reading the banner exists to
 * prevent, rendered on the screen the banner is not on.
 *
 * <p>And the button that line points at does not fail politely. {@code getPreKeyBundle}
 * dereferences the account unguarded, and in this state there is no account, so pressing Invite
 * throws {@code NullPointerException} out of a click listener.
 * {@code sendPreKeyResponseMessageToApplication} catches {@code TooManyCharsException} and
 * {@code IOException} only. Out of a click listener there is nothing left to catch it and the
 * input-method process dies, in whatever app the user is in - the same failure the clipboard
 * listener and {@code E2EEStrip.decryptMessage} each carry an explicit unchecked-catch for.
 */
@RunWith(RobolectricTestRunner.class)
public class UnreadableStorageScreensTest {

  private E2EEStripView strip;

  @Before
  public void setUp() {
    // The real shape of this state: initialize() refuses to generate over existing data and the
    // reload yields nothing, so no account is loaded at all.
    SignalProtocolMain.resetForTest();
    SignalProtocolMain.testIsRunning = true;
    SignalProtocolMain.setStorageStateForTest(StorageHelper.StorageState.UNREADABLE);

    strip = new E2EEStripView(new ContextThemeWrapper(RuntimeEnvironment.getApplication(),
        R.style.KeyboardTheme_LXX_Pure_Day), null);
    strip.setListener(new E2EEStripView.Listener() {
      @Override public void onTextInput(final String rawText) { }
      @Override public void onSensitiveContentVisibilityChanged(final boolean sensitive) { }
    }, strip);
    strip.refreshOpeningMessage();
    ShadowToast.reset();
  }

  @After
  public void tearDown() {
    SignalProtocolMain.resetForTest();
    SignalProtocolMain.testIsRunning = false;
  }

  private String mainBanner() {
    final TextView view = strip.findViewById(R.id.e2ee_info_text);
    assertNotNull(view);
    return view.getText().toString();
  }

  private String contactListBanner() {
    final TextView view = strip.findViewById(R.id.e2ee_contact_list_info_text);
    assertNotNull(view);
    return view.getText().toString();
  }

  private View recipientButton() {
    return strip.findViewById(R.id.e2ee_button_select_recipient);
  }

  private View inviteButton() {
    return strip.findViewById(R.id.e2ee_contact_list_invite_new_contact_button);
  }

  /** The premise: the main banner does carry the warning, and the contact list is one tap away. */
  @Test
  public void thepremise() {
    assertEquals("precondition: the main banner must carry the storage warning",
        E2EEStripView.INFO_STORAGE_UNREADABLE, mainBanner());
    assertTrue("precondition: the contact-list button is not disabled by the banner - only "
        + "encrypt and decrypt are", recipientButton().isEnabled());
    recipientButton().performClick();
    assertEquals("precondition: and it opens", View.VISIBLE,
        strip.findViewById(R.id.e2ee_contact_list_wrapper).getVisibility());
  }

  /**
   * The contact list must not read as a fresh install while the account is locked away.
   *
   * <p>It shows no contacts - there is no account to load them from - under a line inviting the
   * user to invite someone new. Every word of that is the reading {@code INFO_STORAGE_UNREADABLE}
   * was written to prevent, and the user reaches it by pressing one button that the storage state
   * does not disable.
   */
  @Test
  public void thecontactListSaysWhyItIsEmpty() {
    recipientButton().performClick();

    final String shown = contactListBanner();
    assertTrue("the contact list is empty because the account cannot be unlocked, and says so "
            + "nowhere. What the user is shown instead: \"" + shown + "\" - over a list with no "
            + "rows, which reads as a fresh install, which is exactly the reading that gets every "
            + "verified pin replaced",
        shown.contains("cannot be unlocked") || shown.contains("re-invite"));
  }

  /**
   * Pressing Invite must not kill the keyboard.
   *
   * <p>Nothing unchecked may leave a click listener. Measured before this file existed:
   * {@code NullPointerException: Cannot invoke "Account.getSignalProtocolStore()" because
   * "this.mAccount" is null}, thrown from {@code getPreKeyBundle} straight through
   * {@code View.performClick}.
   */
  @Test
  public void pressingInviteDoesNotKillTheProcess() {
    recipientButton().performClick();
    assertNotNull("precondition: the invite button exists on this screen", inviteButton());

    inviteButton().performClick();   // must not throw

    final String toast = ShadowToast.getTextOfLatestToast();
    assertNotNull("refusing silently is not enough - the user pressed a button and must be told "
        + "why nothing happened", toast);
    assertEquals("and it must be the branch that knows WHY, not the unchecked-throw net behind "
            + "it. Those carry different words on purpose: with one message, deleting either the "
            + "null-account guard in createPreKeyResponseMessage or the RuntimeException catch "
            + "left the screen identical and neither deletion could be detected. Shown: " + toast,
        E2EEStripView.INFO_INVITE_UNAVAILABLE, toast);
  }

  /**
   * And the refusal must not itself erase the warning.
   *
   * <p>The banner is the only lasting surface in this app. A refusal that wrote an ordinary line
   * over "do NOT re-invite anyone" would trade one defect for the one this branch has spent several
   * rounds closing.
   */
  @Test
  public void therefusalLeavesTheStorageWarningStanding() {
    recipientButton().performClick();
    inviteButton().performClick();

    assertEquals("the storage warning must still own the main banner",
        E2EEStripView.INFO_STORAGE_UNREADABLE, mainBanner());
  }
}
