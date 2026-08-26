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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A sentence that makes a claim about what happened must not be copied into a second message.
 *
 * <p>This exists because one defect has now been found in three consecutive review rounds, always
 * by the same mechanism: a message is written for one state, and when a neighbouring state needs a
 * message someone reuses its wording. The wording carries a factual claim with it, and the claim is
 * false in the new state.
 *
 * <p>Concretely, all three:
 * <ul>
 *   <li>{@code INFO_KEY_REJECTED}'s "this app has already been given a wrong key for them once" was
 *       copied into the no-pin message, where no key had ever been offered.
 *   <li>The correction, "none had been stored yet", was then false in the other no-pin state — a
 *       completed rejection, where a key very much had been stored.
 *   <li>{@code INFO_VERIFY_AFTER_REJECTION}'s "the number below is the key in use now" was reused on
 *       the screen whose digits are blank.
 * </ul>
 *
 * <p>Each was caught by a reviewer or a test, and each cost a round. The common step is mechanical —
 * the same sentence appearing in two constants — so it can be caught mechanically.
 *
 * <p><b>What this does NOT do.</b> It cannot tell a true sentence from a false one; that is a
 * judgement about states, and it stays a judgement. What it does is refuse to let the copy happen
 * <em>silently</em>. Sharing a sentence is often correct — the advice to compare by voice belongs in
 * several messages — so shared sentences are allowed, but each has to be written down here first.
 * The writing-down is the point: it is the moment someone asks "is this true in the new state?",
 * which is exactly the question all three defects skipped.
 */
public class NoClaimSentenceIsSharedBetweenMessagesTest {

  private static Path mainSources() {
    for (final String candidate : new String[] {"src/main/java", "app/src/main/java"}) {
      final Path path = Paths.get(candidate);
      if (Files.isDirectory(path)) return path;
    }
    throw new IllegalStateException("could not locate the main source tree from "
        + Paths.get("").toAbsolutePath());
  }

  private static final String STRIP =
      "com/amnesica/kryptey/inputmethod/latin/e2ee/E2EEStripView.java";

  /**
   * Sentences deliberately shared, each with the reason it is true wherever it appears.
   *
   * <p>Advice, not claims. None of these asserts that a particular thing happened, which is why
   * repeating them is safe: they tell the user what to do, and the instruction is true in every
   * state that shows it.
   */
  private static final Map<String, Set<String>> DELIBERATELY_SHARED = new HashMap<>();

  static {
    // Scoped to the exact constants, NOT to the sentence. This distinction is the whole value of
    // the test: the sentence below ends in a claim - "this app has already been given a wrong key
    // for them once" - and allowing it globally would permit precisely the historical defect, a
    // copy into the message for a contact that never had a key. Both constants named here describe
    // a contact whose key WAS pinned and WAS reported wrong, so it is true in both and in no third
    // place without someone arguing for it.
    // Pure advice: what to do when a key arrives, which does not depend on what has happened yet.
    // Note which sentence is NOT here - the variant ending "this app has already been given a wrong
    // key for them once" is a claim, and it now lives in INFO_KEY_REJECTED alone, the one state
    // where a key really was pinned and really was reported wrong.
    DELIBERATELY_SHARED.put(
        "When one arrives, compare the number with them by voice before sending anything private.",
        new HashSet<>(Arrays.asList("INFO_NOTHING_TO_REJECT", "INFO_ALREADY_REJECTED")));
    // A consequence of holding no key, not an event that happened.
    DELIBERATELY_SHARED.put("Nothing can be sent to them until they send a new invite.",
        new HashSet<>(Arrays.asList("INFO_KEY_REJECTED", "INFO_ALREADY_REJECTED")));
    // This one IS a claim, and it is shared deliberately because it is the weakest true statement
    // the rejection record supports. rejectContactKey marks the address whether or not a key was
    // pinned - the no-fingerprint verify screen enables Reject as the escape hatch from a standing
    // warning - so "you compared the numbers and they did not match" is NOT implied by the record,
    // and four messages used to assert it. What the record does mean, in every state that sets it,
    // is that the user told the app not to trust keys at that address. Any message keyed off
    // wasKeyRejected may say that and no more; a message that wants to claim a comparison needs a
    // record that only a comparison writes, and there is no such record today.
    // Advice, and identical in both refusal states: what to do about an invite that did not
    // verify does not depend on whether an older session survived it.
    DELIBERATELY_SHARED.put(
        "Ask them to send another, and if it keeps failing, send it a different way.",
        new HashSet<>(Arrays.asList("INFO_INVITE_REFUSED", "INFO_INVITE_REFUSED_SESSION_KEPT")));
    DELIBERATELY_SHARED.put("You told this app not to trust keys arriving for %s.",
        new HashSet<>(Arrays.asList("INFO_NO_FINGERPRINT_AFTER_REJECTION",
            "INFO_VERIFY_AFTER_REJECTION")));
  }

