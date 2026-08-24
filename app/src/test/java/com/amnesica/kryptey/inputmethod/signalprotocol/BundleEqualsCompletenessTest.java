package com.amnesica.kryptey.inputmethod.signalprotocol;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;

import com.amnesica.kryptey.inputmethod.signalprotocol.encoding.EnvelopeCodec;
import com.amnesica.kryptey.inputmethod.signalprotocol.prekey.KyberPreKeyEntity;
import com.amnesica.kryptey.inputmethod.signalprotocol.prekey.PreKeyEntity;
import com.amnesica.kryptey.inputmethod.signalprotocol.prekey.PreKeyResponse;
import com.amnesica.kryptey.inputmethod.signalprotocol.prekey.PreKeyResponseItem;
import com.amnesica.kryptey.inputmethod.signalprotocol.prekey.SignedPreKeyEntity;

import org.junit.Before;
import org.junit.Test;
import org.signal.libsignal.protocol.IdentityKey;
import org.signal.libsignal.protocol.ecc.ECKeyPair;

import java.util.ArrayList;
import java.util.List;

/**
 * Every field of a key bundle must participate in its {@code equals}.
 *
 * <p>Nothing in production compares two bundles - but the serialisation tests do, and they compare
 * them transitively: {@code assertEquals(sent, received)} on a {@code MessageEnvelope} cascades into
 * {@code PreKeyResponse.equals} and from there into each {@code PreKeyResponseItem}. A field those
 * ignore is a field the wire round trip could silently drop with every test still green - and the
 * fields here are the identity key, the signed pre-key, the one-time pre-key and the Kyber
 * pre-key, i.e. everything a session is built from.
 *
 * <p>This is the same gap already found in {@code MessageEnvelope.equals}, one level down. Finding
 * it there and not looking here would have been the mistake.
 */
public class BundleEqualsCompletenessTest {

  private IdentityKey identityKey;
  private SignedPreKeyEntity signed;
  private PreKeyEntity preKey;
  private KyberPreKeyEntity kyber;

  @Before
  public void setUp() throws Exception {
    SignalProtocolMain.testIsRunning = true;
    SignalProtocolMain.initialize(null);
    final PreKeyResponse real = SignalProtocolMain.getPreKeyResponseMessage().getPreKeyResponse();
    assertNotNull("fixture: a real bundle", real);

    identityKey = real.getIdentityKey();
    final PreKeyResponseItem item = real.getDevices().get(0);
    signed = item.getSignedPreKey();
    preKey = item.getPreKey();
    kyber = item.getKyberPreKey();
  }

  private PreKeyResponseItem item(final int deviceId, final int registrationId,
      final SignedPreKeyEntity s, final PreKeyEntity p, final KyberPreKeyEntity k) {
    return new PreKeyResponseItem(deviceId, registrationId, s, p, k);
  }

  private PreKeyResponse bundle(final IdentityKey key, final PreKeyResponseItem item) {
    final List<PreKeyResponseItem> devices = new ArrayList<>();
    devices.add(item);
    return new PreKeyResponse(key, devices);
  }

  private PreKeyResponse baseline() {
    return bundle(identityKey, item(1, 42, signed, preKey, kyber));
  }

  @Test
  public void twoIdenticalBundlesAreEqual() {
    assertEquals(baseline(), baseline());
    assertEquals(baseline().hashCode(), baseline().hashCode());
  }

  @Test
  public void aDifferentIdentityKeyIsNotEqual() {
    final IdentityKey other = new IdentityKey(ECKeyPair.generate().getPublicKey());
    assertNotEquals("the identity key is the whole point of a bundle",
        baseline(), bundle(other, item(1, 42, signed, preKey, kyber)));
  }

  @Test
  public void aDifferentDeviceIdIsNotEqual() {
    assertNotEquals(baseline(), bundle(identityKey, item(2, 42, signed, preKey, kyber)));
  }

  @Test
  public void aDifferentRegistrationIdIsNotEqual() {
    assertNotEquals(baseline(), bundle(identityKey, item(1, 43, signed, preKey, kyber)));
  }

  @Test
  public void aMissingSignedPreKeyIsNotEqual() {
    assertNotEquals("a bundle without its signed pre-key must not compare equal to one with it",
        baseline(), bundle(identityKey, item(1, 42, null, preKey, kyber)));
  }

  @Test
  public void aMissingOneTimePreKeyIsNotEqual() {
    assertNotEquals(baseline(), bundle(identityKey, item(1, 42, signed, null, kyber)));
  }

  /** The one PQXDH depends on, and the one most recently added to the format. */
  @Test
  public void aMissingKyberPreKeyIsNotEqual() {
    assertNotEquals("dropping the Kyber pre-key must not round-trip as equal",
        baseline(), bundle(identityKey, item(1, 42, signed, preKey, null)));
  }

  @Test
  public void aDifferentDeviceListIsNotEqual() {
    final PreKeyResponse twoDevices = bundle(identityKey, item(1, 42, signed, preKey, kyber));
    twoDevices.getDevices().add(item(2, 43, signed, preKey, kyber));

    assertNotEquals(baseline(), twoDevices);
  }

  /**
   * The round trip that relies on all of the above actually preserves the bundle.
   *
   * <p>Compares the BUNDLE, not the whole envelope. The envelope's timestamp deliberately does not
   * cross the wire - {@code BinaryEnvelope} does not encode it, because a sender-supplied timestamp
   * lets a peer backdate a message, so the receiver stamps arrival instead. An envelope-level
   * equality here would therefore fail on a one-millisecond difference, which is what my first
   * version did: the expectation was wrong, not the codec.
   */
  @Test
  public void aRealBundleSurvivesTheWireRoundTrip() throws Exception {
    final MessageEnvelope sent = SignalProtocolMain.getPreKeyResponseMessage();
    final MessageEnvelope received = EnvelopeCodec.fromWire(EnvelopeCodec.toWire(sent));

    assertEquals("the bundle must survive the wire unchanged - and this assertion is only as "
            + "strong as the equals methods above",
        sent.getPreKeyResponse(), received.getPreKeyResponse());
    assertNotNull("including the Kyber pre-key, which PQXDH needs",
        received.getPreKeyResponse().getDevices().get(0).getKyberPreKey());
    // Stamp a distinctive past value and check it does not survive. Comparing the two timestamps
    // directly was flaky: the round trip usually takes under a millisecond, so "they differ" is
    // true only when the clock happens to tick mid-test.
    sent.setTimestamp(1_600_000_000_000L);
    final MessageEnvelope again = EnvelopeCodec.fromWire(EnvelopeCodec.toWire(sent));
    assertNotEquals("a sender-supplied timestamp must not cross the wire - it would let a peer "
            + "backdate a message", 1_600_000_000_000L, again.getTimestamp());
  }
}
