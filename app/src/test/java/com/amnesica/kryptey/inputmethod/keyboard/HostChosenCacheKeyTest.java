package com.amnesica.kryptey.inputmethod.keyboard;

import static org.junit.Assert.assertTrue;

import android.os.Bundle;
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
}
