package com.amnesica.kryptey.inputmethod.latin.e2ee;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.text.InputType;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.TextView;

import com.amnesica.kryptey.inputmethod.R;
import com.amnesica.kryptey.inputmethod.keyboard.KeyboardTheme;
import com.amnesica.kryptey.inputmethod.latin.LatinIME;
import com.amnesica.kryptey.inputmethod.latin.RichInputConnection;
import com.amnesica.kryptey.inputmethod.compat.PreferenceManagerCompat;
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
 * Adversarial review of the state CARRIED across an input-view rebuild (commit 21dd3ae).
 *
 * <p>Harness copied from {@link StripSurvivesConfigurationChangeTest}: identities are built before
 * the IME, and the account is persisted through the {@code setStorageHelperFactoryForTest} seam so
 * the reload that {@code LatinIME.setInputView} performs on every rebuild finds the same user.
 *
 * <p>That commit answers "what must survive the rebuild" with {@code surrenderState}/{@code
 * adoptState}. These tests ask the two questions it does not: what does the carried state OVERWRITE
 * on the way in, and what state on that same view is still dropped.
 */
@RunWith(RobolectricTestRunner.class)
public class StripCarriedStateAdversarialTest {

  private ServiceController<LatinIME> controller;
  private LatinIME ime;
  private RichInputConnection connection;

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

    final StorageHelper helper = jvmHelper(context, newKey());
    helper.storeAllInformationInSharedPreferences(fresh);
    SignalProtocolMain.setStorageHelperFactoryForTest(ctx -> helper);
    SignalProtocolMain.getInstance().setStorageHelperForTest(helper);

    controller = Robolectric.buildService(LatinIME.class);
    ime = controller.create().get();

    final View inputView = ime.onCreateInputView();
    ime.setInputView(inputView);
    ShadowLooper.idleMainLooper();

    connection = (RichInputConnection) get(ime, LatinIME.class, "mRichInputConnection");
    assertNotNull("the IME's one long-lived input connection", connection);
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

  private Contact bob() {
    return SignalProtocolMain.getInstance().getAccount().getContactList().get(0);
  }

  /** The night-mode rebuild, exactly as the existing lifecycle test drives it. */
  private void configurationChange() {
    final Configuration conf = new Configuration(ime.getResources().getConfiguration());
    conf.uiMode = (conf.uiMode & ~Configuration.UI_MODE_NIGHT_MASK)
        | Configuration.UI_MODE_NIGHT_YES;
    ime.onConfigurationChanged(conf);
    ShadowLooper.idleMainLooper();
  }

  private static EditorInfo passwordField() {
    final EditorInfo info = new EditorInfo();
    info.inputType = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD;
    info.packageName = "com.messenger";
    return info;
  }

  // ---------------------------------------------------------------------------------------------
  // 1. The carried banner overwrites a banner the NEW strip legitimately computed.
  // ---------------------------------------------------------------------------------------------

  /**
   * A warning raised BY the rebuild must not be erased by the banner carried into it.
   *
   * <p>{@code LatinIME.setInputView} calls {@code refreshOpeningMessage} and then {@code
   * adoptState}, and the comment says the order is deliberate "so a carried warning wins over the
   * opening banner". The reverse pairing was not considered: when the carried banner is NOT a
   * warning and the opening banner IS one, {@code adoptState} takes the {@code else if
   * (carried.banner.length() > 0)} branch and writes the stale ordinary banner straight over the
   * fresh warning - while {@code mWarningStanding}, set by {@code refreshOpeningMessage}, stays
   * true.
   *
   * <p>{@code refreshOpeningMessage} can raise exactly one warning, and it is the one whose whole
   * point is that it must not look like an ordinary empty app. Its own javadoc: a user whose
   * storage cannot be decrypted "used to see 'No contact chosen' - byte-identical to a healthy
   * install that simply has no contacts yet ... the obvious response to an apparently empty app is
   * to re-invite everyone. Re-inviting replaces every pin they had already compared, so a key loss
   * silently becomes a fresh trust-on-first-use window for every contact at once."
   *
   * <p>Reachable without the messenger: the store becomes unreadable when the Keystore key is
   * invalidated - a lock-screen change or a new biometric enrolment - which does not restart the
   * keyboard process, and the next rebuild is the sunset night-mode flip. With the messenger it
   * needs no waiting at all: any app may call {@code setRequestedOrientation} on itself.
   */
  @Test
  public void aWarningRaisedByTheRebuildMustNotBeErasedByTheCarriedBanner() {
    assertEquals("precondition: the strip opens with the ordinary banner",
        E2EEStripView.INFO_NO_CONTACT_CHOSEN_TEXT, bannerOf(liveStrip()).getText().toString());

    // The identity store stops being decryptable while the keyboard is up.
    SignalProtocolMain.setStorageStateForTest(StorageHelper.StorageState.UNREADABLE);

    configurationChange();

    assertEquals("the rebuild's own storage warning must not be overwritten by the carried banner",
        E2EEStripView.INFO_STORAGE_UNREADABLE, bannerOf(liveStrip()).getText().toString());
  }

