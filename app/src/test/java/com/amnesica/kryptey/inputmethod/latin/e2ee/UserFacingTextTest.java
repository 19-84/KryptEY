package com.amnesica.kryptey.inputmethod.latin.e2ee;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.amnesica.kryptey.inputmethod.signalprotocol.encoding.FairyTaleEncoder;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The app's user-facing text, read as a security surface.
 *
 * <p>Same class of defect the verify screen had: a string that is not false, that a careful user can
 * follow exactly, and that leaves them unprotected when they do. Read from source and from
 * {@code strings.xml} rather than through Android, because the point is what ships in them.
 */
@RunWith(RobolectricTestRunner.class)
public class UserFacingTextTest {

  /**
   * Accepting an invite is the one moment trust-on-first-use pins a key, and the messenger chose
   * every byte of that key. Four outcomes of that flow write a message; three of them - a key at a
   * previously rejected address, a name collision with a live contact, a name collision with a
   * deleted one - tell the user to compare the number by voice.
   *
   * <p>The fourth is the ordinary first contact, and it is the only one of the four where nothing
   * else is protecting the user: the other three fire precisely because the app already noticed
   * something. That one says "You can send messages now" and stops. A user who follows it does
   * exactly that, and if the messenger substituted the bundle the message goes to the messenger.
   * The app warns on every derivative case and green-lights the base case.
   */
  @Test
  public void everyOutcomeOfAcceptingAnInviteSaysToCompareTheNumber() throws IOException {
    final String source = stripViewSource();

    final Map<String, String> outcomes = new LinkedHashMap<>();
    outcomes.put("a key at an address whose number the user previously rejected",
        constant(source, "INFO_PINNED_AFTER_REJECT"));
    outcomes.put("a second contact under a name already in use",
        constant(source, "INFO_DUPLICATE_CONTACT_NAME"));
    outcomes.put("a contact reusing the name of a deleted one",
        constant(source, "INFO_RETIRED_CONTACT_NAME"));
    outcomes.put("an ordinary first contact - the only outcome that pins a messenger-supplied key "
        + "with nothing else standing behind it", createdContactMessage(source));

    final StringBuilder says = new StringBuilder();
    final StringBuilder silent = new StringBuilder();
    for (final Map.Entry<String, String> outcome : outcomes.entrySet()) {
      (outcome.getValue().contains("by voice") ? says : silent)
          .append("\n  - ").append(outcome.getKey());
    }

    assertTrue("these outcomes of accepting an invite tell the user to compare the number by "
            + "voice:" + says + "\nand these do not:" + silent
            + "\nEvery outcome that fires because the app noticed something is covered. The one "
            + "that is not is the plain first contact - where the app noticed nothing precisely "
            + "because there was nothing to notice, and trust-on-first-use is pinning a key the "
            + "messenger chose. A user who follows that message does the one thing it says: sends.",
        silent.length() == 0);
  }

  /**
   * The help promises confidentiality unconditionally, in a section about the text modes: "In both
   * modes, all messages are still encrypted and cannot be read by third parties."
   *
   * <p>The app cannot know that. Confidentiality against the messenger holds only if the pinned key
   * is the contact's, and the only thing that establishes that is the voice comparison the help
   * itself describes three sections earlier. A user who reads this sentence has been told, by the
   * app, that the comparison is not load-bearing - encryption alone already excludes third parties -
   * so they skip it. The messenger is the third party this sentence excludes on their behalf.
   */
  @Test
  public void thehelpDoesNotPromiseConfidentialityTheAppCannotKnow() {
    final String modes = section(helpText(), "What are the text modes");

    if (!modes.contains("cannot be read")) return; // a rewrite that drops the claim is fine

    assertTrue("the help claims messages \"cannot be read by third parties\" with no condition "
            + "attached. That holds only against a key the user has actually compared, which the "
            + "app cannot know they did - so the claim must carry the condition: " + modes,
        modes.contains("voice") || modes.contains("verified") || modes.contains("compare"));
  }

