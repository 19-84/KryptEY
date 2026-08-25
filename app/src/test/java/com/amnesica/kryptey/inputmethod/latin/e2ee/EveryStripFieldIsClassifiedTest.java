package com.amnesica.kryptey.inputmethod.latin.e2ee;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.view.View;

import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Every piece of state on the strip must have been decided about, and the decision must be here.
 *
 * <p>The strip is destroyed and rebuilt on every configuration change — a theme switch, night mode
 * at sunset, a rotation an app can force. State living on it therefore has a lifetime shorter than
 * the threat it defends against, and four consecutive review rounds have each found defects in that
 * gap, each in the previous round's fix. Every one of them was the same question answered wrongly by
 * omission: <em>this field — does it need to survive?</em>
 *
 * <p>"Remember to add it to {@code CarriedState}" has now failed twice, and it has no mechanical
 * answer. This gives it one. A new field on this class fails the build until somebody writes down
 * which of three things it is, and the writing-down is the point — not the list.
 *
 * <p>What this does NOT do is decide correctly for you. It cannot: whether the chosen recipient
 * should survive a rotation is a judgement, and it was made (it should not — re-choosing anyone is a
 * change from null, so {@code setChosenContact} empties the compose box and a carried draft cannot
 * reach the wrong person). What it does is stop the judgement being skipped silently, which is how
 * the password-field guard came to be armed on a view discarded eight lines later.
 */
public class EveryStripFieldIsClassifiedTest {

  /** Carried across a rebuild by {@code surrenderState}/{@code adoptState}. */
  private static final Set<String> CARRIED = new HashSet<>(Arrays.asList(
      "encodingMethod",        // the user's choice of wire encoding; a rebuild reset it to RAW
      "mHostFieldIsPassword",  // armed by onStartInputViewInternal, which then rebuilds the strip
      "mWarningStanding"));    // its javadoc says the messenger cannot clear it; a rebuild could

  /**
   * Deliberately NOT carried, with the reason. Each of these has been argued and tested.
   */
  private static final Set<String> DELIBERATELY_DROPPED = new HashSet<>(Arrays.asList(
      // Re-choosing anyone is a change from null, and setChosenContact empties the compose box on
      // any change - so a carried draft cannot reach the wrong person. Asserted by
      // StripCarriedStateAdversarialTest#aRebuildDropsTheRecipientAndReChoosingOneClearsTheCarriedDraft.
      "chosenContact",
      // Re-injected by LatinIME.setInputView on the new view; surrenderState hands the old one a
      // null otherIC so the connection stops pinning the discarded compose box.
      "mRichInputConnection",
      // Rebuilt with the view. Registered in the constructor and now released in surrenderState,
      // which is what stopped the discarded strip running on every clipboard change.
      "mClipboardListener",
      // Facades over SignalProtocolMain and the IME; hold no state of their own.
      "mE2EEStrip", "mListener", "mMainKeyboardView",
      // Animators on this view's own digit views, cancelled when the digits are blanked.
      "mCodeAnimators",
      // The verify screen's twelve digit views. Rendering, but classified by hand rather than
      // excluded automatically: it is an ARRAY of views, so the type check below does not see it,
      // and it holds the last safety number the user was shown - which is exactly the content a
      // review round found stranded on the discarded strip. surrenderState blanks it through
      // clearFingerprintViews, and that is load-bearing rather than tidy.
      "mCodes",
      // A holder for the strip's own child views; carries no state of its own.
      "mE2EEStripVisibilityGroup"));

  /** Types whose contents cannot change, so a final field of that type cannot hold state. */
  private static boolean isImmutable(final Class<?> type) {
    return type.isPrimitive()
        || type == String.class
        || Number.class.isAssignableFrom(type)
        || type == Boolean.class
        || type == Character.class
        || type.isEnum();
  }

  /**
   * Fields whose whole content is rendering: views, layouts, and the ids of views.
   *
   * <p>An acknowledged hole, left open rather than papered over. {@code CarriedState.banner} is a
   * {@code TextView}'s text, so a view's CONTENT can be carried state even though the field holding
   * the view is rendering. Which view's content is also state is not derivable from a type, and a
   * scan claiming to answer it would assert more than it checks — this file is honest about
   * covering the mechanical half.
   */
  private static boolean isRendering(final Field field) {
    return View.class.isAssignableFrom(field.getType())
        || field.getType().getName().startsWith("android.widget")
        || field.getType().getName().startsWith("android.view");
  }

  @Test
  public void everyMutableFieldOnTheStripIsClassified() {
    final List<String> unclassified = new ArrayList<>();
    int examined = 0;

    for (final Field field : E2EEStripView.class.getDeclaredFields()) {
      if (field.isSynthetic()) continue;
      // Constants and statics do not belong to an instance and cannot be lost with one.
      if (Modifier.isStatic(field.getModifiers())) continue;
      // final is NOT a reason to skip. A final field cannot be reassigned; its CONTENTS can still
      // be state, and the one instance here is the case that matters: mCodeAnimators is a final
      // List of running ValueAnimators, and a running animator is held by the process-wide
      // AnimationHandler through a lambda capturing a digit TextView - the exact retention shape a
      // review round found for the clipboard listener. Skipping finals meant it was listed as
      // classified and never examined, so removing it from the list failed nothing.
      if (Modifier.isFinal(field.getModifiers()) && isImmutable(field.getType())) continue;
      if (isRendering(field)) continue;

      examined++;
      final String name = field.getName();
      if (!CARRIED.contains(name) && !DELIBERATELY_DROPPED.contains(name)) {
        unclassified.add(name + " (" + field.getType().getSimpleName() + ")");
      }
    }

    assertTrue("this test reflects over the strip's fields; examining none means it has stopped "
        + "testing anything", examined >= 5);
    assertEquals("a new field on E2EEStripView has not been classified. The strip is rebuilt on "
        + "every configuration change, so decide now whether this survives one: add it to CARRIED "
        + "and to surrenderState/adoptState, or to DELIBERATELY_DROPPED with the reason. Four "
        + "review rounds have each found a defect that was this question skipped:\n"
        + String.join("\n", unclassified), 0, unclassified.size());
  }

  /**
   * And the classification cannot rot into naming fields that no longer exist.
   *
   * <p>Without this the lists would slowly fill with names of deleted fields, and a reader counting
   * them would believe more had been decided than actually was.
   */
  @Test
  public void theclassificationNamesOnlyFieldsThatExist() {
    final Set<String> actual = new HashSet<>();
    for (final Field field : E2EEStripView.class.getDeclaredFields()) {
      actual.add(field.getName());
    }

    final List<String> stale = new ArrayList<>();
    for (final String name : CARRIED) {
      if (!actual.contains(name)) stale.add("CARRIED: " + name);
    }
    for (final String name : DELIBERATELY_DROPPED) {
      if (!actual.contains(name)) stale.add("DELIBERATELY_DROPPED: " + name);
    }

    assertEquals("the classification names fields that no longer exist; remove them rather than "
        + "leaving them to suggest a decision that has no subject:\n" + String.join("\n", stale),
        0, stale.size());
  }
}
