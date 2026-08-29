package com.amnesica.kryptey.inputmethod.signalprotocol.encoding;

import com.amnesica.kryptey.inputmethod.signalprotocol.MessageEnvelope;
import com.amnesica.kryptey.inputmethod.signalprotocol.prekey.KyberPreKeyEntity;
import com.amnesica.kryptey.inputmethod.signalprotocol.prekey.PreKeyEntity;
import com.amnesica.kryptey.inputmethod.signalprotocol.prekey.PreKeyResponse;
import com.amnesica.kryptey.inputmethod.signalprotocol.prekey.PreKeyResponseItem;
import com.amnesica.kryptey.inputmethod.signalprotocol.prekey.SignedPreKeyEntity;

import org.signal.libsignal.protocol.IdentityKey;
import org.signal.libsignal.protocol.InvalidKeyException;
import org.signal.libsignal.protocol.ecc.ECPublicKey;
import org.signal.libsignal.protocol.kem.KEMPublicKey;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedList;
import java.util.List;

/**
 * Compact binary encoding for {@link MessageEnvelope}, replacing base64(minified JSON).
 *
 * <p>Two problems with the JSON form, both measured rather than assumed:
 *
 * <ul>
 *   <li><b>Size — a modest win, and smaller than it first appears.</b> Measured against what the
 *       app actually transmits (raw JSON via the identity {@code RawEncoder}, 2517 characters),
 *       binary-plus-base64 is 2484 — about 1%. The raw binary is 1863 bytes versus 2517, but
 *       base64's 4/3 expansion hands most of that back. Do not justify this format on size.
 *   <li><b>Metadata.</b> Every message carried plaintext JSON field names plus a wall-clock
 *       {@code timestamp}, and the timestamp is not encoded here at all.
 *       <p>Correcting an earlier version of this note, which claimed nothing read that field: it
 *       <em>was</em> read, at {@code SignalProtocolMain.decrypt}, and used as the stored time of a
 *       received message. Dropping it therefore changes behaviour — received messages are now
 *       stamped with the local decode time rather than a time the sender asserted. That is the
 *       better default, and deliberate: a sender-supplied timestamp let a peer backdate or
 *       post-date entries and so reorder the recipient's local history.
 * </ul>
 *
 * <p>The real justifications are the metadata and parsing points above, plus one forward-looking
 * one: masking the envelope later is natural over a byte string and awkward over a JSON document.
 *
 * <p>This is <em>not</em> confidentiality — the payload is a Signal ciphertext that is already
 * authenticated and encrypted, and everything here is recoverable by anyone who knows the format.
 * It removes gratuitous structure and gratuitous metadata. Masking the envelope so it is not
 * recognisable as KryptEY output needs a secret shared before the first message, which this app
 * does not have until out-of-band key exchange exists.
 *
 * <p>Layout — all integers big-endian, all variable-length fields length-prefixed:
 *
 * <pre>
 *   u8   version          = 2
 *   u8   flags            bit0 = has pre-key response, bit1 = has ciphertext
 *   u8   senderNameLen    followed by that many UTF-8 bytes
 *   u8   deviceId         libsignal constrains this to [1,127]
 *   [if bit1]  u8 ciphertextType, u16 ciphertextLen, ciphertext
 *   [if bit0]  u8 identityKeyLen, identityKey,
 *              u8 deviceCount, then per device:
 *                u8 deviceId, u32 registrationId,
 *                u32 signedPreKeyId, u8 len + key, u8 len + signature,
 *                u8 hasPreKey, [u32 preKeyId, u8 len + key],
 *                u8 hasKyber,  [u32 kyberId, u16 len + key, u8 len + signature]
 *              u8 bundleSignatureLen, bundleSignature
 * </pre>
 *
 * <p>The bundle signature covers everything from {@code identityKeyLen} to the end of the device
 * section - the whole bundle as written above, and nothing outside it. It is made with the identity
 * key the bundle carries, and it exists because libsignal signs the signed pre-key and the Kyber
 * pre-key individually and signs nothing tying a bundle's fields to each other. The sender address
 * and the ciphertext are deliberately outside it: the address is what a substitution relabels, and
 * the trust layer is what answers for that.
 *
 * <p>Each length-prefixed key field must contain exactly the key it decodes to. libsignal's
 * deserialisers read from the front and ignore trailing bytes, so without that check the same
 * envelope would have more than one wire spelling - and since the signature is verified over the
 * canonical re-encoding, a padded field would verify.
 */
