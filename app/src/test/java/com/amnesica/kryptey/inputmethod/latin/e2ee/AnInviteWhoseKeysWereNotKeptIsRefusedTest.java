package com.amnesica.kryptey.inputmethod.latin.e2ee;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.inputmethodservice.InputMethodService;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.InputConnection;
import android.widget.TextView;

import com.amnesica.kryptey.inputmethod.R;
import com.amnesica.kryptey.inputmethod.latin.RichInputConnection;
import com.amnesica.kryptey.inputmethod.latin.RichInputMethodManager;
import com.amnesica.kryptey.inputmethod.signalprotocol.Account;
import com.amnesica.kryptey.inputmethod.signalprotocol.SignalProtocolMain;
import com.amnesica.kryptey.inputmethod.signalprotocol.helper.StorageHelper;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

/**
 * An invite whose private halves did not reach disk must not be handed over.
 *
 * <p>The invite carries public keys — a one-time pre-key, and on the monthly path a freshly rotated
 * signed and Kyber pre-key — whose private halves this app has to keep. The write that keeps them
 * had its result discarded, and the export path's own comment explains why that is the one write
 * that cannot be best-effort: <em>the invite has to be carried to a messenger to be delivered, so a
 * reload always intervenes</em> before the peer's reply arrives.
 *
 * <p>So an invite exported while nothing can be written is dead on arrival, and silently. The peer's
 * first message cannot be decrypted; the app reports a generic decryption failure; and its standard
 * advice for a failed decrypt is to delete the contact and ask for a new invite — the
 * key-substitution window, reached out of a storage fault nobody mentioned. Refusing to hand the
 * invite over is the only outcome that does not end there.
 */
@RunWith(RobolectricTestRunner.class)
public class AnInviteWhoseKeysWereNotKeptIsRefusedTest {

  private E2EEStripView strip;
  private BaseInputConnection hostField;

  @Before
  public void setUp() throws Exception {
    final Application app = RuntimeEnvironment.getApplication();
    RichInputMethodManager.init(app);

    SignalProtocolMain.resetForTest();
    SignalProtocolMain.testIsRunning = true;
    SignalProtocolMain.initialize(null);
    SignalProtocolMain.setStorageStateForTest(StorageHelper.StorageState.READABLE);

    strip = new E2EEStripView(
        new ContextThemeWrapper(app, R.style.KeyboardTheme_LXX_Pure_Day), null);
    hostField = new BaseInputConnection(new View(app), true);
    final RichInputConnection connection = new RichInputConnection(new InputMethodService() {
      @Override
      public InputConnection getCurrentInputConnection() {
        return hostField;
      }
    });
    strip.setRichInputConnection(connection);
    strip.setListener(new E2EEStripView.Listener() {
      @Override
      public void onTextInput(final String rawText) {
        connection.commitText(rawText, 1);
      }

      @Override public void onSensitiveContentVisibilityChanged(final boolean sensitive) { }
    }, strip);
  }

  @After
  public void tearDown() {
    SignalProtocolMain.resetForTest();
    SignalProtocolMain.testIsRunning = false;
  }

  private String banner() {
    return String.valueOf(((TextView) strip.findViewById(R.id.e2ee_info_text)).getText());
  }

  @Test
  public void aninviteIsNotHandedOverWhenItsKeysCouldNotBeKept() {
    SignalProtocolMain.getInstance().setStorageHelperForTest(
        new StorageHelper(RuntimeEnvironment.getApplication(), (ctx, has) -> null) {
          @Override
          public boolean storeAllInformationInSharedPreferences(final Account account) {
            return false;
          }
        });

    strip.findViewById(R.id.e2ee_contact_list_invite_new_contact_button).performClick();

    final CharSequence delivered = hostField.getEditable();
    assertTrue("nothing may reach the host field: an invite whose private halves are not on disk "
            + "produces a reply this device cannot read, and the app's advice for an unreadable "
            + "message is to delete the contact and re-invite - which is the key swap. Delivered: "
            + delivered,
        delivered == null || delivered.toString().trim().isEmpty());
    assertTrue("and the user must be told why, on the surface that lasts: " + banner(),
        banner().contains("Do not send this invite"));
  }

  /** And a healthy store still hands one over, or the refusal has eaten the feature. */
  @Test
  public void aninviteIsStillHandedOverWhenTheKeysAreKept() {
    com.amnesica.kryptey.inputmethod.signalprotocol.storage.TestStores.writesLand();

    strip.findViewById(R.id.e2ee_contact_list_invite_new_contact_button).performClick();

    assertNotNull(hostField.getEditable());
    assertTrue("a working store must still produce an invite",
        hostField.getEditable().toString().trim().length() > 0);
  }
}
