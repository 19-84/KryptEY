package com.amnesica.kryptey.inputmethod.latin.e2ee;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.InputType;
import android.view.View;
import android.view.inputmethod.EditorInfo;


import com.amnesica.kryptey.inputmethod.R;
import com.amnesica.kryptey.inputmethod.latin.InputAttributes;
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
 * The other half of "password field", and the guard reads only one of them.
 *
 * <p>Android spells a password box two ways. {@code textPassword} (and its web and numeric
 * siblings) masks the characters; {@code textVisiblePassword} does not, which is what a Wi-Fi
 * passphrase box, a "show password" login, a recovery phrase and a PIN box with a reveal toggle all
 * declare. Both are password boxes. {@code TextView} keeps them apart only because one of them has
 * to draw dots.
 *
 * <p>This app already knows that. {@code InputAttributes.mIsPasswordField} is {@code
 * isPasswordInputType(inputType) || isVisiblePasswordInputType(inputType)}, and {@code
 * KeyboardId.passwordInput()} is the same disjunction - so the keyboard suppresses suggestions and
 * picks its password layout for both. The security gate does not: {@code
 * LatinIME.onStartInputViewInternal} passes the strip only {@code
 * InputTypeUtils.isPasswordInputType(editorInfo.inputType)}, the narrow half, and
 * {@code isVisiblePasswordInputType} - the sibling that exists in the same file for exactly this
 * distinction - is never asked. So over {@code textVisiblePassword} the app suppresses its
 * suggestions, draws its password keyboard, and offers every E2EE action anyway.
 *
 * <p>{@code inputType} is declared by the host, and in this threat model the host is the messenger.
 * It does not even need to lie: {@code textVisiblePassword} is what real password boxes in other
 * apps already use, so the gap is open on ordinary fields the user genuinely must not have a key
 * bundle or a decrypted message typed into.
 *
 * <p>Harness copied from {@link InviteOverApasswordFieldTest}, whose assertions this mirrors on the
 * field type the guard misses.
 */
@RunWith(RobolectricTestRunner.class)
public class VisiblePasswordFieldGuardTest {

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

  /** A Wi-Fi passphrase box, a "show password" login, a recovery-phrase box. */
  private static EditorInfo visiblePasswordField() {
    final EditorInfo info = new EditorInfo();
    info.inputType =
        InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD;
    info.packageName = "com.messenger";
    return info;
  }

  private void theHostFieldIsAvisiblePasswordBox() {
    ime.onStartInputView(visiblePasswordField(), false);
    ShadowLooper.idleMainLooper();
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
   * The app's own classifier, asked about the same {@code EditorInfo} the strip is handed.
   *
   * <p>This is the premise the rest of the file rests on, and it is the app's answer rather than
   * mine: {@code InputAttributes} - built by {@code SettingsValues} on every input session from
   * this exact object - says the field is a password field. The guard is fed a different predicate
   * about the same integer.
   */
  @Test
  public void theappsownClassifierCallsThisApasswordField() {
    assertTrue("precondition: InputAttributes.mIsPasswordField is the disjunction, and the "
            + "keyboard already treats textVisiblePassword as a password box",
        new InputAttributes(visiblePasswordField(), false).mIsPasswordField);
  }

  /** So the guard the strip runs on must be armed for it too. */
  @Test
  public void theguardMustBeArmedOverAvisiblePasswordField() {
    theHostFieldIsAvisiblePasswordBox();

    assertFalse("textVisiblePassword is a password box - the characters are shown, that is the "
            + "whole difference - and the E2EE actions must be refused there for the same reason "
            + "they are refused over textPassword",
        liveStrip().actionsAreAvailable());
  }

  /**
   * And the buttons must say so, on the same reasoning as {@link PasswordFieldButtonStateTest}.
   *
   * <p>With a recipient chosen, so the banner the strip is showing is one that ENABLES them. Without
   * that this passes on the opening "No contact chosen" banner, which disables both for a reason
   * that has nothing to do with the field - the assertion would hold with the guard deleted.
   */
  @Test
  public void theactionButtonsMustBeOffOverAvisiblePasswordField() {
    liveStrip().selectContact(
        SignalProtocolMain.getInstance().getAccount().getContactList().get(0));
    theHostFieldIsAvisiblePasswordBox();

    assertFalse("Encrypt must not be offered where encryption must not run",
        liveStrip().<View>findViewById(R.id.e2ee_button_encrypt).isEnabled());
    assertFalse("Decrypt must not be offered where decryption must not run",
        liveStrip().<View>findViewById(R.id.e2ee_button_decrypt).isEnabled());
  }

  /**
   * The premise for the invite half: this route really does commit a bundle to the host.
   *
   * <p>Without it the assertion below would pass on an install where Invite does nothing at all.
   */
  @Test
  public void thepremiseThatInviteCommitsAbundle() {
    pressInvite();

    assertNotNull("precondition: pressing Invite over an ordinary field commits a key bundle to "
        + "whatever has focus", committed);
    assertTrue("precondition: and it is a substantial one, not an empty string",
        committed.length() > 64);
  }

  /**
   * The reproduced harm: a whole key bundle typed into another app's visible password box.
   *
   * <p>Same two taps, same {@code mListener.onTextInput}, same consequence
   * {@code InviteOverApasswordFieldTest} spells out - handed to that app's storage, autofill and
   * whatever it syncs. The only difference from that test is one bit of the {@code inputType} the
   * host declared.
   */
  @Test
  public void aninviteMustNotBeCommittedIntoAvisiblePasswordField() {
    theHostFieldIsAvisiblePasswordBox();

    pressInvite();

    assertNull("a key bundle must not be typed into another app's password box, masked or not. "
        + "Committed: " + committed, committed);
  }

  /** Refusing silently is not enough: the user pressed a button and must be told why. */
  @Test
  public void therefusalMustBeExplainedOverAvisiblePasswordField() {
    theHostFieldIsAvisiblePasswordBox();

    pressInvite();

    assertEquals("and it must be the password-field reason, which is the true one",
        E2EEStripView.INFO_PASSWORD_FIELD, ShadowToast.getTextOfLatestToast());
  }

}