public final class BinaryEnvelope {

  /**
   * Two, because a bundle now carries a signature binding its fields to each other.
   *
   * <p>A version bump rather than an optional field: an optional signature is one an attacker omits.
   * Mixed-version compatibility is not a requirement here, so the decoder refuses version one
   * outright rather than accepting an unsigned bundle from an older build.
   */
  /**
   * An {@code int}, not a {@code byte}, and the difference is a trap rather than a preference.
   *
   * <p>{@code decode} compares this against {@code c.u8("version")}, which returns 0 to 255. As a
   * {@code byte} any future value from 128 up sign-extends to a negative number, so the comparison
   * fails for every envelope - including ones this encoder has just written, since
   * {@code write(int)} emits the low eight bits either way. The decoder would refuse its own output
   * and every message from every peer would read as "not a valid envelope", on a routine version
   * bump. Nothing is wrong at 2, and nothing would be up to 127; the trap only springs on the
   * bump after next, which is exactly the kind that gets made quickly.
   *
   * <p>Not fixed by masking on the write side. That makes the wire byte right and leaves the
   * comparison signed, so the refusal stays and the cause moves.
   */
  static final int VERSION = 2;
  private static final int FLAG_PRE_KEY_RESPONSE = 0x01;
  private static final int FLAG_CIPHERTEXT = 0x02;

  /**
   * The parser is the right place to enforce protocol constraints, because everything downstream
   * takes these values on trust. libsignal validates them too — but it does so by throwing
   * <em>unchecked</em> {@code IllegalArgumentException} ("device ID is out of range", "integer
   * overflow during conversion"), and no caller on the clipboard path catches unchecked exceptions.
   * A hostile bundle with an inner deviceId of 0, or a registrationId with the high bit set, decoded
   * cleanly here and then killed the IME process inside {@code new PreKeyBundle(...)}.
   */
  /**
   * A presence flag is 0 or 1 and nothing else.
   *
   * <p>Read as {@code != 0}, the 254 other values all meant "present" - so every envelope carrying
   * an optional field had 254 alternative wire texts that decoded to an identical object and passed
   * both the canonical-encoding check and {@code requireExhausted}. Canonicality is a property of
   * the whole wire text; a byte with 255 accepted spellings is a hole in it.
   */
  private static boolean requireFlag(final int value, final String field) throws IOException {
    if (value != 0 && value != 1) {
      throw new IOException(field + " must be 0 or 1, was " + value);
    }
    return value == 1;
  }

  private static void requireValidDeviceId(final int deviceId) throws IOException {
    if (deviceId < 1 || deviceId > 127) {
      throw new IOException("device id out of libsignal's range [1,127]: " + deviceId);
    }
  }

  /** libsignal treats these as unsigned; a sign-extended negative overflows during conversion. */
  private static void requireUnsigned(final int value, final String what) throws IOException {
    if (value < 0) throw new IOException(what + " must be non-negative, got " + value);
  }

  private static final int MAX_SENDER_NAME_CHARS = 64;

  /**
   * Constrains the sender name to characters that cannot misrepresent themselves on screen.
   *
   * <p>This value is attacker-supplied and reaches the contact list and the info banner
   * ("Detected contact: …"). Left unchecked it admits C0/C1 control characters, and bidirectional
   * overrides such as U+202E, which reorder surrounding text when rendered — a cheap way to make
   * one contact display as another. Invalid UTF-8 also silently becomes U+FFFD rather than being
   * rejected.
   *
   * <p>The constraint is tight because it can be: every name this app generates is a
   * {@code UUID.randomUUID().toString()}, so printable ASCII costs nothing legitimate. If the
   * naming scheme ever changes — deriving it from the identity key fingerprint, say — this is the
   * place to widen, and the envelope's version byte is how to do it compatibly.
   */
  private static void requireDisplaySafeName(final String name) throws IOException {
    if (name.isEmpty()) throw new IOException("sender name is empty");
    if (name.length() > MAX_SENDER_NAME_CHARS) {
      throw new IOException("sender name too long: " + name.length());
    }
    for (int i = 0; i < name.length(); i++) {
      final char c = name.charAt(i);
      if (c < 0x20 || c > 0x7E) {
        throw new IOException("sender name contains a non-printable or non-ASCII character at "
            + i + " (U+" + String.format("%04X", (int) c) + ")");
      }
    }
  }

