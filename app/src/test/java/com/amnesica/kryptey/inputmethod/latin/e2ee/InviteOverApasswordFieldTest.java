package com.amnesica.kryptey.inputmethod.latin.e2ee;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.InputType;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;

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
import org.robolectric.shadows.ShadowToast;
import org.signal.libsignal.protocol.SignalProtocolAddress;

import java.lang.reflect.Field;
import java.util.ArrayList;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;

/**
 * The fourth thing that writes into the host field, and the only one that does not ask permission.
 *
 * <p>{@code mHostFieldIsPassword} exists because "decrypting writes the plaintext into whatever
 * field has focus, and writing a decrypted message into another app's password box hands it to that
 * app's own storage, autofill and whatever it syncs". {@code actionsAreAvailable()} is how that fact
 * is asked for, and {@code encryptAndSendInputFieldContent} and {@code decryptMessageInClipboard}
 * both ask it as their first statement.
 *
 * <p>{@code sendPreKeyResponseMessageToApplication} does not ask, and it is the path that writes the
 * LONGEST string this app produces into the host field - the whole encoded key bundle, straight
 * through {@code mListener.onTextInput}. It also stages it in the strip's own compose box, which
 * {@code setHostFieldIsPassword(true)} had just emptied for precisely this reason.
 *
 * <p>Nothing gates the route either: the Invite button lives on the contact-list screen, and the
 * button that opens that screen is the one {@code UnreadableStorageScreensTest} records as
 * deliberately not disabled. So over a password box the strip says "This is a password field.
 * Encryption and decryption are turned off here" and two taps later commits a key bundle into it.
 * The banner is the app's claim; this is the path the claim does not cover.
 *
 * <p>Harness copied from {@link StripCarriedStateAdversarialTest}, so the strip has the IME's real
 * input connection behind it and the field's {@code inputType} arrives the way the messenger sends
 * it. Only the listener is swapped, for one that records what would have been committed.
 */
@RunWith(RobolectricTestRunner.class)
public class InviteOverApasswordFieldTest {

  private ServiceController<LatinIME> controller;
  private LatinIME ime;
  private String committed;

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

    final E2EEStripView strip = liveStrip();
    strip.setListener(new E2EEStripView.Listener() {
      @Override public void onTextInput(final String rawText) { committed = rawText; }
      @Override public void onSensitiveContentVisibilityChanged(final boolean sensitive) { }
    }, strip);
    ShadowToast.reset();
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

  private EditText composeBox() {
    return liveStrip().findViewById(R.id.e2ee_input_field);
  }

  private static EditorInfo passwordField() {
    final EditorInfo info = new EditorInfo();
    info.inputType = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD;
    info.packageName = "com.messenger";
    return info;
  }

  private void theHostFieldIsApasswordBox() {
    ime.onStartInputView(passwordField(), false);
    ShadowLooper.idleMainLooper();
    assertTrue("precondition: the guard must actually be armed",
        !liveStrip().actionsAreAvailable());
    ShadowToast.reset();
    committed = null;
  }

  /** What the user does: open the contact list, press Invite. Both buttons are always live. */
  private void pressInvite() {
    final View recipient = liveStrip().findViewById(R.id.e2ee_button_select_recipient);
    assertNotNull(recipient);
    recipient.performClick();
    final View invite =
        liveStrip().findViewById(R.id.e2ee_contact_list_invite_new_contact_button);
    assertNotNull(invite);
    invite.performClick();
  }

  /**
   * The premise, asserted rather than assumed: this route really does commit a bundle to the host.
   *
   * <p>Without it the guard tests below would pass on an install where Invite does nothing at all.
   */
  @Test
  public void thepremise() {
    pressInvite();

    assertNotNull("precondition: pressing Invite over an ordinary field commits a key bundle to "
        + "whatever has focus", committed);
    assertTrue("precondition: and it is a substantial one, not an empty string",
        committed.length() > 64);
  }

  /**
   * And over a password box it must not.
   *
   * <p>The same refusal its two siblings give, for the same reason, on the path that writes more
   * into the host field than either of them.
   */
  @Test
  public void aninviteMustNotBeCommittedIntoApasswordField() {
    theHostFieldIsApasswordBox();

    pressInvite();

    assertNull("a key bundle must not be typed into another app's password box - that hands it to "
        + "that app's storage, autofill and whatever it syncs. Committed: " + committed, committed);
  }

