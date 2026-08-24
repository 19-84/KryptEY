package com.amnesica.kryptey.inputmethod.signalprotocol.encoding;

import android.content.Context;
import android.util.Log;

import com.amnesica.kryptey.inputmethod.R;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class FairyTaleEncoder {

  static final String TAG = FairyTaleEncoder.class.getSimpleName();

  public static final Map<Integer, String> mSentencesMap = new HashMap<>();

  private static void init(final Context context) {
    extractSentencesAndPutInMap(mSentencesMap, context.getResources().getString(R.string.e2ee_rapunzel));
    extractSentencesAndPutInMap(mSentencesMap, context.getResources().getString(R.string.e2ee_cinderella));
  }

  // for test only
  public static void initForTest(final String rapunzel, final String cinderella) {
    // Clear first. The map is static, so without this a test inherits whatever an earlier test in
    // the same JVM left behind - the sentences it thinks it installed are not the sentences it gets,
    // and a test that needs an empty map cannot have one.
    mSentencesMap.clear();
    extractSentencesAndPutInMap(mSentencesMap, rapunzel);
    extractSentencesAndPutInMap(mSentencesMap, cinderella);
  }

  private static void extractSentencesAndPutInMap(final Map<Integer, String> sentencesMap, String text) {
    final String regex = "([^.]\\w*[,\\s]*[^.]*)";
    final Pattern pattern = Pattern.compile(regex);
    final Matcher matcher = pattern.matcher(text);

    // Append after whatever is already there. This used to restart at 0 for each text, so the
    // second story silently overwrote the first's entries at the same indices and the map ended up
    // holding roughly one story rather than two - halving the decoy pool without any sign of it.
    int i = sentencesMap.size();
    while (matcher.find()) {
      final String sentence = text.substring(matcher.start(), matcher.end())
          .replaceAll("\n", " ")
          .replaceAll("\\s+", " ")
          .trim();
      sentencesMap.put(i, sentence);
      i++;
    }
  }

  public static String encode(final String message, final Context context) throws IOException {
    if (message == null) return null;

    // hint: for testing use initForTest method before calling this method
    //
    // Not covered by any test, and recorded rather than left implicit: the context arm can only be
    // exercised with a real Android Context and the string assets behind it, which needs an
    // instrumentation run. Inverting this condition survives the JVM suite. The isEmpty check below
    // is what stops that surviving mutation from becoming a crash.
    if (mSentencesMap.size() == 0 && context != null) init(context);

    // Fail as a checked IOException rather than letting the decoy pick crash.
    //
    // The line below is Random.nextInt(mSentencesMap.size()), which throws IllegalArgumentException
    // for an empty map - unchecked, on the IME main thread, inside a clipboard callback where
    // nothing catches it. The map is empty whenever init could not run: no context (the test path),
    // or assets that failed to load. Callers already handle IOException from this method, so this
    // turns a keyboard-killing crash into an ordinary encode failure.
    if (mSentencesMap.isEmpty()) {
      throw new IOException("no decoy sentences loaded; cannot encode");
    }

    Log.d(TAG, "message: " + message);
    Log.d(TAG, "length message (bytes): " + message.getBytes().length);

    final String minifiedJson = EncodeHelper.minifyJSON(message);
    Log.d(TAG, "minifiedJson message: " + minifiedJson);

    final byte[] compressedMessage = EncodeHelper.compressString(minifiedJson);
    final String decoySentence = mSentencesMap.get(new Random().nextInt(mSentencesMap.size()));
    final String binaryMessage = EncodeHelper.convertByteArrayToBinary(compressedMessage);

    Log.d(TAG, "binary message: " + binaryMessage);
    Log.d(TAG, "binary message (bytes): " + binaryMessage.getBytes().length);

    final String invisibleMessage = EncodeHelper.convertBinaryToInvisibleString(binaryMessage);
    Log.d(TAG, "length invisible message: " + invisibleMessage.length());

    return decoySentence + invisibleMessage;
  }

  public static String decode(final String encodedText) throws IOException {
    if (encodedText == null) return null;
    final String binary = EncodeHelper.convertInvisibleStringToBinary(encodedText);
    Log.d(TAG, "binary message: " + binary);
    Log.d(TAG, "length invisible message: " + binary.length());
    final byte[] compressedResult = EncodeHelper.convertBinaryToByteArray(binary);
    final String decompressedResult = EncodeHelper.decompressString(compressedResult);
    return EncodeHelper.deSimplifyJsonKeys(decompressedResult);
  }
}
