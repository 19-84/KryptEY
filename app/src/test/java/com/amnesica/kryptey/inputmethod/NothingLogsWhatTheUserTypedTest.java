package com.amnesica.kryptey.inputmethod;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * No log line may carry the text the user typed.
 *
 * <p>REVIVAL records, as a settled finding, that "nothing anywhere logs typed text, with the one
 * candidate site logging a host-supplied {@code CompletionInfo} behind a compile-time
 * {@code false}". {@code DebugLoggingStaysOffTest} guards half of that - it pins the debug switches
 * as compile-time constants and off - and nothing guarded the other half: a NEW log line that is
 * not behind a switch at all. Adding one is a two-word edit, it breaks no test, and what it puts in
 * logcat is the plaintext of a message this app exists to encrypt.
 *
 * <p>The scan is worth having precisely because its noise floor is one. Across the whole of
 * {@code src/main} exactly one {@code Log} call interpolates a text-bearing identifier, and it is
 * the site the finding names - so the exemption list is one entry and any second hit is a real
 * change rather than a fixture to argue with.
 *
 * <p><b>Not a proof.</b> It matches identifiers by name, so a log of typed text through a variable
 * called something else passes. It is a tripwire on the obvious way to do it by accident, which is
 * the way it would actually happen.
 */
public class NothingLogsWhatTheUserTypedTest {

  /**
   * The one permitted site, and why.
   *
   * <p>{@code commitCompletion}'s argument is a {@code CompletionInfo} the HOST supplied - a
   * completion the messenger offered this keyboard, not something the user typed into it - and the
   * call sits behind {@code if (DEBUG)}, which {@code DebugLoggingStaysOffTest} pins as a
   * compile-time constant that is false. Both halves matter: the content is not the user's, and the
   * line is not in a release build.
   */
  private static final String PERMITTED_FILE = "E2EEInputConnection.java";

  /**
   * Any {@code Log} call that concatenates something. Strings are blanked before this runs.
   *
   * <p>{@code wtf} is listed separately because {@code [dviwe]} does not match it - the character
   * after {@code Log.w} is {@code t}, not an open bracket - and {@code Log.wtf} is the level a
   * developer reaches for when something should never happen, which is exactly when a payload gets
   * dumped to help work out why.
   */
  private static final Pattern LOG_CALL =
      Pattern.compile("Log\\.(?:wtf|[dviwe])\\s*\\([^;]*?\\)\\s*;", Pattern.DOTALL);

  private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*");

  /**
   * Whether an identifier plausibly holds what the user typed.
   *
   * <p>Substring, case-insensitive, and NOT a whole-word match - which is the correction that made
   * this scan work. The first version required {@code \btext\b}, so it matched a bare
   * {@code text} and missed every realistic name: {@code decryptedText}, {@code mComposeText},
   * {@code typedChars}. A control logging {@code decryptedText} passed against it.
   *
   * <p>Two exclusions, both measured rather than assumed. {@code context} contains "text" and is
   * everywhere. And an ALL_CAPS identifier is a compile-time constant - {@code LatinIME} logs
   * {@code TYPE_TEXT_FLAG_CAP_CHARACTERS}, a host-declared input-type flag, never the user's
   * content. With those two out, the whole of {@code src/main} yields exactly one hit, which is
   * the site the finding names.
   */
  private static boolean looksLikeTypedText(final String identifier) {
    final String lower = identifier.toLowerCase(java.util.Locale.ROOT);
    if (lower.contains("context")) return false;
    if (identifier.matches("[A-Z0-9_$]+")) return false;
    return lower.contains("text") || lower.contains("word") || lower.contains("typed")
        || lower.contains("chars") || lower.contains("plain") || lower.contains("decrypt");
  }

  /** Every hit in one file, as "identifier :: the call". */
  private static List<String> textualLogsIn(final String body) {
    final List<String> found = new ArrayList<>();
    final Matcher call = LOG_CALL.matcher(body);
    while (call.find()) {
      final String text = call.group();
      if (!text.contains("+")) continue;
      final Matcher identifier = IDENTIFIER.matcher(text);
      while (identifier.find()) {
        if (looksLikeTypedText(identifier.group())) {
          found.add(identifier.group() + " :: " + text.replaceAll("\\s+", " ").trim());
          break;
        }
      }
    }
    return found;
  }

