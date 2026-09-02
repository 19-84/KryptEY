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
    return bundleEnvelopeWithIds(11, 12, 13);
  }

  /**
   * The same bundle, with the three key ids chosen by the caller.
   *
   * <p>Exists so a test can build the input a hostile ISSUER sends. The encoder guards only
   * {@code registrationId} with {@code requireUnsigned} - {@code writeU32} takes the other three
   * ids as given - so a negative id encodes cleanly and the bundle it produces is internally
   * consistent and correctly signed by whoever issued it. No byte editing, and nothing for a
   * signature check to notice.
   */
  static MessageEnvelope bundleEnvelopeWithIds(final int signedId, final int preKeyId,
      final int kyberId) {
    final IdentityKeyPair identity = IdentityKeyPair.generate();
    final ECKeyPair signed = ECKeyPair.generate();
    final ECKeyPair oneTime = ECKeyPair.generate();
    final KEMKeyPair kyber = KEMKeyPair.generate(KEMKeyType.KYBER_1024);

    final List<PreKeyResponseItem> devices = new ArrayList<>();
    devices.add(new PreKeyResponseItem(3, 4242,
        new SignedPreKeyEntity(signedId, signed.getPublicKey(),
            identity.getPrivateKey().calculateSignature(signed.getPublicKey().serialize())),
        new PreKeyEntity(preKeyId, oneTime.getPublicKey()),
        new KyberPreKeyEntity(kyberId, kyber.getPublicKey(),
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

  /**
   * The length-prefix offsets of the three key fields inside the bundle's single device.
   *
   * <p>Walked rather than searched for, the same discipline {@link #padTheIdentityKeyField} uses:
   * a fixture that hunts for a byte pattern can quietly start editing a different field when the
   * format changes, and then reports the guard it was aiming at as covered.
   *
   * <p>Returns {@code {signedKeyLenAt, preKeyLenAt, kyberKeyLenAt}}, with {@code -1} where the
   * optional field is absent. The Kyber length is a u16 and the other two are u8, which is the one
   * asymmetry that matters here: it is why the untested field can carry roughly 4 KB of rider bytes
   * where the tested one is capped at 222.
   */
  private static int[] keyFieldLengthOffsets(final byte[] e) {
    final int nameLength = e[2] & 0xFF;
    final int identityLengthAt = 3 + nameLength + 1;
    final int identityLength = e[identityLengthAt] & 0xFF;

    int p = identityLengthAt + 1 + identityLength;   // deviceCount
    p += 1;                                          // device deviceId
    p += 1;                                          // registrationId
    p += 4;                                          // signedPreKeyId
    p += 4;                                          // signedKeyLen
    final int signedKeyLenAt = p;
    p += 1 + (e[p] & 0xFF);                          // past the signed key
    p += 1 + (e[p] & 0xFF);                          // past its signature

    int preKeyLenAt = -1;
    if ((e[p] & 0xFF) != 0) {
      p += 1 + 4;                                    // hasPreKey, preKeyId
      preKeyLenAt = p;
      p += 1 + (e[p] & 0xFF);                        // past the one-time key
    } else {
      p += 1;
    }

    int kyberKeyLenAt = -1;
    if ((e[p] & 0xFF) != 0) {
      p += 1 + 4;                                    // hasKyber, kyberPreKeyId
      kyberKeyLenAt = p;
    }
    return new int[] {signedKeyLenAt, preKeyLenAt, kyberKeyLenAt};
  }

  /** Appends eight bytes to the field whose length prefix sits at {@code lengthAt}. */
  private static byte[] padFieldAt(final byte[] encoded, final int lengthAt, final boolean u16) {
    if (lengthAt < 0) throw new IllegalStateException("the fixture does not carry that field");
    final int prefixWidth = u16 ? 2 : 1;
    final int length = u16
        ? (((encoded[lengthAt] & 0xFF) << 8) | (encoded[lengthAt + 1] & 0xFF))
        : (encoded[lengthAt] & 0xFF);

    final ByteArrayOutputStream out = new ByteArrayOutputStream();
    out.write(encoded, 0, lengthAt);
    final int padded = length + 8;
    if (u16) {
      out.write((padded >>> 8) & 0xFF);
      out.write(padded & 0xFF);
    } else {
      out.write(padded);
    }
    out.write(encoded, lengthAt + prefixWidth, length);
    for (int i = 0; i < 8; i++) out.write(0x41);
    final int rest = lengthAt + prefixWidth + length;
    out.write(encoded, rest, encoded.length - rest);
    return out.toByteArray();
  }

  /** Pads the signed pre-key's public key - one of the two fields {@code ec()} reads. */
  static byte[] padTheSignedPreKeyField(final byte[] encoded) {
    return padFieldAt(encoded, keyFieldLengthOffsets(encoded)[0], false);
  }

  /** Pads the one-time pre-key's public key - the other field {@code ec()} reads. */
  static byte[] padTheOneTimePreKeyField(final byte[] encoded) {
    return padFieldAt(encoded, keyFieldLengthOffsets(encoded)[1], false);
  }

  /** Pads the Kyber public key, whose length prefix is a u16. */
  static byte[] padTheKyberPreKeyField(final byte[] encoded) {
    return padFieldAt(encoded, keyFieldLengthOffsets(encoded)[2], true);
  }
}
