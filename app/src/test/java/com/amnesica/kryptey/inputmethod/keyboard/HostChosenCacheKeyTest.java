package com.amnesica.kryptey.inputmethod.keyboard;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.view.inputmethod.EditorInfo;

import com.amnesica.kryptey.inputmethod.latin.LatinIME;
import com.amnesica.kryptey.inputmethod.latin.common.Constants;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.shadows.ShadowLooper;

import java.lang.reflect.Field;
import java.util.Map;

/**
 * The host writes part of the key of a map that lives as long as the process, and nothing ever
 * removes an entry.
 *
 * <p>{@code sKeyboardCache} is a {@code static HashMap<KeyboardId, SoftReference<Keyboard>>}. The
 * soft reference protects the VALUE: a built keyboard can be collected under memory pressure. The
 * KEY is an ordinary strong reference, and it is a {@code KeyboardId} - which copies
 * {@code EditorInfo.actionLabel} into {@code mCustomActionLabel} and compares it in both
 * {@code equals} and {@code hashCode}. {@code actionLabel} is a {@code CharSequence} the
 * application on screen sets on its own {@code EditText}; the keyboard neither chose it nor
 * checked it.
 *
 * <p>So one entry accrues per distinct label the host has ever declared, forever, and each entry
 * holds that session's whole {@code EditorInfo} - including the {@code Bundle} in its
 * {@code extras}, whose contents and size are also the host's. The same label reaches
 * {@code sUniqueKeysCache} through the enter key it labels, which is another unbounded
 * strong-keyed map. {@code onStartInputView} is what fills them, and any app may cause that as
 * often as it likes by moving focus between fields.
 *
 * <p>This is the shape the review already treats as security-relevant elsewhere in this branch: an
 * {@code OutOfMemoryError} is an {@code Error}, so none of the {@code catch (Exception)} handlers
 * guarding the keyboard's callbacks stop it, and the input-method process dies in whatever app the
 * user happens to be typing in - taking the E2EE strip, and the conversation on it, with it.
 *
 * <p>Harness: a real {@code LatinIME} service with a real input view, driven through
 * {@code onStartInputView} the way the framework drives it.
 */
@RunWith(RobolectricTestRunner.class)
public class HostChosenCacheKeyTest {

  /** Comfortably past MAX_CACHED_KEYBOARDS, and nothing like a number real use produces. */
  private static final int SESSIONS = 300;

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
    // Static and shared with every other test in this JVM.
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

  private static int cap() {
    try {
      final Field f = KeyboardLayoutSet.class.getDeclaredField("MAX_CACHED_KEYBOARDS");
      f.setAccessible(true);
      return f.getInt(null);
    } catch (final Exception e) {
      throw new AssertionError(e);
    }
  }

  /** A field like any messenger's compose box, with the label the host chose on its action key. */
  private static EditorInfo fieldLabelled(final String label) {
    final EditorInfo info = new EditorInfo();
    info.inputType = InputType.TYPE_CLASS_TEXT;
    info.packageName = "com.messenger";
    info.actionLabel = label;
    info.extras = new Bundle();
    info.extras.putString("ballast", label);
    return info;
  }

  private void startSessions(final int count, final boolean distinctLabels) {
    for (int i = 0; i < count; i++) {
      ime.onStartInputView(fieldLabelled(distinctLabels ? "send-" + i : "send"), false);
      ShadowLooper.idleMainLooper();
    }
  }

  /**
   * The premise: the label really is part of the key, so a host that varies it really is minting
   * new entries rather than hitting the same one.
   */
  @Test
  public void thepremise() {
    startSessions(10, false /* one label, repeated */);
    final int afterRepeats = cache().size();

    cache().clear();
    startSessions(10, true /* ten different labels */);

    assertTrue("ten sessions on one label must not build ten cache entries; measured "
        + afterRepeats, afterRepeats < 10);
    assertTrue("ten sessions on ten host-chosen labels must build more entries than ten on one - "
            + "otherwise the label is not part of the key and there is nothing here to bound. "
            + "one label: " + afterRepeats + ", ten labels: " + cache().size(),
        cache().size() > afterRepeats);
  }

  /** The finding: the map must not grow one permanent entry per string the host invents. */
  @Test
  public void thecachedoesnotgrowwiththehostslabels() {
    startSessions(SESSIONS, true /* distinct labels */);

    assertTrue("the keyboard cache is keyed on a string the host picks and nothing evicts from "
            + "it: " + SESSIONS + " input sessions left " + cache().size() + " entries, against a "
            + "bound of " + cap(), cache().size() <= cap());
  }

  /** And the bound must not have been bought by throwing the cache away on ordinary use. */
  @Test
  public void anordinaryfieldstillcaches() {
    startSessions(SESSIONS, false /* the same label every time */);

    assertTrue("repeating one field must reuse its keyboards, not rebuild them: "
        + cache().size() + " entries after " + SESSIONS + " identical sessions",
        cache().size() < cap());
    assertTrue("and it must actually be caching something", cache().size() > 0);
  }

