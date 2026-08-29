package com.amnesica.kryptey.inputmethod.keyboard.internal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.lang.reflect.Field;

/**
 * Two parallel arrays indexed by the same number must be the same length.
 *
 * <p>{@code getCode} looks a name up in one array and indexes the other with the result. They were
 * 16 and 14 long: {@code key_left} resolved to whatever sat at index 13 of the shorter one — which
 * happened to be {@code CODE_UNSPECIFIED}, silently wrong — and {@code key_right} and
 * {@code key_unspecified} threw {@code ArrayIndexOutOfBoundsException} out of the layout parser.
 *
 * <p>No shipped XML used any of the three, so nothing was broken. The trap was for the next person
 * to write {@code !code/key_unspecified}, which is the idiomatic AOSP spelling for a key that does
 * nothing: they would have got a keyboard that fails to build with an out-of-bounds error rather
 * than a "no such code" one.
 *
 * <p>Asserting the lengths is a better guard than fixing the arrays was, because it also catches
 * the next divergence — and the divergence is the defect, not the particular three names.
 */
public class KeyboardCodesSetTest {

  private static Object staticField(final String name) throws Exception {
    final Field f = KeyboardCodesSet.class.getDeclaredField(name);
    f.setAccessible(true);
    return f.get(null);
  }

  @Test
  public void thetwoParallelArraysAreTheSameLength() throws Exception {
    final String[] names = (String[]) staticField("ID_TO_NAME");
    final int[] codes = (int[]) staticField("DEFAULT");

    assertTrue("the name table must not be empty, or this asserts nothing", names.length > 0);
    assertEquals("getCode indexes the code table with the name table's index, so a name past the "
        + "end of the codes either resolves to the wrong key or throws out of the layout parser",
        names.length, codes.length);
  }

  /** And every name resolves, which is what the equal lengths are for. */
  @Test
  public void everyNameResolvesToAcode() throws Exception {
    for (final String name : (String[]) staticField("ID_TO_NAME")) {
      KeyboardCodesSet.getCode(name);
    }
  }
}
