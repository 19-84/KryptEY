package com.amnesica.kryptey.inputmethod.latin.e2ee;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.amnesica.kryptey.inputmethod.compat.PreferenceManagerCompat;
import com.amnesica.kryptey.inputmethod.latin.settings.Settings;
import com.amnesica.kryptey.inputmethod.signalprotocol.encoding.Encoder;
import com.amnesica.kryptey.inputmethod.signalprotocol.encoding.FairyTaleEncoder;
import com.amnesica.kryptey.inputmethod.signalprotocol.exceptions.TooManyCharsException;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.util.List;
import java.util.Random;

/**
 * The limit is enforced where the encoded value exists and before anything irreversible.
 *
 * <p>{@code encryptMessage} advances the ratchet and records the plaintext before it encodes, and
 * rolls the record back only for a refusal thrown by {@code encode}. So a message that cannot go
 * out under the limit has to be refused THERE - a FairyTale message past it, which cannot be split,
 * and raw text no part count can carry - rather than discovered at the send, after the record is
 * made and the ratchet has moved.
 */
@RunWith(RobolectricTestRunner.class)
public class AmessageOverTheChatLimitIsSplitOrRefusedAtTheEncoderTest {

  private E2EEStrip strip;

  private static void limit(final String value) {
    PreferenceManagerCompat.getDeviceSharedPreferences(RuntimeEnvironment.getApplication())
        .edit().putString(Settings.PREF_MESSAGE_LENGTH_LIMIT, value).commit();
  }

  /** Base64-alphabet text of the given length. Random, so it does not compress. */
  private static String wire(final int length) {
    final String alphabet =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    final Random random = new Random(length);
    final StringBuilder sb = new StringBuilder(length);
    for (int i = 0; i < length; i++) sb.append(alphabet.charAt(random.nextInt(alphabet.length())));
    return sb.toString();
  }

  @Before
  public void setUp() {
    FairyTaleEncoder.initForTest(
        "Once upon a time there was a keyboard. It kept its own counsel. Nobody read its mail.",
        "The miller had a daughter. She spun straw. The straw was never gold, only straw.");
    strip = new E2EEStrip(RuntimeEnvironment.getApplication());
    limit("0");
  }

  @After
  public void tearDown() {
    limit("0");
  }

  @Test
  public void withNoLimitNothingIsSplit() throws Exception {
    final String text = wire(3000);
    assertEquals(text, strip.encode(text, Encoder.RAW));
    final List<String> parts = strip.partsOf(text, Encoder.RAW);
    assertEquals("the default must be what the app always did", 1, parts.size());
    assertEquals(text, parts.get(0));
  }

  @Test
  public void rawTextOverTheLimitEncodesWholeAndSplitsIntoPartsThatFit() throws Exception {
    limit("300");
    final String text = wire(900);
    assertEquals("encode hands back the whole; the split is the send's business",
        text, strip.encode(text, Encoder.RAW));
    final List<String> parts = strip.partsOf(text, Encoder.RAW);
    assertTrue("expected several parts, got " + parts.size(), parts.size() >= 3);
    for (final String part : parts) {
      assertTrue("a part is " + part.length() + " characters against 300", part.length() <= 300);
    }
  }

  @Test
  public void fairyTaleTextOverTheLimitIsRefusedAtTheEncoder() throws Exception {
    limit("300");
    try {
      strip.encode(wire(900), Encoder.FAIRYTALE);
      fail("a FairyTale message that cannot fit the limit was handed back to be sent");
    } catch (final TooManyCharsException refused) {
      assertTrue(refused.getMessage(), refused.getMessage().contains("fairytale"));
      assertTrue("the refusal names the limit it was measured against: " + refused.getMessage(),
          refused.getMessage().contains("300"));
    }
  }

  @Test
  public void fairyTaleTextThatFitsGetsAdecoyThatFitsAndIsNeverSplit() throws Exception {
    limit("300");
    final String encoded = strip.encode(wire(40), Encoder.FAIRYTALE);
    assertTrue("the decoy is chosen to fit the limit, not only the recipient's cap: "
        + encoded.length(), encoded.length() <= 300);
    assertEquals("FairyTale text is never cut into parts", 1,
        strip.partsOf(encoded, Encoder.FAIRYTALE).size());
  }

  @Test
  public void rawTextNoPartCountCanCarryIsRefusedAtTheEncoderToo() throws Exception {
    // Not a value the settings screen offers; the preference is a string and anything can write
    // one. Fourteen characters leaves one body character per part below ten parts and none above.
    limit("14");
    try {
      strip.encode(wire(100), Encoder.RAW);
      fail("raw text no split can carry was handed back to be sent whole, after the record was "
          + "made");
    } catch (final TooManyCharsException refused) {
      assertTrue(refused.getMessage(), refused.getMessage().contains("cannot be sent in parts"));
    }
  }
}
