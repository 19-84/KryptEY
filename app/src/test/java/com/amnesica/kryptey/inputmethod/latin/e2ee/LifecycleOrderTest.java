package com.amnesica.kryptey.inputmethod.latin.e2ee;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;
import android.inputmethodservice.InputMethodService;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.InputConnection;
import android.widget.EditText;
import android.widget.TextView;

import com.amnesica.kryptey.inputmethod.R;
import com.amnesica.kryptey.inputmethod.latin.LatinIME;
import com.amnesica.kryptey.inputmethod.latin.RichInputConnection;
import com.amnesica.kryptey.inputmethod.latin.RichInputMethodManager;
import com.amnesica.kryptey.inputmethod.signalprotocol.Account;
import com.amnesica.kryptey.inputmethod.signalprotocol.SignalProtocolMain;
import com.amnesica.kryptey.inputmethod.signalprotocol.helper.StorageHelper;
import com.amnesica.kryptey.inputmethod.signalprotocol.storage.GcmCryptoBox;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.shadows.ShadowLooper;

import java.lang.reflect.Field;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;

/**
 * Two steps that are correct, reached, and worth nothing if they happen at the wrong moment.
 *
 * <p>Neither is a missing operation. Both are present at HEAD and both were measured as unpinned:
 * moving either one past its neighbour left the whole suite green, so nothing in 874 tests
 * described WHEN they have to run.
 *
 * <ul>
 *   <li>{@code LatinIME.setInputView} builds the storage layer BEFORE asking the strip to choose
 *       its opening banner. {@code storageState()} answers {@code NONE} when there is no storage
 *       helper, and the helper is created by nothing but {@code initialize}/{@code reloadAccount} -
 *       so refreshing the banner first makes a user whose identity cannot be decrypted read "No
 *       contact chosen". {@code refreshOpeningMessage}'s own javadoc records that this is why the
 *       call was moved out of the view's constructor; nothing checked that it stayed moved.
 *   <li>{@code sendEncryptedMessageToApplication} lowers the compose-box redirect BEFORE handing
 *       the ciphertext to the IME. While the redirect is up every commit goes into the strip's own
 *       box, so committing first means the message the user pressed send on is typed back into the
 *       keyboard and then wiped by the clear that follows - the messenger receives nothing at all.
 * </ul>
 */
@RunWith(RobolectricTestRunner.class)
public class LifecycleOrderTest {

  private static final String CIPHERTEXT = "kAgxN2s9wQ==";

  private ServiceController<LatinIME> controller;

  private static SecretKey newKey() {
    try {
      final KeyGenerator generator = KeyGenerator.getInstance("AES");
      generator.init(256);
      return generator.generateKey();
    } catch (final java.security.NoSuchAlgorithmException e) {
      throw new AssertionError(e);
    }
  }

  private static StorageHelper jvmHelper(final Context context, final SecretKey key) {
    return new StorageHelper(context, (ctx, alreadyEncrypted) -> new GcmCryptoBox() {
      @Override
      protected SecretKey key() {
        return key;
      }
    });
  }

  private static Object get(final Object target, final Class<?> owner, final String name) {
    try {
      final Field f = owner.getDeclaredField(name);
      f.setAccessible(true);
      return f.get(target);
    } catch (final Exception e) {
      throw new AssertionError(e);
    }
  }

  @Before
  public void setUp() {
    SignalProtocolMain.resetForTest();
    SignalProtocolMain.testIsRunning = true;
  }

  @After
  public void tearDown() {
    if (controller != null) controller = null;
    SignalProtocolMain.setStorageHelperFactoryForTest(null);
    SignalProtocolMain.resetForTest();
    SignalProtocolMain.testIsRunning = false;
  }