  /**
   * The same defect seen from the other side: the strip must not be wedged by it.
   *
   * <p>{@code refreshOpeningMessage} raised the warning through {@code setWarningMessage}, so
   * {@code mWarningStanding} is true on the new strip; {@code adoptState} then replaced the TEXT
   * without touching the flag. That is the exact wedge {@code resetChosenContactAndInfoText}
   * documents - "the flag still said a warning was on screen, so nothing could replace the text,
   * while the text itself said 'No contact chosen'" - reached here through the new carry path
   * instead.
   */
  @Test
  public void theCarriedBannerMustNotLeaveTheWarningFlagStandingOverAnOrdinaryBanner() {
    SignalProtocolMain.setStorageStateForTest(StorageHelper.StorageState.UNREADABLE);
    configurationChange();

    // The store recovers (the user re-authenticates); only the flag/text mismatch is left.
    SignalProtocolMain.setStorageStateForTest(StorageHelper.StorageState.READABLE);
    final E2EEStripView strip = liveStrip();

    final boolean warningTextOnScreen = E2EEStripView.INFO_STORAGE_UNREADABLE
        .equals(bannerOf(strip).getText().toString());
    assertEquals("the banner may only be frozen while a warning is actually on screen",
        warningTextOnScreen, !strip.mayOverwriteInfoBanner());
  }

  // ---------------------------------------------------------------------------------------------
  // 2. State on the strip that the rebuild still drops: the password-field guard.
  // ---------------------------------------------------------------------------------------------

  /**
   * The password-field guard must survive the rebuild that {@code onStartInputView} itself causes.
   *
   * <p>{@code mHostFieldIsPassword} is a plain field on the strip, exactly like {@code
   * mWarningStanding} was, and it is not in {@code CarriedState}. It is re-established only by
   * {@code LatinIME.onStartInputViewInternal} - and that method sets it on the strip that exists
   * when it starts, then, eight lines later, calls {@code switcher.updateKeyboardTheme(...)}, which
   * on any theme change calls {@code mLatinIME.setInputView(onCreateInputView(uiMode))}
   * SYNCHRONOUSLY. The strip that comes back has the flag at its default of false, and nothing
   * later in that method sets it again.
   *
   * <p>So this needs no framework behaviour and no waiting for sunset: the user changes the
   * keyboard theme in KryptEY's own settings (which fires no configuration change), and the very
   * next field the messenger focuses - a field whose {@code inputType} the messenger declares -
   * arms the guard on a strip that is thrown away microseconds later. {@code actionsAreAvailable}
   * then returns true over the messenger's password box, which is the state the guard's javadoc
   * exists to forbid: "decrypting writes the plaintext into whatever field has focus, and writing a
   * decrypted message into another app's password box hands it to that app's own storage, autofill
   * and whatever it syncs."
   */
  @Test
  public void thePasswordFieldGuardMustSurviveTheRebuildStartInputViewItselfCauses() {
    final SharedPreferences devicePrefs =
        PreferenceManagerCompat.getDeviceSharedPreferences(ime);
    KeyboardTheme.saveKeyboardThemeId(KeyboardTheme.THEME_ID_PURE_DAY, devicePrefs);
    ShadowLooper.idleMainLooper();

    final E2EEStripView before = liveStrip();
    ime.onStartInputView(passwordField(), false);
    ShadowLooper.idleMainLooper();

    assertNotSame("precondition: onStartInputView's own theme update rebuilt the strip",
        before, liveStrip());
    assertFalse("encryption and decryption must stay off over a password field",
        liveStrip().actionsAreAvailable());
  }

