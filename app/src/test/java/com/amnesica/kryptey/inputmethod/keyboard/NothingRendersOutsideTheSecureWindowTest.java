package com.amnesica.kryptey.inputmethod.keyboard;

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
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * {@code FLAG_SECURE} protects a window, so anything drawn outside that window is not protected.
 *
 * <p>The flag is set on the IME window while the strip shows decrypted plaintext. A keyboard also
 * draws a magnified preview of every key as it is pressed, and a long-press panel of alternatives —
 * so while the user types a secret, each character is rendered twice. Whether that second rendering
 * is captured along with everything else depends entirely on which window it lives in, and nothing
 * about the code says so at the point where it matters.
 *
 * <p>It is the right way round today, and that was checked rather than assumed:
 * {@code MainKeyboardView} adds its {@code DrawingPreviewPlacerView} to
 * {@code getRootView().findViewById(android.R.id.content)} — the IME's own window — and no class
 * under {@code keyboard/} or {@code latin/} constructs a {@code PopupWindow} or calls
 * {@code WindowManager.addView}. AOSP upstream has historically drawn more-keys panels in a
 * {@code PopupWindow}, so this is a live direction for a future change to take, and taking it would
 * silently move every keystroke of a plaintext message outside the protection.
 *
 * <p><b>One known exception, deliberately not asserted here:</b> the strip raises toasts, and a
 * toast is a separate system window. None of them carry message plaintext, several carry a contact's
 * display name, and the banner that carries the same name IS inside the protected window. That is
 * recorded in REVIVAL.md rather than papered over with an allowlist, because the fix is a product
 * decision about which of those messages need to be toasts at all.
 */
public class NothingRendersOutsideTheSecureWindowTest {

  private static Path mainSources() {
    for (final String candidate : new String[] {"src/main/java", "app/src/main/java"}) {
      final Path path = Paths.get(candidate);
      if (Files.isDirectory(path)) return path;
    }
    throw new IllegalStateException("could not locate the main source tree from "
        + Paths.get("").toAbsolutePath());
  }

  private static List<Path> javaSources(final String subPackage) throws IOException {
    try (Stream<Path> walk =
             Files.walk(mainSources().resolve("com/amnesica/kryptey/inputmethod/" + subPackage))) {
      return walk.filter(p -> p.toString().endsWith(".java")).collect(Collectors.toList());
    }
  }

  /**
   * Nothing that draws while the user types creates a window of its own.
   *
   * <p>Scoped to the packages that render during typing. A settings screen opening a dialog is an
   * Activity's business and has nothing to do with the IME window.
   */
  @Test
  public void nothingUnderTheKeyboardOrImeDrawsIntoItsOwnWindow() throws IOException {
    // The qualifier is not optional decoration: the first version of this pattern was
    // "new\\s+PopupWindow" and a control that wrote new android.widget.PopupWindow(...) walked
    // straight past it. A scanner that only sees the unqualified form catches the careless change
    // and misses the deliberate one.
    final Pattern escape = Pattern.compile(
        "new\\s+(?:[A-Za-z0-9_]+\\.)*(?:PopupWindow|Dialog)\\b"
            + "|WindowManager\\s*\\)?\\s*\\.?\\s*addView\\b");
    final List<String> offenders = new ArrayList<>();
    int filesScanned = 0;

    for (final String subPackage : new String[] {"keyboard", "latin/e2ee"}) {
      for (final Path source : javaSources(subPackage)) {
        filesScanned++;
        final String text = new String(Files.readAllBytes(source), StandardCharsets.UTF_8);
        final Matcher matcher = escape.matcher(text);
        while (matcher.find()) {
          offenders.add(source + ": " + matcher.group());
        }
      }
    }

    assertTrue("this test scans source; scanning nothing means it has stopped testing anything",
        filesScanned >= 30);
    assertEquals("something now draws outside the IME window. FLAG_SECURE protects a window, so "
        + "whatever this is will be captured while the user types an encrypted message:\n"
        + String.join("\n", offenders), 0, offenders.size());
  }

  /**
   * And the preview placer is still attached to the IME window's own content view.
   *
   * <p>The scan above sees a new window being constructed. It would not see the placer view being
   * re-parented to somewhere else that already exists, which reaches the same end by a route that
   * looks like a layout change.
   */
  @Test
  public void thekeyPreviewPlacerIsAttachedToTheImeWindow() throws IOException {
    final Path file = mainSources().resolve(
        "com/amnesica/kryptey/inputmethod/keyboard/MainKeyboardView.java");
    assertTrue("expected to find " + file, Files.exists(file));

    final String text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    assertTrue("the preview placer must be added to this window's own content view - every "
            + "keystroke of a plaintext message is drawn into it",
        text.contains("rootView.findViewById(android.R.id.content)")
            && text.contains("windowContentView.addView(mDrawingPreviewPlacerView)"));
  }
}