  private BinaryEnvelope() {
  }

  public static byte[] encode(final MessageEnvelope envelope) throws IOException {
    if (envelope == null) throw new IOException("envelope is null");

    final ByteArrayOutputStream out = new ByteArrayOutputStream(2048);
    final boolean hasBundle = envelope.getPreKeyResponse() != null;
    final boolean hasCiphertext = envelope.getCiphertextMessage() != null;
    if (!hasBundle && !hasCiphertext) throw new IOException("envelope carries nothing");

    out.write(VERSION);
    out.write((hasBundle ? FLAG_PRE_KEY_RESPONSE : 0) | (hasCiphertext ? FLAG_CIPHERTEXT : 0));

    requireDisplaySafeName(nonNull(envelope.getSignalProtocolAddressName(), "sender name"));
    final byte[] name = envelope.getSignalProtocolAddressName().getBytes(StandardCharsets.UTF_8);
    out.write(name.length);
    out.write(name, 0, name.length);

    requireValidDeviceId(envelope.getDeviceId());
    writeU8(out, envelope.getDeviceId(), "deviceId");

    if (hasCiphertext) {
      writeU8(out, envelope.getCiphertextType(), "ciphertextType");
      writeVarU16(out, envelope.getCiphertextMessage());
    }

    if (hasBundle) {
      writeBundle(out, envelope.getPreKeyResponse());
      // Mandatory whenever a bundle is present. An encoder that can emit an unsigned bundle is a
      // decoder that has to accept one.
      writeVarU8(out, nonNull(envelope.getBundleSignature(), "bundleSignature"));
    }

    return out.toByteArray();
  }

  public static MessageEnvelope decode(final byte[] bytes) throws IOException {
    final Cursor c = new Cursor(bytes);

    final int version = c.u8("version");
    if (version != VERSION) throw new IOException("unsupported envelope version: " + version);
    final int flags = c.u8("flags");
    if ((flags & ~(FLAG_PRE_KEY_RESPONSE | FLAG_CIPHERTEXT)) != 0) {
      throw new IOException("unknown envelope flags: " + flags);
    }
    if ((flags & (FLAG_PRE_KEY_RESPONSE | FLAG_CIPHERTEXT)) == 0) {
      // encode() refuses to emit an envelope carrying nothing; decode must not accept one either,
      // or the parser admits a shape the encoder cannot produce.
      throw new IOException("envelope carries nothing");
    }

    final String name = new String(c.bytes(c.u8("nameLen"), "senderName"), StandardCharsets.UTF_8);
    requireDisplaySafeName(name);
    final int deviceId = c.u8("deviceId");
    requireValidDeviceId(deviceId);

    byte[] ciphertext = null;
    int ciphertextType = 0;
    if ((flags & FLAG_CIPHERTEXT) != 0) {
      ciphertextType = c.u8("ciphertextType");
      final int ciphertextLen = c.u16("ciphertextLen");
      if (ciphertextLen == 0) throw new IOException("ciphertext flag set but length is zero");
      ciphertext = c.bytes(ciphertextLen, "ciphertext");
    }

    PreKeyResponse bundle = null;
    byte[] bundleSignature = null;
    if ((flags & FLAG_PRE_KEY_RESPONSE) != 0) {
      bundle = readBundle(c);
      bundleSignature = c.bytes(c.u8("bundleSignatureLen"), "bundleSignature");
      if (bundleSignature.length == 0) throw new IOException("bundle signature is empty");
    }

    c.requireExhausted();

    final MessageEnvelope envelope;
    if (ciphertext != null) {
      envelope = new MessageEnvelope(ciphertext, ciphertextType, name, deviceId);
      if (bundle != null) envelope.setPreKeyResponse(bundle);
    } else {
      envelope = new MessageEnvelope(bundle, name, deviceId);
    }
    if (bundleSignature != null) envelope.setBundleSignature(bundleSignature);
    return envelope;
  }

