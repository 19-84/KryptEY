package com.amnesica.kryptey.inputmethod.signalprotocol.encoding;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.io.IOException;

/**
 * The lazy initialisation in {@code FairyTaleEncoder.encode} — recorded for two sweeps as a
 * mutation survivor that "can only be exercised with a real Android Context and the string assets
 * behind it, which needs an instrumentation run".
 *
 * <p>That was wrong, and it is worth being precise about why, because the same reasoning has now
 * deferred coverage twice. {@code init} does not touch assets or hardware: it calls
 * {@code context.getResources().getString(R.string.e2ee_rapunzel)}. Robolectric serves string
 * resources from the real {@code strings.xml}, so the context arm runs perfectly well in the JVM
 * suite. The barrier was assumed, not measured — the same shape of mistake as folding the C1
 * controls to a space because they looked like C0.
 *
 * <p>What the survivor actually cost: inverting {@code mSentencesMap.size() == 0} means encode
 * either never initialises, or re-initialises on every call. The first drops every message onto the
 * {@code IOException} path — the keyboard silently stops being able to send. The second appends
 * both stories to the map again on every single encode, so the decoy pool grows without bound on
 * the IME's own heap.
 */
@RunWith(RobolectricTestRunner.class)
public class FairyTaleInitTest {

  private Context context;

  @Before
  public void emptyTheStaticMap() {
    // The map is static and public, so whatever ran before in this JVM is still in it.
    FairyTaleEncoder.mSentencesMap.clear();
    context = RuntimeEnvironment.getApplication();
  }

  /** The context arm: a real Context populates the map from the real string resources. */
  @Test
  public void encodingWithAContextLoadsTheDecoySentences() throws IOException {
    assertTrue("precondition: the map must start empty",
        FairyTaleEncoder.mSentencesMap.isEmpty());

    final String encoded = FairyTaleEncoder.encode("{\"a\":1}", context);

    assertNotNull("encode returned nothing with a usable context", encoded);
    assertTrue("the context arm did not populate the decoy map",
        FairyTaleEncoder.mSentencesMap.size() > 1);
  }

  /**
   * Both stories, not one. {@code extractSentencesAndPutInMap} used to restart its index at 0 for
   * each text, so the second silently overwrote the first and the pool was half the size with
   * nothing to show for it. The single-story case still populates a non-empty map, so a size check
   * alone does not see it.
   */
  @Test
  public void bothStoriesAreLoadedNotJustOne() throws IOException {
    FairyTaleEncoder.encode("{\"a\":1}", context);
    final int both = FairyTaleEncoder.mSentencesMap.size();

    FairyTaleEncoder.mSentencesMap.clear();
    FairyTaleEncoder.initForTest(
        context.getResources().getString(com.amnesica.kryptey.inputmethod.R.string.e2ee_rapunzel),
        "");
    final int rapunzelOnly = FairyTaleEncoder.mSentencesMap.size();

    assertTrue("the two stories together must yield more sentences than one of them",
        both > rapunzelOnly);
  }

  /**
   * Initialisation happens once. Re-running it per call appends both stories again every time, so
   * the map grows on every keystroke-driven encode - unbounded, on the IME main thread's heap.
   */
  @Test
  public void aSecondEncodeDoesNotReloadTheStories() throws IOException {
    FairyTaleEncoder.encode("{\"a\":1}", context);
    final int afterFirst = FairyTaleEncoder.mSentencesMap.size();

    for (int i = 0; i < 5; i++) {
      FairyTaleEncoder.encode("{\"a\":" + i + "}", context);
    }

    assertEquals("the decoy map grew across encodes - init is running more than once",
        afterFirst, FairyTaleEncoder.mSentencesMap.size());
  }

  /**
   * The null-context arm, which is the path the JVM suite always took. It must fail as a checked
   * IOException: the next line is {@code Random.nextInt(map.size())}, which throws an unchecked
   * IllegalArgumentException on an empty map, on the IME main thread, inside a clipboard callback
   * where nothing catches it.
   */
  @Test
  public void encodingWithNoContextAndNoSentencesFailsAsAnIoException() {
    final IOException thrown = assertThrows(IOException.class,
        () -> FairyTaleEncoder.encode("{\"a\":1}", null));

    assertTrue("the failure must name the cause, not surface as a generic error",
        thrown.getMessage() != null && thrown.getMessage().contains("decoy"));
  }

