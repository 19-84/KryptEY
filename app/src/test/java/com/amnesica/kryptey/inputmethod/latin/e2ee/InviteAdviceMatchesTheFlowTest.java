package com.amnesica.kryptey.inputmethod.latin.e2ee;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * The help offers the user a choice about how their invite travels. The app does not.
 *
 * <p>Sending an invite is the one moment trust-on-first-use has nothing to fall back on: there is no
 * earlier key, so a messenger that substitutes this bundle is not detected by anything. The help says
 * so, and then tells the user what to do about it — hand it over "in person, in an email, in a note",
 * which "keeps the messenger from ever seeing that first key".
 *
 * <p>Not as written, and the correction went one step too far before it went far enough. The invite
 * button commits the bundle into <b>whichever app currently has focus</b>, deliberately: {@code
 * sendEncryptedMessageToApplication} lowers the typing redirect first, precisely so the text lands
 * in the host app rather than in the strip. There is no affordance that hands the user their invite
 * any other way — the only {@code setPrimaryClip} call writes an empty clip, because its job is
 * clearing.
 *
 * <p>So tapping invite <em>inside the messenger</em> loses before the user can route anything: the
 * app they were told to route around is already holding it in an EditText it owns. But tapping it
 * inside a notes app or an email draft puts it there instead, and the messenger genuinely never sees
 * it. The capability was always real; the help simply never said which order to do things in, which
 * is the whole of the defect. An earlier version of this test banned the "never sees" claim
 * outright — that would now suppress a true and useful sentence, so it requires the instruction
 * beside it instead.
 *
 * <p>The sentence conflates two adversaries the rest of this codebase is careful to separate: the
 * messenger's <em>servers</em>, which see the invite only if the user presses send, and the messenger
 * <em>app</em>, which already has it. Every other control here — {@code FLAG_SECURE}, the typing
 * redirect, clearing the IME caches on send, clearing the clipboard — exists because the app is the
 * adversary. Advice that protects only against the servers, in a section explaining the one
 * unprotected moment in the trust model, tells a careful user they have closed a hole they have not.
 *
 * <p>Behaviour is asserted first, so the wording is measured against what the code does.
 */
@RunWith(RobolectricTestRunner.class)
public class InviteAdviceMatchesTheFlowTest {

  private static Path mainSources() {
    for (final String candidate : new String[] {"src/main/java", "app/src/main/java"}) {
      final Path path = Paths.get(candidate);
      if (Files.isDirectory(path)) return path;
    }
    throw new IllegalStateException("could not locate the main source tree");
  }

  private static String source(final String simpleName) throws IOException {
    final Path path = mainSources().resolve(
        "com/amnesica/kryptey/inputmethod/latin/e2ee/" + simpleName + ".java");
    assertTrue("expected to find " + path, Files.exists(path));
    return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
  }

  private static String helpText() {
    final Context context = RuntimeEnvironment.getApplication();
    return context.getString(
        com.amnesica.kryptey.inputmethod.R.string.e2ee_help_view_text);
  }

  private static String startAChatSection() {
    final String help = helpText();
    final int from = help.indexOf("How to start a chat");
    assertTrue("expected the help to contain a \"How to start a chat?\" section", from > 0);
    final int to = help.indexOf("<h3>", from + 1);
    return to > from ? help.substring(from, to) : help.substring(from);
  }

  /**
   * The invite is committed into the messenger, and nothing offers it any other way.
   *
   * <p>The premise of everything below. If a copy affordance is ever added, this test fails and the
   * wording assertion should be revisited rather than the source scan loosened.
   */
  @Test
  public void theinviteGoesStraightIntoTheMessengerAndNothingOffersItAnyOtherWay()
      throws IOException {
    final String stripView = source("E2EEStripView");
    final String strip = source("E2EEStrip");

    assertTrue("the invite button must be what triggers the bundle - if this moved, the rest of "
            + "this test is describing a flow that no longer exists",
        stripView.contains("sendPreKeyResponseMessageToApplication()"));
    assertTrue("the generated invite must be handed to sendEncryptedMessageToApplication, which is "
            + "what puts it in the messenger",
        stripView.contains("sendEncryptedMessageToApplication(encoded)"));
    assertTrue("and that method commits into the host app's input connection",
        stripView.contains("mListener.onTextInput"));

    // The one place a copy affordance could live. Both calls here write an empty clip - the method
    // exists to clear the clipboard, not to fill it.
    final int filled = strip.split("ClipData\\.newPlainText\\(\"\", \"\"\\)", -1).length - 1;
    final int total = strip.split("ClipData\\.newPlainText", -1).length - 1;
    assertTrue("this scan found no clipboard writes at all, so it has stopped testing anything",
        total >= 1);
    assertTrue("every clipboard write in the strip must be the empty clip used for clearing. A "
            + "non-empty one would mean an affordance now exists to hand the user their invite "
            + "without committing it to the messenger - in which case the help's advice becomes "
            + "followable and the wording assertion below should be revisited", filled == total);
  }

  /**
   * So the help must not promise the messenger never sees it.
   *
   * <p>Scoped to the specific false claim rather than to phrasing. Explaining why the first key is
   * the exposed moment is correct and should stay; telling the user that another route closes it is
   * what the flow contradicts.
   */
  @Test
  public void thehelpDoesNotPromiseTheMessengerNeverSeesTheInvite() {
    final String section = startAChatSection().toLowerCase(java.util.Locale.ROOT);

    // Not a ban on the claim - a requirement that it carry the instruction that makes it true.
    //
    // The first version of this test forbade "never sees" outright, because the help made that
    // promise while saying nothing about how to achieve it: the invite is committed to whichever
    // app currently has focus, so a reader who tapped invite inside the messenger had already lost
    // before they could route it anywhere. That reading was too pessimistic in one direction. The
    // capability is real - open the other app FIRST and the invite lands there instead - and the
    // defect was that the help never said so. Forbidding the claim would now hide a true and
    // useful one; requiring the instruction beside it is what the user actually needs.
    if (section.contains("never sees") || section.contains("ever seeing")) {
      assertTrue("the help says the messenger never sees the first key, which is true only if the "
              + "user opens the other app BEFORE tapping invite - the invite goes to whichever app "
              + "has focus. Said without that instruction it is a promise the reader cannot act on: "
              + section,
          section.contains("open the other app first"));
    }
  }

  /**
   * And it must still say that the invite lands in the messenger's field.
   *
   * <p>The other half. A rewrite that fixed the false promise by deleting the whole passage would
   * pass the test above and leave the user knowing less than before — the first bullet's admission
   * that the invite is placed in the host app's text field is the true and useful part.
   */
  @Test
  public void thehelpStillSaysWhereTheInviteActuallyGoes() {
    final String section = startAChatSection().toLowerCase(java.util.Locale.ROOT);
    assertTrue("the help must keep telling the user that the invite is placed into the text field "
            + "of whatever app they are in - that is what makes the exposure understandable: "
            + section,
        section.contains("text field") || section.contains("text box"));
  }
}
