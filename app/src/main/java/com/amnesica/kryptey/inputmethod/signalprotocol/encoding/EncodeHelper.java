package com.amnesica.kryptey.inputmethod.signalprotocol.encoding;

import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.Inflater;

public class EncodeHelper {

  private static final String TAG = EncodeHelper.class.getSimpleName();
  static HashMap<String, String> simplifiedJSONMap = initSimplifiedJsonMap();

  public static String convertByteArrayToBinary(byte[] input) {
    final StringBuilder result = new StringBuilder();
    for (byte b : input) {
      int val = b;
      for (int i = 0; i < 8; i++) {
        result.append((val & 128) == 0 ? 0 : 1); // 128 = 1000 0000
        val <<= 1;
      }
    }
    return result.toString();
  }

  public static byte[] convertBinaryToByteArray(String binary) {
    // An empty bit string throws below rather than being decoded as zero bytes. Ordinary multi-line text
    // reaches here: decodeMessage routes anything containing a \p{C} character - including a plain
    // newline or tab - to the FairyTale decoder, and text with no zero-width characters yields an
    // empty bit string. Copying any two-line message therefore killed the IME process.
    if (binary == null || binary.isEmpty()) {
      throw new IllegalArgumentException("no encoded bits present");
    }

    // Byte by byte, NOT via BigInteger.
    //
    // This used to be new BigInteger(binary, 2).toByteArray(), which is arithmetic where the job is
    // transport. BigInteger is signed, so it corrupted the payload in both directions:
    //
    //   - first byte >= 0x80: toByteArray() prepends a 0x00 sign byte, handing Inflater one byte
    //     more than was compressed. Measured, "{\"hello\":\"world\"}" compresses to 19 bytes and
    //     came back as 20, and decode died with ZipException: invalid stored block lengths.
    //   - leading 0x00 bytes: they are not significant digits of an integer, so they are dropped
    //     and the payload comes back short.
    //
    // The first byte of a DEFLATE stream is >= 0x80 for a large share of inputs, so roughly half of
    // all FAIRYTALE-encoded messages could not be decoded by the recipient. It survived every test
    // because the one round-trip fixture in the suite happens to compress to a first byte under
    // 0x80 - the bug is a property of the message, and only ever one message was tried.
    //
    // convertByteArrayToBinary writes exactly 8 padded bits per byte, so the inverse is exact and
    // needs no sign handling at all.
    if (binary.length() % 8 != 0) {
      throw new IllegalArgumentException(
          "encoded bits are not a whole number of bytes: " + binary.length());
    }
    final byte[] bytes = new byte[binary.length() / 8];
    for (int i = 0; i < bytes.length; i++) {
      int value = 0;
      for (int bit = 0; bit < 8; bit++) {
        final char c = binary.charAt(i * 8 + bit);
        if (c != '0' && c != '1') {
          throw new IllegalArgumentException("not a bit at index " + (i * 8 + bit) + ": " + c);
        }
        value = (value << 1) | (c - '0');
      }
      bytes[i] = (byte) value;
    }
    return bytes;
  }

  public static String convertInvisibleStringToBinary(String encodedMessage) {
    StringBuilder result = new StringBuilder();
    StringBuilder resultUnicode = new StringBuilder();
    final String regex = "\\p{C}";
    Pattern pattern = Pattern.compile(regex);
    Matcher matcher = pattern.matcher(encodedMessage);

    while (matcher.find()) {
      final String s = encodedMessage.substring(matcher.start(), matcher.end());
      switch (s) {
        case "\u200C":
          result.append("0000");
          resultUnicode.append("\\u200C");
          break;
        case "\u200D":
          result.append("0001");
          resultUnicode.append("\\u200D");
          break;
        case "\u2060":
          result.append("0010");
          resultUnicode.append("\\u2060");
          break;
        case "\u2062":
          result.append("0011");
          resultUnicode.append("\\u2062");
          break;

        case "\u200B":
          result.append("0100");
          resultUnicode.append("\\u200B");
          break;
        case "\u200E":
          result.append("0101");
          resultUnicode.append("\\u200E");
          break;
        case "\u200F":
          result.append("0110");
          resultUnicode.append("\\u200F");
          break;
        case "\u2064":
          result.append("0111");
          resultUnicode.append("\\u2064");
          break;

        case "\u206A":
          result.append("1000");
          resultUnicode.append("\\u206A");
          break;
        case "\u206B":
          result.append("1001");
          resultUnicode.append("\\u206B");
          break;
        case "\u206C":
          result.append("1010");
          resultUnicode.append("\\u206C");
          break;
        case "\u206D":
          result.append("1011");
          resultUnicode.append("\\u206D");
          break;

        case "\u206E":
          result.append("1100");
          resultUnicode.append("\\u206E");
          break;
        case "\u206F":
          result.append("1101");
          resultUnicode.append("\\u206F");
          break;
        case "\uFEFF":
          result.append("1110");
          resultUnicode.append("\\uFEFF");
          break;
        case "\u061C":
          result.append("1111");
          resultUnicode.append("\\u061C");
          break;
      }
    }
    Log.d(TAG, String.valueOf(resultUnicode));
    return result.toString();
  }

