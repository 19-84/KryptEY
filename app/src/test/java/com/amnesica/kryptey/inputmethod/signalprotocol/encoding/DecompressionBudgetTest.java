package com.amnesica.kryptey.inputmethod.signalprotocol.encoding;

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

  @Test
  public void thebudgetStaysWithinASmallMultipleOfTheWireCap() {
    assertTrue("the budget is " + EncodeHelper.MAX_DECOMPRESSED_BYTES + " against a wire cap of "
            + EnvelopeCodec.MAX_WIRE_CHARS + " - anything decompressed here has to be wire text, "
            + "which EnvelopeCodec refuses above that cap, so a budget far larger than it is "
            + "bounding nothing",
        EncodeHelper.MAX_DECOMPRESSED_BYTES <= 4 * EnvelopeCodec.MAX_WIRE_CHARS);
  }

  /** And high enough that the largest legitimate payload is not rejected. */
  @Test
  public void thebudgetIsAtLeastTheWireCap() {
    assertTrue("a budget below the wire cap would reject payloads the codec accepts",
        EncodeHelper.MAX_DECOMPRESSED_BYTES >= EnvelopeCodec.MAX_WIRE_CHARS);
  }
}
