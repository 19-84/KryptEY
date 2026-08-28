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
    Path here = Paths.get("").toAbsolutePath();
    while (here != null && !Files.exists(here.resolve("gradle/verification-metadata.xml"))) {
      here = here.getParent();
    }
    assertTrue("the repository root must be findable", here != null);
    return new String(Files.readAllBytes(here.resolve(
        "app/src/main/java/com/amnesica/kryptey/inputmethod/latin/e2ee/E2EEStripView.java")),
        StandardCharsets.UTF_8).replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("//[^\n]*", " ");
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