  /** A null message short-circuits before any of this, with or without a context. */
  @Test
  public void aNullMessageIsNullRegardlessOfContext() throws IOException {
    org.junit.Assert.assertNull(FairyTaleEncoder.encode(null, context));
    org.junit.Assert.assertNull(FairyTaleEncoder.encode(null, null));
  }

  /** And what encode produces with a real context still round-trips. */
  @Test
  public void theRealResourcesProduceDecodableOutput() throws IOException {
    final String message = "{\"hello\":\"world\"}";
    final String encoded = FairyTaleEncoder.encode(message, context);

    assertEquals("a message encoded against the real string resources did not survive decoding",
        message, FairyTaleEncoder.decode(encoded));
  }

  /**
   * The user-visible property: a message that encodes must decode. Many messages, because the
   * defect this covers was a property of the payload rather than of the encoder.
   *
   * <p>The bit decoder was {@code new BigInteger(binary, 2).toByteArray()}. A compressed stream
   * whose first byte is >= 0x80 came back with an extra 0x00 sign byte, and {@code Inflater} died
   * with "invalid stored block lengths" - so the recipient saw "could not decode message" for a
   * message the sender had encoded successfully. The suite had one round-trip fixture and it
   * happened to compress to a first byte under 0x80.
   *
   * <p>This runs against the real string resources, which is how it surfaced: the earlier
   * round-trip test passes a null context and its own sample text.
   */
  @Test
  public void manyMessagesSurviveEncodeAndDecode() throws IOException {
    final String[] messages = {
        "{\"a\":1}",
        "{\"hello\":\"world\"}",
        "{\"type\":3,\"body\":\"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA\"}",
        "{\"name\":\"Maria del Carmen Fernandez\",\"id\":\"9f1c\"}",
        "{\"x\":\"\u00e4\u00f6\u00fc \u05d0\u05d1\u05d2 \u4e2d\u6587\"}",
        "{\"n\":0}",
        "{\"n\":\"" + "z".repeat(300) + "\"}",
    };

    int decoded = 0;
    for (final String message : messages) {
      final String encoded = FairyTaleEncoder.encode(message, context);
      assertNotNull("encode returned nothing for " + message, encoded);

      // The pipeline is deliberately asymmetric: encode minifies and simplifies JSON keys, decode
      // de-simplifies them. So the contract is not identity - it is that decode recovers exactly
      // what encode put on the wire. Asserting identity here would have failed for a second,
      // unrelated reason and hidden the one being tested.
      final String expected =
          EncodeHelper.deSimplifyJsonKeys(EncodeHelper.minifyJSON(message));

      final String back = FairyTaleEncoder.decode(encoded);
      assertEquals("a message that encoded did not decode: " + message, expected, back);
      decoded++;
    }

    assertEquals("every message must have made the round trip", messages.length, decoded);
  }

  /**
   * And specifically a payload whose compressed form starts with the high bit set - the exact
   * shape that failed. Found by search rather than assumed, so the test still means something if
   * the compressor changes.
   */
  @Test
  public void aPayloadCompressingToAHighLeadingByteRoundTrips() throws IOException {
    String found = null;
    for (int i = 0; i < 500 && found == null; i++) {
      final String candidate = "{\"k\":\"" + i + "-abcdefghijklmnopqrstuvwxyz\"}";
      final byte[] compressed =
          EncodeHelper.compressString(EncodeHelper.minifyJSON(candidate));
      if ((compressed[0] & 0x80) != 0) found = candidate;
    }

    assertNotNull("no payload in the search space compressed to a leading byte >= 0x80, so this "
        + "test would prove nothing - widen the search rather than deleting it", found);

    assertEquals("a payload whose compressed stream starts with the high bit set did not decode",
        EncodeHelper.deSimplifyJsonKeys(EncodeHelper.minifyJSON(found)),
        FairyTaleEncoder.decode(FairyTaleEncoder.encode(found, context)));
  }
}
