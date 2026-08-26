package com.amnesica.kryptey.inputmethod.latin.e2ee;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.view.ContextThemeWrapper;
import android.widget.EditText;

import com.amnesica.kryptey.inputmethod.R;
import com.amnesica.kryptey.inputmethod.signalprotocol.SignalProtocolMain;
import com.amnesica.kryptey.inputmethod.signalprotocol.chat.Contact;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

/**
 * The strip itself, driven rather than reasoned about.
 *
 * <p>Nothing had ever inflated {@code E2EEStripView} in a test. That is the file where the user's
 * decisions actually happen - 1300 lines of it - and a review that finally did found that every
 * guard in it was a surviving mutant, because the suite could not reach any of them.
 *
 * <p>What this pins is the recipient change. The input field used to survive a change it did not
 * cause, and exploiting that needed no cryptographic capability at all: a messenger reposting
 * another contact's OWN earlier invite text, verbatim, sets the chosen contact to them while the
 * field still holds the plaintext just decrypted from somebody else. Driven end to end, one
 * contact's message was re-encrypted and written to another's chat log.
 */
@RunWith(RobolectricTestRunner.class)
public class StripRecipientChangeTest {

  private E2EEStripView strip;

  @Before
  public void inflateTheStrip() {
    SignalProtocolMain.resetForTest();
    SignalProtocolMain.testIsRunning = true;
    SignalProtocolMain.initialize(null);

    // A themed context: the strip's styles resolve against the keyboard theme, and a bare
    // application context cannot inflate it.
    strip = new E2EEStripView(new ContextThemeWrapper(RuntimeEnvironment.getApplication(),
        R.style.KeyboardTheme_LXX_Pure_Day), null);
    assertNotNull("the strip must inflate at all - if this fails the harness is broken, not the "
        + "behaviour under test", strip);
  }

  private EditText inputField() {
    final EditText field = strip.findViewById(R.id.e2ee_input_field);
    assertNotNull("the strip must have an input field", field);
    return field;
  }

  private static Contact contact(final String first, final String uuid) {
    return new Contact(first, "Peer", uuid, 7, false);
  }

  @Test
  public void changingTheRecipientClearsWhateverWasStagedForTheLastOne() {
    strip.selectContact(contact("Alice", "alice-uuid"));
    inputField().setText("the key is under the third flowerpot");

    strip.selectContact(contact("Bob", "bob-uuid"));

    assertEquals("a message staged for Alice must not be left addressed to Bob - a messenger can "
            + "cause this switch by reposting Bob's own earlier invite, with no forgery and no "
            + "keys", "", inputField().getText().toString());
  }

  /** Selecting the SAME contact again must not throw away what the user is typing. */
  @Test
  public void reselectingTheSameContactKeepsTheDraft() {
    final Contact alice = contact("Alice", "alice-uuid");
    strip.selectContact(alice);
    inputField().setText("half a sentence so far");

    strip.selectContact(contact("Alice", "alice-uuid"));

    assertEquals("re-selecting the same contact must not discard the user's draft",
        "half a sentence so far", inputField().getText().toString());
  }

  /** And clearing the contact entirely clears the draft with it. */
  @Test
  public void clearingTheContactClearsTheDraft() {
    strip.selectContact(contact("Alice", "alice-uuid"));
    inputField().setText("something private");

    strip.removeContact(contact("Alice", "alice-uuid"));

    assertEquals("a draft must not outlive the contact it was addressed to",
        "", inputField().getText().toString());
  }

  private android.widget.TextView infoField() {
    final android.widget.TextView view = strip.findViewById(R.id.e2ee_info_text);
    assertNotNull("the strip must have an info banner", view);
    return view;
  }

  /**
   * A security warning must survive whatever the messenger puts on the clipboard.
   *
   * <p>The info banner is the app's only persistent warning surface - a toast lasts about three and
   * a half seconds, and then the screen looks like an ordinary success. Overwriting it therefore
   * erases the warning entirely, and it cost the attacker one extra post: the user copies it as
   * part of the ordinary workflow, the listener writes "Keybundle detected", and nothing is left.
   * An unverified contact after a refused substitution is byte-identical to one the user simply
   * never compared.
   */
  @Test
  public void aStandingWarningIsNotOverwrittenByClipboardTraffic() {
    strip.setWarningMessageForTest("Someone offered a different key for Bob.");

    // Calls the same decision the listener calls, NOT a re-implementation of its body.
    //
    // Driving the real listener was the first attempt and does not work: Robolectric's clipboard
    // shadow does not notify OnPrimaryClipChangedListener, so writing to the clipboard fired
    // nothing and the test passed because nothing had happened - which two controls then proved by
    // surviving. What IS covered is the guard itself; the listener's one-line call to it is not,
    // and no test here can cover it. Stated rather than implied.
    strip.onClipboardChangedForTest();

    assertEquals("clipboard traffic must not erase a security warning",
        "Someone offered a different key for Bob.", infoField().getText().toString());
  }

