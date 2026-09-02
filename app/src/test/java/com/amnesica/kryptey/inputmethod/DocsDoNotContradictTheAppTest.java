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

  /**
   * No document may promise that fairytale mode fits inside a 3500-byte message.
   *
   * <p>The README and the store listing both said: <em>"To stay under the 3500 bytes limit, only
   * 500 characters are allowed for raw and fairytale mode."</em> The 500 is a cap on the user's
   * plaintext; what the messenger carries is the encrypted envelope. Measured by
   * {@code SendableIsDecodableTest#whattravelsIsMeasuredInBytesAgainstThePromisedLimit}: a full
   * 500-byte message is 3068 bytes in raw mode and 13,827 in fairytale, because every character
   * fairytale emits is drawn from U+200B-U+206F and costs three UTF-8 bytes.
   *
   * <p>The cost of the old sentence was a user following the app's stated limit, having the app
   * accept and encode the message, and the messenger truncating or refusing it - with the sender's
   * history already holding the plaintext and the recipient holding an undecodable paste. Nothing
   * on the sender's side went wrong, which is the shape this project already identifies as the
   * worst one on this path.
   *
   * <p>This pins the claim rather than the number: the documents may say what they like about
   * sizes, but not that fairytale mode stays under that limit. Phrased as a scan for the two
   * spellings of the old sentence, and its limit is the usual one - it cannot catch a promise
   * worded in a way nobody has used yet.
   */
  @Test
  public void nodocumentPromisesFairytaleModeFitsTheSmallestMessengerLimit() throws IOException {
    for (final String doc : new String[] {
        "README.md", "fastlane/metadata/android/en-US/full_description.txt"}) {
      final String text = read(doc).replaceAll("\\s+", " ");
      assertFalse(doc + " promises that 500 characters keeps BOTH modes under the 3500-byte "
              + "limit. Measured, fairytale sends 13,827 bytes for a 500-byte message - nearly "
              + "four times it. Raw is the mode that claim is true of",
          text.contains("only 500 characters are allowed for raw and fairytale mode"));
      assertFalse(doc + " still ties the 500-byte input cap to staying under the 3500-byte limit; "
              + "the cap is on the plaintext, and the envelope is several times larger",
          text.contains("To stay under the 3500 bytes limit"));
    }
  }

  /**
   * And both help copies must actually CARRY the fairytale disclaimer, not merely lack the bad
   * phrasings.
   *
   * <p>The scan beside this one asserts the absence of three phrasings across four documents, and
   * it passed on {@code HELP.md} vacuously for as long as that file had no text-modes section at
   * all: nothing to contain the banned strings, so nothing to catch. The mutant that shows it is
   * re-adding a fairytale section worded as concealment without reusing any of the three - the
   * scan stays green and the claim ships.
   *
   * <p>An absence check can only ever be as strong as the presence of the text it scans. This is
   * the presence half, on the two copies that are the app's actual explanation of the mode.
   */
  @Test
  public void bothCopiesOfTheHelpCarryTheFairytaleDisclaimer() throws IOException {
    for (final String doc : new String[] {"HELP.md", "app/src/main/res/values/strings.xml"}) {
      final String text = read(doc).replaceAll("\\s+", " ");
      assertTrue(doc + " must explain what fairytale mode is, or the check that it is not described "
              + "as concealment has nothing to read", text.contains("Fairy Tale mode"));
      assertTrue(doc + " must say, in the section that explains the mode, that it conceals nothing "
              + "from the messenger. Without this sentence a reader takes 'looks unremarkable' for "
              + "'the messenger cannot tell', which is the one thing it does not buy",
          text.contains("does not conceal anything from the messenger"));
    }
  }

  /**
   * No document may claim Signal's properties carry over without naming the one that does not.
   *
   * <p>The README and the store listing both said, flatly: <em>"The existing security properties
   * for the Signal Protocol are also valid for the keyboard."</em> The app's own help says the
   * opposite in as many words - <em>"Encryption on its own does not rule out the messenger, because
   * the messenger is what delivered the key"</em> - and the property that genuinely does not carry
   * over is authenticated key distribution. Signal delivers bundles through its own server; here
   * they arrive through the adversary, and {@code IdentityKeyStoreImpl} pins whatever comes first
   * because there is nothing yet to compare against. That is the design, correctly described by the
   * help, and it is not a Signal property that is "also valid for the keyboard".
   *
   * <p>In the README the flat sentence at least had a counterweight twenty lines below it. The
   * store listing's Security section was that one sentence and nothing else - and the store listing
   * is the widest-read text this project has, and where somebody choosing this app for a
   * chat-control threat model decides. They were told Signal's properties hold, installed, and
   * skipped the one step the help calls load-bearing.
   *
   * <p>Pinned as: if a document makes the carry-over claim, it must also name the voice comparison.
   * Not as a ban on the claim, which is true of the cryptography and worth saying.
   */
  @Test
  public void nodocumentClaimsSignalsPropertiesCarryOverWithoutTheStepThatMakesThemHold()
      throws IOException {
    for (final String doc : new String[] {
        "README.md", "fastlane/metadata/android/en-US/full_description.txt"}) {
      final String text = read(doc).replaceAll("\\s+", " ");
      assertFalse(doc + " still makes the unqualified carry-over claim. What does not carry over "
              + "is key DISTRIBUTION: Signal uses its own server, this uses the messenger being "
              + "defended against",
          text.contains("The existing security properties for the Signal Protocol are also valid"));
      assertTrue(doc + " describes the protocol's properties but never names the voice comparison, "
              + "which is the only step that tells the chat partner apart from the messenger. The "
              + "claim without the remedy reads as 'you are already safe'",
          !text.contains("Signal Protocol") || text.contains("by voice"));
    }
  }

  /**
   * And both copies must say the comparison needs an invite in each direction.
   *
   * <p>The instruction to compare before sending anything private was, on its own, unfollowable at
   * the moment it is given. An invite carries only the sender's own key and creates nothing for the
   * recipient, so after one arrives the receiver has a number and the sender has none — no pinned
   * key, no contact row. The only step in the documented flow that would give the sender digits is
   * the send the instruction forbids.
   *
   * <p>Measured both ways in {@code WhoCanReadAnumberAfterAninviteTest}: one-way leaves only the
   * receiver with a number, and swapping invites gives both sides the same one. The second half is
   * why this advice can be given at all — telling people to exchange invites is only honest if the
   * resulting pair of sessions works, and that is asserted rather than hoped.
   */
  @Test
  public void bothCopiesSayAninviteIsNeededInEachDirection() throws IOException {
    for (final String doc : new String[] {"HELP.md", "app/src/main/res/values/strings.xml"}) {
      final String text = read(doc).replaceAll("\\s+", " ");
      assertTrue(doc + " tells the user to compare a security number before sending, without "
              + "saying the other side needs their invite too. Until it arrives the peer has "
              + "nothing to compare against, so the instruction cannot be followed and the only "
              + "step that would fix it is the send it forbids",
          text.contains("Send them your own invite too"));
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
  /**
   * The lint-baseline figures BACKLOG.md quotes must be the ones in the baseline.
   *
   * <p>That file's summary of the baseline went stale once already: it said "502 filtered errors"
   * and stood six commits after a section of the same document had counted them properly. A summary
   * contradicting the thing it summarises is the one defect class on this branch that no behavioural
   * test catches, and three instances were found in two days by reading rather than by any guard.
   *
   * <p>So the checkable part is checked. Three numbers, all mechanical: how many issues the baseline
   * holds, how many distinct ids, and how many of them are the inherited `UnusedResources` that make
   * the total misleading on its own.
   */
  @Test
  public void thebacklogQuotesTheRealLintBaselineFigures() throws IOException {
    final String baseline = read("app/lint-baseline.xml");
    final String backlog = read("BACKLOG.md");

    final java.util.List<String> ids = new java.util.ArrayList<>();
    final java.util.regex.Matcher matcher =
        java.util.regex.Pattern.compile("<issue\\s+id=\"([^\"]+)\"").matcher(baseline);
    while (matcher.find()) ids.add(matcher.group(1));

    final long unused = ids.stream().filter("UnusedResources"::equals).count();
    final int distinct = new java.util.HashSet<>(ids).size();

    assertTrue("the baseline must actually hold issues, or this checks nothing", ids.size() > 100);
    assertTrue("BACKLOG.md must quote the real issue count " + ids.size() + "; it is the figure a "
            + "reader uses to decide whether the baseline hides anything",
        backlog.contains(String.valueOf(ids.size())));
    assertTrue("...and the real number of distinct ids, " + distinct,
        backlog.contains(String.valueOf(distinct)));
    assertTrue("...and how many are the inherited UnusedResources, " + unused + " - without that "
            + "share the total reads as unexamined defects when it is mostly one category",
        backlog.contains(String.valueOf(unused)));
  }
}
