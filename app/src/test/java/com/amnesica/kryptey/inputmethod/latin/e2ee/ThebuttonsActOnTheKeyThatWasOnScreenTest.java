package com.amnesica.kryptey.inputmethod.latin.e2ee;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Context;
import android.view.ContextThemeWrapper;
import android.widget.ImageButton;

import com.amnesica.kryptey.inputmethod.R;
import com.amnesica.kryptey.inputmethod.signalprotocol.Account;
import com.amnesica.kryptey.inputmethod.signalprotocol.SignalProtocolMain;
import com.amnesica.kryptey.inputmethod.signalprotocol.chat.Contact;
import com.amnesica.kryptey.inputmethod.signalprotocol.encoding.EnvelopeCodec;
import com.amnesica.kryptey.inputmethod.signalprotocol.helper.StorageHelper;
import com.amnesica.kryptey.inputmethod.signalprotocol.storage.GcmCryptoBox;
import com.amnesica.kryptey.inputmethod.signalprotocol.storage.TestStores;
import com.amnesica.kryptey.inputmethod.signalprotocol.util.ProtocolAddresses;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.signal.libsignal.protocol.IdentityKey;
import org.signal.libsignal.protocol.SignalProtocolAddress;

import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;

/**
 * The verify screen shows a number and offers two buttons that act on a key. They were not bound to
 * each other.
 *
 * <p>The digits are painted once, from the account held at that moment. The hazard is
 * {@code reloadAccountIfStorageRecovered}, which runs on every keyboard raise while a store fault
 * stands and repaints nothing — and {@code onStartInputViewInternal} runs on any
 * {@code restartInput} or focus move, without the window ever hiding, so the messenger picks the
 * moment by presenting a field. (A theme change replaces the account too, but its caller
 * {@code setInputView} calls {@code surrenderState} three statements later, which blanks the digits
 * and the binding together, so no press can land in that one.)
 *
 * <p>So a user can read a number aloud, hear it match, press Verify, and record "I compared this"
 * against a key that was never on screen. That is the one failure this trust model has no recovery
 * from: the badge is what the user checks <em>instead of</em> comparing again.
 * {@code verifyContactInContactList}'s only key check is {@code getIdentity(address) != null} —
 * "something is pinned", never "the pinned key is the one the digits were built from".
 *
 * <p>Reject is permanent and lands purely by address, so it is worse in the other direction: a press
 * meaning "this number is wrong" is recorded against whatever replaced it.
 */
@RunWith(RobolectricTestRunner.class)
public class ThebuttonsActOnTheKeyThatWasOnScreenTest {

  private Context context;
  private SecretKey key;
  private E2EEStripView strip;
  private Account victim;
  private Contact bob;
  private SignalProtocolAddress bobAddress;
  private String bobBundle;
  private String attackerBundle;

  private static SecretKey newKey() {
    try {
      final KeyGenerator generator = KeyGenerator.getInstance("AES");
      generator.init(256);
      return generator.generateKey();
    } catch (NoSuchAlgorithmException e) {
      throw new AssertionError(e);
    }
  }

  private StorageHelper helper() {
    return new StorageHelper(context, (ctx, hasExistingData) -> new GcmCryptoBox() {
      @Override
      protected SecretKey key() {
        return key;
      }
    });
  }

  @Before
  public void setUp() throws Exception {
    final Application app = RuntimeEnvironment.getApplication();
    context = app;
    context.getSharedPreferences("protocol", Context.MODE_PRIVATE).edit().clear().commit();

    SignalProtocolMain.resetForTest();
    SignalProtocolMain.testIsRunning = true;
    key = newKey();

    // Null context for the two peers, so each mints its own identity instead of loading the one the
    // previous initialize stored - otherwise all three keys are the same and nothing here differs.
    SignalProtocolMain.initialize(null);
    final Account peer = SignalProtocolMain.getInstance().getAccount();
    bobAddress = ProtocolAddresses.of(peer.getSignalProtocolAddress().getName(), peer.getDeviceId());
    bobBundle = SignalProtocolMain.exportOwnKeyBundle();

    SignalProtocolMain.initialize(null);
    attackerBundle = SignalProtocolMain.exportOwnKeyBundle();

    SignalProtocolMain.setStorageHelperFactoryForTest(ctx -> helper());
    SignalProtocolMain.initialize(context);
    victim = SignalProtocolMain.getInstance().getAccount();
    victim.setMessageLogLoader(ArrayList::new);
    TestStores.writesLand();

    bob = new Contact("Bob", "Jones", bobAddress.getName(), bobAddress.getDeviceId(), false);
    final ArrayList<Contact> contacts = new ArrayList<>();
    contacts.add(bob);
    victim.setContactList(contacts);

    strip = new E2EEStripView(
        new ContextThemeWrapper(app, R.style.KeyboardTheme_LXX_Pure_Day), null);
    strip.setListener(new E2EEStripView.Listener() {
      @Override public void onTextInput(final String rawText) { }
      @Override public void onSensitiveContentVisibilityChanged(final boolean sensitive) { }
    }, strip);
    SignalProtocolMain.setStorageStateForTest(StorageHelper.StorageState.READABLE);
  }