  /**
   * The strip must never say the actions are off while they are on.
   *
   * <p>The banner IS carried, so after the rebuild above the user reads {@code INFO_PASSWORD_FIELD}
   * - "This is a password field. Encryption and decryption are turned off here, so a decrypted
   * message is never written into another app's password box" - on a strip where {@code
   * actionsAreAvailable()} has gone back to true. Carrying the reassurance while dropping the thing
   * it is reassuring about is worse than dropping both, and it is the same pairing {@code
   * setHostFieldIsPassword} already records once: "The strip was left reading 'encryption is turned
   * off here' while the actions were back on and working."
   */
  @Test
  public void theStripMustNotClaimTheActionsAreOffWhileTheyAreOn() {
    final SharedPreferences devicePrefs =
        PreferenceManagerCompat.getDeviceSharedPreferences(ime);
    KeyboardTheme.saveKeyboardThemeId(KeyboardTheme.THEME_ID_PURE_DAY, devicePrefs);
    ShadowLooper.idleMainLooper();

    ime.onStartInputView(passwordField(), false);
    ShadowLooper.idleMainLooper();

    final E2EEStripView strip = liveStrip();
    final boolean saysOff =
        E2EEStripView.INFO_PASSWORD_FIELD.equals(bannerOf(strip).getText().toString());
    assertFalse("the banner must not announce a guard that is no longer in force",
        saysOff && strip.actionsAreAvailable());
  }

  /**
   * Same guard, reached by the plainest rebuild there is.
   *
   * <p>Separate from the test above so that the finding does not stand or fall on the theme route:
   * once {@code setHostFieldIsPassword(true)} has been called, ANY rebuild drops it, including the
   * night-mode one the existing lifecycle test drives.
   */
  @Test
  public void thePasswordFieldGuardMustSurviveANightModeRebuild() {
    liveStrip().setHostFieldIsPassword(true);
    assertFalse("precondition: the guard is armed", liveStrip().actionsAreAvailable());

    configurationChange();

    assertFalse("a rebuild must not re-enable the actions over a password field",
        liveStrip().actionsAreAvailable());
  }

  // ---------------------------------------------------------------------------------------------
  // 3. The user's encoding choice.
  // ---------------------------------------------------------------------------------------------

  /**
   * The chosen wire encoding must survive the rebuild.
   *
   * <p>{@code encodingMethod} is a field on the strip initialised to {@code Encoder.RAW}, and it is
   * not carried. Choosing FAIRYTALE is the user saying "do not let this look like ciphertext in the
   * transcript"; a rebuild silently puts it back to RAW, and the next message the same user sends
   * in the same conversation goes out as an obvious encrypted blob. The messenger picks the moment,
   * because it picks when the configuration changes.
   */
  @Test
  public void theChosenEncodingMustSurviveARebuild() {
    final E2EEStripView before = liveStrip();
    final View toFairyTale = before.findViewById(R.id.e2ee_button_select_encoding_raw);
    assertNotNull(toFairyTale);
    toFairyTale.performClick();
    final Object chosen = get(before, E2EEStripView.class, "encodingMethod");
    assertEquals("precondition: the user picked the cover encoding", "FAIRYTALE",
        String.valueOf(chosen));

    configurationChange();

    assertEquals("the user's encoding choice must not be silently reset by a rebuild",
        "FAIRYTALE", String.valueOf(get(liveStrip(), E2EEStripView.class, "encodingMethod")));
  }

  // ---------------------------------------------------------------------------------------------
  // 4. Anti-vacuity for the recipient, which IS dropped and which this test says is safe.
  // ---------------------------------------------------------------------------------------------

  /**
   * Documents, rather than reports, the recipient reset.
   *
   * <p>{@code chosenContact} is not carried, so a rebuild mid-conversation drops the recipient.
   * That direction is fail-safe and this asserts why: the draft carried into the new strip cannot
   * be sent to the wrong person, because re-choosing anyone at all is a change from {@code null}
   * and {@code setChosenContact} empties the compose box on any change.
   */
  @Test
  public void aRebuildDropsTheRecipientAndReChoosingOneClearsTheCarriedDraft() {
    final E2EEStripView first = liveStrip();
    first.selectContact(bob());
    final EditText compose = composeBoxOf(first);
    assertTrue(compose.requestFocus());
    connection.commitText("the meeting is at nine", 1);

    configurationChange();

    final E2EEStripView second = liveStrip();
    assertEquals("precondition: the draft was carried",
        "the meeting is at nine", composeBoxOf(second).getText().toString());
    assertEquals("precondition: the recipient was not", null,
        get(second, E2EEStripView.class, "chosenContact"));

    second.selectContact(bob());
    assertEquals("re-choosing a recipient must empty a draft staged for an unknown one",
        "", composeBoxOf(second).getText().toString());
  }

