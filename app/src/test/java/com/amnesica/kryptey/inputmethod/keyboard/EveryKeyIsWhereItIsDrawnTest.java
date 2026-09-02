package com.amnesica.kryptey.inputmethod.keyboard;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.text.InputType;
import android.view.View;
import android.view.inputmethod.EditorInfo;

import com.amnesica.kryptey.inputmethod.latin.LatinIME;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.shadows.ShadowLooper;

import java.lang.ref.SoftReference;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The key you touch is the key you see.
 *
 * <p>Three sweeps have answered the security question about {@code keyboard/} - settings persist no
 * text, nothing logs typed text, the 196 layout files add no capability. Nothing had answered the
 * behaviour question, and for a keyboard the behaviour question that matters most is whether the
 * geometry that decides a touch agrees with the geometry that was drawn. A disagreement types a
 * character the user did not choose, and in this app it types it into a message that is then
 * encrypted and sent.
 *
 * <p>Asserted as properties over every key of a real, inflated keyboard rather than as expectations
 * about particular keys: a test naming Q and W would pin the layout instead of the invariant, and
 * this fork ships 196 layout files.
 */
@RunWith(RobolectricTestRunner.class)
public class EveryKeyIsWhereItIsDrawnTest {

  private ServiceController<LatinIME> controller;
  private LatinIME ime;

  @Before
  public void setUp() {
    controller = Robolectric.buildService(LatinIME.class);
    ime = controller.create().get();
    final View inputView = ime.onCreateInputView();
    ime.setInputView(inputView);
    ShadowLooper.idleMainLooper();
    cache().clear();
  }

  @After
  public void tearDown() {
    cache().clear();
  }

  @SuppressWarnings("unchecked")
  private static Map<Object, Object> cache() {
    try {
      final Field f = KeyboardLayoutSet.class.getDeclaredField("sKeyboardCache");
      f.setAccessible(true);
      return (Map<Object, Object>) f.get(null);
    } catch (final Exception e) {
      throw new AssertionError(e);
    }
  }

  /** A real keyboard, built the way the framework builds one. */
  private Keyboard anInflatedKeyboard() {
    final EditorInfo info = new EditorInfo();
    info.inputType = InputType.TYPE_CLASS_TEXT;
    info.packageName = "com.messenger";
    ime.onStartInputView(info, false);
    ShadowLooper.idleMainLooper();

    for (final Object value : cache().values()) {
      final Object held = value instanceof SoftReference ? ((SoftReference<?>) value).get() : value;
      if (held instanceof Keyboard) {
        final Keyboard keyboard = (Keyboard) held;
        if (!keyboard.getSortedKeys().isEmpty()) return keyboard;
      }
    }
    throw new AssertionError("no keyboard was built; this test would assert over an empty list");
  }

  @Test
  public void thepremiseThatArealKeyboardWithKeysIsBuilt() {
    final Keyboard keyboard = anInflatedKeyboard();
    assertNotNull(keyboard);
    assertTrue("a text keyboard must carry a substantial number of keys, or every property below "
            + "holds vacuously over a handful: " + keyboard.getSortedKeys().size(),
        keyboard.getSortedKeys().size() >= 20);
  }

  /**
   * Every key answers to the middle of its own drawn rectangle.
   *
   * <p>The weakest form of "the hit area is the drawn area", and the one that fails first if a
   * layout's geometry and its hit-testing ever disagree about padding, gaps or row offsets.
   */
  @Test
  public void everyKeyContainsItsOwnCentre() {
    final Keyboard keyboard = anInflatedKeyboard();
    final List<String> wrong = new ArrayList<>();

    for (final Key key : keyboard.getSortedKeys()) {
      final int centreX = key.getX() + key.getWidth() / 2;
      final int centreY = key.getY() + key.getHeight() / 2;
      if (!key.isOnKey(centreX, centreY)) {
        wrong.add(describe(key) + " does not contain its own centre (" + centreX + "," + centreY
            + ")");
      }
    }

    assertEquals("a key that does not answer to the middle of where it is drawn types the wrong "
            + "character, or none:\n" + String.join("\n", wrong), 0, wrong.size());
  }

  /**
   * And no two keys are DRAWN over each other.
   *
   * <p>The drawn rectangles, deliberately - not the hitboxes. {@code isOnKey} reads
   * {@code mHitbox}, which by design extends to halfway towards each adjacent key so that no touch
   * falls in a dead gap; hitboxes meeting at those midpoints is the intended arrangement, and a
   * test forbidding it would be asserting the opposite of the design.
   *
   * <p>What must not overlap is what is painted. Two keys sharing screen area means one is drawn
   * over the other, so the label the user reads is not the label of the key whose hitbox they are
   * most likely to hit.
   */
  @Test
  public void notwoKeysOverlap() {
    final Keyboard keyboard = anInflatedKeyboard();
    final List<Key> keys = keyboard.getSortedKeys();
    final List<String> clashes = new ArrayList<>();

    for (int i = 0; i < keys.size(); i++) {
      for (int j = i + 1; j < keys.size(); j++) {
        final Key a = keys.get(i);
        final Key b = keys.get(j);
        final int overlapX = Math.min(a.getX() + a.getWidth(), b.getX() + b.getWidth())
            - Math.max(a.getX(), b.getX());
        final int overlapY = Math.min(a.getY() + a.getHeight(), b.getY() + b.getHeight())
            - Math.max(a.getY(), b.getY());
        if (overlapX > 0 && overlapY > 0) {
          clashes.add(describe(a) + " overlaps " + describe(b)
              + " by " + overlapX + "x" + overlapY);
        }
      }
    }

    assertEquals("two keys painted over the same area means the label the user reads is not the "
            + "label of the key they are about to press:\n" + String.join("\n", clashes),
        0, clashes.size());
  }

  /**
   * The nearest-key search agrees with the rectangles.
   *
   * <p>{@code getNearestKeys} is what a touch actually consults, and it reads a precomputed
   * proximity grid rather than the key rectangles - so it is a second description of the same
   * geometry, and two descriptions can drift. A key must be among the candidates offered for a
   * touch at its own centre.
   */
  @Test
  public void thenearestKeySearchOffersTheKeyUnderTheTouch() {
    final Keyboard keyboard = anInflatedKeyboard();
    final List<String> missing = new ArrayList<>();

    for (final Key key : keyboard.getSortedKeys()) {
      final int centreX = key.getX() + key.getWidth() / 2;
      final int centreY = key.getY() + key.getHeight() / 2;
      if (!keyboard.getNearestKeys(centreX, centreY).contains(key)) {
        missing.add(describe(key) + " is not offered for a touch at its own centre");
      }
    }

    assertEquals("the proximity grid and the key rectangles are two descriptions of one geometry; "
            + "where they disagree, the character typed is not the one drawn under the finger:\n"
            + String.join("\n", missing), 0, missing.size());
  }

  private static String describe(final Key key) {
    return "key '" + key.getLabel() + "' at " + key.getX() + "," + key.getY()
        + " " + key.getWidth() + "x" + key.getHeight();
  }
}
