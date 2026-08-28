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
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * No storage failure may push the user toward swapping keys.
 *
 * <p>This project's most-repeated finding: the app's generic advice for a failed decrypt is delete
 * the contact and ask for a new invite, and that advice is a key-substitution window — re-inviting
 * is the one moment an attacker can supply their own key and have it accepted as a first sighting.
 * A messenger can induce a failed decrypt at will by replaying a message or flipping a bit.
 *
 * <p>Storage failures are the same shape from the user's side — something did not work — and
 * completely different underneath: nothing about the keys is wrong, and the remedy is free space or
 * an unlock. A storage notice that reaches for the re-invite advice hands the attacker a second way
 * to provoke the exchange, this time out of a condition they do not even have to cause.
 *
 * <p>It has been reached for twice: the receive-side lost-write notice was written on top of the
 * generic decryption-failure message, and the lost-session notice reused the failed-add sentence,
 * whose instruction is "add them again successfully". Both are corrected; this stops the third.
 *
 * <p>The scan is over the storage notices by name, and the phrases are the ones this app actually
 * uses for that advice. Its limit is stated: it cannot catch advice phrased in words nobody has used
 * yet.
 */
public class NostorageFailureAdvisesReInvitingTest {

  /** The notices that report a storage failure. */
  private static final List<String> STORAGE_NOTICES = Arrays.asList(
      "INFO_CONTACT_NOT_SAVED",
      "INFO_DELETE_NOT_SAVED",
      "INFO_SESSION_NOT_SAVED",
      "INFO_DELETED_BUT_MESSAGES_REMAIN",
      "INFO_MESSAGE_NOT_SAVED",
      "INFO_SENT_MESSAGE_NOT_SAVED",
      "INFO_REJECTION_NOT_SAVED");

  /** How this app words the advice it must not give here. */
  private static final List<String> REINVITE_ADVICE = Arrays.asList(
      "fresh invite", "a new invite", "new invite", "re-invite", "reinvite",
      "delete the contact", "delete this contact", "ask for another invite");

  private static String source() throws IOException {
    Path here = Paths.get("").toAbsolutePath();
    while (here != null && !Files.exists(here.resolve("gradle/verification-metadata.xml"))) {
      here = here.getParent();
    }
    assertTrue("the repository root must be findable", here != null);
    return new String(Files.readAllBytes(here.resolve(
        "app/src/main/java/com/amnesica/kryptey/inputmethod/latin/e2ee/E2EEStripView.java")),
        StandardCharsets.UTF_8);
  }

  /** The declared text of a notice, or null when it does not exist. */
  private static String textOf(final String notice, final String source) {
    final Matcher m = Pattern.compile(
        "\\b" + notice + "\\s*=\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(source);
    return m.find() ? m.group(1) : null;
  }

  @Test
  public void nostorageNoticeTellsTheUserToSwapKeys() throws IOException {
    final String source = source();
    final List<String> offenders = new ArrayList<>();
    int checked = 0;

    for (final String notice : STORAGE_NOTICES) {
      final String text = textOf(notice, source);
      assertTrue("this test names a notice that no longer exists, so it is checking one fewer "
          + "thing than it claims: " + notice, text != null);
      checked++;
      final String lower = text.toLowerCase(java.util.Locale.ROOT);
      for (final String advice : REINVITE_ADVICE) {
        if (lower.contains(advice)) offenders.add(notice + " says \"" + advice + "\"");
      }
    }

    assertEquals("this test must check every notice it names", STORAGE_NOTICES.size(), checked);
    assertEquals("a storage failure is telling the user to swap keys. Nothing about their keys is "
        + "wrong; the remedy is free space or an unlock. Re-inviting is the one moment an attacker "
        + "can supply their own key and have it accepted as a first sighting, and this advice has "
        + "already been reached for twice on these paths:\n" + String.join("\n", offenders),
        0, offenders.size());
  }

  /**
   * And the help says the same thing to the user in as many words.
   *
   * <p>The notices avoiding the advice is not enough on its own: a user who has just been told
   * something could not be saved will look for what to do, and the help is where they look.
   */
  @Test
  public void thehelpSaysNotToSwapKeysOverAstorageError() throws IOException {
    Path here = Paths.get("").toAbsolutePath();
    while (here != null && !Files.exists(here.resolve("gradle/verification-metadata.xml"))) {
      here = here.getParent();
    }
    final String help = new String(Files.readAllBytes(
        here.resolve("app/src/main/res/values/strings.xml")), StandardCharsets.UTF_8);

    assertTrue("the help must cover what a storage failure means, because the notices deliberately "
        + "do not carry the explanation",
        help.contains("could not be saved. What does that mean?"));
    assertTrue("and it must say plainly not to swap keys over one - the notices avoid the advice, "
            + "and the help is where a user goes looking for what to do instead",
        help.contains("Do not delete the contact and ask for a new invite"));
  }
}
