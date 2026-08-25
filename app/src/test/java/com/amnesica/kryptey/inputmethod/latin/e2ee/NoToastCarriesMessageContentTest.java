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
import java.util.List;

/**
 * A toast is a separate system window, so {@code FLAG_SECURE} does not reach it.
 *
 * <p>The flag is set on the IME window while the strip shows decrypted plaintext. Every toast the
 * strip raises is drawn outside that protection and is captured by a screen recording taken while
 * the strip itself is not. What may therefore appear in one is a security question, and the answer
 * has until now lived only in REVIVAL.md as a sentence: "None of the strip's 26 carry message
 * plaintext — that was checked."
 *
 * <p>It was checked, twice, by hand, and it is true. It was pinned by nothing, and two of the sites
 * are true only by an accident that one word would undo.
 *
 * <p>Those two toast {@code e.getMessage()} raw. That is safe strictly because each sits under
 * {@code catch (TooManyCharsException e)}, and all four throw sites of that exception build their
 * message from byte counts. Widen either catch to {@code IOException} — the parent class, the
 * obvious tidy-up, a one-word edit — and arbitrary exception text goes to an unprotected window
 * from a method whose local variable is the user's plaintext. Nothing would have failed.
 *
 * <p>So this asserts the shape rather than the current strings: every toast argument must be a
 * constant, a format of a constant, or that one narrowly-caught exception. The count is deliberately
 * not asserted — REVIVAL.md said 26 and there are 30, which is what an asserted count is worth.
 */
public class NoToastCarriesMessageContentTest {

  private static Path mainSources() {
    for (final String candidate : new String[] {"src/main/java", "app/src/main/java"}) {
      final Path path = Paths.get(candidate);
      if (Files.isDirectory(path)) return path;
    }
    throw new IllegalStateException("could not locate the main source tree from "
        + Paths.get("").toAbsolutePath());
  }

  private static final String[] STRIP_FILES = {
      "com/amnesica/kryptey/inputmethod/latin/e2ee/E2EEStripView.java",
      "com/amnesica/kryptey/inputmethod/latin/e2ee/E2EEStrip.java",
  };

  /** One {@code Toast.makeText(...)} call: the argument text, and where it is. */
  private static final class ToastSite {
    final String file;
    final int line;
    final String args;
    final String enclosingCatch;

    ToastSite(final String file, final int line, final String args, final String enclosingCatch) {
      this.file = file;
      this.line = line;
      this.args = args;
      this.enclosingCatch = enclosingCatch;
    }

    @Override
    public String toString() {
      return file + ":" + line + "  " + args.replaceAll("\\s+", " ").trim();
    }
  }

  private static List<ToastSite> toastSites() throws IOException {
    final List<ToastSite> sites = new ArrayList<>();
    for (final String relative : STRIP_FILES) {
      final Path path = mainSources().resolve(relative);
      assertTrue("expected to find " + path, Files.exists(path));
      final String source = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
      final String name = relative.substring(relative.lastIndexOf('/') + 1);

      int from = 0;
      while (true) {
        final int open = source.indexOf("Toast.makeText(", from);
        if (open < 0) break;
        from = open + 1;

        // Commented-out debug toasts are not raised; two of them exist.
        final int lineStart = source.lastIndexOf('\n', open) + 1;
        if (source.substring(lineStart, open).trim().startsWith("//")) continue;

        // The argument region runs to the duration argument, which every call passes last.
        final int end = source.indexOf("Toast.LENGTH", open + "Toast.makeText(".length());
        if (end < 0) continue;
        String args = source.substring(open + "Toast.makeText(".length(), end);
        // Drop the leading Context argument so what remains is the text the user is shown.
        final int firstComma = args.indexOf(',');
        if (firstComma >= 0) args = args.substring(firstComma + 1);

        // The nearest catch above this call - which is what makes e.getMessage() safe or not.
        final int catchAt = source.lastIndexOf("catch (", open);
        String caught = "";
        if (catchAt >= 0) {
          final int catchEnd = source.indexOf(')', catchAt);
          if (catchEnd > catchAt) caught = source.substring(catchAt + "catch (".length(), catchEnd);
        }

        final int line = 1 + (int) source.substring(0, open).chars().filter(c -> c == '\n').count();
        sites.add(new ToastSite(name, line, args, caught.trim()));
      }
    }
    return sites;
  }

