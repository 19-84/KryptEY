package com.amnesica.kryptey.inputmethod.latin.e2ee;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.InputType;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.TextView;

import com.amnesica.kryptey.inputmethod.R;
import com.amnesica.kryptey.inputmethod.latin.LatinIME;
import com.amnesica.kryptey.inputmethod.signalprotocol.Account;
import com.amnesica.kryptey.inputmethod.signalprotocol.SignalProtocolMain;
import com.amnesica.kryptey.inputmethod.signalprotocol.chat.Contact;
import com.amnesica.kryptey.inputmethod.signalprotocol.encoding.EnvelopeCodec;
import com.amnesica.kryptey.inputmethod.signalprotocol.helper.StorageHelper;
import com.amnesica.kryptey.inputmethod.signalprotocol.storage.GcmCryptoBox;
import com.amnesica.kryptey.inputmethod.signalprotocol.util.ProtocolAddresses;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.shadows.ShadowLooper;
import org.signal.libsignal.protocol.SignalProtocolAddress;

import java.lang.reflect.Field;
import java.util.ArrayList;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;

/**
 * What the two action buttons say while the password-field guard is on, and after it comes off.
 *
 * <p>{@code afterTextChanged} decides whether Encrypt and Decrypt are enabled by reading the TEXT
 * currently in the info banner: {@code disablesActionButtons} names two strings, and every other
 * banner - including every one nobody has thought of yet - is read as a reason to enable both. The
 * password-field notice is one of those. {@code setHostFieldIsPassword(true)} writes {@code
 * INFO_PASSWORD_FIELD} onto the banner, the watcher sees a string that is not one of the two, and
 * both buttons come ON - on the one field type where {@code actionsAreAvailable()} forbids both
 * actions outright. Focusing a password box is the only event in this app that turns the action
 * buttons on by telling the user they are off.
 *
 * <p>The mirror is worse, because it outlives the field. {@code setHostFieldIsPassword(false)}
 * lowers the flag and writes nothing, so the notice stays on the banner - the app's only lasting
 * surface - after the user has moved to an ordinary field where both actions are back on and
 * working. {@code setHostFieldIsPassword}'s own comment already records that pairing as a defect
 * ("The strip was left reading 'encryption is turned off here' while the actions were back on and
 * working"), and {@code StripWarningErasureTest.apasswordFieldDoesNotWedgeTheStrip} asserts it must
 * not happen - but only along the route where a warning is standing, and along that route {@code
 * setInfoUnlessWarned} refuses to write the notice at all, so the assertion can never see it. The
 * plain route, with no warning anywhere, is the one the user takes.
 *
 * <p>Both are messenger-reachable and neither needs a forged anything: {@code
 * LatinIME.onStartInputViewInternal} hands the strip the host field's {@code inputType} on every
 * input session, and the messenger declares the {@code inputType} of every field it presents. One
 * "re-enter your PIN" box, then back to the chat box, and the strip reads "Encryption and
 * decryption are turned off here" for the rest of the session.
 *
 * <p>Harness copied from {@link StripCarriedStateAdversarialTest}.
 */
@RunWith(RobolectricTestRunner.class)
public class PasswordFieldButtonStateTest {

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