  /**
   * The host's action label is bounded before it becomes a key label.
   *
   * <p>{@code EditorInfo.actionLabel} is the application's to choose, and it was copied verbatim
   * into the enter key's drawn label. The keyboard view is hardware-accelerated, so {@code onDraw}
   * redraws every key rather than the invalidated ones — the label was measured with
   * {@code getTextBounds} and painted with {@code drawText} over its whole length on every frame,
   * on the IME's UI thread. That process serves every application on the device, so the cost is the
   * user's keyboard everywhere, not just in the messenger.
   *
   * <p>Bounded at the copy, not at the point of drawing, because this value is part of the cache
   * key: truncating for display while keying on the full string would let two hosts whose labels
   * share a prefix render each other's keyboards. Truncated here, a collision shows the same label
   * it keyed on.
   */
  @Test
  public void thehostsActionLabelIsBoundedBeforeItBecomesAkeyLabel() {
    final StringBuilder enormous = new StringBuilder();
    for (int i = 0; i < 50_000; i++) enormous.append("send ");

    ime.onStartInputView(fieldLabelled(enormous.toString()), false);
    ShadowLooper.idleMainLooper();

    // Read off the cache keys, the way the sibling tests in this file read them: those keys ARE
    // KeyboardIds, and mCustomActionLabel is the value that is both the key and the string
    // Key.mLabel is assigned from - so it is the one place both costs are decided, the per-frame
    // measure-and-draw and the retention.
    String carried = null;
    for (final Object key : cache().keySet()) {
      final String label = ((KeyboardId) key).mCustomActionLabel;
      if (label != null) {
        carried = label;
        break;
      }
    }
    assertNotNull("the keyboard must have carried the host's label, or this measures nothing",
        carried);
    assertTrue("a label the host chose is measured and painted on every frame; 250,000 characters "
            + "of it is the user's keyboard, in every app, for as long as that field has focus. "
            + "Length was " + carried.length(),
        carried.length() <= 128);
  }

  /**
   * And the bound cuts on a character rather than through one.
   *
   * <p>The limit counts UTF-16 units, so a label whose 128th and 129th units are the two halves of
   * one code point was split, leaving a lone high surrogate as the last unit. Both consumers
   * tolerate that - {@code drawText} paints a replacement glyph and {@code codePointCount} counts
   * it - which is exactly why nothing failed and nothing noticed. What was wrong is that the value
   * stopped being a well-formed string, and this class puts it in a cache key and hands it to the
   * renderer.
   *
   * <p>The label is built so the split lands mid-pair on purpose: 127 ASCII characters followed by
   * emoji, which puts a high surrogate at index 127 and its low surrogate at 128, the first unit
   * the bound discards.
   */
  @Test
  public void theboundDoesNotCutAcharacterInHalf() {
    final StringBuilder label = new StringBuilder();
    for (int i = 0; i < 127; i++) label.append('a');
    for (int i = 0; i < 20; i++) label.append("\uD83D\uDE00");   // U+1F600, a surrogate pair
    assertTrue("fixture: the 128th unit must be the high half of a pair, or this tests nothing",
        Character.isHighSurrogate(label.charAt(127)));

    ime.onStartInputView(fieldLabelled(label.toString()), false);
    ShadowLooper.idleMainLooper();

    String carried = null;
    for (final Object key : cache().keySet()) {
      final String value = ((KeyboardId) key).mCustomActionLabel;
      if (value != null) {
        carried = value;
        break;
      }
    }
    assertNotNull("the keyboard must have carried the host's label, or this measures nothing",
        carried);
    assertTrue("the bound must still hold: " + carried.length(), carried.length() <= 128);
    assertTrue("the truncated label must be a well-formed string - a lone surrogate is not a "
            + "character, and this value is both a cache key and the string the renderer is "
            + "handed. Last unit was 0x"
            + Integer.toHexString(carried.charAt(carried.length() - 1)),
        !Character.isHighSurrogate(carried.charAt(carried.length() - 1)));
  }

  /** And an ordinary label is untouched, so the bound is not quietly mangling real ones. */
  @Test
  public void anordinaryActionLabelIsUnchanged() {
    ime.onStartInputView(fieldLabelled("Send"), false);
    ShadowLooper.idleMainLooper();

    boolean found = false;
    for (final Object key : cache().keySet()) {
      final String label = ((KeyboardId) key).mCustomActionLabel;
      if (label != null) {
        assertEquals("a real action label must survive the bound exactly", "Send", label);
        found = true;
      }
    }
    assertTrue("no keyboard carried the label, so this asserts nothing", found);
  }
}
