package com.amnesica.kryptey.inputmethod.signalprotocol;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.amnesica.kryptey.inputmethod.signalprotocol.encoding.EnvelopeCodec;
import com.amnesica.kryptey.inputmethod.signalprotocol.prekey.PreKeyResponse;
import com.amnesica.kryptey.inputmethod.signalprotocol.prekey.PreKeyResponseItem;
import com.amnesica.kryptey.inputmethod.signalprotocol.util.ProtocolAddresses;

import org.junit.Before;
import org.junit.Test;
import org.signal.libsignal.protocol.SignalProtocolAddress;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * A relay that deletes one optional-looking field from an invite must not be obeyed.
 *
 * <p>Both fields here are unsigned: the one-time pre-key and the Kyber pre-key are covered by
 * neither the signed-pre-key signature nor each other's, so removing either costs a relay one byte
 * and leaves every signature verifying and the base64 canonical. Nothing upstream can notice — the
 * wire format's own guards are about structure, and a bundle with a field absent is structurally
 * perfect.
 *
 * <p>libsignal permits an absent one-time pre-key: {@code PreKeyBundle} marks it {@code @Nullable}
 * and {@code NULL_PRE_KEY_ID} is -1, because on Signal a server's pre-key pool can legitimately run
 * dry. <b>This app has no server.</b> {@code getPreKeyBundle} allocates a one-time pre-key or
 * throws, so a bundle without one is not a depleted pool — it is a modified invite, and the
 * inherited permission is a hole rather than a feature.
 *
 * <p>What acceptance costs is forward secrecy against later device seizure: the PQXDH secret loses
 * its DH against the one-time key, and everything remaining derives from material that stays on the
 * device for up to 32 days. The session builds, the UI advances exactly as for a good invite, and
 * nothing is logged — a silent downgrade, which is the failure mode the Kyber check four lines
 * below already refuses in so many words. This pins both, because the Kyber half had no test
 * either: deleting its check left the suite green.
 */
public class StrippedBundleFieldsAreRefusedTest {

  private PreKeyResponse genuine;
  private SignalProtocolAddress peerAddress;

  @Before
  public void setUp() throws Exception {
    SignalProtocolMain.resetForTest();
    SignalProtocolMain.testIsRunning = true;

    SignalProtocolMain.initialize(null);
    final Account peer = SignalProtocolMain.getInstance().getAccount();
    peerAddress = ProtocolAddresses.of(peer.getSignalProtocolAddress().getName(),
        peer.getDeviceId());
    genuine = EnvelopeCodec.fromWire(SignalProtocolMain.exportOwnKeyBundle()).getPreKeyResponse();

    // The recipient.
    SignalProtocolMain.initialize(null);
  }

  /** Precondition for everything else: this app never emits a bundle without a one-time key. */
  @Test
  public void thisappalwaysSendsAoneTimePreKey() {
    assertNotNull("a genuine invite from this app must carry a one-time pre-key, or 'absent means "
            + "tampered' is not a rule this app is entitled to enforce",
        genuine.getDevices().get(0).getPreKey());
    assertNotNull("and a Kyber pre-key", genuine.getDevices().get(0).getKyberPreKey());
  }

  private PreKeyResponse without(final boolean preKey, final boolean kyber) {
    final PreKeyResponseItem device = genuine.getDevices().get(0);
    final List<PreKeyResponseItem> devices = new ArrayList<>();
    devices.add(new PreKeyResponseItem(device.getDeviceId(), device.getRegistrationId(),
        device.getSignedPreKey(),
        preKey ? null : device.getPreKey(),
        kyber ? null : device.getKyberPreKey()));
    return new PreKeyResponse(genuine.getIdentityKey(), devices);
  }

  /**
   * The defect: the one-time pre-key removed, and the session built anyway.
   *
   * <p>Asserted at {@code createPreKeyBundle} rather than through the UI because that is where the
   * decision is, and because the consequence — a weaker root key — is invisible everywhere above
   * it. A session that builds successfully is exactly what the attack produces.
   */
  @Test
  public void abundleStrippedOfItsOneTimePreKeyIsRefused() {
    try {
      SignalProtocolMain.getInstance().createPreKeyBundle(without(true, false));
      fail("a bundle with no one-time pre-key was accepted. libsignal permits this because a "
          + "Signal server's pool can run dry; this app has no server and always sends one, so an "
          + "absent one-time key means the invite was modified in transit. Accepting it drops the "
          + "DH against that key from the PQXDH secret, leaving a root key derivable from "
          + "long-lived material - so a recorded conversation becomes readable on later device "
          + "seizure, which is the whole property the one-time key provides.");
    } catch (final IOException expected) {
      assertTrue("the refusal must say what happened: " + expected.getMessage(),
          expected.getMessage().contains("one-time pre key"));
    }
  }

  /** The sibling check, which was equally untested. */
  @Test
  public void abundleStrippedOfItsKyberPreKeyIsRefused() {
    try {
      SignalProtocolMain.getInstance().createPreKeyBundle(without(false, true));
      fail("a bundle with no Kyber pre-key was accepted, which is a silent downgrade out of PQXDH");
    } catch (final IOException expected) {
      assertTrue("the refusal must say what happened: " + expected.getMessage(),
          expected.getMessage().contains("kyber"));
    }
  }

  /**
   * And the refusal reaches the caller as a refusal, rather than being swallowed into "accepted".
   *
   * <p>Without this, a fix that threw in the right place but was caught somewhere above would still
   * leave the user looking at a contact that appears connected.
   */
  @Test
  public void astrippedBundleDoesNotProduceAsession() throws Exception {
    final boolean accepted = SignalProtocolMain.processPreKeyResponseMessage(
        new MessageEnvelope(without(true, false), peerAddress.getName(),
            peerAddress.getDeviceId()), peerAddress);

    assertTrue("a stripped bundle must be reported as refused, not merely fail internally",
        !accepted);
    assertNull("and nothing may be pinned for that address, or the user has a contact whose key "
            + "arrived on an invite the app knows was modified",
        SignalProtocolMain.getInstance().getAccount().getSignalProtocolStore()
            .getIdentityKeyStore().getIdentity(peerAddress));
  }
}
