package com.amnesica.kryptey.inputmethod;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The repository's help is a second copy of the app's help, and it had none of the corrections.
 *
 * <p>{@code HELP.md} duplicates the in-app help section for section. The in-app copy was audited as a
 * security surface and changed substantially: the verify step gained the channel and the reason, the
 * received-invite walkthrough gained a comparison step before the first send, and the invite step
 * gained the ordering that makes an out-of-band hand-off actually work. {@code HELP.md} kept the
 * original text throughout — including the verify wording whose defect started that audit, which a
 * reader satisfies exactly by pasting the numbers into the chat they are already in.
 *
 * <p>That copy is what is read on the web and in the store listing, so the correction that mattered
 * most reached the smaller audience. Duplicated text drifts; the only question is whether anything
 * notices.
 *
 * <p>This asserts the security-critical claims in both copies rather than that the files match. They
 * are different documents with different jobs — the in-app one is inside a keyboard strip and the
 * markdown one is not — and demanding equality would be a test of formatting. What must not diverge
 * is the advice on which the trust model depends.
 */
public class DocsDoNotContradictTheAppTest {

  private static String read(final String name) throws IOException {
    for (final String candidate : new String[] {name, "../" + name}) {
      final Path path = Paths.get(candidate);
      if (Files.exists(path)) {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
      }
    }
    throw new IllegalStateException("could not locate " + name + " from "
        + Paths.get("").toAbsolutePath());
  }

  /**
   * And the store listing must name the protocol the app negotiates.
   *
   * <p>The description a user reads before installing is the widest-read text this project has, and
   * it was the last copy still saying X3DH after the README, KRYPTEY.md and the in-app help had all
   * been corrected. Someone choosing this app for a recorded-now-decrypted-later threat decides here.
   */
  @Test
  public void thestoreListingNamesTheProtocolTheAppNegotiates() throws IOException {
    final String text = read("fastlane/metadata/android/en-US/full_description.txt");
    assertTrue("the store description must name PQXDH", text.contains("PQXDH"));
    assertFalse("and must not still present X3DH as the key agreement in use",
        text.contains("X3DH Key Agreement Protocol"));
  }

  /**
   * Every document quoting the pinned-artifact count must quote the real one.
   *
   * <p>Three files state it — the CI workflow, `tools/README.md` and REVIVAL.md — and two of the
   * three were wrong at the same time: 368 in the workflow and 387 in the tooling README against an
   * actual 386. Nobody mistypes a number twice in the same direction by accident; it drifted because
   * the metadata grew and the prose did not, which is the failure this whole file exists for.
   *
   * <p>Counted from `verification-metadata.xml` rather than hard-coded here, so this test cannot
   * become the fourth stale copy of the same figure.
   */
  @Test
  public void everyDocumentQuotesTheRealPinnedArtifactCount() throws IOException {
    final String metadata = read("gradle/verification-metadata.xml");
    final int components = metadata.split("<component ", -1).length - 1;
    assertTrue("this test counts <component> entries and found almost none; it has stopped testing "
        + "anything", components > 100);

    final java.util.regex.Pattern quoted =
        java.util.regex.Pattern.compile("(\\d{3,4}) (?:components|artifacts|pinned)");
    for (final String doc : new String[] {
        ".github/workflows/build.yml", "tools/README.md", "REVIVAL.md"}) {
      final String text = read(doc);
      final java.util.regex.Matcher m = quoted.matcher(text);
      while (m.find()) {
        // A number introduced by ~ or opening a quotation is being CITED, not claimed. This file
        // records its own corrections, so it quotes superseded figures on purpose - "the comment
        // said ~368 artifacts and the count is 386" must not be read as two claims. Skipping them
        // is what lets the document keep its history instead of erasing it to satisfy a checker.
        final char before = m.start() == 0 ? ' ' : text.charAt(m.start() - 1);
        if (before == '~' || before == '"' || before == '\u201c') continue;
        assertEquals(doc + " quotes " + m.group(1) + " pinned artifacts; the metadata pins "
            + components + ". Two of these three files were wrong at once before this test existed",
            components, Integer.parseInt(m.group(1)));
      }
    }
  }

  /**
   * A claim settled against one library version must name that version.
   *
   * <p>`REVIEW-SETTLED.md` records claims that were investigated and refuted, so a later review round
   * does not spend itself on ground already covered. Two of those entries are refuted **by
   * measurement against libsignal 0.86.5** — a corrupted first message reaches no store callback, so
   * it burns no base key and marks no pre-key used. That is a property of the library, not of this
   * code. An upgrade could move those callbacks earlier and make both claims true again, and the
   * ledger would go on saying they were refuted.
   *
   * <p>So the version in the ledger is checked against the one the build actually pins. If they
   * diverge the ledger is stale by construction, and the entry has to be re-measured rather than
   * re-read. {@code AcorruptedFirstMessageDoesNotPoisonTheGenuineOneTest} is what would fail if the
   * behaviour changed; this is what fails if the version changes and nobody looked.
   */
  @Test
  public void theSettledLedgerNamesTheLibsignalVersionTheBuildPins() throws IOException {
    final Matcher pinned = Pattern.compile("org\\.signal:libsignal-android:([0-9.]+)")
        .matcher(read("app/build.gradle"));
    assertTrue("the build must pin a libsignal version; if the dependency was renamed, re-point "
        + "this guard rather than deleting it", pinned.find());

    final String ledger = read("REVIEW-SETTLED.md");
    assertTrue("REVIEW-SETTLED.md refutes two claims by measurement against a specific libsignal "
            + "version, and the build now pins " + pinned.group(1) + ". Re-measure those entries "
            + "against the new version and update the ledger - a refutation that names the wrong "
            + "version is worse than no entry, because a reviewer will trust it",
        ledger.contains("libsignal " + pinned.group(1)));
  }