  private static StorageHelper jvmHelper(final Context context, final SecretKey key) {
    return new StorageHelper(context, (ctx, alreadyEncrypted) -> new GcmCryptoBox() {
      @Override
      protected SecretKey key() {
        return key;
      }
    });
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
  public void setUp() throws Exception {
    SignalProtocolMain.resetForTest();
    SignalProtocolMain.testIsRunning = true;

    final Context context = RuntimeEnvironment.getApplication();
    final SharedPreferences preferences =
        context.getSharedPreferences("protocol", Context.MODE_PRIVATE);
    preferences.edit().clear().commit();

    SignalProtocolMain.initialize(null);
    final Account peer = SignalProtocolMain.getInstance().getAccount();
    final SignalProtocolAddress peerAddress = ProtocolAddresses.of(
        peer.getSignalProtocolAddress().getName(), peer.getDeviceId());
    final String peerBundle = SignalProtocolMain.exportOwnKeyBundle();

    SignalProtocolMain.initialize(null);
    final Account fresh = SignalProtocolMain.getInstance().getAccount();
    assertTrue(SignalProtocolMain.processPreKeyResponseMessage(
        EnvelopeCodec.fromWire(peerBundle), peerAddress));

    final ArrayList<Contact> contacts = new ArrayList<>();
    contacts.add(new Contact("Bob", "Jones", peerAddress.getName(), peerAddress.getDeviceId(),
        false));
    fresh.setContactList(contacts);

    final StorageHelper helper = jvmHelper(context, newKey());
    helper.storeAllInformationInSharedPreferences(fresh);
    SignalProtocolMain.setStorageHelperFactoryForTest(ctx -> helper);
    SignalProtocolMain.getInstance().setStorageHelperForTest(helper);

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

  private String banner() {
    final TextView view = liveStrip().findViewById(R.id.e2ee_info_text);
    assertNotNull(view);
    return view.getText().toString();
  }

  private View encryptButton() {
    return liveStrip().findViewById(R.id.e2ee_button_encrypt);
  }

  private View decryptButton() {
    return liveStrip().findViewById(R.id.e2ee_button_decrypt);
  }

  private Contact bob() {
    return SignalProtocolMain.getInstance().getAccount().getContactList().get(0);
  }

  private static EditorInfo passwordField() {
    final EditorInfo info = new EditorInfo();
    info.inputType = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD;
    info.packageName = "com.messenger";
    return info;
  }

  private static EditorInfo ordinaryField() {
    final EditorInfo info = new EditorInfo();
    info.inputType = InputType.TYPE_CLASS_TEXT;
    info.packageName = "com.messenger";
    return info;
  }

  /** The premise: the strip really is over a password box, and really does say so. */
  @Test
  public void thepremise() {
    ime.onStartInputView(passwordField(), false);
    ShadowLooper.idleMainLooper();

    assertFalse("precondition: the guard must be armed over a password box",
        liveStrip().actionsAreAvailable());
    assertTrue("precondition: and the banner must be the notice that says so. Shown: " + banner(),
        E2EEStripView.INFO_PASSWORD_FIELD.equals(banner()));
  }

  /**
   * A button offering an action the strip has just refused must not be pressable-looking.
   *
   * <p>The buttons are the control surface; the banner is prose beside it. Leaving both lit while
   * {@code actionsAreAvailable()} is false is the app inviting the press it will refuse, on the
   * field type whose whole point is that neither action may run there.
   */
  @Test
  public void theactionButtonsMustBeOffOverApasswordField() {
    ime.onStartInputView(passwordField(), false);
    ShadowLooper.idleMainLooper();

    assertFalse("Encrypt must not be enabled where encryption is refused. Banner: " + banner(),
        encryptButton().isEnabled());
    assertFalse("Decrypt must not be enabled where decryption is refused. Banner: " + banner(),
        decryptButton().isEnabled());
  }

  /**
   * And the notice must not outlive the guard it describes.
   *
   * <p>Moving from a password box to an ordinary one is one {@code onStartInputView} inside the
   * same app - no keyboard dismissal, no rebuild, nothing that resets anything else.
   */
  @Test
  public void thenoticeMustNotSurviveTheFieldItDescribes() {
    ime.onStartInputView(passwordField(), false);
    ShadowLooper.idleMainLooper();
    ime.onStartInputView(ordinaryField(), false);
    ShadowLooper.idleMainLooper();

    assertTrue("precondition: the actions are back on over an ordinary field",
        liveStrip().actionsAreAvailable());
    assertFalse("the banner must not still claim encryption is turned off here while it is on. "
            + "Shown: " + banner(),
        E2EEStripView.INFO_PASSWORD_FIELD.equals(banner()));
  }

  /**
   * And the buttons must come back with it, or the refusal has outlived its reason too.
   *
   * <p>With a recipient chosen, so the banner the strip goes back to is one that enables them -
   * this asserts the guard comes OFF, not that the strip happens to be in its opening state.
   */
  @Test
  public void theactionButtonsMustComeBackWithTheOrdinaryField() {
    liveStrip().selectContact(bob());
    ime.onStartInputView(passwordField(), false);
    ShadowLooper.idleMainLooper();
    ime.onStartInputView(ordinaryField(), false);
    ShadowLooper.idleMainLooper();

    assertTrue("Decrypt must be usable again over an ordinary field. Banner: " + banner(),
        decryptButton().isEnabled());
    assertTrue("Encrypt must be usable again over an ordinary field. Banner: " + banner(),
        encryptButton().isEnabled());
  }

  /**
   * And an ordinary input session must not erase a banner the strip legitimately computed.
   *
   * <p>The other half of the same repair, and the reason it is conditional. {@code
   * onStartInputView} runs on every field the keyboard is raised over, and the messenger decides
   * when that is - so a repair that repainted the banner on every session would hand it a one-call
   * eraser for "Keybundle detected: click on decrypt to save the content", which is written by the
   * clipboard listener at exactly the moment before the user taps the chat box to paste. That is
   * the next gesture in the app's own workflow, not an unusual one.
   */
  @Test
  public void anordinaryInputSessionMustNotEraseAbannerTheStripComputed() {
    liveStrip().onClipboardChangedForTest();
    final String detected = banner();
    assertTrue("precondition: the clipboard listener owns the banner. Shown: " + detected,
        detected.contains("Keybundle detected"));

    ime.onStartInputView(ordinaryField(), false);
    ShadowLooper.idleMainLooper();

    assertEquals("an ordinary input session must leave the banner alone", detected, banner());
  }

  /**
   * The guard must hold when the banner is not the strip's to write.
   *
   * <p>{@code setHostFieldIsPassword} posts its notice through {@code setInfoUnlessWarned}, which
   * refuses over a standing security warning - correctly, because the warning is the only lasting
   * surface this app has. But the button state is derived from that banner, so refusing to write it
   * also means refusing to change the buttons: over a password box, with a substitution warning on
   * screen, both actions stay lit. That is the state where BOTH of the app's reasons to refuse are
   * live at once.
   */
  @Test
  public void theactionButtonsMustBeOffOverApasswordFieldEvenUnderAstandingWarning() {
    liveStrip().setWarningMessageForTest(
        "Someone offered a different key for Bob. It was refused and is not in use.");

    ime.onStartInputView(passwordField(), false);
    ShadowLooper.idleMainLooper();

    assertFalse("precondition: the guard is armed", liveStrip().actionsAreAvailable());
    assertFalse("Encrypt must be off over a password box whatever owns the banner. Banner: "
        + banner(), encryptButton().isEnabled());
    assertFalse("Decrypt must be off over a password box whatever owns the banner. Banner: "
        + banner(), decryptButton().isEnabled());
  }
}
