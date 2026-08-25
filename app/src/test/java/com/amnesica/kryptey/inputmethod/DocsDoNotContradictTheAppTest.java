package com.amnesica.kryptey.inputmethod;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

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
