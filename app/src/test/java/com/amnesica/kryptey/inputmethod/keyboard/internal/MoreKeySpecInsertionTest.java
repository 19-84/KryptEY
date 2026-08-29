package com.amnesica.kryptey.inputmethod.keyboard.internal;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

/**
 * Leftover additional more-keys are appended in order, not the first one repeated.
 *
 * <p>The loop advanced its index and the subscript did not, so the first leftover was appended once
 * per leftover and the rest were dropped. No shipped layout reaches it — the branch needs a key
 * whose {@code moreKeys} contains a {@code %} marker <em>and</em> three or more
 * {@code additionalMoreKeys}, and every {@code %}-bearing key in this tree has at most two.
 *
 * <p>Which is exactly why it is worth fixing and pinning now: while nothing depends on the wrong
 * output, the change is a provable no-op. A locale added later would otherwise silently lose a
 * character from a long-press menu and show a duplicate in its place, and the loss would look like
 * a translation problem rather than an index.
 */
@RunWith(RobolectricTestRunner.class)
public class MoreKeySpecInsertionTest {

  @Test
  public void leftoverAdditionalKeysAreAppendedInOrder() {
    final String[] result = MoreKeySpec.insertAdditionalMoreKeys(
        new String[] {"a", "%"}, new String[] {"x", "y", "z"});

    assertArrayEquals("one marker consumes 'x'; 'y' and 'z' are appended in order. The bug "
            + "appended 'y' twice and dropped 'z'",
        new String[] {"a", "x", "y", "z"}, result);
  }

  /** The ordinary shapes, so the fix is not measured against a single crafted case. */
  @Test
  public void themarkerlessAndExactCasesAreUnchanged() {
    assertArrayEquals("no marker: additional keys go to the head",
        new String[] {"x", "a", "b"},
        MoreKeySpec.insertAdditionalMoreKeys(new String[] {"a", "b"}, new String[] {"x"}));

    assertArrayEquals("one marker, one additional: it is substituted in place",
        new String[] {"a", "x"},
        MoreKeySpec.insertAdditionalMoreKeys(new String[] {"a", "%"}, new String[] {"x"}));
  }

  /** And no additional keys at all leaves the more-keys exactly as they were. */
  @Test
  public void nothingToInsertChangesNothing() {
    final String[] moreKeys = {"a", "b"};
    assertEquals(moreKeys, MoreKeySpec.insertAdditionalMoreKeys(moreKeys, null));
  }
}
