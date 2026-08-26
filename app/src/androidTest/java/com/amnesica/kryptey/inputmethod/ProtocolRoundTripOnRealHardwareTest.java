package com.amnesica.kryptey.inputmethod;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.amnesica.kryptey.inputmethod.signalprotocol.Account;
import com.amnesica.kryptey.inputmethod.signalprotocol.MessageEnvelope;
import com.amnesica.kryptey.inputmethod.signalprotocol.SignalProtocolMain;
import com.amnesica.kryptey.inputmethod.signalprotocol.encoding.EnvelopeCodec;
import com.amnesica.kryptey.inputmethod.signalprotocol.util.ProtocolAddresses;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.signal.libsignal.protocol.SignalProtocolAddress;

/**
 * A full PQXDH round trip against libsignal's <em>Android</em> native library.
 *
 * <p>Every other protocol test in this project runs on a desktop JVM, which loads a different
 * build of libsignal: a different architecture, a different compiler, a different set of runtime
 * assumptions. Those tests establish that the Java above the library is right. They cannot
 * establish that the library the phone actually loads behaves the same way, and a keyboard whose
 * encryption works everywhere except on phones is not a working keyboard.
 *
 * <p>So this is deliberately end to end rather than a unit: two accounts, a real invite, a session
 * built through the wire codec, a message encrypted and decrypted, and the plaintext compared. If
 * the native library on this device disagrees with the desktop one about anything on that path,
 * this is where it shows.
 *
 * <p>It also pins the two properties that would be embarrassing to get wrong and are invisible from
 * the plaintext: that the session really is PQXDH rather than X3DH, and that the wire text does not
 * contain the message.
 */
@RunWith(AndroidJUnit4.class)
public class ProtocolRoundTripOnRealHardwareTest {

  private static final String MESSAGE = "the meeting moved to nine";

  private Context context;
  private Account alice;
  private Account bob;

  @Before
  public void createBothParties() {
    context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    SignalProtocolMain.resetForTest();
    SignalProtocolMain.testIsRunning = true;

    // No storage: this is about the protocol and the native library, and a null context keeps the
    // Keystore and SharedPreferences out of it. The keystore itself is covered separately.
    SignalProtocolMain.initialize(null);
    alice = SignalProtocolMain.getInstance().getAccount();
    SignalProtocolMain.initialize(null);
    bob = SignalProtocolMain.getInstance().getAccount();
    assertNotNull("the protocol must initialise on this device", alice);
    assertNotNull(bob);
  }

  @After
  public void tearDown() {
    SignalProtocolMain.resetForTest();
    SignalProtocolMain.testIsRunning = false;
  }

  private void activate(final Account account) {
    SignalProtocolMain.getInstance().setAccount(account);
  }

  private static SignalProtocolAddress addressOf(final Account account) {
    return ProtocolAddresses.of(account.getSignalProtocolAddress().getName(),
        account.getDeviceId());
  }

  /** Invite, accept, send, read — the whole thing, on the hardware that will run it. */
  @Test
  public void amessageSurvivesAfullRoundTripOnThisDevice() throws Exception {
    activate(alice);
    final String invite = EnvelopeCodec.toWire(SignalProtocolMain.getPreKeyResponseMessage());
    assertTrue("an invite must be produced", invite.length() > 0);

    activate(bob);
    assertTrue("the invite must be accepted on this device",
        SignalProtocolMain.processPreKeyResponseMessage(
            EnvelopeCodec.fromWire(invite), addressOf(alice)));

    final MessageEnvelope outbound = SignalProtocolMain.encryptMessage(MESSAGE, addressOf(alice));
    assertNotNull("encryption must produce an envelope", outbound);
    final String wire = EnvelopeCodec.toWire(outbound);

    // The wire text must not be the message. Obvious, and worth one line: this is the string that
    // goes into somebody else's messenger.
    assertTrue("the wire text must not contain the plaintext", !wire.contains(MESSAGE));

    activate(alice);
    assertEquals("the plaintext must survive a full round trip through the native library this "
            + "device actually loads", MESSAGE,
        SignalProtocolMain.decryptMessage(EnvelopeCodec.fromWire(wire), addressOf(bob)));
  }

  /** And the session established on this device is post-quantum, not X3DH. */
  @Test
  public void thesessionBuiltOnThisDeviceIsPqxdh() throws Exception {
    activate(alice);
    final String invite = EnvelopeCodec.toWire(SignalProtocolMain.getPreKeyResponseMessage());

    activate(bob);
    assertTrue(SignalProtocolMain.processPreKeyResponseMessage(
        EnvelopeCodec.fromWire(invite), addressOf(alice)));

    final int version = bob.getSignalProtocolStore()
        .loadSession(addressOf(alice)).getSessionVersion();
    assertEquals("a session built on this device must be version 4 (PQXDH). Version 3 is X3DH, "
        + "which is what this app used before the libsignal upgrade and has no post-quantum "
        + "component at all.", 4, version);
  }

  /** Two devices must not produce the same identity, which would be a broken RNG on this platform. */
  @Test
  public void twoAccountsOnThisDeviceGetDifferentIdentities() {
    assertNotEquals("two accounts generated on this device share an identity key - the platform "
            + "RNG is not delivering distinct keys, and every session would be forgeable",
        alice.getIdentityKeyPair().getPublicKey().getFingerprint(),
        bob.getIdentityKeyPair().getPublicKey().getFingerprint());
  }
}
