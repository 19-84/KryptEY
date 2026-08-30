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
   * The tablet Tab key reaches {@code performEditorAction} with no redirect guard.
   *
   * <p>{@code InputLogic.onCodeInput} handles {@code CODE_ACTION_NEXT}/{@code PREVIOUS} by calling
   * {@code performEditorAction} directly. Forty lines below, the Enter path asks first — 
   * {@code mConnection.isUsingOtherIC() ? IME_ACTION_NONE : …} — and its comment says why, from a
   * measurement this project already made: <em>"IME_ACTION_NEXT made TextView.onEditorAction move
   * focus off it, which is the typing redirection above reached by a route the app chooses rather
   * than one the attacker pokes."</em> So the mechanism is not in question; only whether the second
   * route asks.
   *
   * <p>Narrow: it needs {@code sw600dp} resources, the user to have added the PC subtype, and the
   * host to declare a navigate flag — and only the last is the adversary's to choose. Nothing
   * reaches the messenger either way, because nothing on this path lowers the redirect. It is
   * reported and fixed because it is the third instance of "a control on one branch is not a
   * control", and it costs one condition.
   */
  @Test
  public void thetabKeyMustNotMoveFocusOffTheComposeBoxWhileRedirected() {
    assertTrue(compose.requestFocus());
    connection.commitText("a draft", 1);
    assertTrue("precondition: the redirect is up", connection.isUsingOtherIC());

    // Anti-vacuity: prove the action actually reached the compose box. Without this, "focus was
    // kept" could be true because performEditorAction did nothing at all - and that is exactly the
    // load-bearing unknown the reviewer named, since the box sets no imeOptions and no listener, so
    // TextView.onEditorAction plausibly falls through to a key event rather than moving focus.
    final boolean[] reached = {false};
    compose.setOnEditorActionListener((v, actionId, event) -> {
      reached[0] = true;
      return false;
    });

    connection.performEditorAction(android.view.inputmethod.EditorInfo.IME_ACTION_NEXT);

    assertTrue("the action never reached the compose box, so the assertion below would hold "
        + "whatever the platform did with it", reached[0]);
    assertTrue("an editor action the host declared moved focus off the compose box while typing "
            + "was still redirected into it, so the app's compose affordances go dark while the "
            + "keystrokes keep landing in the box - the same state the Enter path refuses to "
            + "produce, reached by the branch that does not ask",
        compose.hasFocus());
  }

  /**
   * A round trip through another screen must not leave the app lying about where typing goes.
   *
   * <p>Entering any non-main screen sets {@code mLayoutE2EEMainView} GONE, which clears the compose
   * box's focus — the file states that mechanism itself — and the blur hides the Clear button and
   * the encoding selector while deliberately NOT lowering the redirect. Returning to the main view
   * restores neither, so typing still lands in the strip while the two controls that
   * {@code changeVisibilityInputFieldButtons} calls <em>"the app's statement that the user is
   * composing inside the keyboard"</em> stay dark.
   *
   * <p>Harmless in the direction that matters — nothing reaches the messenger — and labelled as
   * such so it stays cheap. The concrete cost: after a decrypt the peer's plaintext is in the box
   * and Clear, the only control that erases it, is unreachable until the user taps the box.
   */
  @Test
  public void aroundTripThroughAnotherScreenLeavesTheComposeAffordancesLit() {
    assertTrue(compose.requestFocus());
    connection.commitText("a draft", 1);
    assertTrue("precondition: the redirect is up", connection.isUsingOtherIC());
    assertEquals("precondition: the app says so", View.VISIBLE,
        strip.<View>findViewById(R.id.e2ee_button_clear_text).getVisibility());

    strip.showMessagesListForTest();
    strip.showMainViewForTest();

    // The invariant is the pairing, not either half: the buttons must agree with the redirect.
    assertTrue("after the round trip the redirect is " + (connection.isUsingOtherIC() ? "UP" : "DOWN")
            + " and the compose buttons are "
            + (strip.<View>findViewById(R.id.e2ee_button_clear_text).getVisibility() == View.VISIBLE
                ? "LIT" : "DARK")
            + ". Those two are the app's statement about where typing goes and where it actually "
            + "goes; when they disagree the app is lying about its own state, and Clear - the only "
            + "control that erases a decrypted message from the box - is unreachable",
        connection.isUsingOtherIC()
            == (strip.<View>findViewById(R.id.e2ee_button_clear_text).getVisibility()
                == View.VISIBLE));
  }

  /**
   * A send must actually hand typing back, not hand it back and take it again.
   *
   * <p>{@code sendEncryptedMessageToApplication} lowers the redirect and then, three statements
   * later, calls {@code mInputEditText.clearFocus()}. Clearing focus inside a focusable container
   * does not leave nothing focused — the container re-grants it to the next candidate, and on the
   * main view the compose box is the only {@code focusableInTouchMode} view up at that moment. Its
   * focus listener then calls {@code composeInsideTheKeyboard()}, which raises the redirect again.
   *
   * <p>This file already records the platform half of that, from the other direction: "Measured:
   * {@code clearFocus()} on the only focusable view in a container hands focus straight back." So
   * the send is one of the four enumerated lowerings, and it is the one that does not survive its
   * own method.
   *
   * <p>Not a disclosure — it fails safe, since text goes to the strip rather than the messenger.
   * What it costs is that the app's stated invariant is false, and that after a send the user's
   * next message is composed inside the keyboard rather than in the chat box, so the messenger's
   * own Send does nothing until they notice.
   */
  @Test
  public void asendHandsTypingBackAndLeavesItThere() throws Exception {
    assertTrue(compose.requestFocus());
    connection.commitText(SECRET, 1);
    assertTrue("precondition: the redirect must be up while composing",
        connection.isUsingOtherIC());

    strip.setListener(new E2EEStripView.Listener() {
      @Override
      public void onTextInput(final String rawText) {
        connection.commitText(rawText, 1);
      }

      @Override
      public void onSensitiveContentVisibilityChanged(final boolean showing) { }
    }, new View(RuntimeEnvironment.getApplication()));

    assertTrue("precondition: the box must hold focus going in, or the clearFocus() this test is "
        + "about is a no-op and the assertion below passes for the wrong reason", compose.hasFocus());

    strip.sendEncryptedMessageToApplicationForTest("ciphertext-goes-to-the-host");

    // Stated because it decides what this test is worth. If focus came back here, the re-grant
    // happened and the redirect assertion below is measuring the thing it names. If it did not,
    // this fixture cannot produce the re-grant at all and the question belongs on a device - which
    // is what AsendHandsTypingBackOnDeviceTest answers.
    System.out.println("MEASURED after send: composeHasFocus=" + compose.hasFocus()
        + " redirectUp=" + connection.isUsingOtherIC());

    assertFalse("a send is one of the four places that hand typing back to the host, and after it "
            + "the redirect is up again - clearFocus() re-granted focus to the compose box and its "
            + "listener raised it. The next thing the user types goes into the keyboard rather "
            + "than into the chat box they think they are typing in",
        connection.isUsingOtherIC());
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
    //
    // And that first assertion is carried by that commit rather than by forgetCachedText: commitText
    // clears and refills the SAME buffer, and this fixture's listener commits immediately after the
    // send, so the plaintext is gone either way. Measured - deleting the clear inside
    // forgetCachedText leaves this line green while reddening five other tests, two of them below in
    // this file. The load-bearing one here is the committed-text cache beneath it.
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

  private boolean keyboardStillHolds(final String text) throws Exception {
    return bufferField(connection, "mCommittedTextBeforeComposingText").toString().contains(text)
        || bufferField(connection, "mTempObjectForCommitText").toString().contains(text);
  }

  /**
   * Every path that empties the compose box empties the keyboard's copy with it.
   *
   * <p>Written as one assertion over all the paths rather than one test per button, because the
   * one-test-per-button version is how this got missed three times. Dismissal cleared the caches
   * from the start; send did not, then the clear button did not, and a recipient change and the
   * password-field guard did not either — each found separately, by someone asking "what else ends
   * a message's life?" and remembering to ask. A test shaped like the question catches the sixth
   * path without anyone having to think of it.
   *
   * <p>If a new way to empty the box is added and this fails, the fix is to route it through
   * {@code clearComposeFieldAndCaches} rather than to add a case here.
   */
  @Test
  public void everyPathThatEmptiesTheComposeBoxEmptiesTheKeyboardsCopy() throws Exception {
    final java.util.Map<String, Runnable> paths = new java.util.LinkedHashMap<>();
    // First, while no recipient has been chosen yet, so choosing one is genuinely what clears the
    // box rather than a path that ran before it. This one was missing from the first version of
    // this list, and the control caught that - which is the same lesson the test itself is about.
    paths.put("choosing a recipient", () -> strip.selectContact(
        new com.amnesica.kryptey.inputmethod.signalprotocol.chat.Contact(
            "Bob", "Jones", "a-peer-address", 7, false)));
    paths.put("the clear button", () -> strip.clearUserInputStringForTest());
    paths.put("the keyboard being dismissed", () -> strip.onKeyboardHidden());
    paths.put("focusing a password field", () -> strip.setHostFieldIsPassword(true));
    paths.put("dismissing decrypted content", () -> strip.clearDecryptedContent());

    for (final java.util.Map.Entry<String, Runnable> path : paths.entrySet()) {
      strip.setHostFieldIsPassword(false);
      // Focus must be re-taken, not merely still held: the listener is what raises the redirect,
      // and a view that never lost focus fires no focus-change event. Without this the loop's
      // later iterations quietly typed into the host and measured nothing.
      compose.clearFocus();
      assertTrue(compose.requestFocus());
      connection.commitText(SECRET, 1);
      assertTrue("precondition for " + path.getKey() + ": the draft must be staged first",
          keyboardStillHolds(SECRET));

      path.getValue().run();

      assertEquals("after " + path.getKey() + ", the visible draft must be gone", "",
          compose.getText().toString());
      assertFalse("after " + path.getKey() + ", the keyboard must not still hold the plaintext",
          keyboardStillHolds(SECRET));
    }
  }

  /**
   * The contact-name fields carry the same asymmetry, and leaving the screen is what lowers it.
   *
   * <p>These two fields had the shape the compose box's own comment calls the app's central promise
   * broken: they lowered the typing redirect on focus loss, so blurring one handed the rest of a
   * correspondent's name to the messenger's field. No path was found that drives it, which is why
   * this pins the invariant rather than replaying an exploit — the compose-box case was written on
   * the same argument.
   *
   * <p>The second half is what makes the first safe. Copying the asymmetry without a lowering path
   * would leave the redirect up pointing at a field that is no longer on screen, and every keystroke
   * on the main view would vanish into it — a total functional break rather than a leak. Leaving the
   * add-contact screen is the choke point, so that is where it is lowered.
   */
  @Test
  public void anameFieldLosingFocusDoesNotRedirectTypingIntoTheHostApp() {
    strip.showAddContactViewForTest();
    final android.widget.EditText firstName =
        strip.findViewById(R.id.e2ee_add_contact_first_name_input_field);
    assertNotNull("the name field must inflate", firstName);
    assertTrue("the name field must take focus", firstName.requestFocus());
    assertTrue("precondition: typing must be going into the strip", connection.isUsingOtherIC());

    // The listener is driven with the argument the framework delivers, rather than through
    // clearFocus(). Measured: clearFocus() on the only focusable view in a container hands focus
    // straight back, so the blur never happens and the first version of this test passed against a
    // build that still lowered on blur - the mutant caught it. Calling the production listener with
    // hasFocus=false is the blur, minus a focus manager that refuses to produce one here.
    firstName.getOnFocusChangeListener().onFocusChange(firstName, false);

    assertTrue("focus loss is not the user asking for their typing to go to the messenger: the "
        + "rest of a contact's name would be committed into the host app's own field",
        connection.isUsingOtherIC());
  }

  @Test
  public void leavingTheAddContactScreenHandsTypingBack() {
    strip.showAddContactViewForTest();
    final android.widget.EditText firstName =
        strip.findViewById(R.id.e2ee_add_contact_first_name_input_field);
    assertTrue(firstName.requestFocus());
    assertTrue("precondition: the redirect must be up", connection.isUsingOtherIC());

    strip.showMainViewForTest();

    assertTrue("the redirect must not survive the screen it was raised on: left up, it points at a "
            + "field that is no longer shown and the user's typing disappears into it",
        !connection.isUsingOtherIC());
  }

  /**
   * And the add-contact screen is the same defect through the one route with an adversary in it.
   *
   * <p>The sibling above walks to the contact list and back. This walks to the ADD-CONTACT screen
   * and back, which is where the lowering lives — and its escape hatch asked
   * {@code mInputEditText.hasFocus()}, a condition that can never be true there: the compose box
   * lives inside the main wrapper, which is {@code GONE} for the whole life of that screen, and a
   * GONE subtree cannot hold focus. So the lowering was unconditional, the draft was still on
   * screen, and the only visible change was two small buttons going dark.
   *
   * <p>The attacker picks the moment. An invite from an unknown address routes the decrypt straight
   * to this screen, and Cancel is the response this file elsewhere calls the correct one.
   */
  @Test
  public void leavingTheAddContactScreenWithAdraftMustNotRedirectTypingIntoTheHostApp() {
    assertTrue(compose.requestFocus());
    connection.commitText("the meeting ", 1);

    strip.showAddContactViewForTest();
    strip.showMainViewForTest();

    assertEquals("the draft is still on screen where the user left it", "the meeting ",
        compose.getText().toString());

    connection.commitText("is at nine", 1);

    assertEquals("the rest of the message must not be committed into the messenger's own field",
        "", hostField.received.toString());
  }

  /**
   * And an EMPTY compose box still hands typing back, or the fix is the mirror defect.
   *
   * <p>Re-pointing unconditionally would take focus the user never gave the box, on a screen switch
   * they made for another reason — so ordinary typing would start disappearing into the strip. The
   * condition is "is there something to protect", not "did we come back to the main view".
   */
  @Test
  public void leavingTheAddContactScreenWithNoDraftStillHandsTypingBack() {
    strip.showAddContactViewForTest();
    final android.widget.EditText firstName =
        strip.findViewById(R.id.e2ee_add_contact_first_name_input_field);
    assertTrue(firstName.requestFocus());
    assertTrue("precondition: the redirect must be up", connection.isUsingOtherIC());
    assertEquals("precondition: the compose box must be empty", "",
        compose.getText().toString());

    strip.showMainViewForTest();

    assertTrue("with nothing in the compose box there is nothing to protect, and the user's typing "
            + "belongs to the app they are in",
        !connection.isUsingOtherIC());
  }

  /**
   * The redirect and the connection it points at must never disagree.
   *
   * <p>"Typing goes to the compose box" is two facts: the flag, and a non-null {@code mOtherIC}. If
   * the flag is up while the connection is null, {@code getIC()} returns null and every keystroke is
   * silently discarded — the user types and nothing happens anywhere, in any app, until the strip is
   * rebuilt again. Not a disclosure, but the keyboard has stopped being a keyboard.
   *
   * <p>{@code surrenderState} nulls the connection and deliberately does not lower the flag, so the
   * pair is inconsistent between a rebuild's two halves by construction. What has to hold is that
   * {@code adoptState} closes it — and it used to rely on a bare {@code requestFocus()} whose return
   * value it discarded, which is the exact mechanism {@code composeInsideTheKeyboard}'s javadoc says
   * must not be relied on.
   */
  @Test
  public void theredirectAndItsConnectionAgreeAfterArebuild() {
    assertTrue(compose.requestFocus());
    connection.commitText("half a sentence", 1);
    assertTrue("precondition: the redirect must be up", connection.isUsingOtherIC());

    final E2EEStripView.CarriedState carried = strip.surrenderState();
    final E2EEStripView rebuilt = new E2EEStripView(new android.view.ContextThemeWrapper(
        org.robolectric.RuntimeEnvironment.getApplication(),
        R.style.KeyboardTheme_LXX_Pure_Day), null);
    rebuilt.setListener(new E2EEStripView.Listener() {
      @Override public void onTextInput(final String rawText) { }
      @Override public void onSensitiveContentVisibilityChanged(final boolean sensitive) { }
    }, rebuilt);
    rebuilt.setRichInputConnection(connection);

    // The compose box cannot take focus at this moment. That is the case the guarded raise exists
    // for - a GONE ancestor, a window not yet focusable - and it is the only case where the two
    // forms differ: Robolectric grants focus otherwise, so a test that does not force the refusal
    // passes against a bare requestFocus() too. Written without this first, and the mutant said so.
    final android.widget.EditText rebuiltBox = rebuilt.findViewById(R.id.e2ee_input_field);
    rebuiltBox.setFocusable(false);
    rebuiltBox.setFocusableInTouchMode(false);

    rebuilt.adoptState(carried);

    assertTrue("the draft must have come across", 
        ((android.widget.EditText) rebuilt.findViewById(R.id.e2ee_input_field))
            .getText().toString().contains("half a sentence"));

    // The pair, asserted together: either the redirect is down, or it points somewhere real.
    connection.commitText(" continues", 1);
    assertEquals("the keystroke must not reach the host application",
        "", hostField.received.toString());
    assertTrue("and it must have reached the rebuilt compose box. With the flag up from before the "
            + "rebuild and mOtherIC still null - surrenderState nulls it and does not lower - "
            + "getIC() returns null and the keystroke goes nowhere at all: the user types and "
            + "nothing happens, in any app, until the strip is rebuilt again",
        rebuiltBox.getText().toString().contains("continues"));
  }
}