  /**
   * Fairy Tale mode is described as hiding "all information ... in a decoy text". Against the
   * messenger - the adversary this app exists for - it hides nothing at all.
   *
   * <p>What actually goes on the wire is one sentence from the shipped Rapunzel/Cinderella text
   * followed by two invisible characters per payload byte, drawn from a fixed sixteen-character
   * alphabet. Anything containing a run of those characters is a KryptEY message and nothing else,
   * and the visible half is a sentence anyone can look up in the app's own strings.xml.
   *
   * <p>The user this wording is written for is the one who does not want the messenger to know they
   * encrypt - that is the only reason to prefer a decoy over Raw. They pick Fairy Tale, believe the
   * messenger sees a fairy tale, and are wrong. The behaviour is asserted here first so the wording
   * is measured against it rather than against taste.
   */
  @Test
  public void fairyTaleModeDoesNotHideAnythingFromTheMessenger() throws IOException {
    FairyTaleEncoder.mSentencesMap.clear();
    final String encoded =
        FairyTaleEncoder.encode("{\"a\":1,\"b\":2,\"c\":3}", RuntimeEnvironment.getApplication());

    int invisible = 0;
    for (int i = 0; i < encoded.length(); i++) {
      if (INVISIBLE.indexOf(encoded.charAt(i)) >= 0) invisible++;
    }
    assertTrue("precondition: the wire text must carry its payload as invisible characters, "
        + "otherwise this test is measuring the wrong thing", invisible > 8);

    // The visible half is a sentence the app ships; the invisible half is a marker nothing else
    // produces. A messenger applies one substring test and knows.
    final String visible = encoded.substring(0, encoded.length() - invisible);
    assertTrue("precondition: the decoy must come from the shipped stories",
        FairyTaleEncoder.mSentencesMap.containsValue(visible));

    final String modes = section(helpText(), "What are the text modes");
    assertFalse("the help says information is \"hidden\" in a decoy text, but every fairy-tale "
            + "message ends in a run of " + invisible + " characters from a fixed invisible "
            + "alphabet and begins with a sentence shipped in the app's own strings.xml - so the "
            + "messenger can identify it exactly. A user picks this mode to keep the messenger from "
            + "knowing they encrypt at all, which is the one thing it does not do. Either drop the "
            + "concealment wording or say who it hides from: " + modes,
        modes.contains("hidden") && !modes.contains("messenger"));
  }

  /**
   * The received-invite walkthrough ends by telling the user to send an encrypted message, and
   * never mentions the safety number.
   *
   * <p>Its sibling section, "How to start a chat?", got both halves: the out-of-band option and the
   * note that comparing by voice is still the check. This one is the same moment from the other
   * side - a key the messenger delivered, about to be pinned - and has neither. Three steps,
   * followed exactly, end with the user's first message encrypted to whatever key arrived.
   */
  @Test
  public void thereceivedInviteWalkthroughSaysToCompareTheNumber() {
    final String receiving = section(helpText(), "Someone sent me an invite message");

    assertTrue("the section that walks a user from an incoming invite to their first sent message "
            + "must say to compare the security number before sending - it is the only step in the "
            + "flow that distinguishes the contact from the messenger: " + receiving,
        receiving.contains("security number") || receiving.contains("by voice"));
  }

  /** The sixteen characters {@code EncodeHelper} encodes payload nibbles as. */
  private static final String INVISIBLE =
      "‌‍⁠⁢​‎‏⁤"
          + "⁪⁫⁬⁭⁮⁯؜﻿";

  /** The literal shown when an invite is accepted and nothing else fired. */
  private static String createdContactMessage(final String source) {
    final int at = source.indexOf("setInfoUnlessWarned(\"Contact \"");
    assertTrue("the created-contact message must still exist", at > 0);
    return source.substring(at, source.indexOf(");", at));
  }

  private static String constant(final String source, final String name) {
    final int at = source.indexOf(name + " =");
    assertTrue(name + " must still exist", at > 0);
    return source.substring(at, source.indexOf(";", at));
  }

  /** One {@code <h3>} section of the help, from its heading to the next one. */
  private static String section(final String help, final String heading) {
    final int at = help.indexOf(heading);
    assertTrue("the help section \"" + heading + "\" must still exist", at > 0);
    final int next = help.indexOf("<h3>", at);
    return help.substring(at, next < 0 ? help.length() : next);
  }

  private static String stripViewSource() throws IOException {
    final Path root = Paths.get(
        Files.isDirectory(Paths.get("src/main/java")) ? "src/main/java" : "app/src/main/java");
    return new String(Files.readAllBytes(
        root.resolve("com/amnesica/kryptey/inputmethod/latin/e2ee/E2EEStripView.java")),
        StandardCharsets.UTF_8);
  }

  private static String helpText() {
    for (final String candidate
        : new String[] {"src/main/res/values/strings.xml", "app/src/main/res/values/strings.xml"}) {
      final Path path = Paths.get(candidate);
      if (!Files.isDirectory(path.getParent())) continue;
      try {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
      } catch (IOException e) {
        throw new IllegalStateException("could not read " + path, e);
      }
    }
    throw new IllegalStateException("could not locate strings.xml");
  }
}