  @After
  public void tearDown() {
    SignalProtocolMain.resetForTest();
    SignalProtocolMain.testIsRunning = false;
  }

  private ImageButton verifyButton() {
    return strip.findViewById(R.id.e2ee_verify_contact_verify_button);
  }

  private ImageButton rejectButton() {
    return strip.findViewById(R.id.e2ee_verify_contact_reject_button);
  }

  private IdentityKey pinned() {
    return SignalProtocolMain.pinnedIdentityFor(bobAddress);
  }

  /**
   * Puts the user in front of a number, with a different key on disk.
   *
   * <p>Bob's real key is written to the store. Then the pin is moved in memory to the attacker's -
   * the state after a rejection followed by a re-pin - and the verify screen is opened, so the
   * digits on screen are the ATTACKER's. The reload then installs the stored account, whose pin is
   * Bob's real key: exactly what the per-raise re-read does, and the digits are now stale.
   */
  private IdentityKey thenumberOnScreenAndTheStoreDisagree() throws Exception {
    assertTrue(SignalProtocolMain.processPreKeyResponseMessage(
        EnvelopeCodec.fromWire(bobBundle), bobAddress));
    helper().storeAllInformationInSharedPreferences(victim);
    final IdentityKey onDisk = pinned();
    assertNotNull("precondition: Bob's real key must be pinned and stored", onDisk);

    SignalProtocolMain.rejectContactKey(bob);
    assertTrue(SignalProtocolMain.processPreKeyResponseMessage(
        EnvelopeCodec.fromWire(attackerBundle), bobAddress));
    final IdentityKey onScreen = pinned();
    assertFalse("precondition: the two keys must differ, or this measures nothing",
        onDisk.equals(onScreen));

    strip.showVerifyContactForTest(bob);
    assertTrue("precondition: the digits must be painted and the buttons live",
        verifyButton().isEnabled() && rejectButton().isEnabled());

    // The raise, or a theme change: the account object is replaced with the stored one.
    final Account loaded = helper().getAccountFromSharedPreferences();
    assertNotNull("precondition: the store must reload", loaded);
    SignalProtocolMain.getInstance().setAccount(loaded);
    assertTrue("precondition: the pin must now be the stored key, not the painted one",
        onDisk.equals(pinned()));
    return onScreen;
  }

  /**
   * Verify must not record a comparison of a key that was never on screen.
   *
   * <p>The badge is the only indicator the trust model surfaces, and a user who sees it does not
   * compare again. Recording it against a substituted key is unrecoverable in a way nothing else
   * here is.
   */
  @Test
  public void verifyRefusesWhenTheKeyMovedUnderTheDigits() throws Exception {
    thenumberOnScreenAndTheStoreDisagree();

    verifyButton().performClick();

    assertFalse("Verify means 'the user compared THIS key against the peer's own device'. The "
            + "account was replaced while the screen was up, so the key the press landed on is not "
            + "the one whose number was read aloud - and verifyContactInContactList's only check "
            + "is that SOMETHING is pinned",
        SignalProtocolMain.isContactKeyTrustworthy(
            SignalProtocolMain.getInstance().getAccount().getContactList().get(0)));
  }

  /**
   * And Reject must not brand a key that replaced the one the user was looking at.
   *
   * <p>{@code rejectContactKey} acts purely by address on whatever the account holds now, discards
   * the pin, and writes a record that is deliberately permanent.
   */
  @Test
  public void rejectRefusesWhenTheKeyMovedUnderTheDigits() throws Exception {
    thenumberOnScreenAndTheStoreDisagree();

    rejectButton().performClick();

    assertNotNull("Reject is permanent and lands by address. Pressed against a number that is no "
            + "longer the pinned key, it discards a key the user never complained about and brands "
            + "the address for the life of the install",
        pinned());
    assertFalse("and nothing may be recorded as rejected either",
        SignalProtocolMain.wasKeyRejected(bobAddress));
  }

