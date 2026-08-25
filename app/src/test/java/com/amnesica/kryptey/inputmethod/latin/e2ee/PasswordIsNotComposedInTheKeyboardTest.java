package com.amnesica.kryptey.inputmethod.latin.e2ee;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.inputmethodservice.InputMethodService;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.InputConnection;
import android.widget.EditText;

import com.amnesica.kryptey.inputmethod.R;
import com.amnesica.kryptey.inputmethod.latin.RichInputConnection;
import com.amnesica.kryptey.inputmethod.latin.RichInputMethodManager;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.lang.reflect.Field;

/**
 * The other half of the password-field guard: the keyboard must not capture the password either.
 *
 * <p>{@code mHostFieldIsPassword}'s own javadoc states both halves. Decrypting is refused because
 * it "would hand a decrypted message to that app's storage, autofill and whatever it syncs", and
 * "Encrypting is the mirror - a password typed into the compose box would be encrypted and pasted
 * somewhere as ciphertext nobody wants". The first half is enforced and tested from several
 * directions. The second was answered by darkening the Encrypt button, which stops the press and
 * not the capture.
 *
 * <p>The redirect was left up. Measured at 13fb158, driving the real guard:
 *
 * <pre>
 *   isUsingOtherIC=true            clearButtonVisible=true
 *   hostReceived=[]                composeAfterTyping=[hunter2-the-users-password]
 *   composeStillHolds=[hunter2-the-users-password]   (after the guard is lowered again)
 * </pre>
 *
 * <p>So the password field received nothing, the user's password was committed one character at a
 * time into the strip's own compose box, and it was still sitting there - on screen, in the IME's
 * text caches, and in the box Encrypt sends - the moment the user moved to an ordinary field and
 * the button came back on. Nothing in the app removes it; the guard's whole point is that this is
 * the material that must not be encrypted to a contact and written to the message history.
 *
 * <p>The messenger declares the {@code inputType} of every field it presents, so it chooses when
 * this happens; but no adversary is needed for the ordinary case, which is a user tapping any
 * password box while the keyboard is up.
 */
@RunWith(RobolectricTestRunner.class)
public class PasswordIsNotComposedInTheKeyboardTest {

  private static final String PASSWORD = "hunter2-the-users-password";

  /** Stands in for the host's field - here, its password box. */
  private static final class HostField extends BaseInputConnection {
    final StringBuilder received = new StringBuilder();

    HostField(final View dummy) {
      super(dummy, false);
    }

    @Override
    public boolean commitText(final CharSequence text, final int newCursorPosition) {
      received.append(text);
      return true;
    }
  }

  private HostField hostField;
  private RichInputConnection connection;
  private E2EEStripView strip;
  private EditText compose;

  @Before
  public void setUp() {
    final Context app = RuntimeEnvironment.getApplication();
    RichInputMethodManager.init(app);
    hostField = new HostField(new View(app));
    final InputMethodService messengerSide = new InputMethodService() {
      @Override
      public InputConnection getCurrentInputConnection() {
        return hostField;
      }
    };
    connection = new RichInputConnection(messengerSide);
    strip = new E2EEStripView(new ContextThemeWrapper(app, R.style.KeyboardTheme_LXX_Pure_Day),
        null);
    strip.setRichInputConnection(connection);
    compose = strip.findViewById(R.id.e2ee_input_field);
    assertNotNull("the compose field must inflate, or this test measures nothing", compose);
  }

  private static String buffer(final Object target, final String name) throws Exception {
    final Field f = target.getClass().getDeclaredField(name);
    f.setAccessible(true);
    return String.valueOf(f.get(target));
  }

  private boolean composeAffordancesAreLit() {
    return strip.<View>findViewById(R.id.e2ee_button_clear_text).getVisibility() == View.VISIBLE;
  }

  /**
   * Anti-vacuity: while composing, the harness really does keep typing inside the keyboard. Without
   * this, "the host received the password" below could be true because nothing was routing at all.
   */
  @Test
  public void aaWhileComposingTypingReallyDoesStayInsideTheKeyboard() {
    assertTrue("the compose field must be focusable in touch mode", compose.requestFocus());

    connection.commitText("a draft", 1);

    assertEquals("precondition: the draft belongs in the strip", "a draft",
        compose.getText().toString());
    assertEquals("precondition: and nothing reaches the host", "",
        hostField.received.toString());
  }

  /** The finding: the password belongs to the field the user is typing into. */
  @Test
  public void apasswordMustReachThePasswordFieldAndNotTheComposeBox() {
    assertTrue(compose.requestFocus());
    connection.commitText("a draft", 1);

    strip.setHostFieldIsPassword(true);
    hostField.received.setLength(0);

    connection.commitText(PASSWORD, 1);

    assertEquals("a password typed over another app's password box must reach that box",
        PASSWORD, hostField.received.toString());
    assertFalse("and must not be captured into the keyboard's own compose box, which is the "
            + "material this guard exists to keep out of an encrypted message: "
            + compose.getText(),
        compose.getText().toString().contains(PASSWORD));
  }

  /**
   * The consequence that makes it more than a mis-typed character: the captured password survives
   * the guard and is what Encrypt would send once the button comes back on.
   */
  @Test
  public void nothingTypedOverApasswordFieldIsLeftForEncryptToSend() throws Exception {
    assertTrue(compose.requestFocus());
    strip.setHostFieldIsPassword(true);
    connection.commitText(PASSWORD, 1);

    // The user moves to an ordinary field; both buttons come back on.
    strip.setHostFieldIsPassword(false);

    assertTrue("precondition: the actions are available again", strip.actionsAreAvailable());
    assertFalse("the password must not still be staged in the box Encrypt sends: "
        + compose.getText(), compose.getText().toString().contains(PASSWORD));
    assertFalse("nor in the keyboard's own commit buffer",
        buffer(connection, "mTempObjectForCommitText").contains(PASSWORD));
    assertFalse("nor in its committed-text cache",
        buffer(connection, "mCommittedTextBeforeComposingText").contains(PASSWORD));
  }

  /**
   * And the strip must not be claiming the user composes here while it does not - the same
   * invariant {@code ReplyAfterDecryptTest} states from the other side.
   */
  @Test
  public void thestripMustNotClaimTheUserComposesHereOverApasswordField() {
    assertTrue(compose.requestFocus());
    assertTrue("precondition: composing", composeAffordancesAreLit());

    strip.setHostFieldIsPassword(true);

    assertFalse("the clear button and the encoding selector say typing lands in the keyboard; "
        + "over a password field it must not", composeAffordancesAreLit());
    assertFalse("and the redirect itself must be down", connection.isUsingOtherIC());
  }
}
