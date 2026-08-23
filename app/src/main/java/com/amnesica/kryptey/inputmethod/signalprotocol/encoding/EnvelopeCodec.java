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
    final byte[] bytes;
    try {
      bytes = Base64.decode(text.trim());
    } catch (IOException | RuntimeException e) {
      // Base64.decode raises IllegalArgumentException on short input and IOException on a stray
      // character; ordinary clipboard text hits one or the other constantly.
      throw new IOException("not a valid encoded envelope", e);
    }
    return BinaryEnvelope.decode(bytes);
  }

}