  /** Refusing silently is not enough: the user pressed a button and must be told why. */
  @Test
  public void therefusalMustBeExplained() {
    theHostFieldIsApasswordBox();

    pressInvite();

    assertEquals("and it must be the password-field reason, which is the true one",
        E2EEStripView.INFO_PASSWORD_FIELD, ShadowToast.getTextOfLatestToast());
  }

  /** Nor may it be staged in the strip's own box, which the guard had just emptied. */
  @Test
  public void aninviteMustNotBeStagedInTheComposeBoxOverApasswordField() {
    theHostFieldIsApasswordBox();

    pressInvite();

    assertEquals("the compose box must stay empty: setHostFieldIsPassword cleared it on the way "
            + "in, and re-filling it puts content back on a screen the guard had just cleared",
        0, composeBox().getText().length());
  }

  /**
   * Encrypt and Decrypt must refuse over a password box too, and nothing tests that they do.
   *
   * <p>Three call sites ask {@code actionsAreAvailable()} before doing anything: Invite, Encrypt and
   * Decrypt. A round deleted all three at once and ran the whole {@code latin} package: only the
   * Invite site's tests went red. The other two refusals were enforced by nothing — every test that
   * mentions the password field asserts the predicate's value or the buttons' {@code isEnabled()},
   * and none presses a lit-looking control while the host field is a password box.
   *
   * <p>{@code View.performClick()} invokes a listener whatever the enabled state, and this file's
   * history records a state where both buttons stayed lit over a password box with these guards the
   * only thing refusing. The repaint is reliable today, so this is defence in depth — but it is the
   * layer {@code INFO_PASSWORD_FIELD}'s own sentence is written about: <em>"a decrypted message is
   * never written into another app's password box"</em>. {@code decryptMessageInClipboard} is what
   * would write it, and it was the untested one.
   *
   * <p>Driven through the real IME, so the strip has a live input connection and the refusal is what
   * stops the commit rather than a null dereference on the way to it.
   */
  @Test
  public void encryptRefusesOverApasswordBox() {
    theHostFieldIsApasswordBox();
    committed = null;

    final View encrypt = liveStrip().findViewById(R.id.e2ee_button_encrypt);
    assertNotNull(encrypt);
    org.robolectric.shadows.ShadowToast.reset();
    encrypt.performClick();
    ShadowLooper.idleMainLooper();

    // The TOAST, not the absence of a commit. Nothing commits here either way - with the guard gone
    // the press falls through to "choose a contact first" and still writes nothing - so asserting
    // an empty host field passes in both worlds and measures the fixture rather than the refusal.
    // Which sentence is shown is the one observable that differs, and it is also the thing the user
    // gets: the reason.
    assertEquals("pressing Encrypt over a password box must be refused BY THE PASSWORD GUARD, and "
            + "say so. A dark button is a hint and not a control - performClick reaches the listener "
            + "regardless - so this sentence is the evidence the guard ran. Shown: "
            + org.robolectric.shadows.ShadowToast.getTextOfLatestToast(),
        E2EEStripView.INFO_PASSWORD_FIELD,
        org.robolectric.shadows.ShadowToast.getTextOfLatestToast());
    assertNull("...and nothing may reach the host field", committed);
  }

  @Test
  public void decryptRefusesOverApasswordBox() {
    theHostFieldIsApasswordBox();
    committed = null;

    final android.content.ClipboardManager clipboard =
        (android.content.ClipboardManager) RuntimeEnvironment.getApplication()
            .getSystemService(Context.CLIPBOARD_SERVICE);
    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("", "anything at all"));

    final View decrypt = liveStrip().findViewById(R.id.e2ee_button_decrypt);
    assertNotNull(decrypt);
    org.robolectric.shadows.ShadowToast.reset();
    decrypt.performClick();
    ShadowLooper.idleMainLooper();

    // Same reasoning as its sibling: with the guard gone the press reads the clipboard, fails to
    // decrypt what is there, and still commits nothing - so only the sentence distinguishes the two
    // worlds. This is the call site INFO_PASSWORD_FIELD's promise is about.
    assertEquals("pressing Decrypt over a password box must be refused by the password guard and "
            + "say so - this is the call site that would write a decrypted message into the host "
            + "field, and the sentence promises in so many words that it never does. Shown: "
            + org.robolectric.shadows.ShadowToast.getTextOfLatestToast(),
        E2EEStripView.INFO_PASSWORD_FIELD,
        org.robolectric.shadows.ShadowToast.getTextOfLatestToast());
    assertNull("...and nothing may reach the host field", committed);
  }
}
