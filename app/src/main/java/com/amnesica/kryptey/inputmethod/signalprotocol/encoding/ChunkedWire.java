package com.amnesica.kryptey.inputmethod.signalprotocol.encoding;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Splits a wire string across several messages, and puts it back together.
 *
 * <p>An invite is about 2,572 characters and the UI emits it as one message, so every platform
 * whose per-message limit is below that cannot carry a handshake at all - Discord and Signal at
 * 2,000, Mastodon at 500, Bluesky at 300, a free X post at 280. Those same platforms carry an
 * established conversation comfortably, because once the peer has replied a message costs about
 * 200 characters rather than 2,404. So the limit does not make the app unusable on them; it makes
 * it unstartable, which is a much smaller problem wearing a larger one's clothes.
 *
 * <p>Reassembly already half-worked by accident: {@code EnvelopeCodec.fromWire} strips all
 * whitespace before decoding, so two halves pasted together with a space between them parse. Two
 * halves pasted with a NEWLINE between them do not, because {@code E2EEStrip.decodeMessage} routes
 * any text containing a {@code \p{C}} character to the FairyTale decoder - and a paste of two chat
 * messages is newline-separated. The one workaround available was the one a user would get wrong.
 *
 * <h2>The format</h2>
 *
 * <pre>#K&lt;part&gt;/&lt;total&gt;#&lt;set&gt;#&lt;body&gt;</pre>
 *
 * <p>for example {@code #K2/3#a7c91f#eyJ0eXAiOi...}. The wire alphabet is base64
 * ({@code A-Za-z0-9+/=}), so a leading {@code #} cannot occur in an unchunked message and there is
 * no ambiguity to resolve: text either starts with the marker or it does not.
 *
 * <h2>What the set identifier is, and what it is not</h2>
 *
 * <p>{@code set} is the first six hex characters of the SHA-256 of the whole assembled wire text.
 * It exists so that parts of two DIFFERENT invites cannot be silently spliced together - which is a
 * live case rather than a hypothetical, because re-inviting someone is the app's own advice after a
 * failed decrypt, and the second invite's parts land in the same chat as the first's.
 *
 * <p>It is <strong>not</strong> an integrity check and must not be read as one. Twenty-four bits
 * would be a poor one, and more importantly the thing it would be protecting is already protected:
 * a bundle carries an issuing signature that {@code requireTheBundleWasIssuedAsOneUnit} verifies,
 * and that is what stands between the user and a tampered invite. This only answers "are these the
 * parts of one message, and are they all here" - a question about the user's clipboard, not about
 * an adversary.
 *
 * <h2>Nothing here throws an unchecked exception</h2>
 *
 * <p>Its input comes off the clipboard, and this codebase's most persistent crash mode is an
 * unchecked exception reaching {@code LatinIME.setInputView()} and killing the input-method process
 * in whatever app the user happens to be in. Every failure below is an {@link IOException} whose
 * message says what to do about it.
 *
 * <h2>The exception text never contains the input</h2>
 *
 * <p>The strip shows {@link PartRefusedException#getMessage()} in a toast, and a toast is a separate
 * system window that {@code FLAG_SECURE} does not cover. So every message thrown here is built from
 * literals and part counts only - never from a body, a header, or anything else that was pasted.
 * {@code NoToastCarriesMessageContentTest} admits this exception on that argument, and
 * {@code ChunkedWireTest.noRefusalMessageContainsWhatWasPasted} is the control for it.
 */
public final class ChunkedWire {

  /**
   * A pasted part that cannot be used, with a message written for the user.
   *
   * <p>Its own type so that the strip can catch it narrowly and show the text: the parent class is
   * what every decoder in this package throws, and the text of those is not written for a screen.
   */
  public static final class PartRefusedException extends IOException {
    PartRefusedException(final String message) {
      super(message);
    }
  }

  /** Outside the base64 alphabet, so an unchunked wire string can never begin with it. */
  public static final String MARKER = "#K";

  /** Above this the header's own digits start costing more than the split saves. */
  private static final int MAX_PARTS = 999;

  private static final Pattern CHUNK = Pattern.compile(
      "^#K(\\d{1,3})/(\\d{1,3})#([0-9a-f]{6})#([A-Za-z0-9+/=]*)$");

  /** The header alone, for recognising a part before anything is done with it. */
  private static final Pattern HEADER = Pattern.compile("^#K\\d{1,3}/\\d{1,3}#[0-9a-f]{6}#");

  /**
   * Where one part ends and the next begins, inside a paste that carries several.
   *
   * <p>The whole header, and the reason is a defect this had: splitting on the two-character
   * {@link #MARKER} cuts a part in half whenever its BODY begins with the letter {@code K}. The
   * character before the body is the header's own closing {@code #}, so {@code "…#a7c91f#KLMN…"}
   * contains {@code "#K"} and the splitter cuts there - leaving a headerless fragment and a
   * header-shaped nothing, both refused as damaged.
   *
   * <p>Base64 is a sixth of the way through its alphabet at {@code K}, so this is one part in
   * sixty-four, several parts to a message: about one split message in eleven was refused, on
   * correctly produced parts, with the app telling the user their invite was damaged and to ask for
   * another - which would be refused at the same rate. It surfaced as a flaky test, green alone and
   * red in the full suite, because whether it fires depends on the random key material in the
   * invite being split. Pinned by {@code abodyBeginningWithKisNotAsecondPart}.
   *
   * <p>Safe where the marker was not, because the body cannot contain {@code #} at all: base64 is
   * {@code A-Za-z0-9+/=}, so every {@code #} in a compacted paste belongs to a header.
   */
  private static final Pattern PART_BOUNDARY =
      Pattern.compile("(?=#K\\d{1,3}/\\d{1,3}#[0-9a-f]{6}#)");

  private static final Pattern WHITESPACE = Pattern.compile("\\s+");

  private ChunkedWire() {
  }

  /** The first six hex characters of the SHA-256 of {@code wire}. See the class note. */
  public static String setIdOf(final String wire) throws IOException {
    if (wire == null) throw new IOException("no text to identify");
    try {
      final byte[] digest =
          MessageDigest.getInstance("SHA-256").digest(wire.getBytes(StandardCharsets.UTF_8));
      final StringBuilder hex = new StringBuilder(6);
      // Locale.ROOT rather than the default: the id travels between two devices in whatever
      // locales they have, and must read the same on both.
      for (int i = 0; i < 3; i++) {
        hex.append(String.format(java.util.Locale.ROOT, "%02x", digest[i] & 0xff));
      }
      return hex.toString();
    } catch (final NoSuchAlgorithmException impossible) {
      // SHA-256 is required of every Java platform. Reported rather than thrown unchecked, because
      // this is reachable from a click listener.
      throw new IOException("this device has no SHA-256", impossible);
    }
  }

  private static String header(final int part, final int total, final String setId) {
    return MARKER + part + "/" + total + "#" + setId + "#";
  }

  /**
   * True when {@code text} begins with a whole part header, whitespace ignored.
   *
   * <p>The whole header and not the two-character marker. The first version tested
   * {@code startsWith("#K")}, and the strip lights Decrypt on whatever this says yes to - so
   * copying "#Kubernetes" in any app lit it, wrote "part of a split message is on the clipboard",
   * and a press then destroyed the clipboard with a refusal about a damaged part. Whitespace is
   * removed before matching rather than only trimmed, because a messenger may wrap the header
   * itself and {@link Assembler#accept} is written to tolerate exactly that.
   */
  public static boolean isChunk(final String text) {
    if (text == null) return false;
    return HEADER.matcher(WHITESPACE.matcher(text).replaceAll("")).lookingAt();
  }

  /**
   * Splits {@code wire} into messages of at most {@code maxChars} characters each, headers included.
   *
   * <p>Returns the input unchanged, in a single-element list and with no header, when it already
   * fits. A message that does not need chunking must not be wrapped: the recipient of an unwrapped
   * message needs no new behaviour at all, and that keeps the ordinary path exactly as it was.
   *
   * @throws IOException if {@code maxChars} is too small for any split to make progress
   */
  public static List<String> split(final String wire, final int maxChars) throws IOException {
    if (wire == null || wire.isEmpty()) throw new IOException("nothing to split");
    final List<String> out = new ArrayList<>();
    if (wire.length() <= maxChars) {
      out.add(wire);
      return out;
    }

    final String setId = setIdOf(wire);
    for (int parts = 2; parts <= MAX_PARTS; parts++) {
      // The widest header this set can produce, not the first one. Part 9 of 10 has a shorter
      // header than part 10 of 10, and sizing against the shorter one puts the last message over
      // the limit - on the platform whose limit was the entire reason for splitting.
      final int widest = header(parts, parts, setId).length();
      final int body = maxChars - widest;
      if (body <= 0) continue;
      if ((long) body * parts >= wire.length()) {
        // Every part is non-empty, so the count in the headers always matches the number of
        // messages produced - which matters, because a recipient told to expect a part that was
        // never sent waits for it forever.
        //
        // The argument, since the first version of this carried a re-numbering fallback for a case
        // that cannot arise: this loop takes the SMALLEST parts count that fits, so at parts-1 the
        // text did not fit - body(parts-1) * (parts-1) < length. A header never narrows as the
        // count rises, so body(parts) <= body(parts-1), and therefore
        // body(parts) * (parts-1) < length too. The last part starts at (parts-1) * body(parts),
        // which is below length, so it has at least one character in it. Pinned by
        // everyPartIsNonEmptyAtEverySize.
        for (int i = 0; i < parts; i++) {
          final int from = i * body;
          final int to = Math.min(wire.length(), from + body);
          out.add(header(i + 1, parts, setId) + wire.substring(from, to));
        }
        return out;
      }
    }
    throw new IOException("A limit of " + maxChars + " characters is too small to send this in "
        + "parts. Use a channel that allows longer messages.");
  }

  /** Collects the parts of one chunked message until they are all present. */
  public static final class Assembler {

    private final Map<Integer, String> parts = new TreeMap<>();
    private String setId;
    private int total;

    /**
     * Takes one pasted message, which may contain several chunks, and returns the assembled wire
     * text once every part has arrived - or {@code null} while parts are still missing.
     *
     * @throws PartRefusedException when the text is not chunked at all, or when a part cannot
     *     belong to the set already being collected; the message is written for the user
     * @throws IOException only if this device cannot compute SHA-256
     */
    public String accept(final String pastedText) throws IOException {
      if (pastedText == null) throw new PartRefusedException("Nothing to add.");
      // Whitespace goes first and everywhere. Messengers wrap long strings, users paste what they
      // see, and both the header and the body are whitespace-free by construction - so there is
      // nothing here that a space could legitimately be part of.
      final String compact = WHITESPACE.matcher(pastedText).replaceAll("");
      if (!HEADER.matcher(compact).lookingAt()) {
        throw new PartRefusedException("That is not part of a split message.");
      }

      // One paste can carry several parts - a user selecting a run of messages gets all of them at
      // once, and refusing that would be refusing the easiest way to do this correctly.
      for (final String piece : PART_BOUNDARY.split(compact)) {
        if (piece.isEmpty()) continue;
        acceptOne(piece);
      }

      if (total == 0 || parts.size() < total) return null;

      final StringBuilder joined = new StringBuilder();
      for (int i = 1; i <= total; i++) joined.append(parts.get(i));
      final String wire = joined.toString();

      if (!setIdOf(wire).equals(setId)) {
        // Every part was present and self-consistent and the whole is still wrong, so a part's
        // CONTENT is damaged - truncated by a paste, or altered by a messenger that reformats text.
        reset();
        throw new PartRefusedException("The parts are all here but they do not fit together. One "
            + "of them was probably cut short when it was copied. Ask for it to be sent again.");
      }
      return wire;
    }

    private void acceptOne(final String piece) throws PartRefusedException {
      final Matcher m = CHUNK.matcher(piece);
      if (!m.matches()) {
        throw new PartRefusedException("One of those parts is damaged and cannot be read. Ask for "
            + "it to be sent again rather than re-copying it.");
      }
      final int index = Integer.parseInt(m.group(1));
      final int declared = Integer.parseInt(m.group(2));
      final String id = m.group(3);
      final String body = m.group(4);

      if (declared < 1 || index < 1 || index > declared) {
        throw new PartRefusedException("That part is numbered " + index + " of " + declared
            + ", which cannot be right. Ask for it to be sent again.");
      }

      if (setId == null) {
        setId = id;
        total = declared;
      } else if (!setId.equals(id)) {
        // The case this exists for: re-inviting is what the app itself advises after a failed
        // decrypt, so a second invite's parts land in the same conversation as the first's. Joined
        // blindly they would produce a string that decodes to nothing, reported as a corrupt
        // invite, and the user would have no way to tell which of the two was at fault.
        throw new PartRefusedException("That part belongs to a different message from the "
            + parts.size() + " already collected. Finish one message before starting another, or "
            + "tap the text above the keyboard to start over - that tap also clears the chosen "
            + "contact and the message box.");
      } else if (declared != total) {
        throw new PartRefusedException("That part says there are " + declared + " parts and an "
            + "earlier one said " + total + ". Ask for it to be sent again.");
      }

      final String already = parts.get(index);
      if (already != null && !already.equals(body)) {
        throw new PartRefusedException("Two different versions of part " + index + " have been "
            + "pasted. Ask for it to be sent again.");
      }
      parts.put(index, body);
    }

    public int collected() {
      return parts.size();
    }

    public int expected() {
      return total;
    }

    /** Forgets everything collected so far. */
    public void reset() {
      parts.clear();
      setId = null;
      total = 0;
    }
  }
}