  /**
   * The refusal is not a dead end: the current number is painted and the buttons work again.
   *
   * <p>A refusal with no next step is the failure this screen has produced three times - the user
   * is told something is wrong and finds nothing to press. After the refusal the digits are the
   * current ones, so the response the screen asks for is available immediately.
   */
  @Test
  public void therefusalLeavesTheUserSomethingToDo() throws Exception {
    thenumberOnScreenAndTheStoreDisagree();

    verifyButton().performClick();

    assertTrue("both buttons must come back live against the repainted number, or refusing to act "
            + "on a stale number is just a different way of stranding the user",
        verifyButton().isEnabled() && rejectButton().isEnabled());

    verifyButton().performClick();

    assertTrue("and the second press, against the number now on screen, must be recorded",
        SignalProtocolMain.isContactKeyTrustworthy(
            SignalProtocolMain.getInstance().getAccount().getContactList().get(0)));
  }

  /**
   * And the ordinary case still works, which is what stops the guard from being a way to disable
   * verification altogether.
   */
  @Test
  public void verifyStillWorksWhenNothingMoved() throws Exception {
    assertTrue(SignalProtocolMain.processPreKeyResponseMessage(
        EnvelopeCodec.fromWire(bobBundle), bobAddress));
    strip.showVerifyContactForTest(bob);

    verifyButton().performClick();

    assertTrue("a comparison of the key actually on screen must be recorded, or this guard has "
            + "turned the badge off rather than made it mean something",
        SignalProtocolMain.isContactKeyTrustworthy(
            SignalProtocolMain.getInstance().getAccount().getContactList().get(0)));
  }

  /**
   * The refusal must not tell the user to compare a number the repaint just erased.
   *
   * <p>The reloaded account can pin nothing at that address — a rejected address, or a session
   * whose write never landed — and then the repaint takes the null-fingerprint arm, blanks all
   * twelve digits and darkens both buttons. The first version of this refusal chose its sentence
   * before the repaint, so it said "the number below is the current one - compare it" over a line
   * saying there is no number and beside a control that was not live. This file has rejected that
   * exact reuse once already, for a different constant, and the argument is the same: a sentence
   * the screen disproves is one the user stops believing, and everything else this app has to say
   * is a sentence.
   */
  @Test
  public void therefusalDoesNotPromiseAnumberThatIsNotThere() throws Exception {
    // Bob's key is pinned and stored, then rejected on disk: the stored account has the contact and
    // no pin for it.
    assertTrue(SignalProtocolMain.processPreKeyResponseMessage(
        EnvelopeCodec.fromWire(bobBundle), bobAddress));
    SignalProtocolMain.rejectContactKey(bob);
    helper().storeAllInformationInSharedPreferences(victim);

    // In memory a key is pinned again, so there is a number to paint and press against.
    assertTrue(SignalProtocolMain.processPreKeyResponseMessage(
        EnvelopeCodec.fromWire(attackerBundle), bobAddress));
    strip.showVerifyContactForTest(bob);
    assertTrue("precondition: the digits must be painted", verifyButton().isEnabled());

    // The raise installs the stored account, which pins nothing at that address.
    final Account loaded = helper().getAccountFromSharedPreferences();
    assertNotNull(loaded);
    SignalProtocolMain.getInstance().setAccount(loaded);
    assertNull("precondition: the reloaded account must pin nothing for Bob",
        SignalProtocolMain.pinnedIdentityFor(bobAddress));

    verifyButton().performClick();

    final String said = org.robolectric.shadows.ShadowToast.getTextOfLatestToast();
    final CharSequence firstDigit =
        ((android.widget.TextView) strip.findViewById(R.id.code_first)).getText();
    assertNotNull("the refusal must say something", said);
    assertTrue("the repaint left no number on screen, so the refusal must not point at one: the "
            + "digits are '" + firstDigit + "' and it said: " + said,
        firstDigit.length() > 0 || !said.contains("The number below is the current one"));
    assertTrue("and whichever sentence it chose must still say that nothing was recorded - that is "
            + "the clause a user who pressed Verify has no other way to learn: " + said,
        said.contains("nothing was recorded"));
  }
}
