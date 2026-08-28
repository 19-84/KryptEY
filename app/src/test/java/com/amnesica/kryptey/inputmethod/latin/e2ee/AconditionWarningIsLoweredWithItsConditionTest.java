package com.amnesica.kryptey.inputmethod.latin.e2ee;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A warning raised from a CONDITION must be lowered when the condition goes. One raised from an
 * EVENT must not, because the event still happened.
 *
 * <p>That rule was written three times in this file before it was stated once. The storage warning
 * kept asserting an unreadable store after it recovered; the contacts-unreadable warning kept
 * asserting it after an unlock, including the clause describing its own exit; the shared-name
 * warning kept asserting a clash after the user had resolved it by comparing the number. Each time
 * the raise was correct and nothing took it down, and each time the sentence held
 * {@code mWarningStanding}, which suppresses every other notice for the life of the process.
 *
 * <p>The distinction is not cosmetic. A condition warning that cannot be lowered is a permanent
 * banner and therefore a permanent suppression of everything else the app has to say — and this app
 * says security-relevant things. An event warning that CAN be lowered by a passing refresh is worse:
 * a detected key substitution is not undone by the store becoming readable.
 *
 * <p>So every method that raises a warning is classified here, and a condition-raiser must contain
 * its own lowering path. The classification is the point rather than the list: a new raiser fails
 * the build until somebody decides which kind it is.
 */
public class AconditionWarningIsLoweredWithItsConditionTest {

  /**
   * Raisers whose warning describes a state that can stop being true. Each must lower its own.
   */
  private static final Set<String> CONDITION_RAISERS = new LinkedHashSet<>(Arrays.asList(
      // Raises INFO_STORAGE_UNREADABLE and INFO_CONTACTS_UNREADABLE, both of which describe what
      // the store can do right now, and both of which recover.
      "refreshOpeningMessage",
      // Two live rows sharing a folded name, or a name the user deleted reappearing. Deleting a row
      // ends the first; comparing the safety number ends the second.
      "warnIfNameIsShared"));

  /**
   * Raisers whose warning describes something that happened. These must NOT lower on a refresh.
   *
   * <p>They are still re-derivable — from the stored record of the event — which is what makes
   * displacement survivable. What ends them is the user answering the event: verifying, rejecting,
   * or deleting the contact. That is a different thing from a condition going away, and conflating
   * the two is how a key substitution would come to be forgotten because a disk emptied.
   */
  private static final Set<String> EVENT_RAISERS = new LinkedHashSet<>(Arrays.asList(
      "warnIfIdentityChanged",       // a different key was offered at a pinned address
      "warnIfKeyWasRejected",        // a key was pinned where the user had reported a mismatch
      "addContact",                  // the duplicate-name and same-address refusals, at add time
      "decryptMessageAndShowMessageInMainInputField",  // the invite-refusal outcomes
      "adoptState"));                // restores what the outgoing view was already showing

  /** Methods that only pass a warning through, and so classify as neither. */
  private static final Set<String> PLUMBING = new LinkedHashSet<>(Arrays.asList(
      "setInviteRefusalWarning",
      "setWarningMessageForTest", "setWarningMessageAboutForTest"));

  private static String source() throws IOException {
    return sourceOf(repositoryRoot(),
        "app/src/main/java/com/amnesica/kryptey/inputmethod/latin/e2ee/E2EEStripView.java");
  }

  /** The same, for any file in the tree, with comments stripped so no claim is read from one. */
  private static String sourceOf(final Path root, final String path) throws IOException {
    return new String(Files.readAllBytes(root.resolve(path)), StandardCharsets.UTF_8)
        .replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("//[^\n]*", " ");
  }

  /**
   * The repository root, found by walking up from the working directory.
   *
   * <p>Marked by a file this task already declares as an input. Any other marker would be a repo
   * path a test reads and Gradle does not track, which is a guard of its own here - and it fired
   * the moment this method was written with a different one.
   */
  private static Path repositoryRoot() {
    Path here = Paths.get("").toAbsolutePath();
    while (here != null && !Files.exists(here.resolve("gradle/verification-metadata.xml"))) {
      here = here.getParent();
    }
    assertTrue("the repository root must be findable", here != null);
    return here;
  }