  /**
   * The instrumentation-test count in `tools/README.md` must be the real one.
   *
   * <p>It said seventeen for several rounds after the suite had grown to thirty, with a
   * three-group breakdown that accounted for barely half of it. Nothing was wrong with the tests;
   * what was wrong is that the only document describing them told a reader they had covered ground
   * they had not, and that reader is whoever next decides whether something needs a device test.
   *
   * <p>Counted from the source rather than from a list kept here, so the number cannot drift again
   * the moment a test is added. The count is textual - `@Test` occurrences outside comments - which
   * is exactly what a person would count, and that is the number the sentence is claiming.
   */
  @Test
  public void thereadmeQuotesTheRealInstrumentationTestCount() throws IOException {
    Path androidTests = Paths.get("app/src/androidTest/java");
    if (!Files.isDirectory(androidTests)) androidTests = Paths.get("src/androidTest/java");
    assertTrue("the instrumentation sources must be findable from " + Paths.get("").toAbsolutePath(),
        Files.isDirectory(androidTests));

    int actual = 0;
    try (java.util.stream.Stream<Path> walk = Files.walk(androidTests)) {
      for (final Path file : walk.filter(p -> p.toString().endsWith(".java"))
          .collect(java.util.stream.Collectors.toList())) {
        final String text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8)
            .replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("//[^\n]*", " ");
        final java.util.regex.Matcher test =
            java.util.regex.Pattern.compile("@Test\\b").matcher(text);
        while (test.find()) actual++;
      }
    }
    assertTrue("no instrumentation tests were found; this guard has stopped counting anything",
        actual >= 10);

    final String readme = read("tools/README.md");
    final java.util.regex.Matcher stated = java.util.regex.Pattern.compile(
        "tools/test-on-emulator` runs them\\. There are (\\d+)").matcher(readme);
    assertTrue("the instrumentation section of tools/README.md must state a count; if the sentence "
        + "was reworded, reword this guard with it rather than deleting it", stated.find());

    assertEquals("tools/README.md states an instrumentation-test count that the source contradicts. "
        + "It is the only document describing what a device test covers here, so a stale number "
        + "tells the next reader they have coverage they do not have",
        actual, Integer.parseInt(stated.group(1)));
  }

  /**
   * The comparison must name its channel, in both copies.
   *
   * <p>The original defect: "compare the number with your chat partner's number" is satisfied by
   * pasting it into the messenger, and whatever can substitute the keys can substitute the numbers.
   */
  @Test
  public void bothCopiesOfTheHelpSayToCompareByVoiceAndWhy() throws IOException {
    for (final String doc : new String[] {"HELP.md", "app/src/main/res/values/strings.xml"}) {
      final String text = read(doc);
      assertTrue(doc + " must name the channel - comparing by voice is what makes it a check",
          text.contains("by voice"));
      assertTrue(doc + " must give the reason, or the instruction reads as fussiness and gets "
              + "dropped as clutter by whoever edits it next",
          text.contains("change the numbers you send each other"));
    }
  }

  /** And the receiving side must be told to compare before its first send, in both copies. */
  @Test
  public void bothCopiesTellTheInviteeToCompareBeforeSending() throws IOException {
    for (final String doc : new String[] {"HELP.md", "app/src/main/res/values/strings.xml"}) {
      final String text = read(doc);
      assertTrue(doc + " must tell someone who received an invite to compare the security number "
              + "before sending anything - it is the only step that tells their chat partner apart "
              + "from the messenger", text.contains("security number"));
    }
  }

  /** And the out-of-band route must carry the ordering that makes it work, in both copies. */
  @Test
  public void bothCopiesSayToOpenTheOtherAppFirst() throws IOException {
    for (final String doc : new String[] {"HELP.md", "app/src/main/res/values/strings.xml"}) {
      final String text = read(doc);
      assertTrue(doc + " must say to open the other app BEFORE tapping invite. The invite goes to "
              + "whichever app has focus, so without the ordering the advice cannot be followed",
          text.contains("open the other app first"));
    }
  }

  /**
   * And no document may still claim fairytale mode hides the conversation.
   *
   * <p>Measured by {@code UserFacingTextTest} against the real encoder: the payload is a run of
   * characters from a fixed invisible alphabet after a sentence the app ships. It hides a message
   * from a glance, not from the messenger.
   */
  @Test
  public void nodocumentClaimsFairytaleModeHidesTheConversation() throws IOException {
    // The store listing is included deliberately, and it is the one that nearly escaped: it said
    // "hidden in a decoy TEXT" where the markdown said "decoy MESSAGE", so a check written against
    // the documents alone would have passed while the widest-read copy of the claim stood.
    final String[] docs = {"KRYPTEY.md", "HELP.md", "README.md",
        "fastlane/metadata/android/en-US/full_description.txt"};
    for (final String doc : docs) {
      final String text = read(doc);
      assertFalse(doc + " still presents fairytale mode as concealment. It conceals nothing from "
              + "the messenger, which is the only adversary this app has",
          text.contains("look inconspicuous")
              || text.contains("hidden in a decoy message")
              || text.contains("hidden in a decoy text"));
    }
  }
}