  private static Path mainSources() {
    for (final String candidate : new String[] {"src/main/java", "app/src/main/java"}) {
      final Path path = Paths.get(candidate);
      if (Files.isDirectory(path)) return path;
    }
    throw new IllegalStateException("could not locate the main source tree");
  }

  /**
   * Comments AND string literals removed, and the second one is the whole difficulty.
   *
   * <p>The first version stripped only comments, and the scan immediately flagged
   * {@code Log.d(TAG, "All caps = " ... ", word caps = " ...)} in {@code LatinIME} - matching the
   * word "word" inside a LABEL. Log lines are full of prose naming the things they print, so a
   * scanner looking for identifiers must not read the strings around them, or it reports the
   * project's own diagnostics as leaks and gets an exemption list of things that were never wrong.
   */
  private static String withoutCommentsAndStrings(final String source) {
    return source.replaceAll("(?s)/\\*.*?\\*/", " ")
        .replaceAll("(?m)//[^\n]*", " ")
        .replaceAll("\"(?:[^\"\\\\]|\\\\.)*\"", "\"\"");
  }

  @Test
  public void onlyTheOneKnownSiteLogsAnythingTextual() throws IOException {
    final List<String> found = new ArrayList<>();
    int filesRead = 0;

    try (Stream<Path> files = Files.walk(mainSources())) {
      for (final Path file : (Iterable<Path>) files
          .filter(p -> p.toString().endsWith(".java"))::iterator) {
        filesRead++;
        final String body = withoutCommentsAndStrings(
            new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
        for (final String hit : textualLogsIn(body)) {
          found.add(file.getFileName() + ": " + hit);
        }
      }
    }

    assertTrue("this scan must actually read the source tree; it saw " + filesRead + " files",
        filesRead >= 100);

    final List<String> unexpected = new ArrayList<>();
    boolean sawPermitted = false;
    for (final String hit : found) {
      if (hit.startsWith(PERMITTED_FILE)) {
        sawPermitted = true;
      } else {
        unexpected.add(hit);
      }
    }

    assertEquals("a log line now carries something the user typed. In a release build that is the "
            + "plaintext of a message this app exists to encrypt, sitting in logcat. If the value "
            + "is genuinely not the user's text, say so at the call and add the file here: "
            + unexpected, 0, unexpected.size());

    // The rot check this repo applies to every exemption list: an exemption that no longer names a
    // real site is one nobody will notice has stopped protecting anything.
    assertTrue("the permitted site named here no longer matches anything, so this exemption is "
        + "stale and the scan is weaker than it reads", sawPermitted);
  }

  /** And the permitted site must still be behind the switch that keeps it out of a release. */
  @Test
  public void thepermittedSiteIsStillBehindTheDebugSwitch() throws IOException {
    Path found = null;
    try (Stream<Path> files = Files.walk(mainSources())) {
      for (final Path file : (Iterable<Path>) files
          .filter(p -> p.getFileName().toString().equals(PERMITTED_FILE))::iterator) {
        found = file;
      }
    }
    org.junit.Assert.assertNotNull("the permitted file must exist", found);

    // Matched and indexed in the SAME text. Stripping strings changes the length of the file, so
    // a match found in the stripped copy and read out of the original lands somewhere else
    // entirely - the first version of this case reported the context of an unrelated method.
    final String body = withoutCommentsAndStrings(
        new String(Files.readAllBytes(found), StandardCharsets.UTF_8));
    final List<String> hits = textualLogsIn(body);
    assertTrue("the permitted site must still be there, or this exemption is stale",
        !hits.isEmpty());

    final int at = body.indexOf(hits.get(0).substring(hits.get(0).indexOf(" :: ") + 4).split(" ")[0]);
    final String before = body.substring(Math.max(0, at - 160), Math.max(0, at));
    assertTrue("the permitted log must stay behind if (DEBUG); without it the exemption covers a "
            + "line that ships. Context was: " + before.replaceAll("\\s+", " ").trim(),
        before.contains("DEBUG"));
  }
}
