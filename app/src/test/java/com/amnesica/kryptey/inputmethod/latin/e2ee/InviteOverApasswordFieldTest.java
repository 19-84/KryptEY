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
}
