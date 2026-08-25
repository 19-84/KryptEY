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
import com.amnesica.kryptey.inputmethod.latin.LatinIME;
import com.amnesica.kryptey.inputmethod.latin.RichInputConnection;
import com.amnesica.kryptey.inputmethod.latin.RichInputMethodManager;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.lang.reflect.Field;

/**
 * Where a keystroke goes.
 *
 * <p>The whole premise of this keyboard is that the message is composed somewhere the messenger
 * cannot read, and one boolean decides that: {@code RichInputConnection.shouldUseOtherIC}. True and
 * the character lands in the strip's own compose box; false and it is committed straight into the
 * host application's text field. Nothing had ever tested it - the flag, its two setters and the
 * seam that reads it all still carry {@code // TODO} comments from the original author.
 *
 * <p>It is set by exactly one thing: the compose box's focus listener. So anything that can take
 * focus off that box re-points the user's typing at the app the threat model calls the adversary,
 * while the box stays on screen still holding the characters typed so far.
 *
 * <p>The host app has such a lever, and it is an ordinary one:
 * {@code InputMethodManager.showSoftInput} reaches {@code LatinIME.onShowInputRequested}, whose
 * first statement is {@code mE2EEStripView.clearFocusEditTextView()}.
 */
@RunWith(RobolectricTestRunner.class)
public class TypingDestinationTest {

  private static final String SECRET = "the meeting is at nine";

  /** Stands in for the messenger's own text box - what {@code getCurrentInputConnection} returns. */
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

  private static void set(final Object target, final String name, final Object value)
      throws Exception {
    final Field f = target.getClass().getDeclaredField(name);
    f.setAccessible(true);
    f.set(target, value);
  }

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

    strip = new E2EEStripView(
        new ContextThemeWrapper(app, R.style.KeyboardTheme_LXX_Pure_Day), null);
    strip.setRichInputConnection(connection);

