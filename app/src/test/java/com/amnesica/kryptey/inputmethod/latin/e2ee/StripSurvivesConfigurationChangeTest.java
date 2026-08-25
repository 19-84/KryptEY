package com.amnesica.kryptey.inputmethod.latin.e2ee;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;

import com.amnesica.kryptey.inputmethod.R;
import com.amnesica.kryptey.inputmethod.latin.LatinIME;
import com.amnesica.kryptey.inputmethod.latin.RichInputConnection;
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
import org.robolectric.android.controller.ServiceController;
import org.robolectric.shadows.ShadowLooper;
import org.signal.libsignal.protocol.SignalProtocolAddress;

import java.lang.reflect.Field;
import java.util.ArrayList;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;

/**
 * What a configuration change does to the strip's security state.
 *
 * <p>The E2EE surface is a view. The framework destroys and rebuilds an IME's input view on every
 * configuration change: {@code InputMethodService.onConfigurationChanged} runs {@code
 * resetStateForNewConfiguration}, which calls {@code initViews()} - clearing {@code mViewsCreated}
 * - and then re-shows the window, and {@code showWindow} re-runs {@code onCreateInputView()}.
 * {@code KeyboardSwitcher.onCreateInputView} inflates a brand new {@code input_view}, so a brand
 * new {@code E2EEStripView} with brand new fields. KryptEY reaches the same place by its own route
 * as well: {@code LatinIME.onConfigurationChanged} calls {@code
 * KeyboardSwitcher.updateKeyboardTheme}, which on any {@code uiMode} change calls {@code
 * setInputView(onCreateInputView(uiMode))} itself. That second route is the one this test drives,
 * because it is entirely KryptEY's own code and needs no window token.
 *
 * <p>A configuration change is not a privileged event. Any application may call {@code
 * setRequestedOrientation} on its own activity at any moment, and the messenger is an ordinary
 * application. Battery saver and the sunset schedule flip {@code uiMode} with no app involved at
 * all.
 *
 * <p>So the question this asks about every piece of state on that view is the one the branch keeps
 * asking: does the security property survive everything the messenger can cause?
 */
@RunWith(RobolectricTestRunner.class)
public class StripSurvivesConfigurationChangeTest {

  private static final String SECRET_BEFORE = "the meeting is at ";
  private static final String SECRET_AFTER = "nine, bring the key";

  private ServiceController<LatinIME> controller;
  private LatinIME ime;
  private RichInputConnection connection;

  private Account victim;
  private SignalProtocolAddress peerAddress;
  private String attackerBundle;

  private static Object get(final Object target, final Class<?> owner, final String name) {
    try {
      final Field f = owner.getDeclaredField(name);
      f.setAccessible(true);
      return f.get(target);
    } catch (final Exception e) {
      throw new AssertionError(e);
    }
  }

  /**
   * A storage helper whose crypto box is a plain JCE key, so no Keystore is involved.
   *
   * <p>Real persistence matters here rather than being a convenience. {@code LatinIME.setInputView}
   * calls {@code SignalProtocolMain.initialize}/{@code reloadAccount} every time an input view is
   * created, so a rebuilt view reloads the account from disk. With the production Keystore-backed
   * helper - which has no JVM implementation - that reload yields nothing and the fixture
   * evaporates for reasons that have nothing to do with the defect. Persisting for real is what
   * makes the assertion about the banner, and not about the harness.
   */
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

    final Context context = org.robolectric.RuntimeEnvironment.getApplication();
    final SharedPreferences preferences =
        context.getSharedPreferences("protocol", Context.MODE_PRIVATE);
    preferences.edit().clear().commit();