  /**
   * Nothing derived from the user's message may be handed to a toast.
   *
   * <p>The compose field, decrypted text and the clipboard are the three places message content
   * lives on this class. A toast argument mentioning any of them is plaintext outside the protected
   * window.
   */
  @Test
  public void notoastArgumentIsBuiltFromMessageContent() throws IOException {
    final String[] contentSources = {
        "mInputEditText", "getText()", "decrypted", "getDecrypted", "clipboard", "Clipboard",
        "getUnencryptedMessage", "plaintext",
    };

    final List<ToastSite> sites = toastSites();
    final List<String> offenders = new ArrayList<>();
    for (final ToastSite site : sites) {
      for (final String source : contentSources) {
        if (site.args.contains(source)) {
          offenders.add(site + "   <- mentions " + source);
          break;
        }
      }
    }

    assertTrue("this test scans source; finding no toasts means it has stopped testing anything",
        sites.size() >= 25);
    assertEquals("a toast is drawn outside the IME window, so FLAG_SECURE does not cover it. These "
        + "build their text from message content, which puts plaintext on screen in a window a "
        + "recording captures while the strip itself is protected:\n"
        + String.join("\n", offenders), 0, offenders.size());
  }

  /**
   * And the two raw {@code e.getMessage()} toasts stay under a narrow catch.
   *
   * <p>This is the assertion with a realistic regression behind it. Both sites are inside methods
   * whose whole purpose is handling the user's message, and both are safe only because
   * {@code TooManyCharsException} is the sole exception that can reach them — its four throw sites
   * all build their text from byte counts. Broadening either catch to {@code IOException}, or
   * adding a throw of that exception carrying the message, moves arbitrary text into an
   * unprotected window without touching the toast line at all.
   */
  @Test
  public void everyRawExceptionMessageToastIsUnderANarrowCatch() throws IOException {
    final List<ToastSite> sites = toastSites();
    final List<String> raw = new ArrayList<>();
    final List<String> unsafe = new ArrayList<>();

    for (final ToastSite site : sites) {
      if (!site.args.contains("getMessage()")) continue;
      raw.add(site.toString());
      if (!site.enclosingCatch.startsWith("TooManyCharsException")) {
        unsafe.add(site + "   <- caught as: "
            + (site.enclosingCatch.isEmpty() ? "(no enclosing catch)" : site.enclosingCatch));
      }
    }

    assertEquals("these toast an exception's message verbatim into a window FLAG_SECURE does not "
        + "cover. That is safe only while the exception is TooManyCharsException, whose text is "
        + "byte counts - a wider catch puts arbitrary text on screen from a method holding the "
        + "user's plaintext:\n" + String.join("\n", unsafe), 0, unsafe.size());
    assertTrue("expected to find the two known e.getMessage() toasts; finding none means the scan "
        + "has stopped matching and this test is now vacuous", raw.size() >= 2);
  }

  /**
   * Every other toast argument is a constant or a format of one.
   *
   * <p>The catch-all. The two tests above name the ways this has actually been got wrong; this one
   * fails on a shape nobody has thought of yet, which is the only kind of coverage that survives
   * the next author. Contact display names are permitted — they go through {@code labelFor} /
   * {@code displayLabelFor} / {@code sanitizeForBanner}, and that exposure is recorded separately
   * as a known limit rather than an oversight.
   */
  @Test
  public void everyOtherToastArgumentIsAConstantOrAFormatOfOne() throws IOException {
    final List<String> offenders = new ArrayList<>();

    for (final ToastSite site : toastSites()) {
      final String args = site.args;
      final boolean allowed =
          args.contains("INFO_")                         // a named constant, or a format of one
              || args.contains("getMessage()")           // covered by the test above
              || args.contains("duplicateNameMessage")   // selects between two INFO_ constants
              || args.trim().startsWith("\"")            // a literal written at the call site
              || args.contains("warning")                // local holding a format of an INFO_
              || args.contains("duplicate");             // likewise
      if (!allowed) offenders.add(site.toString());
    }

    assertEquals("this toast argument is not a constant, a format of one, or the narrowly-caught "
        + "exception message. It is drawn outside FLAG_SECURE, so whatever it interpolates is "
        + "visible to a recording - decide what it carries before adding it here:\n"
        + String.join("\n", offenders), 0, offenders.size());
  }
}
