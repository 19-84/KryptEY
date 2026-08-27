package com.amnesica.kryptey.inputmethod.latin.e2ee;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.inputmethodservice.InputMethodService;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.InputConnection;

import com.amnesica.kryptey.inputmethod.R;
import com.amnesica.kryptey.inputmethod.latin.RichInputConnection;
import com.amnesica.kryptey.inputmethod.latin.RichInputMethodManager;
import com.amnesica.kryptey.inputmethod.signalprotocol.SignalProtocolMain;
import com.amnesica.kryptey.inputmethod.signalprotocol.encoding.EnvelopeCodec;
import com.amnesica.kryptey.inputmethod.signalprotocol.helper.StorageHelper;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

/**
 * The invite goes to whatever app holds the cursor, which is what makes out-of-band exchange work.
 *
 * <p>The help tells the user they can hand an invite over outside the messenger by putting the
 * cursor in another app first. That is a claim about behaviour, and it rests on the invite leaving
 * the keyboard by the same route an ordinary encrypted message does rather than staying somewhere
 * private to the strip.
 *
 * <p><b>The route is the listener, not the connection</b>, and getting that wrong is why an earlier
 * attempt at this test was deleted rather than committed. The strip hands text to
 * {@code Listener.onTextInput}; in production that listener is {@code LatinIME}, which wraps it in
 * an {@code Event} and commits it through {@code InputLogic}. A test whose listener ignores the text
 * observes nothing arriving and looks exactly like a broken invite button.
 */
@RunWith(RobolectricTestRunner.class)
public class TheInviteGoesToTheHostAppTest {

  private E2EEStripView strip;
  private BaseInputConnection hostField;
  private RichInputConnection connection;

  @Before
  public void setUp() throws Exception {
    RichInputMethodManager.init(RuntimeEnvironment.getApplication());
    SignalProtocolMain.resetForTest();
    SignalProtocolMain.testIsRunning = true;
    SignalProtocolMain.initialize(null);
    SignalProtocolMain.setStorageStateForTest(StorageHelper.StorageState.READABLE);

    strip = new E2EEStripView(new ContextThemeWrapper(
        RuntimeEnvironment.getApplication(), R.style.KeyboardTheme_LXX_Pure_Day), null);

    hostField = new BaseInputConnection(new View(RuntimeEnvironment.getApplication()), true);
    connection = new RichInputConnection(new InputMethodService() {
      @Override
      public InputConnection getCurrentInputConnection() {
        return hostField;
      }
    });
    strip.setRichInputConnection(connection);

    strip.setListener(new E2EEStripView.Listener() {
      @Override
      public void onTextInput(final String rawText) {
        // What LatinIME does with it: commit through the connection, which is pointed at the host.
        connection.commitText(rawText, 1);
      }

      @Override public void onSensitiveContentVisibilityChanged(final boolean sensitive) { }
    }, strip);
  }

  @After
  public void tearDown() {
    if (strip != null) strip.clear();
    SignalProtocolMain.resetForTest();
    SignalProtocolMain.testIsRunning = false;
  }

  @Test
  public void pressingInviteHandsAkeyBundleToTheAppHoldingTheCursor() throws Exception {
    strip.findViewById(R.id.e2ee_contact_list_invite_new_contact_button).performClick();

    final CharSequence delivered = hostField.getEditable();
    assertNotNull("pressing Invite must hand something to the app holding the cursor", delivered);
    final String text = delivered.toString().trim();
    assertFalse("the host's field must not be left empty. The help tells the user to put the cursor "
            + "in another app and press this; nothing arriving there is the help being wrong, which "
            + "is worse than saying nothing - it is telling someone to do the safest thing "
            + "available and having it not work.",
        text.isEmpty());

    assertNotNull("what is handed over must be a key bundle the other side can accept, not a "
            + "placeholder or an error line",
        EnvelopeCodec.fromWire(text).getPreKeyResponse());
  }
}