    compose = strip.findViewById(R.id.e2ee_input_field);
    assertNotNull("the compose field must inflate, or this test measures nothing", compose);
  }

  /**
   * Precondition, and the anti-vacuity guard for everything below.
   *
   * <p>If this fails the harness is not routing anything and the other test proves nothing.
   */
  @Test
  public void whileTheComposeFieldHasFocusTypingStaysInsideTheKeyboard() {
    assertTrue("the compose field must be focusable in touch mode", compose.requestFocus());

    connection.commitText(SECRET, 1);

    assertEquals("the typed message belongs in the strip's own box",
        SECRET, compose.getText().toString());
    assertEquals("and nothing may reach the host application",
        "", hostField.received.toString());
  }

  /**
   * The compose box losing focus must not redirect the user's typing into the host application.
   *
   * <p>{@code clearFocusEditTextView} is the strip's own production entry point for this, and the
   * next test shows who can pull it. The box is still on screen holding the draft, so nothing tells
   * the user their next characters are going somewhere else.
   */
  @Test
  public void losingFocusMustNotRedirectTypingIntoTheHostApp() {
    assertTrue(compose.requestFocus());
    connection.commitText("the meeting ", 1);
    assertEquals("precondition: composing inside the keyboard",
        "", hostField.received.toString());

    strip.clearFocusEditTextView();

    // The user has not looked away: the box is still on screen holding what they typed.
    assertEquals("the compose box still shows the draft", "the meeting ",
        compose.getText().toString());

    connection.commitText("is at nine", 1);

    assertEquals("the rest of the message must not be committed into the messenger's own field",
        "", hostField.received.toString());
  }

  /**
   * The same defect with no adversary in it at all: look at the contact list and come back.
   *
   * <p>Every screen switch sets the main layout {@code GONE}, which clears focus on the compose box
   * inside it, and coming back makes it {@code VISIBLE} again with the draft still in it and no
   * focus. There is nothing on the screen to distinguish that from the state the user left.
   */
  @Test
  public void visitingAnotherScreenAndComingBackMustNotRedirectTypingIntoTheHostApp() {
    assertTrue(compose.requestFocus());
    connection.commitText("the meeting ", 1);

    strip.showContactListForTest();
    strip.showMainViewForTest();

    assertEquals("the draft is still on screen where the user left it", "the meeting ",
        compose.getText().toString());

    connection.commitText("is at nine", 1);

    assertEquals("the rest of the message must not be committed into the messenger's own field",
        "", hostField.received.toString());
  }

  /**
   * And the host application is what pulls it.
   *
   * <p>Driven through the real {@code LatinIME.onShowInputRequested}, because the severity of the
   * test above rests on the trigger being a public, unprivileged call any app may make at any
   * moment - {@code imm.showSoftInput(itsOwnEditText, 0)} - which apps make routinely. The rest of
   * that method needs an attached service, so it throws once past the line under test; the throw is
   * caught rather than avoided, so the production statement really runs.
   */
  @Test
  public void thehostAppCanTakeFocusOffTheComposeBoxWheneverItLikes() throws Exception {
    assertTrue(compose.requestFocus());

    final LatinIME ime = new LatinIME();
    set(ime, "mE2EEStripView", strip);
    try {
      ime.onShowInputRequested(0 /* flags */, false /* configChange */);
    } catch (final Throwable expected) {
      // Everything after the strip call needs a service with a base context.
    }

    assertFalse("showSoftInput from the host app reaches clearFocusEditTextView",
        compose.hasFocus());
  }

  /**
   * Dismissing the keyboard does return typing to the host, which is the other half of the fix.
   *
   * <p>Once focus loss stopped lowering the redirect, something else had to, or a user who touched
   * the compose box could never type into an ordinary text field again for the life of the IME.
   * Two things do: encrypting and sending, and this. The security fix and the usability of the app
   * are the same line of code from opposite sides, so both directions are asserted - a control
   * removing this lowering leaves the suite green without it.
   */
  @Test
  public void dismissingTheKeyboardReturnsTypingToTheHost() {
    assertTrue(compose.requestFocus());
    connection.commitText("draft", 1);
    assertEquals("precondition: the redirect must be on", "draft", compose.getText().toString());

    // Exactly what LatinIME.onWindowHidden calls.
    strip.onKeyboardHidden();

    connection.commitText("an ordinary message", 1);

    assertEquals("after the keyboard is dismissed, typing belongs to the host again",
        "an ordinary message", hostField.received.toString());
    assertEquals("and must not still be going into the strip", "",
        compose.getText().toString());
  }

  private static Object bufferField(final Object target, final String name) throws Exception {
    final java.lang.reflect.Field f = target.getClass().getDeclaredField(name);
    f.setAccessible(true);
    return f.get(target);
  }

  /**
   * Sending must not leave the plaintext in the keyboard's own text caches.
   *
   * <p>While typing is redirected, the IME's caches fill with the DRAFT - that is what they are for.
   * The send path lowers the redirect and clears the compose box, but the keyboard stays up in the
   * messenger's app afterwards, and those buffers live as long as the service. So the message the
   * user just encrypted was still sitting in cleartext inside the IME, in buffers that ordinary
   * host-side editing then reads from.
   *
   * <p>Same class as the buffers cleared on dismissal, at a moment nobody had looked at: dismissal
   * is the obvious end of a message's life, and pressing send is the far more common one.
   */
  @Test
  public void sendingClearsTheKeyboardsOwnCopyOfTheDraft() throws Exception {
    assertTrue(compose.requestFocus());
    connection.commitText(SECRET, 1);
    assertEquals("precondition: the draft must be in the compose box", SECRET,
        compose.getText().toString());
    assertTrue("precondition: the keyboard's own cache must hold it, or this proves nothing",
        bufferField(connection, "mTempObjectForCommitText").toString().contains(SECRET));

    strip.setListener(new E2EEStripView.Listener() {
      @Override
      public void onTextInput(final String rawText) {
        connection.commitText(rawText, 1);
      }

      @Override
      public void onSensitiveContentVisibilityChanged(final boolean showing) { }
    }, new View(RuntimeEnvironment.getApplication()));

    strip.sendEncryptedMessageToApplicationForTest("ciphertext-goes-to-the-host");

    // Not "empty" - a legitimate commit of the ciphertext follows the send and refills the commit
    // buffer, which is correct. The property is that the PLAINTEXT is not still in there.
    assertFalse("the keyboard's commit buffer must not keep the plaintext after a send: "
            + bufferField(connection, "mTempObjectForCommitText"),
        bufferField(connection, "mTempObjectForCommitText").toString().contains(SECRET));
    assertFalse("nor may its committed-text cache: "
            + bufferField(connection, "mCommittedTextBeforeComposingText"),
        bufferField(connection, "mCommittedTextBeforeComposingText").toString().contains(SECRET));
  }

  /**
   * The clear button must take the keyboard's copy of the draft with it too.
   *
   * <p>Send was the path nobody had looked at; this is its sibling. A user who types a message and
   * changes their mind presses clear, and every visible trace goes - while the IME's own caches, on
   * an object that lives as long as the service, still hold what they typed.
   */
  @Test
  public void clearingTheDraftClearsTheKeyboardsCopyToo() throws Exception {
    assertTrue(compose.requestFocus());
    connection.commitText(SECRET, 1);
    assertTrue("precondition: the keyboard's cache must hold the draft",
        bufferField(connection, "mCommittedTextBeforeComposingText").toString().contains(SECRET)
            || bufferField(connection, "mTempObjectForCommitText").toString().contains(SECRET));

    strip.clearUserInputStringForTest();

    assertEquals("precondition: the visible draft must be gone", "",
        compose.getText().toString());
    assertFalse("and the keyboard's own copy with it: "
            + bufferField(connection, "mCommittedTextBeforeComposingText"),
        bufferField(connection, "mCommittedTextBeforeComposingText").toString().contains(SECRET));
    assertFalse("including the commit buffer: "
            + bufferField(connection, "mTempObjectForCommitText"),
        bufferField(connection, "mTempObjectForCommitText").toString().contains(SECRET));
  }
}
