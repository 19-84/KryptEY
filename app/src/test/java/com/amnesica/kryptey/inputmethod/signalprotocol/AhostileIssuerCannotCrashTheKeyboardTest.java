package com.amnesica.kryptey.inputmethod.signalprotocol;

import com.amnesica.kryptey.inputmethod.signalprotocol.encoding.EnvelopeCodec;
import com.amnesica.kryptey.inputmethod.signalprotocol.prekey.KyberPreKeyEntity;
import com.amnesica.kryptey.inputmethod.signalprotocol.prekey.PreKeyEntity;
import com.amnesica.kryptey.inputmethod.signalprotocol.prekey.PreKeyResponse;
import com.amnesica.kryptey.inputmethod.signalprotocol.prekey.PreKeyResponseItem;
import com.amnesica.kryptey.inputmethod.signalprotocol.prekey.SignedPreKeyEntity;
import com.amnesica.kryptey.inputmethod.signalprotocol.util.ProtocolAddresses;

import org.junit.Before;
import org.junit.Test;
import org.signal.libsignal.protocol.IdentityKeyPair;
import org.signal.libsignal.protocol.SignalProtocolAddress;
import org.signal.libsignal.protocol.ecc.ECKeyPair;
import org.signal.libsignal.protocol.kem.KEMKeyPair;
import org.signal.libsignal.protocol.kem.KEMKeyType;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * What a negative key id costs if the parser ever stops refusing it.
 *
 * <p>{@code AnegativeKeyIdIsRefusedAtTheParserTest} pins where the value is stopped. This pins what
 * it is stopped from doing, along the route a pasted invite really takes: {@code fromWire} into
 * {@code processPreKeyResponseMessage}.
 *
 * <p><b>The adversary is the issuer, not a relay.</b> That distinction is what makes this
 * constructible and it is why the first attempt at this test was worthless. A bundle carrying
 * signature-<em>shaped</em> bytes is refused by {@code requireTheBundleWasIssuedAsOneUnit} long
 * before libsignal sees any id, so the case passed with the parser guards deleted and measured
 * nothing. A hostile issuer signs with its own identity key, which that check accepts by design -
 * "a bundle from an attacker's own identity verifies perfectly" - so the bundle is genuine in every
 * respect except the id inside it.
 *
 * <p>The encoder makes it constructible: it calls {@code requireUnsigned} on {@code registrationId}
 * alone, and {@code writeU32} takes the other three ids as given.
 *
 * <p>Nothing here may escape except {@code IOException}. {@code processPreKeyResponse} catches that
 * and nothing else, and the path begins at the Decrypt button inside a click listener - so an
 * unchecked throw is the input method process dying in whatever app the user is typing in,
 * repeatably, on a paste the sender chose.
 */
public class AhostileIssuerCannotCrashTheKeyboardTest {

  private Account victim;
  private Account issuer;
  private SignalProtocolAddress issuerAddress;

  @Before
  public void setUp() {
    SignalProtocolMain.testIsRunning = true;
    SignalProtocolMain.initialize(null);
    issuer = SignalProtocolMain.getInstance().getAccount();
    issuerAddress = ProtocolAddresses.of(issuer.getSignalProtocolAddress().getName(),
        issuer.getDeviceId());
    SignalProtocolMain.initialize(null);
    victim = SignalProtocolMain.getInstance().getAccount();
    SignalProtocolMain.getInstance().setAccount(victim);
  }

  /** A genuine, correctly self-signed invite whose key ids are whatever the issuer chose. */
  private String inviteWithIds(final int signedId, final int preKeyId, final int kyberId)
      throws IOException {
    final IdentityKeyPair identity = issuer.getIdentityKeyPair();
    final ECKeyPair signed = ECKeyPair.generate();
    final ECKeyPair oneTime = ECKeyPair.generate();
    final KEMKeyPair kyber = KEMKeyPair.generate(KEMKeyType.KYBER_1024);

    final List<PreKeyResponseItem> devices = new ArrayList<>();
    devices.add(new PreKeyResponseItem(issuerAddress.getDeviceId(), 4242,
        new SignedPreKeyEntity(signedId, signed.getPublicKey(),
            identity.getPrivateKey().calculateSignature(signed.getPublicKey().serialize())),
        new PreKeyEntity(preKeyId, oneTime.getPublicKey()),
        new KyberPreKeyEntity(kyberId, kyber.getPublicKey(),
            identity.getPrivateKey().calculateSignature(kyber.getPublicKey().serialize()))));

    final PreKeyResponse bundle = new PreKeyResponse(identity.getPublicKey(), devices);
    return EnvelopeCodec.toWire(BundleSigning.signedEnvelope(issuer, bundle,
        issuerAddress.getName(), issuerAddress.getDeviceId()));
  }

  /**
   * The premise, and it is the whole reason the cases below mean anything.
   *
   * <p>With ordinary ids this invite must be ACCEPTED - not merely "not crash". If the signature or
   * the address were wrong the bundle would be refused before libsignal ever saw an id, and every
   * case below would pass while measuring a refusal instead of a guard. That is exactly how the
   * first version of this test fooled itself.
   */
  @Test
  public void thepremiseThatAgenuineInviteFromThisIssuerIsAccepted() throws Exception {
    org.junit.Assert.assertTrue("a correctly self-signed invite must be accepted, or these cases "
            + "measure the signature check rather than the id guards",
        SignalProtocolMain.processPreKeyResponseMessage(
            EnvelopeCodec.fromWire(inviteWithIds(11, 12, 13)), issuerAddress));
  }

  @Test
  public void anegativeIdMustNotEscapeAsAnUncheckedThrow() throws Exception {
    final int[][] cases = {{-1, 12, 13}, {11, -1, 13}, {11, 12, -1},
        {Integer.MIN_VALUE, 12, 13}};
    for (final int[] ids : cases) {
      final String wire = inviteWithIds(ids[0], ids[1], ids[2]);
      try {
        SignalProtocolMain.processPreKeyResponseMessage(EnvelopeCodec.fromWire(wire),
            issuerAddress);
      } catch (final IOException handled) {
        // The declared outcome, and what the parser's requireUnsigned guards produce.
      } catch (final RuntimeException escaped) {
        throw new AssertionError("an unchecked " + escaped.getClass().getName() + " escaped the "
            + "invite path for ids " + java.util.Arrays.toString(ids) + ": "
            + escaped.getMessage() + ". processPreKeyResponse catches IOException only and this "
            + "runs under a click listener, so the keyboard process dies in whatever app the user "
            + "is typing in - repeatably, on a paste the sender chose", escaped);
      }
    }
  }
}
