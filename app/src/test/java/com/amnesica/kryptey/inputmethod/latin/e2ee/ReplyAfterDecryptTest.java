package com.amnesica.kryptey.inputmethod.latin.e2ee;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.ClipData;
import android.content.ClipboardManager;
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
import com.amnesica.kryptey.inputmethod.signalprotocol.Account;
import com.amnesica.kryptey.inputmethod.signalprotocol.MessageEnvelope;
import com.amnesica.kryptey.inputmethod.signalprotocol.SignalProtocolMain;
import com.amnesica.kryptey.inputmethod.signalprotocol.chat.Contact;
import com.amnesica.kryptey.inputmethod.signalprotocol.encoding.EnvelopeCodec;
import com.amnesica.kryptey.inputmethod.signalprotocol.encoding.RawEncoder;
import com.amnesica.kryptey.inputmethod.signalprotocol.helper.StorageHelper;
import com.amnesica.kryptey.inputmethod.signalprotocol.util.ProtocolAddresses;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.signal.libsignal.protocol.SignalProtocolAddress;

import java.util.ArrayList;

/**
 * The reply to a decrypted message must not be typed into the messenger.
 *
 * <p>{@code TypingDestinationTest} establishes the property from the send side: one boolean,
 * {@code RichInputConnection.shouldUseOtherIC}, decides whether a keystroke lands in the strip's
 * compose box or is committed into the host application's field, and losing focus must not lower
 * it. This is the same property from the receive side, and it had no counterpart at all.
 *
 * <p>The redirect is RAISED by exactly one thing: the compose box's focus listener. The decrypt
 * path never focuses the box. It writes the peer's plaintext into it with {@code setText} and calls
 * {@code changeVisibilityInputFieldButtons(true)}, which lights the clear button and the encoding
 * selector - the only two things on the strip that say "you are composing in here". Measured at
 * 13fb158, driving the real Decrypt button on a genuine message from a known contact:
 *
 * <pre>
 *   composeText=[the meeting is at nine]   composeHasFocus=false
 *   clearButtonVisible=true                isUsingOtherIC=false
 *   hostReceived=[meet me at the usual place]
 * </pre>
 *
 * <p>So the plaintext of the received message is on the strip, the compose affordances are lit
 * beside it, and every character of the reply is committed into the messenger's own text field in
 * cleartext as it is typed. There is no adversary in this and no unusual gesture: receive, decrypt,
 * reply is the workflow the app's own help describes, and it is the workflow that never fires the
 * one listener that raises the redirect.
 *
 * <p>Harness: the strip inflated on its own with a real {@code RichInputConnection} pointed at a
 * stand-in for the messenger's field, from {@code TypingDestinationTest}; the two-account session
 * bootstrap from {@code PostRejectBundlelessWarningTest}.
 */
@RunWith(RobolectricTestRunner.class)
public class ReplyAfterDecryptTest {

  private static final String RECEIVED = "the meeting is at nine";
  private static final String REPLY = "meet me at the usual place";

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
  private Account victim;
  private Account peer;
  private SignalProtocolAddress peerAddress;

  @Before
  public void setUp() throws Exception {
    SignalProtocolMain.resetForTest();
    SignalProtocolMain.testIsRunning = true;
    final Context app = RuntimeEnvironment.getApplication();
    RichInputMethodManager.init(app);

    SignalProtocolMain.initialize(null);
    victim = SignalProtocolMain.getInstance().getAccount();
    SignalProtocolMain.initialize(null);
    peer = SignalProtocolMain.getInstance().getAccount();
    peerAddress = ProtocolAddresses.of(peer.getSignalProtocolAddress().getName(),
        peer.getDeviceId());

    activate(peer);
    final String peerBundle = SignalProtocolMain.exportOwnKeyBundle();
    activate(victim);
    assertTrue("precondition: the peer's key must be pinned, or nothing decrypts",
        SignalProtocolMain.processPreKeyResponseMessage(
            EnvelopeCodec.fromWire(peerBundle), peerAddress));
    final ArrayList<Contact> contacts = new ArrayList<>();
    contacts.add(new Contact("Bob", "Jones", peerAddress.getName(), peerAddress.getDeviceId(),
        false));
    victim.setContactList(contacts);

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
    strip.setListener(new E2EEStripView.Listener() {
      @Override
      public void onTextInput(final String rawText) {
        connection.commitText(rawText, 1);
      }

      @Override
      public void onSensitiveContentVisibilityChanged(final boolean sensitive) { }
    }, strip);
    compose = strip.findViewById(R.id.e2ee_input_field);
    assertNotNull("the compose field must inflate, or this test measures nothing", compose);
    SignalProtocolMain.setStorageStateForTest(StorageHelper.StorageState.READABLE);
  }

  @After
  public void tearDown() {
    SignalProtocolMain.resetForTest();
    SignalProtocolMain.testIsRunning = false;
  }

  private void activate(final Account account) {
    SignalProtocolMain.getInstance().setAccount(account);
  }

  private SignalProtocolAddress victimAddress() {
    return ProtocolAddresses.of(victim.getSignalProtocolAddress().getName(), victim.getDeviceId());
  }

  /** A genuine encrypted message from Bob to the user. */
  private MessageEnvelope messageFromBob() throws Exception {
    activate(victim);
    final String victimBundle = SignalProtocolMain.exportOwnKeyBundle();
    activate(peer);
    assertTrue(SignalProtocolMain.processPreKeyResponseMessage(
        EnvelopeCodec.fromWire(victimBundle), victimAddress()));
    final MessageEnvelope sent = SignalProtocolMain.encryptMessage(RECEIVED, victimAddress());
    assertNotNull("precondition: the peer must be able to encrypt to the user", sent);
    activate(victim);
    return sent;
  }