  /**
   * The exact bytes a bundle signature covers.
   *
   * <p>Produced by the one encoder, so signer and verifier cannot disagree about the canonical form:
   * the fields have a fixed order, the presence markers are exactly 0 or 1, and the decoder refuses
   * every other spelling. Re-encoding what was parsed is what lets the verifier work from the
   * bundle rather than from byte offsets it would have to track through the parser.
   */
  public static byte[] canonicalBundleBytes(final PreKeyResponse bundle) throws IOException {
    if (bundle == null) throw new IOException("bundle is null");
    final ByteArrayOutputStream out = new ByteArrayOutputStream(2048);
    writeBundle(out, bundle);
    return out.toByteArray();
  }

  // ------------------------------------------------------------------ bundle

  private static void writeBundle(final ByteArrayOutputStream out, final PreKeyResponse bundle)
      throws IOException {
    writeVarU8(out, nonNull(bundle.getIdentityKey(), "identityKey").serialize());

    final List<PreKeyResponseItem> devices = bundle.getDevices();
    if (devices == null || devices.isEmpty()) throw new IOException("bundle has no devices");
    // Deliberately still 1..255 here, while the DECODER refuses anything but one.
    //
    // The asymmetry was reported as a written-and-false invariant and the first fix was to tighten
    // this too. That was wrong twice over. An attacker does not use this encoder - the decoder is
    // the boundary, and it is the side that had to change - and this encoder is how the tests build
    // the hostile multi-device wire texts that prove the decoder refuses them. Tightening here
    // removed the ability to construct the adversarial input without protecting anything.
    //
    // So the claim is corrected rather than enforced: what is true is that the app's own callers
    // build exactly one device, which is why refusing more on the way in costs nothing.
    if (devices.size() > 255) throw new IOException("too many devices: " + devices.size());
    out.write(devices.size());

    for (final PreKeyResponseItem device : devices) {
      requireValidDeviceId(device.getDeviceId());
      writeU8(out, device.getDeviceId(), "device deviceId");
      requireUnsigned(device.getRegistrationId(), "registrationId");
      writeU32(out, device.getRegistrationId());

      final SignedPreKeyEntity signed = nonNull(device.getSignedPreKey(), "signedPreKey");
      writeU32(out, signed.getKeyId());
      writeVarU8(out, nonNull(signed.getPublicKey(), "signedPreKey.publicKey").serialize());
      writeVarU8(out, nonNull(signed.getSignature(), "signedPreKey.signature"));

      final PreKeyEntity preKey = device.getPreKey();
      out.write(preKey == null ? 0 : 1);
      if (preKey != null) {
        writeU32(out, preKey.getKeyId());
        writeVarU8(out, nonNull(preKey.getPublicKey(), "preKey.publicKey").serialize());
      }

      final KyberPreKeyEntity kyber = device.getKyberPreKey();
      out.write(kyber == null ? 0 : 1);
      if (kyber != null) {
        writeU32(out, kyber.getKeyId());
        // u16: a Kyber-1024 public key is 1569 bytes and does not fit a u8 length.
        writeVarU16(out, nonNull(kyber.getPublicKey(), "kyberPreKey.publicKey").serialize());
        writeVarU8(out, nonNull(kyber.getSignature(), "kyberPreKey.signature"));
      }
    }
  }

