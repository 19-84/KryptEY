package com.amnesica.kryptey.inputmethod.latin.e2ee;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

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
}