    // Three identities: the peer the user really talks to, an attacker offering a substitute
    // bundle for the peer's address, and the user. Built with no storage helper at all, so each
    // initialize() really does mint a new identity rather than reloading the previous one.
    SignalProtocolMain.initialize(null);
    final Account peer = SignalProtocolMain.getInstance().getAccount();
    peerAddress = ProtocolAddresses.of(peer.getSignalProtocolAddress().getName(),
        peer.getDeviceId());
    final String peerBundle = SignalProtocolMain.exportOwnKeyBundle();

    SignalProtocolMain.initialize(null);
    attackerBundle = SignalProtocolMain.exportOwnKeyBundle();

    SignalProtocolMain.initialize(null);
    final Account fresh = SignalProtocolMain.getInstance().getAccount();
    assertTrue(SignalProtocolMain.processPreKeyResponseMessage(
        EnvelopeCodec.fromWire(peerBundle), peerAddress));

    final ArrayList<Contact> contacts = new ArrayList<>();
    contacts.add(new Contact("Bob", "Jones", peerAddress.getName(), peerAddress.getDeviceId(),
        false));
    fresh.setContactList(contacts);

    // Put the user's account on disk and make every later reload go through the same store, so the
    // IME's own initialize/reloadAccount behaves as it does on a device.
    final StorageHelper helper = jvmHelper(context, newKey());
    helper.storeAllInformationInSharedPreferences(fresh);
    SignalProtocolMain.setStorageHelperFactoryForTest(ctx -> helper);
    SignalProtocolMain.getInstance().setStorageHelperForTest(helper);

    controller = Robolectric.buildService(LatinIME.class);
    ime = controller.create().get();

    // Exactly what the framework does when it first raises the keyboard.
    final View inputView = ime.onCreateInputView();
    ime.setInputView(inputView);
    ShadowLooper.idleMainLooper();

    connection = (RichInputConnection) get(ime, LatinIME.class, "mRichInputConnection");
    assertNotNull("the IME's one long-lived input connection", connection);

    // The IME reloaded the account from disk, so re-read it rather than holding the pre-store copy.
    victim = SignalProtocolMain.getInstance().getAccount();
    assertNotNull("the IME must have loaded the stored account, not minted a new one", victim);
    assertEquals("and it must be the same user, or the fixture did not round-trip",
        1, victim.getContactList().size());
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

  private static EditText composeBoxOf(final E2EEStripView strip) {
    final EditText box = strip.findViewById(R.id.e2ee_input_field);
    assertNotNull("the compose field must inflate, or this test measures nothing", box);
    return box;
  }

  private static TextView bannerOf(final E2EEStripView strip) {
    final TextView banner = strip.findViewById(R.id.e2ee_info_text);
    assertNotNull(banner);
    return banner;
  }

  /** Read from the live account each time: the IME reloads it on every input-view creation. */
  private Contact bob() {
    return SignalProtocolMain.getInstance().getAccount().getContactList().get(0);
  }

  /**
   * The configuration change, driven through the real {@code LatinIME.onConfigurationChanged}.
   *
   * <p>Night mode rather than orientation only because Robolectric's service controller has no
   * window token, so the framework's own re-show never runs here; KryptEY's {@code
   * updateKeyboardTheme} route reaches the identical {@code setInputView(onCreateInputView(...))}.
   */
  private void configurationChange() {
    final Configuration conf = new Configuration(ime.getResources().getConfiguration());
    conf.uiMode = (conf.uiMode & ~Configuration.UI_MODE_NIGHT_MASK)
        | Configuration.UI_MODE_NIGHT_YES;
    ime.onConfigurationChanged(conf);
    ShadowLooper.idleMainLooper();
  }

  // ---------------------------------------------------------------------------------------------
  // Anti-vacuity: the harness really does rebuild the strip, and really does route typing.
  // ---------------------------------------------------------------------------------------------

  /** If this fails, nothing below is measuring a configuration change. */
  @Test
  public void aconfigurationChangeReplacesTheStrip() {
    final E2EEStripView before = liveStrip();
    configurationChange();
    assertNotSame("the input view is rebuilt, so the strip is a different object",
        before, liveStrip());
  }