  /**
   * Anti-vacuity for the theme route: without a theme change, that same call does NOT rebuild.
   *
   * <p>This is what makes the reachability claim above a claim about KryptEY's code rather than
   * about the harness. {@code onStartInputView} on its own leaves the strip alone and the guard in
   * force; it is {@code updateKeyboardTheme}'s synchronous {@code setInputView}, eight lines after
   * the guard is armed, that replaces the strip underneath it.
   */
  @Test
  public void abWithoutAThemeChangeStartInputViewDoesNotRebuildAndTheGuardHolds() {
    final E2EEStripView before = liveStrip();
    ime.onStartInputView(passwordField(), false);
    ShadowLooper.idleMainLooper();

    assertEquals("no theme change, so no rebuild", before, liveStrip());
    assertFalse("and the guard is in force on the strip that armed it",
        liveStrip().actionsAreAvailable());
  }

  // ---------------------------------------------------------------------------------------------
  // 5. The carried warning, after the thing it warns about has been resolved.
  // ---------------------------------------------------------------------------------------------

  /**
   * A resolved warning must not be resurrected by a rebuild.
   *
   * <p>The mirror of the defect 21dd3ae fixes. {@code surrenderState} takes the banner TEXT
   * unconditionally, and {@code adoptState} re-posts it whenever it is non-empty, so the carried
   * text is whatever the outgoing banner happened to be holding - including a warning the user has
   * since dealt with. Pressing Verify is the deliberate response the whole flag exists to wait for:
   * it calls {@code clearStandingWarning} and dismisses the pending change, so afterwards {@code
   * hasUnacceptedIdentityChange} is false and the app's own predicate says there is nothing to warn
   * about. The stale TEXT left on the banner was self-correcting - it was the only thing left, the
   * flag was down, and the next post overwrote it, and before this commit the rebuild itself was
   * one of the things that overwrote it. Now the rebuild re-posts it instead.
   *
   * <p>What the user reads afterwards is not merely stale, it is false: {@code
   * INFO_IDENTITY_CHANGED_EXISTING} says "your messages still go to the key you already had ...
   * compare the number with them by voice", telling a user who has just compared that number to go
   * and compare it again. A security banner that reappears after being resolved is how a real one
   * stops being read.
   */
  @Test
  public void aWarningTheUserHasResolvedMustNotComeBackWithTheRebuild() {
    final E2EEStripView first = liveStrip();
    SignalProtocolMain.importOutOfBandKeyBundle(attackerBundle, peerAddress);
    assertTrue("precondition: a change is pending", first.warnIfIdentityChanged(bob()));
    final String warning = bannerOf(first).getText().toString();

    // The deliberate response: open the number and confirm it matches.
    first.showVerifyContactForTest(bob());
    final View verify = first.findViewById(R.id.e2ee_verify_contact_verify_button);
    assertNotNull(verify);
    verify.performClick();
    assertFalse("precondition: the app itself says there is nothing left to warn about",
        SignalProtocolMain.hasUnacceptedIdentityChange(peerAddress));

    configurationChange();

    assertNotSame("precondition: the strip was rebuilt", first, liveStrip());
    assertFalse("a warning the user has resolved must not be re-posted by a rebuild",
        warning.equals(bannerOf(liveStrip()).getText().toString()));
  }

  /** Anti-vacuity: the identity-change fixture used above really is pending. */
  @Test
  public void aaPendingIdentityChangeFixtureWorks() {
    SignalProtocolMain.importOutOfBandKeyBundle(attackerBundle, peerAddress);
    assertTrue(SignalProtocolMain.hasUnacceptedIdentityChange(peerAddress));
    assertTrue(liveStrip().warnIfIdentityChanged(bob()));
  }
}