  /** Every {@code INFO_*} string constant declared on the strip, by name. */
  private static Map<String, String> messages() throws IOException {
    final Path path = mainSources().resolve(STRIP);
    assertTrue("expected to find " + path, Files.exists(path));
    final String source = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);

    final Map<String, String> found = new HashMap<>();
    final Matcher matcher = Pattern.compile(
        "String\\s+(INFO_[A-Z0-9_]+)\\s*=\\s*\"((?:[^\"\\\\]|\\\\.)*)\"\\s*;").matcher(source);
    while (matcher.find()) {
      found.put(matcher.group(1), matcher.group(2));
    }
    return found;
  }

  /**
   * Sentences long enough to carry a claim. Short fragments ("Ask them.") are noise, not claims,
   * and treating them as claims would bury the signal this test exists to raise.
   */
  private static List<String> claimSentences(final String message) {
    final List<String> sentences = new ArrayList<>();
    for (final String raw : message.split("(?<=[.?!])\\s+")) {
      final String sentence = raw.trim();
      if (sentence.length() >= 40) sentences.add(sentence);
    }
    return sentences;
  }

  @Test
  public void nosentenceAppearsInTwoMessagesWithoutBeingDeclaredShared() throws IOException {
    final Map<String, String> messages = messages();
    assertTrue("this test reads the strip's INFO_ constants out of its source; finding almost none "
        + "means the pattern has stopped matching and it is asserting nothing", messages.size() >= 20);

    final Map<String, Set<String>> owners = new HashMap<>();
    for (final Map.Entry<String, String> entry : messages.entrySet()) {
      for (final String sentence : claimSentences(entry.getValue())) {
        owners.computeIfAbsent(sentence, key -> new TreeSet<>()).add(entry.getKey());
      }
    }

    final List<String> undeclared = new ArrayList<>();
    for (final Map.Entry<String, Set<String>> entry : owners.entrySet()) {
      if (entry.getValue().size() < 2) continue;
      final Set<String> permitted = DELIBERATELY_SHARED.get(entry.getKey());
      if (permitted != null && permitted.containsAll(entry.getValue())) continue;
      undeclared.add(String.join(" + ", entry.getValue()) + "\n    shares: \"" + entry.getKey() + "\"");
    }

    assertEquals("a sentence is repeated across two messages without being declared shared. That "
        + "copy is how three consecutive review rounds introduced a false statement: the wording "
        + "was written for one state and carries its factual claim into another where the claim is "
        + "not true. Decide which this is. If the sentence is ADVICE (true wherever it is shown), "
        + "add it to DELIBERATELY_SHARED. If it CLAIMS something happened - a key was offered, a "
        + "number is on screen, something was stored or forgotten - then it belongs to one state "
        + "and the other message needs its own words:\n" + String.join("\n", undeclared),
        0, undeclared.size());
  }

  /** And the allowlist cannot rot into naming sentences no message contains. */
  @Test
  public void theallowlistNamesOnlySentencesThatAreActuallyShared() throws IOException {
    final Map<String, String> messages = messages();
    final List<String> stale = new ArrayList<>();
    for (final Map.Entry<String, Set<String>> allowed : DELIBERATELY_SHARED.entrySet()) {
      final Set<String> actual = new TreeSet<>();
      for (final Map.Entry<String, String> message : messages.entrySet()) {
        if (claimSentences(message.getValue()).contains(allowed.getKey())) actual.add(message.getKey());
      }
      if (actual.size() < 2) {
        stale.add("(" + actual.size() + " use) " + allowed.getKey());
      } else if (!allowed.getValue().containsAll(actual)) {
        // Not stale but wrong: the sentence has spread beyond what was argued for. Reported here
        // as well as above so the allowlist cannot quietly become broader than its own reason.
        final Set<String> extra = new TreeSet<>(actual);
        extra.removeAll(allowed.getValue());
        stale.add("(now also in " + String.join(", ", extra) + ") " + allowed.getKey());
      }
    }
    assertEquals("this allowlist names sentences that are no longer shared by two messages. Remove "
        + "them rather than leaving them to permit a future copy nobody has looked at:\n"
        + String.join("\n", stale), 0, stale.size());
  }
}
