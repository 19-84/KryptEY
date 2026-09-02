package com.amnesica.kryptey.inputmethod.latin.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import android.text.TextUtils;

import com.amnesica.kryptey.inputmethod.latin.settings.SpacingAndPunctuations;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

/**
 * Auto-capitalisation decides which character the user gets when they press a letter.
 *
 * <p>{@code getCapsMode} is a straight copy of {@code TextUtils.getCapsMode}, and copies drift from
 * their originals silently - nothing here compiles against the platform version or compares with
 * it. Its own header states the contract precisely, so the contract is what is asserted rather than
 * the implementation: CHARACTERS is always on when asked for; WORDS is on when whitespace precedes
 * the cursor; SENTENCES needs whitespace with the end of a sentence before that; opening
 * punctuation is skipped; and a period that is part of an abbreviation does not end a sentence.
 *
 * <p>This package had no tests until the recapitalisation properties beside this file. The security
 * question about it was answered by three sweeps - nothing here logs typed text - and this is the
 * other half: whether the code deciding what the user typed decides correctly.
 *
 * <p>Real {@code SpacingAndPunctuations} built from the app's own resources, not a stub. A stub
 * would let this file define the sentence separator it then asserts about, which is a test of
 * itself.
 */
@RunWith(RobolectricTestRunner.class)
public class AutoCapitalisationReadsTheTextBeforeTheCursorTest {

  private SpacingAndPunctuations spacing;

  private static final int ALL =
      TextUtils.CAP_MODE_CHARACTERS | TextUtils.CAP_MODE_WORDS | TextUtils.CAP_MODE_SENTENCES;

  @Before
  public void setUp() {
    spacing = new SpacingAndPunctuations(RuntimeEnvironment.getApplication().getResources());
  }

  private int modeFor(final String textBeforeCursor) {
    return CapsModeUtils.getCapsMode(textBeforeCursor, ALL, spacing);
  }

  private boolean words(final String text) {
    return (modeFor(text) & TextUtils.CAP_MODE_WORDS) != 0;
  }

  private boolean sentences(final String text) {
    return (modeFor(text) & TextUtils.CAP_MODE_SENTENCES) != 0;
  }

  /** The premise: this really does distinguish states, rather than answering one thing always. */
  @Test
  public void thepremiseThatTheAnswerDependsOnTheText() {
    assertNotEquals("if every input produced the same answer, every case below would hold on a "
        + "function that reads nothing", modeFor("Hello"), modeFor("Hello. "));
  }

  @Test
  public void charactersModeIsOnWheneverItIsAskedFor() {
    for (final String text : new String[] {"", "Hello", "Hello. ", "e.g. "}) {
      assertTrue("CHARACTERS is unconditional when requested, for '" + text + "'",
          (modeFor(text) & TextUtils.CAP_MODE_CHARACTERS) != 0);
    }
  }

  /** And only what was asked for comes back. */
  @Test
  public void nomodeIsReturnedThatWasNotRequested() {
    final int onlyCharacters =
        CapsModeUtils.getCapsMode("Hello. ", TextUtils.CAP_MODE_CHARACTERS, spacing);
    assertEquals("asking for CHARACTERS alone must not also answer WORDS or SENTENCES - the caller "
            + "uses this to decide whether to shift a single key or lock the keyboard",
        TextUtils.CAP_MODE_CHARACTERS, onlyCharacters);
  }

  @Test
  public void thestartOfTheFieldBeginsAwordAndAsentence() {
    assertTrue("an empty field starts a word", words(""));
    assertTrue("...and a sentence", sentences(""));
  }

  @Test
  public void midWordIsNeitherAwordNorAsentenceStart() {
    assertTrue("no whitespace precedes the cursor, so this is mid-word", !words("Hello"));
    assertTrue("and mid-word is never a sentence start", !sentences("Hello"));
  }

  @Test
  public void whitespaceStartsAwordButNotNecessarilyAsentence() {
    assertTrue("a space before the cursor starts a new word", words("Hello "));
    assertTrue("but no sentence ended, so this is not a sentence start", !sentences("Hello "));
  }

  @Test
  public void afullStopThenSpaceStartsAsentence() {
    assertTrue(sentences("Hello. "));
    assertTrue("a question mark ends a sentence too", sentences("Really? "));
    assertTrue("and an exclamation mark", sentences("Stop! "));
  }

  /**
   * An abbreviation's period does not end a sentence.
   *
   * <p>The subtlest rule in the header, and the one whose absence a user would notice constantly:
   * "e.g. " must not capitalise, or every abbreviation mid-sentence forces a capital.
   */
  @Test
  public void anabbreviationDoesNotEndAsentence() {
    assertTrue("'e.g. ' is an abbreviation, not the end of a sentence", !sentences("e.g. "));
    assertTrue("...and it still begins a word", words("e.g. "));
  }

  /**
   * Opening punctuation between the sentence end and the cursor is skipped.
   *
   * <p>Typing an opening quote or bracket after a full stop still leaves the user at the start of a
   * sentence, and the header says this is deliberate for every such character.
   */
  @Test
  public void openingPunctuationIsSkipped() {
    assertTrue("a quote after the sentence end must not cancel the sentence start",
        sentences("Hello. \""));
    assertTrue("nor a bracket", sentences("Hello. ("));
  }
}