  /**
   * The lowering must have a caller that runs on every keyboard RAISE, not only on a rebuild.
   *
   * <p>This is the check that would have caught the defect the whole file is about. The lowering
   * path was added, tested, and could not execute: {@code refreshOpeningMessage}'s callers were
   * {@code setInputView} - always handed a freshly inflated strip, so no warning is standing - and
   * {@code adoptState}, guarded on the same flag. {@code setInputView} runs once per process unless
   * the theme changes, so a keyboard started while the device was locked kept the warning after the
   * unlock, suppressed every other notice for the life of the process, and left Encrypt and Decrypt
   * dark on an install whose storage works.
   *
   * <p>A test asserting the body CONTAINS a lowering path certifies dead code just as happily. So
   * this asserts the reachability instead: {@code onStartInputViewInternal} - which the framework
   * calls every time the keyboard comes up - must ask whether a condition warning is standing and
   * re-derive it if one is.
   */
  @Test
  public void theloweringHasAcallerThatRunsOnEveryKeyboardRaise() throws IOException {
    // sourceOf strips comments, and this scan depends on that more than any other in the file:
    // LatinIME names the safe call in the prose beside it, so a body read with comments intact
    // would be satisfied by the explanation of the fix rather than by the fix. A review round read
    // this as a live defect; it is not, and the mutant says so - deleting the call while keeping
    // its comment fails this test. The dependency is real even though the defect was not, which is
    // why it is written down here rather than left to be re-derived.
    final String latinIme = sourceOf(repositoryRoot(),
        "app/src/main/java/com/amnesica/kryptey/inputmethod/latin/LatinIME.java");
    final int start = latinIme.indexOf("void onStartInputViewInternal");
    assertTrue("onStartInputViewInternal must exist; it is the per-raise entry point", start > 0);

    int depth = 0;
    int i = latinIme.indexOf('{', start);
    final int bodyStart = i;
    while (i < latinIme.length()) {
      if (latinIme.charAt(i) == '{') depth++;
      else if (latinIme.charAt(i) == '}' && --depth == 0) break;
      i++;
    }
    final String body = latinIme.substring(bodyStart, Math.min(i, latinIme.length()));

    assertTrue("the per-raise path must ask whether a condition warning is standing, or the two "
            + "warnings with no other way down are lowered only by a theme change: " + body.length()
            + " characters scanned", body.contains("hasStandingConditionWarning"));
    assertTrue("...and re-derive it when one is. Asking without re-deriving leaves the same "
            + "permanent banner", body.contains("refreshOpeningMessage"));
    assertTrue("...after re-reading the store, since whether the contact list could be read is a "
            + "fact recorded by the last load rather than a live probe. And through the "
            + "recovery-gated re-read specifically: the plain reloadAccount installs a replacement "
            + "account here even when nothing recovered, which on the contacts arm means an emptied "
            + "contact list destroying everything the session did while writes were refused - on "
            + "every raise, at a moment the messenger picks. Asserting on the substring "
            + "'reloadAccount' alone did not catch that, because the safe call contains it",
        body.contains("reloadAccountIfStorageRecovered"));

    // And BEFORE the method's first early return, or a raise over a field the framework describes
    // with a null EditorInfo skips it - a shape the messenger picks by presenting that field.
    final int reconsider = body.indexOf("hasStandingConditionWarning");
    final int firstEarlyReturn = body.indexOf("Null EditorInfo");
    assertTrue("the per-raise entry point must be found to contain both", reconsider > 0);
    // Not "or the marker is missing". A review round pointed out that reading the ordering off a
    // log string and then excusing its absence makes the assertion vacuous on an ordinary
    // rewording - the guard would go quiet at exactly the moment the method was being edited.
    assertTrue("onStartInputViewInternal's early return for a null EditorInfo is the landmark this "
            + "ordering is measured against; if that log line was reworded, re-point this at the "
            + "new one rather than letting the check pass because it cannot find it",
        firstEarlyReturn > 0);
    assertTrue("the re-derivation must run before onStartInputViewInternal's first early return, "
            + "or a raise the host app shapes to hit that return skips it and the warning with no "
            + "other way down stays up",
        reconsider < firstEarlyReturn);
    // What this still cannot see, stated rather than implied: the block being wrapped in a new
    // condition. A scan reads presence and order, not reachability under a guard somebody adds.
  }

  /** Method name → its body, for every method in the file. */
  private static java.util.Map<String, String> methods() throws IOException {
    final String all = source();
    final java.util.Map<String, String> bodies = new java.util.LinkedHashMap<>();
    final Matcher declaration = Pattern.compile(
        "(?m)^\\s*(?:public|protected|private|static|final|synchronized|\\s)*"
            + "[\\w<>\\[\\],.?\\s]+\\s+(\\w+)\\s*\\([^;{]*\\)\\s*(?:throws [^{]+)?\\{")
        .matcher(all);
    while (declaration.find()) {
      int depth = 0;
      int i = all.indexOf('{', declaration.start());
      final int start = i;
      while (i < all.length()) {
        if (all.charAt(i) == '{') depth++;
        else if (all.charAt(i) == '}') {
          depth--;
          if (depth == 0) break;
        }
        i++;
      }
      bodies.put(declaration.group(1), all.substring(start, Math.min(i, all.length())));
    }
    return bodies;
  }