  private static PreKeyResponse readBundle(final Cursor c) throws IOException {
    final IdentityKey identityKey;
    try {
      final byte[] asRead = c.bytes(c.u8("identityKeyLen"), "identityKey");
      identityKey = exactlyItsOwnEncoding(new IdentityKey(asRead, 0),
          new IdentityKey(asRead, 0).serialize(), asRead, "identity key");
    } catch (InvalidKeyException e) {
      throw new IOException("malformed identity key", e);
    }

    final int deviceCount = c.u8("deviceCount");
    if (deviceCount == 0) throw new IOException("bundle has no devices");
    // Exactly one, because exactly one is ever BUILT by this app and exactly one is ever read.
    //
    // Not "exactly one is ever encoded": the encoder still accepts up to 255, deliberately, because
    // it is what the tests use to construct the hostile input this refusal is about. The property
    // that matters is about the app's own callers - createPreKeyResponse builds one item, and the
    // rotation path goes through it - so refusing more on the way in cannot reject anything the app
    // can legitimately produce.
    //
    // createPreKeyBundle consumes getDevices().get(0) and nothing else, so entries 1..254 were
    // parsed, retained and validated against nothing. Each costs about 300 bytes and can carry up
    // to 255 unchecked bytes in its signature field, and filler entries may set both optional flags
    // to zero and reuse one genuine EC point - so within MAX_WIRE_CHARS roughly five kilobytes of
    // arbitrary attacker bytes rode inside an envelope this parser then declared exhausted.
    //
    // That directly contradicts requireExhausted's own sentence: "refusing keeps a hostile envelope
    // from smuggling data past the parser". Nothing renders those bytes, so this was a broken
    // stated invariant and a malleability primitive rather than the staple-prose attack the
    // canonical check defends against - but an invariant that is written down and false is worse
    // than one that was never claimed.
    if (deviceCount != 1) {
      throw new IOException("bundle carries " + deviceCount + " devices; exactly one is encoded "
          + "and exactly one is ever used");
    }

    final List<PreKeyResponseItem> devices = new LinkedList<>();
    for (int i = 0; i < deviceCount; i++) {
      final int deviceId = c.u8("device deviceId");
      requireValidDeviceId(deviceId);
      final int registrationId = c.u32("registrationId");
      requireUnsigned(registrationId, "registrationId");
      // libsignal's registration ids are 14 bits. Accepting the whole non-negative int range let a
      // sender put 31 bits of chosen data in a field the encoder can only ever fill with 14.
      if (registrationId > 16380) {
        throw new IOException("registrationId out of libsignal's range: " + registrationId);
      }

      final int signedId = c.u32("signedPreKeyId");
      requireUnsigned(signedId, "signedPreKeyId");
      final ECPublicKey signedKey = ec(c.bytes(c.u8("signedKeyLen"), "signedPreKey"));
      final byte[] signedSig = c.bytes(c.u8("signedSigLen"), "signedPreKey.signature");

      // The top-level device id and this one are deliberately NOT required to agree.
      //
      // They can disagree, and nothing compares them: the pin is made at the top-level address
      // while this id is what reaches new PreKeyBundle. Requiring agreement was tried and reverted,
      // because it buys nothing and costs something. It buys nothing: the top-level NAME is equally
      // sender-chosen, so a fresh unpinned address is already free, and an attacker who wants one
      // simply writes both ids the same. It costs something: envelopes that disagree are exactly
      // what a lazy splice produces, and refusing them at the parser turns a substitution attempt
      // the trust layer would have WARNED about into an unexplained "not a valid encoded envelope".
      // Refusing input is usually the safe direction; here it removes a signal and blocks nobody.
      //
      // What the disagreement actually costs the user is real and lives elsewhere: two contacts
      // sharing an address name share one chat log, which ImpostorDeviceIdTest pins.

      PreKeyEntity preKey = null;
      if (requireFlag(c.u8("hasPreKey"), "hasPreKey")) {
        final int preKeyId = c.u32("preKeyId");
        requireUnsigned(preKeyId, "preKeyId");
        preKey = new PreKeyEntity(preKeyId, ec(c.bytes(c.u8("preKeyLen"), "preKey")));
      }

      KyberPreKeyEntity kyber = null;
      if (requireFlag(c.u8("hasKyber"), "hasKyber")) {
        final int kyberId = c.u32("kyberPreKeyId");
        requireUnsigned(kyberId, "kyberPreKeyId");
        final byte[] kyberKey = c.bytes(c.u16("kyberKeyLen"), "kyberPreKey");
        final byte[] kyberSig = c.bytes(c.u8("kyberSigLen"), "kyberPreKey.signature");
        try {
          final KEMPublicKey kyberPublic = new KEMPublicKey(kyberKey);
          kyber = new KyberPreKeyEntity(kyberId,
              exactlyItsOwnEncoding(kyberPublic, kyberPublic.serialize(), kyberKey,
                  "Kyber public key"),
              kyberSig);
        } catch (InvalidKeyException e) {
          throw new IOException("malformed kyber pre key", e);
        }
      }

      devices.add(new PreKeyResponseItem(deviceId, registrationId,
          new SignedPreKeyEntity(signedId, signedKey, signedSig), preKey, kyber));
    }
    return new PreKeyResponse(identityKey, devices);
  }

  private static ECPublicKey ec(final byte[] serialized) throws IOException {
    final ECPublicKey key;
    try {
      key = new ECPublicKey(serialized, 0);
    } catch (InvalidKeyException e) {
      throw new IOException("malformed EC public key", e);
    }
    return exactlyItsOwnEncoding(key, key.serialize(), serialized, "EC public key");
  }

