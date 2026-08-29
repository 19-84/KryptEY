package com.amnesica.kryptey.inputmethod.latin.e2ee;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.text.InputType;
import android.view.ContextThemeWrapper;
import android.widget.EditText;

import com.amnesica.kryptey.inputmethod.R;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

/**
 * No field this app owns may offer its text to a spell checker.
 *
 * <p>A spell-checker session ships the field's text over binder to whatever app the user has set as
 * their spell checker — a third-party process, commonly the same vendor as the stock keyboard, and
 * commonly one that retains what it is given. {@code TextView} opens such a session for any editable
 * field whose {@code isSuggestionsEnabled()} is true, which is the default; the compose box holds
 * decrypted messages and the user's draft, and the two contact fields hold a correspondent's name.
 *
 * <p>This is neither autofill nor accessibility, so neither the autofill device test nor
 * {@code FLAG_SECURE} covers it. Whether the platform actually starts a session for a view inside a
 * non-focusable input-method window was not established — and the flag is correct either way, which
 * is why it is set rather than investigated: the cost is one attribute and the alternative is
 * depending on a framework detail nobody here has measured.
 *
 * <p>Asserted on the <b>inflated view</b> rather than by reading the layout XML. A source scan would
 * stay green against a later {@code setInputType} call in code, and would miss the field being
 * skinned by a style — this app has two themes, and a flag put in one of them silently misses the
 * other. What the platform reads is the view, so that is what this reads.
 *
 * <p>Not {@code textPassword}, which would hide the message from the user who is meant to read it.
 */
@RunWith(RobolectricTestRunner.class)
public class NoFieldOffersItsTextToAspellCheckerTest {

  private E2EEStripView strip() {
    final E2EEStripView view = new E2EEStripView(new ContextThemeWrapper(
        RuntimeEnvironment.getApplication(), R.style.KeyboardTheme_LXX_Pure_Day), null);
    view.setListener(new E2EEStripView.Listener() {
      @Override public void onTextInput(final String rawText) { }
      @Override public void onSensitiveContentVisibilityChanged(final boolean sensitive) { }
    }, view);
    return view;
  }

  private void assertRefusesSuggestions(final EditText field, final String what) {
    assertNotNull(what + " must exist", field);
    assertTrue(what + " offers its text to the device's spell checker, which is a third-party "
            + "process: inputType=0x" + Integer.toHexString(field.getInputType()),
        (field.getInputType() & InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS) != 0);
    assertTrue(what + " must still be readable as ordinary text, not masked",
        (field.getInputType() & InputType.TYPE_TEXT_VARIATION_PASSWORD) == 0);
  }

  @Test
  public void thecomposeBoxRefusesSuggestions() {
    assertRefusesSuggestions(strip().findViewById(R.id.e2ee_input_field),
        "the compose box, which holds decrypted messages and the user's draft,");
  }

  @Test
  public void bothContactNameFieldsRefuseSuggestions() {
    final E2EEStripView strip = strip();
    assertRefusesSuggestions(strip.findViewById(R.id.e2ee_add_contact_first_name_input_field),
        "the first-name field, which holds a correspondent's name,");
    assertRefusesSuggestions(strip.findViewById(R.id.e2ee_add_contact_last_name_input_field),
        "the last-name field, which holds a correspondent's name,");
  }

  /**
   * And the field is still multi-line, so the flag was added rather than the type replaced.
   *
   * <p>The floor: without it, a change that dropped {@code textMultiLine} entirely would satisfy
   * every assertion above while making the compose box a single-line field.
   */
  @Test
  public void thecomposeBoxIsStillMultiLine() {
    final EditText field = strip().findViewById(R.id.e2ee_input_field);
    assertEquals("the compose box must remain multi-line", InputType.TYPE_TEXT_FLAG_MULTI_LINE,
        field.getInputType() & InputType.TYPE_TEXT_FLAG_MULTI_LINE);
  }
}
