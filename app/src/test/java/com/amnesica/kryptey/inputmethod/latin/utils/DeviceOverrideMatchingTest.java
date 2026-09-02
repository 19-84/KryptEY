package com.amnesica.kryptey.inputmethod.latin.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.lang.reflect.Method;
import java.util.HashMap;

/**
 * How a device override is chosen, pinned because the semantics are easy to invert by accident.
 *
 * <p>{@code findConstantForKeyValuePairs} decides which per-device value applies - keyboard heights
 * among them - from an array of {@code "condition,value"} strings, where a condition is one or more
 * {@code key=regex} patterns joined by colons. Three rules decide the answer and none of them is
 * visible from a call site:
 *
 * <ul>
 *   <li>the FIRST matching entry wins, and the loop deliberately keeps walking afterwards;
 *   <li>every pattern in a condition must match, not any;
 *   <li>a malformed entry is skipped rather than failing the lookup.
 * </ul>
 *
 * <p>Invert any one of them and the app still runs: it just picks a different override, so a
 * particular device gets a keyboard of the wrong height. That is invisible in review, invisible in
 * this environment, and reported by nobody who is not holding that device.
 *
 * <p>Reached by reflection because the method is private and its callers need a {@code Resources}
 * and a resource id. Widening it for a test would be changing production visibility to make a pure
 * function reachable, which is a worse trade than a reflective call in one test file.
 *
 * <p>Robolectric, though nothing here touches a resource: {@code ResourceUtils}' static initialiser
 * reads the device's build properties, so the class cannot be initialised at all on a bare JVM.
 * Without the runner every case fails with {@code NoClassDefFoundError} before reaching an
 * assertion - which is a failure that says nothing about the matcher.
 */
@RunWith(RobolectricTestRunner.class)
public class DeviceOverrideMatchingTest {

  @SuppressWarnings("unchecked")
  private static String match(final HashMap<String, String> device, final String... entries) {
    try {
      final Method method = ResourceUtils.class.getDeclaredMethod(
          "findConstantForKeyValuePairs", HashMap.class, String[].class);
      method.setAccessible(true);
      return (String) method.invoke(null, device, entries);
    } catch (final ReflectiveOperationException e) {
      throw new AssertionError("the matcher this test names no longer has that shape", e);
    }
  }

  private static HashMap<String, String> device(final String... pairs) {
    final HashMap<String, String> map = new HashMap<>();
    for (int i = 0; i < pairs.length; i += 2) map.put(pairs[i], pairs[i + 1]);
    return map;
  }

  /** The premise: a plain condition matches, so an absence below means something. */
  @Test
  public void thepremiseThatAmatchingConditionIsFound() {
    assertEquals("value", match(device("MODEL", "Pixel"), "MODEL=Pixel,value"));
  }

  @Test
  public void thefirstMatchWinsEvenWhenAlaterOneAlsoMatches() {
    assertEquals("a condition array is ordered, and the first match is the answer - the loop keeps "
            + "walking afterwards on purpose, so a later match must not overwrite it",
        "first", match(device("MODEL", "Pixel"), "MODEL=Pixel,first", "MODEL=Pixel,second"));
  }

  @Test
  public void everyPatternInAconditionMustMatchNotJustOne() {
    final HashMap<String, String> pixelOnAndroid13 = device("MODEL", "Pixel", "VERSION", "13");

    assertEquals("both patterns hold, so the condition holds", "both",
        match(pixelOnAndroid13, "MODEL=Pixel:VERSION=13,both"));
    assertNull("one pattern holding is not enough - a condition is a conjunction, and reading it "
            + "as a disjunction applies overrides to devices they were never written for",
        match(pixelOnAndroid13, "MODEL=Pixel:VERSION=99,both"));
  }

  @Test
  public void themalformedEntriesAreSkippedRatherThanFailingTheLookup() {
    assertEquals("an entry with no comma is skipped, not fatal", "good",
        match(device("MODEL", "Pixel"), "MODEL=Pixel-no-comma-here", "MODEL=Pixel,good"));
    assertEquals("an entry with an empty condition is skipped too", "good",
        match(device("MODEL", "Pixel"), ",orphan", "MODEL=Pixel,good"));
    assertEquals("a condition naming a key this device does not have is skipped", "good",
        match(device("MODEL", "Pixel"), "NO_SUCH_KEY=x,bad", "MODEL=Pixel,good"));
    assertEquals("and so is one whose regex does not compile", "good",
        match(device("MODEL", "Pixel"), "MODEL=[unclosed,bad", "MODEL=Pixel,good"));
  }

  @Test
  public void novalueComesBackWhenNothingMatches() {
    assertNull("no condition matching must answer null, so the caller falls back to its default",
        match(device("MODEL", "Pixel"), "MODEL=Nexus,value"));
  }

  /** Null inputs answer null rather than throwing, which is what the callers rely on. */
  @Test
  public void nullInputsAreAnswerednotThrown() {
    assertNull(match(null, "MODEL=Pixel,value"));
    assertNull(match(device("MODEL", "Pixel"), (String[]) null));
  }

  /**
   * The value is everything after the FIRST comma, so a value may contain commas.
   *
   * <p>Splitting on the last comma, or on all of them, would truncate any override whose value is a
   * list - and the truncated value would still parse, so it would be applied rather than rejected.
   */
  @Test
  public void thevalueKeepsAnyCommasOfItsOwn() {
    assertEquals("1,2,3", match(device("MODEL", "Pixel"), "MODEL=Pixel,1,2,3"));
  }
}