  /** If this fails, nothing below is measuring where a keystroke goes. */
  @Test
  public void abtypingIntoTheComposeBoxWorksAtAll() {
    final EditText compose = composeBoxOf(liveStrip());
    assertTrue("the compose field must be focusable in touch mode", compose.requestFocus());
    connection.commitText(SECRET_BEFORE, 1);
    assertEquals(SECRET_BEFORE, compose.getText().toString());
  }

  // ---------------------------------------------------------------------------------------------
  // 1. Plaintext left behind on the discarded strip.
  // ---------------------------------------------------------------------------------------------

  /**
   * A draft typed before a configuration change must not outlive the keyboard being dismissed.
   *
   * <p>{@code mOtherIC} - the connection that carries a keystroke into the compose box - is a field
   * on {@code RichInputConnection}, which {@code InputLogic} creates once in the {@code LatinIME}
   * constructor and which therefore lives as long as the service. The compose box it points at is a
   * field on the strip, which lives until the next configuration change. So after one rotation the
   * long-lived connection is still holding the DISCARDED box, with the user's draft in it.
   *
   * <p>Nothing can reach that box again. {@code onKeyboardHidden} - the app's own statement that
   * "decrypted plaintext must not outlive the keyboard being dismissed" - runs {@code
   * clearDecryptedContent} on the LIVE strip, and empties a field that was already empty. The
   * plaintext sits in a detached view, held alive by {@code mOtherIC}, for the rest of the
   * keyboard process: through the app switch, through every subsequent dismissal, in whatever app
   * the keyboard next serves.
   */
  @Test
  public void aDraftLeftOnTheDiscardedStripMustNotSurviveTheKeyboardBeingDismissed() {
    final E2EEStripView first = liveStrip();
    final EditText discarded = composeBoxOf(first);
    assertTrue(discarded.requestFocus());
    connection.commitText(SECRET_BEFORE, 1);
    assertEquals("precondition: the draft is in the compose box",
        SECRET_BEFORE, discarded.getText().toString());

    configurationChange();

    // Everything the app has for forgetting plaintext.
    ime.onWindowHidden();
    ShadowLooper.idleMainLooper();

    assertEquals("no decrypted plaintext may outlive the keyboard being dismissed",
        "", discarded.getText().toString());
  }

  /**
   * After a configuration change the next keystroke must not go into the discarded compose box.
   *
   * <p>{@code setShouldUseOtherIC} and {@code setOtherIC} are called only by the compose box's own
   * focus listener. A configuration change replaces the box without going near either, so the
   * redirect stays raised and stays aimed at the old view. The user is looking at an empty compose
   * box on a freshly drawn strip; what they type goes into a view that is no longer on screen.
   *
   * <p>The visible half is that their typing disappears. The half that matters here is that every
   * character of it is being accumulated somewhere no clearing path in the app knows about - the
   * previous test is the same defect after the user gives up and dismisses the keyboard.
   */
  @Test
  public void afterAConfigurationChangeTypingMustNotGoIntoTheDiscardedComposeBox() {
    final EditText discarded = composeBoxOf(liveStrip());
    assertTrue(discarded.requestFocus());

    configurationChange();

    final EditText live = composeBoxOf(liveStrip());
    assertNotSame("precondition: the box on screen is a new one", discarded, live);

    connection.commitText(SECRET_AFTER, 1);

    assertEquals("what the user types must not land in the box that is no longer on screen",
        "", discarded.getText().toString());
  }

  // ---------------------------------------------------------------------------------------------
  // 2. The standing security warning.
  // ---------------------------------------------------------------------------------------------

