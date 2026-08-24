package com.amnesica.kryptey.inputmethod.signalprotocol;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.amnesica.kryptey.inputmethod.signalprotocol.encoding.EnvelopeCodec;
import com.amnesica.kryptey.inputmethod.signalprotocol.util.ProtocolAddresses;

import org.junit.Before;
import org.junit.Test;
import org.signal.libsignal.protocol.SignalProtocolAddress;
import org.signal.libsignal.protocol.state.SessionRecord;

/**
 * That the sessions actually established are PQXDH, not X3DH.
 *
 * <p>This is the headline claim of the libsignal upgrade, and nothing checked it. Every existing
 * test asserts that a session can be built and that messages round-trip - all of which is equally
 * true of an X3DH session. If the Kyber pre-key were dropped from the bundle, ignored by the
 * builder, or silently unusable, sessions would still establish, messages would still decrypt, and
 * the suite would stay green while the post-quantum property the upgrade exists for was absent.
 *
 * <p>libsignal records this in the session version: 3 is X3DH, 4 is PQXDH. That number is the
 * difference between "we upgraded the library" and "we got the protocol".
 */
public class PqxdhSessionTest {

  private static final int X3DH = 3;
  private static final int PQXDH = 4;

  private Account alice;
  private Account bob;
  private SignalProtocolAddress bobAddress;

  @Before
  public void setUp() {
    SignalProtocolMain.testIsRunning = true;
    SignalProtocolMain.initialize(null);
    alice = SignalProtocolMain.getInstance().getAccount();
    SignalProtocolMain.initialize(null);
    bob = SignalProtocolMain.getInstance().getAccount();
    bobAddress = ProtocolAddresses.of(bob.getSignalProtocolAddress().getName(), bob.getDeviceId());
  }

  private void activate(final Account account) {
    SignalProtocolMain.getInstance().setAccount(account);
  }

  private SessionRecord aliceSessionWithBob() throws Exception {
    activate(bob);
    final String bundle = SignalProtocolMain.exportOwnKeyBundle();

    activate(alice);
    assertTrue("the fixture must establish a session",
        SignalProtocolMain.processPreKeyResponseMessage(
            EnvelopeCodec.fromWire(bundle), bobAddress));

    final SessionRecord session =
        alice.getSignalProtocolStore().getSessionStore().loadSession(bobAddress);
    assertNotNull("a session must exist", session);
    return session;
  }

  @Test
  public void aSessionBuiltFromABundleIsPostQuantum() throws Exception {
    final int version = aliceSessionWithBob().getSessionVersion();

    assertEquals("the session must be PQXDH (v" + PQXDH + "), not X3DH (v" + X3DH + ") - a v"
            + X3DH + " session works and carries messages exactly the same way, which is why "
            + "nothing else in the suite can tell them apart",
        PQXDH, version);
  }

  /** The bundle a peer receives must carry the Kyber material a PQXDH handshake needs. */
  @Test
  public void theExportedBundleCarriesKyberMaterial() throws Exception {
    activate(bob);
    final MessageEnvelope envelope = SignalProtocolMain.getPreKeyResponseMessage();

    assertNotNull(envelope.getPreKeyResponse());
    final var device = envelope.getPreKeyResponse().getDevices().get(0);
    assertNotNull("no Kyber pre-key means no PQXDH, whatever the library version",
        device.getKyberPreKey());
    assertNotNull("the Kyber public key must be present", device.getKyberPreKey().getPublicKey());
    assertNotNull("and signed, or the peer cannot trust it",
        device.getKyberPreKey().getSignature());
  }

  /** And the session that results from it still carries messages both ways. */
  @Test
  public void aPostQuantumSessionCarriesMessagesBothWays() throws Exception {
    aliceSessionWithBob();

    activate(alice);
    final MessageEnvelope toBob = SignalProtocolMain.encryptMessage("hello", bobAddress);
    assertNotNull(toBob);

    final SignalProtocolAddress aliceAddress =
        ProtocolAddresses.of(alice.getSignalProtocolAddress().getName(), alice.getDeviceId());
    activate(bob);
    assertEquals("hello", SignalProtocolMain.decryptMessage(
        EnvelopeCodec.fromWire(EnvelopeCodec.toWire(toBob)), aliceAddress));

    assertEquals("and Bob's side of the session must be post-quantum too", PQXDH,
        bob.getSignalProtocolStore().getSessionStore().loadSession(aliceAddress)
            .getSessionVersion());
  }

  /** A session established out of band must be post-quantum as well - same bundle, same handshake. */
  @Test
  public void anOutOfBandSessionIsAlsoPostQuantum() throws Exception {
    activate(bob);
    final String bundle = SignalProtocolMain.exportOwnKeyBundle();

    activate(alice);
    assertTrue(SignalProtocolMain.importOutOfBandKeyBundle(bundle, bobAddress));

    assertEquals(PQXDH, alice.getSignalProtocolStore().getSessionStore()
        .loadSession(bobAddress).getSessionVersion());
  }
}