  /**
   * The banner a lost Keystore key must produce, on the raise that produces it.
   *
   * <p>Driven through a COLD keyboard - the strip's first {@code setInputView} - because that is
   * the only raise where the ordering is observable: on every later one the storage helper already
   * exists and both orders answer the same. It is also the raise that matters, since the strip is
   * built exactly once unless the theme changes.
   *
   * <p>The state is reached through the real path rather than {@code setStorageStateForTest}: that
   * seam short-circuits the very check this test is about, so a forced state would report
   * {@code UNREADABLE} with or without a storage helper and the test would prove nothing.
   */
  @Test
  public void acoldRaiseMustNotReportUndecryptableStorageAsAnEmptyApp() {
    final Context context = RuntimeEnvironment.getApplication();
    final SharedPreferences preferences =
        context.getSharedPreferences("protocol", Context.MODE_PRIVATE);
    preferences.edit().clear().commit();

    // An identity on disk, sealed under a key this device is about to lose.
    final SecretKey lost = newKey();
    SignalProtocolMain.initialize(null);
    final Account existing = SignalProtocolMain.getInstance().getAccount();
    assertNotNull("precondition: an account to store", existing);
    jvmHelper(context, lost).storeAllInformationInSharedPreferences(existing);

    // The keyboard process starts fresh and the Keystore key is gone.
    SignalProtocolMain.resetForTest();
    SignalProtocolMain.testIsRunning = true;
    final SecretKey survivor = newKey();
    assertEquals("precondition: the store must really be unreadable, not merely absent",
        StorageHelper.StorageState.UNREADABLE, jvmHelper(context, survivor).storageState());

    // A fresh helper per call, so the singleton has to build one for itself - which is the step
    // whose position relative to refreshOpeningMessage is under test.
    SignalProtocolMain.setStorageHelperFactoryForTest(ctx -> jvmHelper(ctx, survivor));

    controller = Robolectric.buildService(LatinIME.class);
    final LatinIME ime = controller.create().get();
    final View inputView = ime.onCreateInputView();
    ime.setInputView(inputView);
    ShadowLooper.idleMainLooper();

    final E2EEStripView strip = (E2EEStripView) get(ime, LatinIME.class, "mE2EEStripView");
    assertNotNull("LatinIME must be holding a strip", strip);
    final TextView banner = strip.findViewById(R.id.e2ee_info_text);
    assertNotNull(banner);

    assertEquals("the first raise after a key loss must say the identity cannot be unlocked. "
            + "Choosing the banner before the storage layer exists answers NONE, which is "
            + "byte-identical to a healthy empty install - and the obvious response to an "
            + "apparently empty app is to re-invite everyone, replacing every pin already compared",
        E2EEStripView.INFO_STORAGE_UNREADABLE, banner.getText().toString());
  }

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

  /**
   * Send must lower the redirect before the ciphertext is committed, not after.
   *
   * <p>The redirect is what makes the compose box the destination of every commit. Handing the
   * ciphertext to the IME while it is still up types the message back into the keyboard's own box,
   * where the clear two lines later wipes it - so pressing send does nothing whatsoever and the
   * only visible change is the box emptying, which is exactly what a successful send looks like.
   */
  @Test
  public void sendMustCommitTheCiphertextToTheHostAppAndNotBackIntoTheComposeBox() {
    final Context app = RuntimeEnvironment.getApplication();
    RichInputMethodManager.init(app);

    final HostField hostField = new HostField(new View(app));
    final InputMethodService messengerSide = new InputMethodService() {
      @Override
      public InputConnection getCurrentInputConnection() {
        return hostField;
      }
    };
    final RichInputConnection connection = new RichInputConnection(messengerSide);

    final E2EEStripView strip = new E2EEStripView(
        new ContextThemeWrapper(app, R.style.KeyboardTheme_LXX_Pure_Day), null);
    strip.setRichInputConnection(connection);
    // The IME's side of the send: LatinIME.onTextInput commits the text through this same
    // connection (LatinIME.onTextInput -> InputLogic.onTextInput -> mConnection.commitText).
    strip.setListener(rawText -> connection.commitText(rawText, 1), strip);

    final EditText compose = strip.findViewById(R.id.e2ee_input_field);
    assertNotNull(compose);
    assertTrue("the compose field must be focusable in touch mode", compose.requestFocus());
    assertTrue("precondition: composing inside the keyboard", connection.isUsingOtherIC());
    compose.setText("the meeting is at nine");

    strip.sendEncryptedMessageToApplicationForTest(CIPHERTEXT);

    assertEquals("the ciphertext must reach the messenger. Committed while the redirect is still "
            + "up it lands in the strip's own compose box instead, and the clear that follows "
            + "erases it - the message is never sent and nothing on screen says so",
        CIPHERTEXT, hostField.received.toString());
    assertEquals("and it must not have been typed back into the compose box",
        "", compose.getText().toString());
  }
}