  /**
   * A standing identity-change warning must survive a configuration change.
   *
   * <p>{@code mWarningStanding} is a plain {@code boolean} field on the strip, and the banner it
   * guards is text in a {@code TextView} on the same strip. Its javadoc is explicit that it is
   * "cleared by two deliberate user actions and nothing else" and that "nothing the messenger can
   * cause clears it" - and REVIVAL.md carries a whole family of defects that are exactly the
   * messenger erasing this banner. A configuration change does not erase the banner; it throws away
   * the object the banner is drawn on, which has the same effect and reaches it without touching
   * any of the paths those fixes guard.
   *
   * <p>The threat has not gone anywhere. {@code SignalProtocolMain.hasUnacceptedIdentityChange} is
   * still true after the rebuild - the offered key is still pending, the user has still not
   * compared a safety number - so the app still knows the user is in the state the warning exists
   * to describe. It simply stops saying so, and the new strip writes an ordinary banner over the
   * space where the warning was.
   */
  @Test
  public void aStandingIdentityWarningMustSurviveAConfigurationChange() {
    final E2EEStripView before = liveStrip();
    SignalProtocolMain.importOutOfBandKeyBundle(attackerBundle, peerAddress);
    assertTrue("precondition: a change must be pending",
        SignalProtocolMain.hasUnacceptedIdentityChange(peerAddress));
    assertTrue("precondition: the warning must have been posted",
        before.warnIfIdentityChanged(bob()));
    final String warned = bannerOf(before).getText().toString();
    assertTrue("precondition: the banner must name the danger", warned.length() > 0);

    configurationChange();

    assertTrue("precondition: the pending change is still unaccepted after the rebuild",
        SignalProtocolMain.hasUnacceptedIdentityChange(peerAddress));

    assertEquals("a security warning must survive a configuration change",
        warned, bannerOf(liveStrip()).getText().toString());
  }

  // ---------------------------------------------------------------------------------------------
  // 3. FLAG_SECURE.
  // ---------------------------------------------------------------------------------------------

  private boolean windowIsSecure() {
    final android.app.Dialog dialog = ime.getWindow();
    assertNotNull("the IME window must exist, or this test measures nothing", dialog);
    assertNotNull(dialog.getWindow());
    return (dialog.getWindow().getAttributes().flags
        & android.view.WindowManager.LayoutParams.FLAG_SECURE) != 0;
  }

  /**
   * FLAG_SECURE must not be stuck on for the rest of the keyboard's life by a configuration change.
   *
   * <p>The flag is a property of the IME's window, which survives a configuration change; whether
   * it should be set is computed by {@code E2EEStripView.isShowingSensitiveContent}, which is a
   * property of the strip, which does not. The strip is the only thing that ever calls {@code
   * onSensitiveContentVisibilityChanged}, and it does so from {@code showOnlyUIView} - a screen
   * switch. Rebuilding the strip is not a screen switch, so the new strip never speaks, and the
   * window keeps whatever the discarded one last said.
   *
   * <p>This direction is the harmless one - the window stays MORE protected than it needs to be -
   * and it is reported as the same root cause rather than as a separate danger. It is still a
   * defect against a property the code states outright: "Applied only while such a screen is up,
   * not for the keyboard's whole life, so ordinary typing elsewhere still screenshots normally.
   * That is a product judgement as much as a security one and it is stated here rather than
   * buried." After one rotation on the chat-log screen, it is applied for the keyboard's whole
   * life.
   */
  @Test
  public void flagSecureMustNotBeStuckOnByAConfigurationChange() {
    liveStrip().showMessagesListForTest();
    assertTrue("precondition: the chat log counts as sensitive",
        liveStrip().isShowingSensitiveContent());
    assertTrue("precondition: showing the chat log must raise FLAG_SECURE", windowIsSecure());

    configurationChange();

    assertEquals("precondition: the rebuilt strip shows nothing sensitive",
        false, liveStrip().isShowingSensitiveContent());
    assertEquals("FLAG_SECURE must track what is on screen, not what was on the discarded strip",
        false, windowIsSecure());
  }
}