  /**
   * Refuses a key field carrying more bytes than the key it decodes to.
   *
   * <p>libsignal's deserialisers read a key from the FRONT of the array and ignore what follows -
   * measured: a 33-byte EC key with eight bytes appended is accepted and re-serialises to the same
   * 33 bytes. So a length-prefixed key field could carry arbitrary trailing bytes, and the parser
   * then called itself exhausted. That is the same broken invariant the device-count refusal above
   * was written for, in a field that field's fix does not reach.
   *
   * <p>It matters more now than it did: a bundle's signature is verified over the CANONICAL
   * re-encoding of what was parsed, so padding does not break the signature - it re-encodes away.
   * A genuine invite could therefore be padded by a relay and still verify, which makes the wire
   * text malleable while leaving every downstream decision identical. Nothing renders those bytes,
   * so this is a malleability primitive rather than a live attack; it is refused because the
   * alternative is a written invariant that is false, which this project treats as a defect of its
   * own.
   *
   * <p>By comparison rather than by a length constant: hard-coding 33 and 1569 would turn a
   * libsignal upgrade that changes a serialisation into "every invite is refused".
   */
  private static <T> T exactlyItsOwnEncoding(final T key, final byte[] canonical,
                                             final byte[] asRead, final String what)
      throws IOException {
    if (!java.util.Arrays.equals(canonical, asRead)) {
      throw new IOException(what + " field is " + asRead.length + " bytes but the key it decodes "
          + "to is " + canonical.length + ": trailing bytes ride past a parser that says nothing "
          + "rides past it");
    }
    return key;
  }

  // ------------------------------------------------------------------- write

  private static void writeU8(final ByteArrayOutputStream out, final int value, final String what)
      throws IOException {
    if (value < 0 || value > 255) throw new IOException(what + " out of range: " + value);
    out.write(value);
  }

  private static void writeU32(final ByteArrayOutputStream out, final int value) {
    out.write((value >>> 24) & 0xFF);
    out.write((value >>> 16) & 0xFF);
    out.write((value >>> 8) & 0xFF);
    out.write(value & 0xFF);
  }

  private static void writeVarU8(final ByteArrayOutputStream out, final byte[] value)
      throws IOException {
    if (value.length > 255) throw new IOException("field too long for u8 length: " + value.length);
    out.write(value.length);
    out.write(value, 0, value.length);
  }

  private static void writeVarU16(final ByteArrayOutputStream out, final byte[] value)
      throws IOException {
    if (value.length > 65535) throw new IOException("field too long: " + value.length);
    out.write((value.length >>> 8) & 0xFF);
    out.write(value.length & 0xFF);
    out.write(value, 0, value.length);
  }

  private static <T> T nonNull(final T value, final String what) throws IOException {
    if (value == null) throw new IOException(what + " is null");
    return value;
  }

  /** Bounds-checked reader: a truncated or hostile envelope must raise IOException, never AIOOBE. */
  private static final class Cursor {
    private final byte[] buf;
    private int pos;

    Cursor(final byte[] buf) throws IOException {
      if (buf == null) throw new IOException("envelope is null");
      this.buf = buf;
    }

    int u8(final String what) throws IOException {
      require(1, what);
      return buf[pos++] & 0xFF;
    }

    int u16(final String what) throws IOException {
      require(2, what);
      return ((buf[pos++] & 0xFF) << 8) | (buf[pos++] & 0xFF);
    }

    int u32(final String what) throws IOException {
      require(4, what);
      return ((buf[pos++] & 0xFF) << 24) | ((buf[pos++] & 0xFF) << 16)
          | ((buf[pos++] & 0xFF) << 8) | (buf[pos++] & 0xFF);
    }

    byte[] bytes(final int length, final String what) throws IOException {
      require(length, what);
      final byte[] value = new byte[length];
      System.arraycopy(buf, pos, value, 0, length);
      pos += length;
      return value;
    }

    void requireExhausted() throws IOException {
      // Trailing bytes mean the sender and receiver disagree about the format. Refusing keeps a
      // hostile envelope from smuggling data past the parser.
      if (pos != buf.length) {
        throw new IOException("trailing bytes in envelope: " + (buf.length - pos));
      }
    }

    private void require(final int n, final String what) throws IOException {
      if (n < 0 || pos + n > buf.length) {
        throw new IOException("truncated envelope reading " + what);
      }
    }
  }
}