  public static String convertBinaryToInvisibleString(String binaryString) {
    StringBuilder result = new StringBuilder();
    StringBuilder resultUnicode = new StringBuilder();

    for (int i = 0; i < binaryString.length(); i += 4) {
      final int startInclusive = i;
      final int endExclusive = i + 4;

      if (endExclusive >= binaryString.length() + 1) continue;
      String binaryDigits = binaryString.substring(startInclusive, endExclusive);

      switch (binaryDigits) {
        case "0000":
          result.append("\u200C");
          resultUnicode.append("\\u200C");
          break;
        case "0001":
          result.append("\u200D");
          resultUnicode.append("\\u200D");
          break;
        case "0010":
          result.append("\u2060");
          resultUnicode.append("\\u2060");
          break;
        case "0011":
          result.append("\u2062");
          resultUnicode.append("\\u2062");
          break;

        case "0100":
          result.append("\u200B");
          resultUnicode.append("\\u200B");
          break;
        case "0101":
          result.append("\u200E");
          resultUnicode.append("\\u200E");
          break;
        case "0110":
          result.append("\u200F");
          resultUnicode.append("\\u200F");
          break;
        case "0111":
          result.append("\u2064");
          resultUnicode.append("\\u2064");
          break;

        case "1000":
          result.append("\u206A");
          resultUnicode.append("\\u206A");
          break;
        case "1001":
          result.append("\u206B");
          resultUnicode.append("\\u206B");
          break;
        case "1010":
          result.append("\u206C");
          resultUnicode.append("\\u206C");
          break;
        case "1011":
          result.append("\u206D");
          resultUnicode.append("\\u206D");
          break;

        case "1100":
          result.append("\u206E");
          resultUnicode.append("\\u206E");
          break;
        case "1101":
          result.append("\u206F");
          resultUnicode.append("\\u206F");
          break;
        case "1110":
          result.append("\uFEFF");
          resultUnicode.append("\\uFEFF");
          break;
        case "1111":
          result.append("\u061C");
          resultUnicode.append("\\u061C");
          break;
      }
    }

    Log.d(TAG, String.valueOf(resultUnicode));
    return result.toString();
  }

  public static boolean encodedTextContainsInvisibleCharacters(final String encodedText) throws IOException {
    if (encodedText == null || encodedText.isEmpty())
      throw new IOException("There is no message to check");
    final String regex = "\\p{C}";
    final Pattern pattern = Pattern.compile(regex);
    final Matcher matcher = pattern.matcher(encodedText);
    return matcher.find();
  }

  public static String minifyJSON(String json) {
    String minifiedJSON = json.replaceAll(" ", "")
        .replaceAll("\n", "");
    return simplifyJsonKeys(minifiedJSON);
  }

  public static byte[] compressString(final String message) throws IOException {
    ByteArrayOutputStream stream = new ByteArrayOutputStream();
    Deflater compresser = new Deflater(Deflater.BEST_COMPRESSION, true);
    DeflaterOutputStream deflaterOutputStream = new DeflaterOutputStream(stream, compresser);
    deflaterOutputStream.write(message.getBytes(StandardCharsets.UTF_8));
    deflaterOutputStream.close();
    return stream.toByteArray();
  }

