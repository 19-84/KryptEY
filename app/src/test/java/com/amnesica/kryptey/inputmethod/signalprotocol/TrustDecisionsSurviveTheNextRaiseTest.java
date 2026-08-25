package com.amnesica.kryptey.inputmethod.signalprotocol;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;

import com.amnesica.kryptey.inputmethod.signalprotocol.chat.Contact;
import com.amnesica.kryptey.inputmethod.signalprotocol.encoding.EnvelopeCodec;
import com.amnesica.kryptey.inputmethod.signalprotocol.helper.StorageHelper;
import com.amnesica.kryptey.inputmethod.signalprotocol.storage.CryptoBox;
import com.amnesica.kryptey.inputmethod.signalprotocol.storage.GcmCryptoBox;
import com.amnesica.kryptey.inputmethod.signalprotocol.util.ProtocolAddresses;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.signal.libsignal.protocol.SignalProtocolAddress;

import java.security.NoSuchAlgorithmException;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;

/**
 * A trust decision the user makes has to still be true the next time the keyboard is raised.
 *
 * <p>Every one of these operations mutates the identity store in memory and then makes one call to
 * persist it. {@code reloadAccount} runs on every {@code setInputView} and replaces the account
 * with whatever is on disk, so the persist is the whole of what makes the decision real - and until
 * these tests, nothing asserted it happened. Measured: deleting the persist from
 * {@code rejectContactKey}, from {@code dismissIdentityChange} and from {@code verifyContact}
 * individually left the entire suite green.
 *
 * <p>What that hides is a fail-open with no signal. The user compares safety numbers by voice,
 * finds a mismatch, and taps "does not match"; the screen says the key was forgotten and it was,
 * in memory. On the next raise the impostor's pin, the verified badge and the session all come
 * back, and the rejection record - which is deliberately the one thing that outlives
 * {@code removeIdentity}, so the same forged bundle cannot be re-pinned silently - is gone with
 * them. Nothing tells the user their decision was undone.
 *
 * <p>These are companions to {@code WrongPinRecoveryTest} and {@code PendingChangeExitTest}, which
 * pin what the operations do to the store and never take the account back off disk.
 */
@RunWith(RobolectricTestRunner.class)
public class TrustDecisionsSurviveTheNextRaiseTest {

  private Context context;
  private SecretKey key;
  private SignalProtocolAddress peerAddress;

  private static SecretKey newKey() {
    try {
      final KeyGenerator generator = KeyGenerator.getInstance("AES");
      generator.init(256);
      return generator.generateKey();
    } catch (NoSuchAlgorithmException e) {
      throw new AssertionError(e);
    }
  }

  @Before
  public void setUp() {
    SignalProtocolMain.resetForTest();
    context = RuntimeEnvironment.getApplication();
    final SharedPreferences preferences =
        context.getSharedPreferences("protocol", Context.MODE_PRIVATE);
    preferences.edit().clear().commit();
    key = newKey();
    final CryptoBox box = new GcmCryptoBox() {
      @Override
      protected SecretKey key() {
        return key;
      }
    };
    SignalProtocolMain.setStorageHelperFactoryForTest(
        ctx -> new StorageHelper(ctx, (c, hasExistingData) -> box));
    SignalProtocolMain.testIsRunning = true;
    assertTrue("precondition: the account must be created and persisted",
        SignalProtocolMain.initialize(context));
  }

  @After
  public void tearDown() {
    SignalProtocolMain.resetForTest();
  }

  /** The keyboard is put away and raised again; this is all setInputView does to the account. */
  private void raiseTheKeyboardAgain() {
    SignalProtocolMain.reloadAccount(context);
    assertNotNull("precondition: the account must reload from disk",
        SignalProtocolMain.getInstance().getAccount());
  }

  private Contact storedContact() {
    for (final Contact candidate : SignalProtocolMain.getContactList()) {
      if (candidate.getSignalProtocolAddress().equals(peerAddress)) return candidate;
    }
    throw new AssertionError("the contact is not in the reloaded list");
  }

