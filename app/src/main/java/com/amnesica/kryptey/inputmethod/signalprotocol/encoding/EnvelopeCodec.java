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

  /** Anything larger than this did not come from a peer; the send side caps bundles at 4096. */
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
    // Messengers wrap and re-flow text, so tolerating newlines and spaces inside the encoding is
    // the difference between a keyboard that works and one that does not. Nothing else is
    // tolerated - see the canonicality check below for why.
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
    // re-add from this message" arrives looking like it came with the invite. Without this, the
    // prose has to sit outside the envelope where the user can see it is just text.
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