  /**
   * The largest decompressed payload that can possibly be legitimate.
   *
   * <p>Whatever comes out of here has to be a wire envelope, and {@code EnvelopeCodec} refuses
   * anything longer than {@link EnvelopeCodec#MAX_WIRE_CHARS}. So output past that point cannot be
   * a message under any circumstances - it can only be an attempt to exhaust memory - and there is
   * no legitimate payload this rejects. Doubled once for the UTF-8/UTF-16 slack so the bound is on
   * the codec's terms rather than a coincidence of encoding.
   */
  static final int MAX_DECOMPRESSED_BYTES = 2 * EnvelopeCodec.MAX_WIRE_CHARS;

  /**
   * Decompress with a hard output budget.
   *
   * <p>This used to write into an unbounded {@code InflaterOutputStream}. The receive path caps its
   * INPUT at 8192 characters and capped nothing else, so a compression bomb went straight through:
   * measured end to end through {@code E2EEStrip.decodeMessage}, an 8192-character paste produced
   * 37,855,647 characters in 498ms - about 72MB as a UTF-16 String, an amplification of 4621x. Two
   * stages compose, inflate then the 15 expanding replaceAll passes in {@code deSimplifyJsonKeys},
   * whose best amplifier is "a" to "signalProtocolAddressName".
   *
   * <p>What made it a crash rather than a slow decode: {@code decodeMessage} catches
   * {@code RuntimeException}, and {@code OutOfMemoryError} is an {@code Error}. It escaped into
   * LatinIME's clipboard callback - and that callback runs on EVERY clipboard change, with routing
   * decided by the presence of a \p{C} character rather than by which encoder the user chose. The
   * adversary in this threat model is the messenger, so it needs no cooperation from the user at
   * all.
   *
   * <p>Bounding the output is the fix rather than catching the Error. An OutOfMemoryError has
   * already damaged the process by the time it is thrown, and the budget refuses the bomb before
   * any of the memory is committed.
   */
  public static String decompressString(byte[] compressedMessage) throws IOException {
    final Inflater inflater = new Inflater(true);
    try {
      inflater.setInput(compressedMessage);

      final ByteArrayOutputStream out = new ByteArrayOutputStream();
      final byte[] chunk = new byte[4096];
      while (!inflater.finished()) {
        final int produced;
        try {
          produced = inflater.inflate(chunk);
        } catch (java.util.zip.DataFormatException e) {
          throw new IOException("compressed payload is malformed", e);
        }
        if (produced == 0) {
          // needsInput with nothing left to give, or needsDictionary: the stream cannot continue.
          if (inflater.needsInput() || inflater.needsDictionary()) break;
          continue;
        }
        if (out.size() + produced > MAX_DECOMPRESSED_BYTES) {
          throw new IOException("decompressed payload exceeds " + MAX_DECOMPRESSED_BYTES
              + " bytes; refusing to expand it further");
        }
        out.write(chunk, 0, produced);
      }

      // A stream that stopped early is truncated, not short. InflaterOutputStream.close() does not
      // check this, so a payload cut in half used to come back as a silent prefix of itself.
      if (!inflater.finished()) {
        throw new IOException("compressed payload is truncated");
      }
      return out.toString(java.nio.charset.StandardCharsets.UTF_8.name());
    } finally {
      inflater.end();
    }
  }

  private static String simplifyJsonKeys(String json) {
    return json
        .replaceAll("\"preKeyResponse\"", "\"" + simplifiedJSONMap.get("preKeyResponse") + "\"")
        .replaceAll("\"identityKey\"", "\"" + simplifiedJSONMap.get("identityKey") + "\"")
        .replaceAll("\"publicKey\"", "\"" + simplifiedJSONMap.get("publicKey") + "\"")
        .replaceAll("\"devices\"", "\"" + simplifiedJSONMap.get("devices") + "\"")
        .replaceAll("\"deviceId\"", "\"" + simplifiedJSONMap.get("deviceId") + "\"")
        .replaceAll("\"registrationId\"", "\"" + simplifiedJSONMap.get("registrationId") + "\"")
        .replaceAll("\"signedPreKey\"", "\"" + simplifiedJSONMap.get("signedPreKey") + "\"")
        .replaceAll("\"keyId\"", "\"" + simplifiedJSONMap.get("keyId") + "\"")
        .replaceAll("\"signature\"", "\"" + simplifiedJSONMap.get("signature") + "\"")
        .replaceAll("\"preKey\"", "\"" + simplifiedJSONMap.get("preKey") + "\"")
        .replaceAll("\"ciphertextMessage\"", "\"" + simplifiedJSONMap.get("ciphertextMessage") + "\"")
        .replaceAll("\"ciphertextType\"", "\"" + simplifiedJSONMap.get("ciphertextType") + "\"")
        .replaceAll("\"timestamp\"", "\"" + simplifiedJSONMap.get("timestamp") + "\"")
        .replaceAll("\"signalProtocolAddressName\"", "\"" + simplifiedJSONMap.get("signalProtocolAddressName") + "\"");
  }