  /**
   * Sets the account up as a victim who has pinned - and verified - a key that is in fact the
   * messenger's own, which is what a swapped first invite produces.
   */
  private void pinAndVerifyAKeyForAPeer() throws Exception {
    // A second identity, built with no storage of its own, standing in for whoever supplied the
    // bundle.
    final Account victim = SignalProtocolMain.getInstance().getAccount();
    SignalProtocolMain.getInstance().setStorageHelperForTest(null);
    SignalProtocolMain.initialize(null);
    final Account peer = SignalProtocolMain.getInstance().getAccount();
    peerAddress = ProtocolAddresses.of(peer.getSignalProtocolAddress().getName(),
        peer.getDeviceId());
    final String bundle = SignalProtocolMain.exportOwnKeyBundle();

    // Back to the victim, with its real storage helper.
    SignalProtocolMain.getInstance().setAccount(victim);
    raiseTheKeyboardAgain();

    SignalProtocolMain.addContact("Real", "Peer", peerAddress.getName(),
        peerAddress.getDeviceId());
    assertTrue("precondition: the bundle must pin",
        SignalProtocolMain.processPreKeyResponseMessage(EnvelopeCodec.fromWire(bundle),
            peerAddress));
    assertTrue("precondition: the user must have compared and confirmed the number",
        SignalProtocolMain.verifyContact(storedContact()));

    raiseTheKeyboardAgain();
    assertNotNull("precondition: the pin must be on disk", pinned());
    assertTrue("precondition: the badge must be on disk",
        SignalProtocolMain.isContactKeyTrustworthy(storedContact()));
  }

  private org.signal.libsignal.protocol.IdentityKey pinned() {
    return SignalProtocolMain.getInstance().getAccount().getSignalProtocolStore()
        .getIdentityKeyStore().getIdentity(peerAddress);
  }

  /**
   * "The numbers do not match" is the strongest statement a user can make in this app, and it is
   * the one that must not evaporate.
   */
  @Test
  public void arejectionIsStillInForceAfterTheKeyboardIsRaised() throws Exception {
    pinAndVerifyAKeyForAPeer();

    assertTrue("precondition: a pinned key must have been forgotten",
        SignalProtocolMain.rejectContactKey(storedContact()));

    raiseTheKeyboardAgain();

    assertNull("the rejected key is back: the rejection was never written down, and the next "
        + "setInputView restored the pin the user told the app was wrong", pinned());
    assertTrue("the rejection record is gone too - so the very bundle that provoked the rejection "
            + "can be re-delivered and pinned as a silent first sighting",
        SignalProtocolMain.wasKeyRejected(peerAddress));
    assertFalse("and the verified badge came back over it",
        SignalProtocolMain.isContactKeyTrustworthy(storedContact()));
  }

  /**
   * Dismissal is the sanctioned exit from a pending identity change. If it does not reach disk the
   * warning returns on the next raise and the state is terminal after all - which is the
   * remotely-triggerable, unclearable badge DoS the exit was added to close.
   */
  @Test
  public void adismissalIsStillInForceAfterTheKeyboardIsRaised() throws Exception {
    pinAndVerifyAKeyForAPeer();

    // Somebody offers a different key for this address. One forged bundle is all it takes.
    final Account victim = SignalProtocolMain.getInstance().getAccount();
    SignalProtocolMain.getInstance().setStorageHelperForTest(null);
    SignalProtocolMain.initialize(null);
    final String forged = SignalProtocolMain.exportOwnKeyBundle();
    SignalProtocolMain.getInstance().setAccount(victim);
    raiseTheKeyboardAgain();

    assertFalse("precondition: a displaced key must be refused",
        SignalProtocolMain.processPreKeyResponseMessage(EnvelopeCodec.fromWire(forged),
            peerAddress));
    assertTrue("precondition: the change must be recorded as pending",
        SignalProtocolMain.hasUnacceptedIdentityChange(peerAddress));

    assertTrue("precondition: the user dismisses the offered key",
        SignalProtocolMain.dismissIdentityChange(peerAddress));

    raiseTheKeyboardAgain();

    assertFalse("the dismissed change is pending again after one raise: the dismissal was never "
            + "written down, so the state an attacker can force with a single bundle has no exit "
            + "that survives putting the keyboard away",
        SignalProtocolMain.hasUnacceptedIdentityChange(peerAddress));
  }
}
