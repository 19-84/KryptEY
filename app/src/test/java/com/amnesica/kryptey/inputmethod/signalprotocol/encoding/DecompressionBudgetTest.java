package com.amnesica.kryptey.inputmethod.signalprotocol.encoding;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The decompression budget must stay tied to what the codec downstream will accept.
 *
 * <p>Raising it from 16KiB to 1MiB survived the entire suite. At that size the fifteen expanding
 * {@code replaceAll} passes in {@code deSimplifyJsonKeys} can produce roughly 9MB downstream, so
 * the bomb the budget exists to stop comes most of the way back. Nothing pinned the value within a
 * factor of 64.
 *
 * <p>Lives in this package rather than beside the other bomb tests because the constant is
 * package-private, and a test is not a reason to widen it.
 */
public class DecompressionBudgetTest {

  /**
   * Literal values, because the previous version compared a constant against itself.
   *
   * <p>It asserted {@code MAX_DECOMPRESSED_BYTES <= 4 * MAX_WIRE_CHARS} where the budget is DEFINED
   * as {@code 2 * MAX_WIRE_CHARS} - that is {@code 2X <= 4X}, true for every X. Both the budget's
   * multiplier and the wire cap itself could be raised with the suite green: taking the wire cap to
   * 1 MiB drags the budget to 2 MiB, and measured, {@code deSimplifyJsonKeys} amplifies exactly 9x,
   * so that is 18,874,377 characters - about 36MB - in 288ms on the IME main thread, on every
   * clipboard change. The compression bomb, restored by a one-line edit, invisible to the test
   * written to prevent it.
   *
   * <p>Tying two values that move together is not a bound. These are the numbers.
   */
  @Test
  public void thebudgetAndTheWireCapAreTheValuesTheyAreDocumentedToBe() {
    assertEquals("the wire cap is what bounds every decoded payload; changing it changes what a "
            + "compression bomb can cost", 8192, EnvelopeCodec.MAX_WIRE_CHARS);
    assertEquals("the decompression budget is deliberately twice the wire cap - large enough that "
            + "no legitimate payload is rejected, small enough that the downstream 9x expansion "
            + "stays under 150KB", 16384, EncodeHelper.MAX_DECOMPRESSED_BYTES);
  }

  /**
   * The property, not the relationship: a payload at the wire cap inflates, one past the budget is
   * refused.
   *
   * <p>This asserted {@code MAX_DECOMPRESSED_BYTES >= MAX_WIRE_CHARS} where the budget is DEFINED
   * as twice the cap - {@code 2X >= X}, true for every X. That is the same defect the javadoc above
   * describes killing six lines earlier, and its claim to catch "a change to one if the other is
   * updated to match" is the one thing a definitional identity structurally cannot do. Two
   * constants that move together are not a bound on each other.
   */
  @Test
  public void alargestLegitimatePayloadInflatesAndAbomberIsRefused() throws java.io.IOException {
    final StringBuilder builder = new StringBuilder();
    final String alphabet =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    final java.util.Random random = new java.util.Random(20260824L);
    while (builder.length() < EnvelopeCodec.MAX_WIRE_CHARS) {
      builder.append(alphabet.charAt(random.nextInt(alphabet.length())));
    }
    final String largest = builder.toString();

    assertEquals("a payload the size of the wire cap must inflate without hitting the budget",
        largest, EncodeHelper.decompressString(EncodeHelper.compressString(largest)));

    // And something that would expand past the budget is refused. "a" repeated compresses to
    // almost nothing and inflates without bound, which is what the budget exists for.
    final byte[] bomb = EncodeHelper.compressString("a".repeat(4 * 1024 * 1024));
    final java.io.IOException refused = org.junit.Assert.assertThrows(java.io.IOException.class,
        () -> EncodeHelper.decompressString(bomb));
    assertTrue("the refusal must name the budget: " + refused.getMessage(),
        refused.getMessage() != null && refused.getMessage().contains("exceeds"));
  }
}