  /**
   * And choosing a contact does <b>not</b> clear it, which is the opposite of what this test used
   * to assert.
   *
   * <p>The old version called tapping a row "a deliberate act" and let it dismiss any warning about
   * anything. Tapping a row is not a response to a warning. For the duplicate-name warning it is
   * the ambiguous act the warning exists to flag — its own text ends "Both now appear in your list,
   * tagged by address", so the user opens the list to look, and looking is what erased it. For a
   * warning naming another contact, or naming storage rather than a contact, selection has nothing
   * to do with it at all.
   *
   * <p>The deliberate responses still clear it: comparing a safety number, or saying it does not
   * match. Those are what the flag's javadoc rests on.
   */
  @Test
  public void choosingAcontactDoesNotClearTheWarning() {
    strip.setWarningMessageForTest("Someone offered a different key for Bob.");
    strip.selectContact(contact("Bob", "bob-uuid"));

    assertTrue("selecting a contact must not erase a standing warning",
        infoField().getText().toString().contains("Someone offered a different key for Bob."));

    strip.onClipboardChangedForTest();

    assertTrue("and the warning must still hold off ordinary clipboard traffic afterwards",
        infoField().getText().toString().contains("Someone offered a different key for Bob."));
  }

  /** A warning naming one contact is not erased by choosing a different one. */
  @Test
  public void awarningAboutOneContactIsNotErasedByChoosingAnother() {
    strip.setWarningMessageForTest("Someone offered a different key for Bob.");

    strip.selectContact(contact("Alice", "alice-uuid"));

    final String shown = infoField().getText().toString();
    assertTrue("a warning about Bob was erased by tapping Alice - the re-assertion asks about "
            + "the contact chosen, not the contact the warning names. Banner: " + shown,
        shown.contains("Someone offered a different key for Bob."));
    assertTrue("and the banner must say the next message goes to Alice, or the warning naming Bob "
        + "is the only name on screen while Alice is the recipient: " + shown,
        shown.contains("Alice"));
  }


  /**
   * The opening banner is chosen when storage can be asked, not at inflation.
   *
   * <p>The strip is built before {@code LatinIME} creates the storage helper - KeyboardSwitcher
   * evaluates {@code onCreateInputView(...)} as the ARGUMENT to {@code setInputView} - so a
   * decision made in the constructor could only ever see "no storage at all". On a cold process,
   * which is the normal path, a user whose identity cannot be decrypted saw "No contact chosen" and
   * the banner telling them not to re-invite anyone could never appear.
   */
  @Test
  public void theOpeningBannerIsChosenAfterStorageExists() {
    // At inflation there is no storage helper, so the strip must not have committed to a banner
    // that claims to know the storage state.
    assertEquals("at inflation the strip can only say the neutral thing",
        E2EEStripView.INFO_NO_CONTACT_CHOSEN_TEXT, infoField().getText().toString());

    // Now storage exists and reports UNREADABLE - the state the constructor could never see.
    SignalProtocolMain.setStorageStateForTest(
        com.amnesica.kryptey.inputmethod.signalprotocol.helper.StorageHelper.StorageState.UNREADABLE);
    strip.refreshOpeningMessage();

    assertEquals("once storage can be asked, the warning must appear",
        E2EEStripView.INFO_STORAGE_UNREADABLE, infoField().getText().toString());
  }

  /**
   * And that banner survives clipboard traffic.
   *
   * <p>Twice over, and only one of the two is load-bearing here: {@code mayOverwriteInfoBanner}
   * refuses when storage is unreadable OR a warning is standing, and this banner only exists when
   * the first is already true. So marking it as a standing warning is belt-and-braces for THIS
   * message - a control confirmed it, by surviving. It is kept because the flag is what protects
   * the identity-change and post-reject banners, where no second guard applies, and having one
   * rule for all three is what stops the next banner being added without one.
   */
  @Test
  public void theUnreadableBannerSurvivesClipboardTraffic() {
    SignalProtocolMain.setStorageStateForTest(
        com.amnesica.kryptey.inputmethod.signalprotocol.helper.StorageHelper.StorageState.UNREADABLE);
    strip.refreshOpeningMessage();

    strip.onClipboardChangedForTest();

    assertEquals(E2EEStripView.INFO_STORAGE_UNREADABLE, infoField().getText().toString());
  }

  /**
   * Decrypted plaintext must not survive the keyboard being dismissed.
   *
   * <p>The IME view is not recreated when the user switches apps, and nothing in the input
   * lifecycle touched strip state - the one per-raise call clears focus, not text. So a decrypted
   * message stayed rendered and reappeared the next time the keyboard rose, in whatever app that
   * was.
   */
  @Test
  public void decryptedContentDoesNotSurviveTheKeyboardBeingHidden() {
    strip.selectContact(contact("Alice", "alice-uuid"));
    inputField().setText("meet me at the safe house at nine");

    strip.clearDecryptedContent();

    assertEquals("a decrypted message must not be waiting on screen in the next app",
        "", inputField().getText().toString());
  }
}
