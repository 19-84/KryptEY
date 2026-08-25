package com.amnesica.kryptey.inputmethod.latin;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.View;
import android.widget.EditText;

import com.amnesica.kryptey.inputmethod.R;
import com.amnesica.kryptey.inputmethod.latin.e2ee.E2EEStripView;
import com.amnesica.kryptey.inputmethod.latin.inputlogic.InputLogic;
import com.amnesica.kryptey.inputmethod.latin.utils.RecapitalizeStatus;
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
import java.util.Locale;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;

/**
 * The dismissal clean-up must be reached by dismissing the keyboard, not only by calling it.
 *
 * <p>Three separate pieces of work happen when the IME window goes away, and all three exist
 * because a decrypted message would otherwise outlive it: {@code E2EEStripView.onKeyboardHidden()}
 * empties the compose box, {@code RichInputConnection.forgetCachedText()} empties the buffers that
 * hold a verbatim copy of the last commit, and {@code RecapitalizeStatus.stop()} releases up to
 * 100KB of a recapitalised selection. The IME process is not recreated when the user switches apps,
 * so anything left in them is still in memory behind whatever they open next - which in this threat
 * model may be the messenger itself.
 *
 * <p>Every one of those three was already tested, and each test calls the method directly.
 * {@code PlaintextBufferClearingTest} says so outright - "This covers the one that can be driven
 * without an IME service" - and {@code CommitBufferClearingTest} builds a bare
 * {@code RichInputConnection} because "No IME service in a unit test". {@code StripGuardsTest},
 * {@code ScreenLifetimeTest} and {@code TypingDestinationTest} all call
 * {@code strip.onKeyboardHidden()} on a strip they hold themselves. So the suite proved the three
 * methods do their work and never proved that dismissing the keyboard calls any of them.
 *
 * <p>Measured, not argued. Three mutants, each applied alone at 32df444 and each leaving the whole
 * suite at 854 tests and 0 failures:
 *
 * <ul>
 *   <li>deleting {@code mE2EEStripView.onKeyboardHidden()} from {@code LatinIME.onWindowHidden};
 *   <li>deleting {@code mInputLogic.forgetCachedText()} from the same method;
 *   <li>deleting {@code mRecapitalizeStatus.stop()} from {@code InputLogic.forgetCachedText()}.
 * </ul>
 *
 * <p>These tests therefore drive a real {@code LatinIME} and call {@code onWindowHidden()} - the
 * method the framework calls - rather than the clean-up methods themselves.
 */
@RunWith(RobolectricTestRunner.class)
public class DismissalWiringTest {

  private static final String PLAINTEXT = "meet me at the safe house at nine";

  private ServiceController<LatinIME> controller;
  private LatinIME ime;

  private static Object get(final Object target, final Class<?> owner, final String name) {
    try {
      final Field f = owner.getDeclaredField(name);
      f.setAccessible(true);
      return f.get(target);
    } catch (final Exception e) {
      throw new AssertionError(e);
    }
  }

  private static SecretKey newKey() {
    try {
      final KeyGenerator generator = KeyGenerator.getInstance("AES");
      generator.init(256);
      return generator.generateKey();
    } catch (final java.security.NoSuchAlgorithmException e) {
      throw new AssertionError(e);
    }
  }

  @Before
  public void setUp() {
    SignalProtocolMain.resetForTest();
    SignalProtocolMain.testIsRunning = true;

    final Context context = RuntimeEnvironment.getApplication();
    final SharedPreferences preferences =
        context.getSharedPreferences("protocol", Context.MODE_PRIVATE);
    preferences.edit().clear().commit();

    final SecretKey key = newKey();
    final StorageHelper helper = new StorageHelper(context,
        (ctx, alreadyEncrypted) -> new GcmCryptoBox() {
          @Override
          protected SecretKey key() {
            return key;
          }
        });
    SignalProtocolMain.setStorageHelperFactoryForTest(ctx -> helper);

    controller = Robolectric.buildService(LatinIME.class);
    ime = controller.create().get();
    final View inputView = ime.onCreateInputView();
    ime.setInputView(inputView);
    ShadowLooper.idleMainLooper();
  }

  @After
  public void tearDown() {
    SignalProtocolMain.setStorageHelperFactoryForTest(null);
    SignalProtocolMain.resetForTest();
    SignalProtocolMain.testIsRunning = false;
  }