  /** Bob's own key bundle again - the update/re-invite arm, where the compose box stays empty. */
  private MessageEnvelope bundleFromBob() throws Exception {
    activate(peer);
    final String bundle = SignalProtocolMain.exportOwnKeyBundle();
    activate(victim);
    return EnvelopeCodec.fromWire(bundle);
  }

  private void pasteAndPressDecrypt(final MessageEnvelope envelope) throws Exception {
    final ClipboardManager clipboard = (ClipboardManager) RuntimeEnvironment.getApplication()
        .getSystemService(Context.CLIPBOARD_SERVICE);
    clipboard.setPrimaryClip(ClipData.newPlainText("",
        RawEncoder.encode(EnvelopeCodec.toWire(envelope))));
    strip.findViewById(R.id.e2ee_button_decrypt).performClick();
  }

  private boolean composeAffordancesAreLit() {
    return strip.<View>findViewById(R.id.e2ee_button_clear_text).getVisibility() == View.VISIBLE;
  }

  /**
   * Anti-vacuity, in the direction that matters: with nothing done, the harness really does commit
   * into the messenger's field. Without this, a test that finds the host field empty proves only
   * that the harness is not wired up.
   */
  @Test
  public void aaWithNoRedirectRaisedTypingReallyDoesReachTheMessenger() {
    assertFalse("precondition: the redirect starts down", connection.isUsingOtherIC());

    connection.commitText("an ordinary message", 1);

    assertEquals("the harness must route to the host when the redirect is down, or nothing below "
        + "is measuring anything", "an ordinary message", hostField.received.toString());
  }

  /** And the message really does decrypt, so the test below is not passing on a failed decrypt. */
  @Test
  public void abTheMessageReallyDecrypts() throws Exception {
    pasteAndPressDecrypt(messageFromBob());

    assertEquals("precondition: the peer's plaintext must be on the strip", RECEIVED,
        compose.getText().toString());
  }

  /**
   * The finding.
   *
   * <p>Fails at 13fb158 with the reply in the messenger's field: the decrypt path renders the
   * plaintext and lights the compose affordances without raising the redirect.
   */
  @Test
  public void thereplyToAdecryptedMessageMustNotGoIntoTheMessengersOwnField() throws Exception {
    pasteAndPressDecrypt(messageFromBob());

    assertEquals("precondition: the decrypted message must be on screen in the compose box",
        RECEIVED, compose.getText().toString());
    assertTrue("precondition: and the strip must be claiming the user composes here - that claim "
        + "is what makes the state indistinguishable", composeAffordancesAreLit());

    connection.commitText(REPLY, 1);

    assertEquals("the reply to a decrypted message must not be committed into the messenger's own "
            + "text field in cleartext. The app rendered the peer's plaintext in its own compose "
            + "box and lit the clear button beside it; nothing on screen says the next keystroke "
            + "belongs to the messenger", "", hostField.received.toString());
    // Where in the box is not the property - setText leaves the cursor at 0, so the reply is
    // inserted ahead of the message it answers. That it is in the box at all is.
    assertTrue("and it belongs in the strip's compose box: " + compose.getText(),
        compose.getText().toString().contains(REPLY));
  }

  /** Stated as the invariant rather than only as its consequence. */
  @Test
  public void thestripMayNotClaimTheUserComposesHereWhileTypingGoesToTheHost() throws Exception {
    pasteAndPressDecrypt(messageFromBob());

    assertEquals("the clear button and the encoding selector are the app's only statement that "
            + "typing lands in the keyboard, so they may not be lit while it does not",
        composeAffordancesAreLit(), connection.isUsingOtherIC());
  }

  /**
   * The same arm with an empty box: a re-invite from a contact already known.
   *
   * <p>{@code decryptMessageAndShowMessageInMainInputField} lights the affordances on this path
   * too, and here there is not even a rendered message to look at - only the first reply the user
   * types to a contact whose session was just refreshed.
   */
  @Test
  public void thefirstReplyAfterAsessionUpdateMustNotGoIntoTheMessengersOwnField()
      throws Exception {
    pasteAndPressDecrypt(bundleFromBob());

    assertTrue("precondition: this arm lights the compose affordances too",
        composeAffordancesAreLit());

    connection.commitText(REPLY, 1);

    assertEquals("the first message to a contact whose session was just updated must not be typed "
        + "into the messenger", "", hostField.received.toString());
  }

  /**
   * The control for the change, in the other direction: showing the affordances must be what
   * raises the redirect, and hiding them must still not lower it.
   *
   * <p>Lowering on blur is the defect {@code TypingDestinationTest} exists for. Fixing the raise by
   * making the two symmetric would reintroduce it, so the asymmetry is asserted here where the
   * raise is.
   */
  @Test
  public void hidingTheComposeAffordancesStillDoesNotReturnTypingToTheHost() throws Exception {
    pasteAndPressDecrypt(messageFromBob());
    assertTrue("precondition: the redirect is up", connection.isUsingOtherIC());

    // Every strip screen switch sets the main layout GONE, which blurs the box and hides them.
    strip.showContactListForTest();
    strip.showMainViewForTest();

    connection.commitText(REPLY, 1);

    assertEquals("visiting another screen must not hand the user's typing to the messenger",
        "", hostField.received.toString());
  }
}