  private static HashMap<String, String> initSimplifiedJsonMap() {
    HashMap<String, String> simplifiedJSONMap = new HashMap<>();
    simplifiedJSONMap.put("preKeyResponse", "pR");
    simplifiedJSONMap.put("identityKey", "i");
    simplifiedJSONMap.put("publicKey", "pK");
    simplifiedJSONMap.put("devices", "d");
    simplifiedJSONMap.put("deviceId", "dI");
    simplifiedJSONMap.put("registrationId", "rI");
    simplifiedJSONMap.put("signedPreKey", "sK");
    simplifiedJSONMap.put("keyId", "k");
    simplifiedJSONMap.put("signature", "s");
    simplifiedJSONMap.put("preKey", "prK");
    simplifiedJSONMap.put("ciphertextMessage", "c");
    simplifiedJSONMap.put("ciphertextType", "cT");
    simplifiedJSONMap.put("timestamp", "t");
    simplifiedJSONMap.put("signalProtocolAddressName", "a");
    return simplifiedJSONMap;
  }

  /**
   * Expand the shortened JSON keys.
   *
   * <p>One line here used to read {@code .replaceAll("\"iK\"", getMapKeyFromValue("iK"))}. No map
   * value is "iK", so {@code getMapKeyFromValue} returned null and the replacement rewrote any
   * {@code "iK"} in the payload to the literal string {@code "null"}. It had no counterpart on the
   * encode side at all - nothing ever produces "iK" - so it could only ever corrupt.
   *
   * <p>Unreachable in practice, and worth saying why rather than leaving it implicit: every
   * replacement here is a QUOTED token, and the wire alphabet is base64, which has no quote
   * character. {@code WireTextThroughFairyTaleTest} pins that, so if the wire encoding ever grows
   * one, these become reachable and that test fails first.
   */
  public static String deSimplifyJsonKeys(final String simplifiedJSON) {
    return simplifiedJSON
        .replaceAll("\"pR\"", "\"" + getMapKeyFromValue("pR") + "\"")
        .replaceAll("\"i\"", "\"" + getMapKeyFromValue("i") + "\"")
        .replaceAll("\"pK\"", "\"" + getMapKeyFromValue("pK") + "\"")
        .replaceAll("\"d\"", "\"" + getMapKeyFromValue("d") + "\"")
        .replaceAll("\"dI\"", "\"" + getMapKeyFromValue("dI") + "\"")
        .replaceAll("\"rI\"", "\"" + getMapKeyFromValue("rI") + "\"")
        .replaceAll("\"k\"", "\"" + getMapKeyFromValue("k") + "\"")
        .replaceAll("\"s\"", "\"" + getMapKeyFromValue("s") + "\"")
        .replaceAll("\"sK\"", "\"" + getMapKeyFromValue("sK") + "\"")
        .replaceAll("\"c\"", "\"" + getMapKeyFromValue("c") + "\"")
        .replaceAll("\"cT\"", "\"" + getMapKeyFromValue("cT") + "\"")
        .replaceAll("\"t\"", "\"" + getMapKeyFromValue("t") + "\"")
        .replaceAll("\"prK\"", "\"" + getMapKeyFromValue("prK") + "\"")
        .replaceAll("\"a\"", "\"" + getMapKeyFromValue("a") + "\"");
  }

  private static String getMapKeyFromValue(String value) {
    String key = null;
    for (Map.Entry<String, String> entry : simplifiedJSONMap.entrySet()) {
      if (Objects.equals(value, entry.getValue())) {
        key = entry.getKey();
      }
    }
    return key;
  }
}