  @Test
  public void everyWarningRaiserIsClassified() throws IOException {
    final Set<String> unclassified = new TreeSet<>();
    int raisers = 0;

    for (final java.util.Map.Entry<String, String> method : methods().entrySet()) {
      if (!Pattern.compile("\\b(setWarningMessage|setInviteRefusalWarning)\\s*\\(")
          .matcher(method.getValue()).find()) {
        continue;
      }
      raisers++;
      final String name = method.getKey();
      if (CONDITION_RAISERS.contains(name) || EVENT_RAISERS.contains(name)
          || PLUMBING.contains(name)) {
        continue;
      }
      unclassified.add(name);
    }

    assertTrue("no warning raisers were found; this scan has stopped scanning", raisers >= 5);
    assertEquals("a method raises a warning and has not been classified. Decide which kind it is: a "
        + "warning about a CONDITION must be lowered when the condition goes, or it becomes a "
        + "permanent banner that suppresses everything else the app has to say; a warning about an "
        + "EVENT must not be lowered by a passing refresh, because the event still happened. This "
        + "rule was written three times in this file before it was stated once:\n"
        + String.join("\n", unclassified), 0, unclassified.size());
  }

  @Test
  public void everyConditionRaiserLowersItsOwnWarning() throws IOException {
    final java.util.Map<String, String> bodies = methods();
    final List<String> missing = new ArrayList<>();

    for (final String name : CONDITION_RAISERS) {
      final String body = bodies.get(name);
      assertTrue("the classification names a method that no longer exists: " + name, body != null);
      if (!body.contains("clearStandingWarning")) missing.add(name);
    }

    assertEquals("a condition raiser has no lowering path. Raising a warning from a state that can "
        + "stop being true, and never taking it down, leaves a sentence asserting something false "
        + "while holding mWarningStanding - which suppresses every other notice for the life of the "
        + "process:\n" + String.join("\n", missing), 0, missing.size());
  }

  /** And an event raiser must not lower a warning, which would forget the event. */
  @Test
  public void noeventRaiserLowersAwarningOnArefresh() throws IOException {
    final java.util.Map<String, String> bodies = methods();
    final List<String> offenders = new ArrayList<>();

    for (final String name : EVENT_RAISERS) {
      final String body = bodies.get(name);
      assertTrue("the classification names a method that no longer exists: " + name, body != null);
      // adoptState restores rather than raises, and clears nothing; the rest must not clear either.
      if (body.contains("clearStandingWarning()")) offenders.add(name);
    }

    assertEquals("an event raiser lowers a warning. A detected key substitution is not undone by a "
        + "disk emptying or a store becoming readable, and a warning that a refresh can forget is "
        + "one the messenger can arrange to have forgotten:\n" + String.join("\n", offenders),
        0, offenders.size());
  }

  /**
   * And the classification cannot name methods that no longer raise a warning.
   *
   * <p>Every other classification list in this project is guarded against this rot, and this one was
   * written without it — and the moment it was added it failed, naming five entries that raise
   * nothing. Three of them (`processPreKeyResponse`, `processUpdatedPreKeyResponse`,
   * `processSignalMessage`) call the warn helpers rather than the writer, and two
   * (`setWarningMessage`, `selectContact`) were listed from memory of what the file does. That is
   * the criticism a review round made of this whole effort — the reasoning written faster than it is
   * checked against the body — caught here by a check written for a different reason. Without the check the lists fill quietly with names of methods that have
   * been renamed or that no longer raise anything, and a reader counting them believes more has been
   * decided than actually was — which is the failure the lists exist to prevent, applied to the
   * lists themselves.
   */
  @Test
  public void theclassificationNamesOnlyMethodsThatStillRaiseAwarning() throws IOException {
    final java.util.Map<String, String> bodies = methods();
    final Set<String> raisers = new LinkedHashSet<>();
    for (final java.util.Map.Entry<String, String> method : bodies.entrySet()) {
      if (Pattern.compile("\\b(setWarningMessage|setInviteRefusalWarning)\\s*\\(")
          .matcher(method.getValue()).find()) {
        raisers.add(method.getKey());
      }
    }

    final List<String> stale = new ArrayList<>();
    for (final String name : CONDITION_RAISERS) {
      if (!raisers.contains(name)) stale.add("CONDITION_RAISERS: " + name);
    }
    for (final String name : EVENT_RAISERS) {
      if (!raisers.contains(name)) stale.add("EVENT_RAISERS: " + name);
    }
    for (final String name : PLUMBING) {
      if (!raisers.contains(name)) stale.add("PLUMBING: " + name);
    }

    assertEquals("the classification names something that no longer raises a warning. Remove it "
        + "rather than leave it suggesting a decision with no subject:\n" + String.join("\n", stale),
        0, stale.size());
  }
}
