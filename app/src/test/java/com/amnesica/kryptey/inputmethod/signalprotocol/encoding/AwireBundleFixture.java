package com.amnesica.kryptey.inputmethod.signalprotocol.encoding;

import com.amnesica.kryptey.inputmethod.signalprotocol.MessageEnvelope;
import com.amnesica.kryptey.inputmethod.signalprotocol.prekey.KyberPreKeyEntity;
import com.amnesica.kryptey.inputmethod.signalprotocol.prekey.PreKeyEntity;
import com.amnesica.kryptey.inputmethod.signalprotocol.prekey.PreKeyResponse;
import com.amnesica.kryptey.inputmethod.signalprotocol.prekey.PreKeyResponseItem;
import com.amnesica.kryptey.inputmethod.signalprotocol.prekey.SignedPreKeyEntity;

import org.signal.libsignal.protocol.IdentityKeyPair;
import org.signal.libsignal.protocol.ecc.ECKeyPair;
import org.signal.libsignal.protocol.kem.KEMKeyPair;
import org.signal.libsignal.protocol.kem.KEMKeyType;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

/** A structurally complete bundle envelope, and an edit that pads its identity-key field. */
final class AwireBundleFixture {

  private AwireBundleFixture() {
  }

  static MessageEnvelope bundleEnvelope() {
    final IdentityKeyPair identity = IdentityKeyPair.generate();
    final ECKeyPair signed = ECKeyPair.generate();
    final ECKeyPair oneTime = ECKeyPair.generate();
    final KEMKeyPair kyber = KEMKeyPair.generate(KEMKeyType.KYBER_1024);

    final List<PreKeyResponseItem> devices = new ArrayList<>();
    devices.add(new PreKeyResponseItem(3, 4242,
        new SignedPreKeyEntity(11, signed.getPublicKey(),
            identity.getPrivateKey().calculateSignature(signed.getPublicKey().serialize())),
        new PreKeyEntity(12, oneTime.getPublicKey()),
        new KyberPreKeyEntity(13, kyber.getPublicKey(),
            identity.getPrivateKey().calculateSignature(kyber.getPublicKey().serialize()))));

    return new MessageEnvelope(new PreKeyResponse(identity.getPublicKey(), devices), "peer", 3);
  }

  /**
   * Appends eight bytes to the identity-key field and fixes its length prefix.
   *
   * <p>The identity key is the first field of the bundle section, and the bundle section follows a
   * fixed-width header, so its length prefix is at a computable offset: version, flags, name
   * length, the name, the device id, then the bundle. Written this way rather than by searching for
   * bytes, so the fixture cannot quietly start editing something else.
   */
  static byte[] padTheIdentityKeyField(final byte[] encoded) {
    final int nameLength = encoded[2] & 0xFF;
    final int identityLengthAt = 3 + nameLength + 1;
    final int identityLength = encoded[identityLengthAt] & 0xFF;

    final ByteArrayOutputStream out = new ByteArrayOutputStream();
    out.write(encoded, 0, identityLengthAt);
    out.write(identityLength + 8);
    out.write(encoded, identityLengthAt + 1, identityLength);
    for (int i = 0; i < 8; i++) out.write(0x41);
    final int rest = identityLengthAt + 1 + identityLength;
    out.write(encoded, rest, encoded.length - rest);
    return out.toByteArray();
  }
}
