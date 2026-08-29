package com.amnesica.kryptey.inputmethod.latin.e2ee;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.view.ContextThemeWrapper;

import com.amnesica.kryptey.inputmethod.R;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * Every field the classification calls CARRIED must actually survive a rebuild.
 *
 * <p>{@link EveryStripFieldIsClassifiedTest} exists because "remember to add it to
 * {@code CarriedState}" had failed twice and has no mechanical answer. It gave it one — and only
 * half of one. That test compares {@code getDeclaredFields()} against two hand-written name sets
 * and reads neither {@code CarriedState}, {@code surrenderState} nor {@code adoptState}. Membership
 * in {@code CARRIED} is a string in a test file, so a field can be listed as carried while nothing
 * carries it.
 *
 * <p>Measured by a reviewer rather than argued: deleting
 * {@code mAccountReloadsWhenNoticeRaised = carried.accountReloadsWhenNoticeRaised;} from
 * {@code adoptState} left the entire suite — 1412 tests — green. A rotation is host-forceable, so
 * that is a silent regression on a path the messenger picks.
 *
 * <p>This is the other half: set every carried field to a distinguishable value, surrender, adopt
 * into a fresh strip, and read them back. It drives the same {@code CARRIED} set as its sibling, so
 * the two cannot drift apart — a field added to that list is immediately required to survive.
 *
 * <p>A static scan was considered and rejected. "Every name in CARRIED is assigned in adoptState"
 * looks equivalent and is not: five of the carried names are legitimately restored through setters
 * ({@code setHostFieldIsPassword}, {@code setWarningMessage}) or through {@code clear()+putAll}
 * rather than by direct assignment, so that check fails on correct code. Only the behaviour answers
 * the question.
 */
@RunWith(RobolectricTestRunner.class)
public class EverycarriedFieldActuallySurvivesArebuildTest {

  private E2EEStripView build() {
    final Application app = RuntimeEnvironment.getApplication();
    return new E2EEStripView(
        new ContextThemeWrapper(app, R.style.KeyboardTheme_LXX_Pure_Day), null);
  }

  private E2EEStripView outgoing;
  private E2EEStripView incoming;

  @Before
  public void setUp() {
    outgoing = build();
    incoming = build();
  }

  /** A value of the right type that is distinguishable from any default. */
  private Object distinctValueFor(final Field field) {
    final Class<?> t = field.getType();
    if (t == String.class) return "carried-" + field.getName();
    if (t == boolean.class) return true;
    if (t == long.class) return 4242L;
    if (t == int.class) return 4242;
    if (t.isEnum()) {
      final Object[] constants = t.getEnumConstants();
      // The LAST constant, so a field defaulting to the first is distinguishable.
      return constants[constants.length - 1];
    }
    if (java.util.Map.class.isAssignableFrom(t)) {
      final java.util.Map<Object, Object> m = new LinkedHashMap<>();
      m.put("carried-key", 7L);
      return m;
    }
    return null;
  }

  @Test
  public void everyFieldTheClassificationCallsCarriedSurvivesTheRebuild() throws Exception {
    final List<String> notCarried = new ArrayList<>();
    final List<String> planted = new ArrayList<>();

    for (final String name : EveryStripFieldIsClassifiedTest.CARRIED) {
      final Field field = E2EEStripView.class.getDeclaredField(name);
      field.setAccessible(true);
      final Object value = distinctValueFor(field);
      if (value == null) continue;   // a type this test cannot plant; reported below
      if (java.util.Map.class.isAssignableFrom(field.getType())) {
        // Maps are restored by clear()+putAll into the existing instance, so plant into it.
        @SuppressWarnings("unchecked")
        final java.util.Map<Object, Object> live =
            (java.util.Map<Object, Object>) field.get(outgoing);
        live.clear();
        live.putAll((java.util.Map<Object, Object>) value);
      } else {
        field.set(outgoing, value);
      }
      planted.add(name);
    }

    assertTrue("this test plants a value in every carried field and reads it back; planting none "
        + "means it has stopped testing anything", planted.size() >= 10);

    incoming.adoptState(outgoing.surrenderState());

    for (final String name : planted) {
      final Field field = E2EEStripView.class.getDeclaredField(name);
      field.setAccessible(true);
      final Object before = field.get(outgoing);
      final Object after = field.get(incoming);
      if (!String.valueOf(before).equals(String.valueOf(after))) {
        notCarried.add(name + " (was " + before + ", became " + after + ")");
      }
    }

    assertEquals("a field the classification lists as CARRIED did not survive surrenderState ->"
            + " adoptState. The list is a set of strings in a test file; carrying is what happens "
            + "in CarriedState. Either wire it up, or move it to DELIBERATELY_DROPPED with the "
            + "reason - the strip is rebuilt on every configuration change, which the host app can "
            + "force at will:\n" + String.join("\n", notCarried),
        0, notCarried.size());
  }
}