  private E2EEStripView liveStrip() {
    final E2EEStripView strip = (E2EEStripView) get(ime, LatinIME.class, "mE2EEStripView");
    assertNotNull("LatinIME must be holding a strip", strip);
    return strip;
  }

  private RichInputConnection liveConnection() {
    final RichInputConnection connection =
        (RichInputConnection) get(ime, LatinIME.class, "mRichInputConnection");
    assertNotNull("LatinIME must be holding its one long-lived connection", connection);
    return connection;
  }

  private static CharSequence buffer(final RichInputConnection connection, final String name) {
    return (CharSequence) get(connection, RichInputConnection.class, name);
  }

  /**
   * The visible copy: the decrypted message in the compose box.
   *
   * <p>Kills the mutant that deletes {@code mE2EEStripView.onKeyboardHidden()} from
   * {@code onWindowHidden}. Without it the message is still on screen the next time the keyboard is
   * raised, in whatever app that happens to be.
   */
  @Test
  public void dismissingTheKeyboardMustClearTheDecryptedMessageFromTheStrip() {
    final EditText composeBox = liveStrip().findViewById(R.id.e2ee_input_field);
    assertNotNull(composeBox);
    composeBox.setText(PLAINTEXT);
    ShadowLooper.idleMainLooper();
    assertEquals("precondition: the decrypted message is in the compose box",
        PLAINTEXT, composeBox.getText().toString());

    ime.onWindowHidden();
    ShadowLooper.idleMainLooper();

    assertEquals("dismissing the keyboard must empty the compose box - the IME process is not "
            + "recreated when the user switches apps, so the message would still be on screen the "
            + "next time the keyboard is raised, in whatever app that is",
        "", composeBox.getText().toString());
  }

  /**
   * The invisible copy: the verbatim last commit, on an object that lives as long as the service.
   *
   * <p>Kills the mutant that deletes {@code mInputLogic.forgetCachedText()} from
   * {@code onWindowHidden}. {@code CommitBufferClearingTest} proves the method empties this buffer;
   * nothing proved that dismissing the keyboard calls it.
   */
  @Test
  public void dismissingTheKeyboardMustClearTheImesOwnCopyOfTheCommittedText() {
    final RichInputConnection connection = liveConnection();
    connection.setShouldUseOtherIC(true);
    connection.setOtherIC(new EditText(RuntimeEnvironment.getApplication()));

    connection.commitText(PLAINTEXT, 1);
    assertEquals("precondition: the commit buffer holds the message",
        PLAINTEXT, buffer(connection, "mTempObjectForCommitText").toString());

    ime.onWindowHidden();
    ShadowLooper.idleMainLooper();

    assertEquals("a committed message must not outlive the dismissal in the commit buffer",
        0, buffer(connection, "mTempObjectForCommitText").length());
    assertEquals("nor in the cached text around the cursor",
        0, buffer(connection, "mCommittedTextBeforeComposingText").length());
  }

  /**
   * The largest copy: up to 100KB of a recapitalised selection.
   *
   * <p>Kills the mutant that deletes {@code mRecapitalizeStatus.stop()} from
   * {@code InputLogic.forgetCachedText()} - one level below the call site above, and invisible to
   * {@code PlaintextBufferClearingTest}, which builds its own {@code RecapitalizeStatus}.
   */
  @Test
  public void dismissingTheKeyboardMustClearTheRecapitalisationBuffer() {
    final InputLogic inputLogic = (InputLogic) get(ime, LatinIME.class, "mInputLogic");
    assertNotNull(inputLogic);
    final RecapitalizeStatus status =
        (RecapitalizeStatus) get(inputLogic, InputLogic.class, "mRecapitalizeStatus");
    assertNotNull(status);

    status.enable();
    status.start(0, PLAINTEXT.length(), PLAINTEXT, Locale.ENGLISH);
    assertEquals("precondition: the selection is held while recapitalisation is active",
        PLAINTEXT, get(status, RecapitalizeStatus.class, "mStringBefore"));

    ime.onWindowHidden();
    ShadowLooper.idleMainLooper();

    assertNull("dismissing the keyboard must release the recapitalised selection - it can be "
            + "100KB of decrypted text on an object that lives as long as the service",
        get(status, RecapitalizeStatus.class, "mStringBefore"));
    assertTrue("and the whole clean-up must have run, not part of it",
        buffer(liveConnection(), "mTempObjectForCommitText").length() == 0);
  }
}
