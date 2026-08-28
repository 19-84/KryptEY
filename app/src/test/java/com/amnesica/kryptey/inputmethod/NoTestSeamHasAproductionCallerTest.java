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
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * A seam that tests drive must not be reachable from the app itself.
 *
 * <p>There are twenty-eight of them now, and they exist because this surface is hard to drive
 * otherwise. Most are inert if they leak — {@code showContactListForTest} shows a screen the user
 * could reach anyway. Several are not: {@code setWarningMessageForTest},
 * {@code setCautionForTest} and {@code setStoreNoticeForTest} fabricate standing items, which are
 * the app's entire trust surface. A production path that reached one of those could put a security
 * sentence on screen that describes nothing, or — worse — hold the banner with a fake so a real one
 * cannot be written.
 *
 * <p>The sibling test guards the one seam whose leak would change protocol behaviour
 * ({@code testIsRunning}). This guards the shape: no seam has a caller outside the seams. It is a
 * source scan, and its limits are stated — it matches the naming convention, so a seam not called
 * {@code …ForTest} is invisible to it, and it cannot see reflection.
 */
public class NoTestSeamHasAproductionCallerTest {

  private static Path mainSources() {
    for (final String candidate : new String[] {"src/main/java", "app/src/main/java"}) {
      final Path path = Paths.get(candidate);
      if (Files.isDirectory(path)) return path;
    }
    throw new IllegalStateException("could not locate the main source tree");
  }

  /** Strips comments so a seam named in prose is not mistaken for a call. */
  private static String code(final Path file) throws IOException {
    return new String(Files.readAllBytes(file), StandardCharsets.UTF_8)
        .replaceAll("(?s)/\\*.*?\\*/", " ")
        .replaceAll("//[^\n]*", " ");
  }

  @Test
  public void noseamIsCalledFromProductionCode() throws IOException {
    final List<String> offenders = new ArrayList<>();
    final TreeSet<String> seamsSeen = new TreeSet<>();

    try (Stream<Path> files = Files.walk(mainSources())) {
      for (final Path file : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".java"))
          ::iterator) {
        final String source = code(file);

        // Every method declaration in the file, with the offset it starts at.
        final List<int[]> methodStarts = new ArrayList<>();
        final List<String> methodNames = new ArrayList<>();
        final Matcher declaration = Pattern.compile(
            "(?m)^\\s*(?:public|protected|private|static|final|synchronized|abstract|\\s)*"
                + "[\\w<>\\[\\],.?\\s]+\\s+(\\w+)\\s*\\([^;{]*\\)\\s*(?:throws [^{]+)?\\{")
            .matcher(source);
        while (declaration.find()) {
          methodStarts.add(new int[] {declaration.start(), declaration.end()});
          methodNames.add(declaration.group(1));
        }

        final Matcher call = Pattern.compile("\\b(\\w+ForTest)\\s*\\(").matcher(source);
        while (call.find()) {
          final String seam = call.group(1);
          seamsSeen.add(seam);

          // The declaration of the seam itself is not a call.
          boolean isDeclaration = false;
          for (int i = 0; i < methodStarts.size(); i++) {
            if (methodNames.get(i).equals(seam)
                && call.start() >= methodStarts.get(i)[0]
                && call.start() < methodStarts.get(i)[1]) {
              isDeclaration = true;
              break;
            }
          }
          if (isDeclaration) continue;

          // Which method is this call inside? The last one that starts before it.
          String enclosing = "<file scope>";
          for (int i = 0; i < methodStarts.size(); i++) {
            if (methodStarts.get(i)[0] < call.start()) enclosing = methodNames.get(i);
          }
          // A seam calling another seam is fine: they are all test-only together.
          if (enclosing.endsWith("ForTest")) continue;

          offenders.add(file.getFileName() + ": " + enclosing + "() calls " + seam + "()");
        }
      }
    }

    assertTrue("no seams were found at all; the naming convention this scan relies on has changed "
        + "and it is checking nothing", seamsSeen.size() >= 10);
    assertEquals("a test seam has a production caller. Several of these fabricate standing items - "
        + "warnings, cautions, the store notice - which are this app's entire trust surface: a "
        + "production path into one could put a security sentence on screen that describes nothing, "
        + "or hold the banner with a fake so a real one cannot be written:\n"
        + String.join("\n", offenders), 0, offenders.size());
  }
}
