package com.amnesica.kryptey.inputmethod.signalprotocol.encoding;

import com.amnesica.kryptey.inputmethod.signalprotocol.MessageEnvelope;
import com.amnesica.kryptey.inputmethod.signalprotocol.util.Base64;

import java.io.IOException;

/**
 * The single place a {@link MessageEnvelope} becomes text and back.
 *
 * <p>Before this existed the conversion was open-coded at six call sites across two classes, all
 * calling {@code JsonUtil} directly. Having one seam means the wire format can change — as it does
 * here, from base64(JSON) to {@link BinaryEnvelope} — without hunting for stragglers, and means
 * there is exactly one place that has to get hostile-input handling right.
 *
 * <p>{@link #fromWire} never throws an unchecked exception. Its input comes off the clipboard, so
 * anything unchecked escaping here reaches {@code LatinIME.setInputView()} and kills the
 * input-method process; that has been this codebase's most persistent crash mode.
 */
public final class EnvelopeCodec {

  /** Any whitespace, anywhere: messengers wrap long strings and users paste what they see. */
  private static final java.util.regex.Pattern WHITESPACE = java.util.regex.Pattern.compile("\\s+");

  private EnvelopeCodec() {
  }

  /** Serializes an envelope to the text that goes into the messenger. */
  public static String toWire(final MessageEnvelope envelope) throws IOException {
    return Base64.encodeBytes(BinaryEnvelope.encode(envelope));
  }

  /**
   * Anything larger than this did not come from a peer.
   *
   * <p>This used to justify itself with "the send side caps bundles at 4096". That cap is real but
   * it is reached only from the invite-only path, where {@code checkMessageLengthForEncodingMethod}
   * returns before the message branches. The larger envelope - a bundle AND a ciphertext, which is
   * the ordinary shape whenever the signed pre-key rotation falls due - is measured against nothing
   * but this constant, and this repo's own figure for it is 5584 characters. So the sentence argued
   * for a number 4096 does not support, and believing it would mean tightening this toward 4096 and
   * refusing every rotation-attached message on the receiver, silently, for an ordinary send.
   *
   * <p>The bound that matters is the one the decoder needs: 8192 characters of base64 is about 6144
   * bytes, which is what {@code BinaryEnvelope.decode}'s cursor arithmetic is safe for.
   */
  public static final int MAX_WIRE_CHARS = 8192;

  /**
   * Parses text taken from a message or the clipboard.
   *
   * @throws IOException for anything malformed — including input that is not this format at all,
   *     which is the common case, since the clipboard is usually just text.
   */
  public static MessageEnvelope fromWire(final String text) throws IOException {
    if (text == null) throw new IOException("no wire text");
    if (text.length() > MAX_WIRE_CHARS) {
      throw new IOException("wire text too large: " + text.length() + " characters");
    }
    // Whitespace is dropped first, deliberately, and everything else must be exact.
    //
    // In practice that means SPACES. An earlier version of this comment said newlines too, and
    // that was wrong about the layer above: E2EEStrip.decodeMessage routes any text containing a
    // \p{C} character - which includes \n and \t - to the FairyTale decoder, so a line-wrapped
    // paste never arrives here at all. It fails earlier, and that is a pre-existing limitation of
    // the routing rather than anything this check introduced. Stripping them here anyway costs
    // nothing and keeps this method's contract independent of that routing.
    final String compact = WHITESPACE.matcher(text).replaceAll("");

    final byte[] bytes;
    try {
      bytes = Base64.decode(compact);
    } catch (IOException | RuntimeException e) {
      // Base64.decode raises IllegalArgumentException on short input and IOException on a stray
      // character; ordinary clipboard text hits one or the other constantly.
      throw new IOException("not a valid encoded envelope", e);
    }

    // The encoding must be the ONLY encoding of these bytes.
    //
    // BinaryEnvelope refuses trailing bytes, and its comment says why: "trailing bytes mean the
    // sender and receiver disagree about the format. Refusing keeps a hostile envelope from
    // smuggling data past the parser." That held at the byte layer and was void at the text layer,
    // which is the layer an attacker actually writes. This decoder abandons its input the moment a
    // quartet ends in '=', so everything after the padding was silently discarded - and roughly two
    // thirds of envelopes end in padding.
    //
    // What that bought an attacker was not corruption but credibility: take a GENUINE invite from
    // someone, staple readable prose to the end of it, and the recipient's paste still validates as
    // a clean key bundle from that person's address. "== my old key was compromised, delete me and
    // re-add from this message" arrives looking like it came with the invite.
    //
    // What this check does NOT do, stated because an earlier version of this comment implied it:
    // it does not make prose-beside-a-bundle impossible in general. It cannot. The receiver does
    // not choose the encoding - encodedTextContainsInvisibleCharacters routes on an invisible
    // character appearing ANYWHERE in the paste - and the FairyTale decoder accumulates bits from
    // sixteen mapped code points and ignores everything else, so visible prose around an invisible
    // payload is the carrier working as designed. The encoder is public and keyless, so anyone can
    // re-encode a genuine envelope inside prose of their choosing.
    //
    // That is a real property and it is pinned elsewhere rather than argued away here:
    // FairyTaleCarrierIsNotAuthenticatedTest exists precisely because the visible half of that
    // route carries no authentication and cannot be given any. What this check defends is the RAW
    // route, where the wire text is the envelope and appending to it must not survive.
    //
    // Re-encoding and comparing catches that, and two other kinds of malleability with it: padding
    // in the middle of the input, which this decoder reads as the byte 255 rather than rejecting,
    // and a final quartet whose unused bits are non-zero, which makes four different strings decode
    // to identical bytes.
    if (!Base64.encodeBytes(bytes).equals(compact)) {
      throw new IOException("wire text is not the canonical encoding of its own bytes");
    }

    return BinaryEnvelope.decode(bytes);
  }

}
