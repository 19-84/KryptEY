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
import java.util.stream.Stream;

/**
 * Every class Jackson reflects over must be one the ProGuard rules keep.
 *
 * <p>REVIVAL records that the rules were checked once, by turning {@code minifyEnabled} on in a
 * throwaway build: R8 completed clean and the reflective entry points survived. That was a
 * measurement of the rules as they stood, and {@code minifyEnabled} is still false - so the rules
 * are 63 lines that no ordinary build applies, and nothing notices them drifting away from the code
 * they protect.
 *
 * <p>The drift that matters is not an edit to the rules. It is a new class: someone adds a
 * {@code @JsonProperty} model outside {@code signalprotocol.**}, everything works because nothing
 * is minified, and whoever turns minification on next gets a field silently dropped from the store
 * that persists the user's identity keys. R8 does not warn about that - the class is simply
 * renamed, and Jackson then writes JSON nobody can read back.
 *
 * <p>So this checks the pairing rather than the rules: every source file with a Jackson annotation
 * must sit under a package one of the {@code -keep} rules names. It cannot run R8, and does not
 * pretend to - it catches the one way this goes wrong without anybody editing
 * {@code proguard-rules.pro}.
 */
public class EverySerialisedClassIsKeptTest {

  private static Path mainSources() {
    for (final String candidate : new String[] {"src/main/java", "app/src/main/java"}) {
      final Path path = Paths.get(candidate);
      if (Files.isDirectory(path)) return path;
    }
    throw new IllegalStateException("could not locate the main source tree");
  }

  private static String rules() throws IOException {
    for (final String candidate : new String[] {"proguard-rules.pro", "app/proguard-rules.pro"}) {
      final Path path = Paths.get(candidate);
      if (Files.exists(path)) {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
      }
    }
    throw new IllegalStateException("could not locate proguard-rules.pro");
  }

  /** The package prefixes the keep rules cover, read from the rules rather than assumed. */
  private static List<String> keptPackages(final String rules) {
    final List<String> kept = new ArrayList<>();
    final java.util.regex.Matcher matcher = java.util.regex.Pattern
        // -keep only. NOT -keepclassmembers, which keeps the MEMBERS of classes that survive and
        // does nothing to stop the class itself being renamed - and a renamed class is exactly what
        // breaks Jackson. The first version accepted both, so narrowing the real -keep rule to a
        // package that does not exist left this test green: the -keepclassmembers rule beside it
        // still named signalprotocol and the scan counted it as protection.
        .compile("(?m)^-keep\\s+class\\s+([A-Za-z0-9_.]+)\\.\\*\\*")
        .matcher(rules);
    while (matcher.find()) kept.add(matcher.group(1));
    return kept;
  }

  @Test
  public void everyJacksonAnnotatedClassSitsUnderAkeptPackage() throws IOException {
    final List<String> kept = keptPackages(rules());
    assertTrue("no wildcard -keep rule was found, so either the rules changed shape or this scan "
        + "has stopped matching them: " + kept, !kept.isEmpty());

    final TreeSet<String> unprotected = new TreeSet<>();
    int annotated = 0;

    try (Stream<Path> files = Files.walk(mainSources())) {
      for (final Path file : (Iterable<Path>) files
          .filter(p -> p.toString().endsWith(".java"))::iterator) {
        final String body = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        if (!body.contains("@JsonProperty") && !body.contains("@JsonCreator")) continue;
        annotated++;

        final String type = mainSources().relativize(file).toString()
            .replace(java.io.File.separatorChar, '.').replace(".java", "");
        boolean covered = false;
        for (final String prefix : kept) {
          if (type.startsWith(prefix + ".")) covered = true;
        }
        if (!covered) unprotected.add(type);
      }
    }

    assertTrue("no Jackson-annotated class was found at all, so this scan is measuring nothing",
        annotated >= 10);

    assertEquals("a class Jackson reflects over sits outside every -keep rule. Nothing fails today "
            + "because minifyEnabled is false; whoever turns it on gets these classes renamed, and "
            + "Jackson then writes JSON that cannot be read back - in the store that persists the "
            + "user's identity keys. Either move the class or add a keep rule for it: "
            + unprotected, 0, unprotected.size());
  }

  /**
   * And minification really is still off, so the paragraph above describes the build that ships.
   *
   * <p>If it is ever turned on, this fails - which is the right moment to re-run the throwaway-build
   * measurement REVIVAL describes rather than trusting a result taken when the rules were younger.
   */
  @Test
  public void minificationIsStillOffSoTheseRulesAreStillUnapplied() throws IOException {
    String script = null;
    for (final String candidate : new String[] {"build.gradle", "app/build.gradle"}) {
      final Path path = Paths.get(candidate);
      if (Files.exists(path)) {
        script = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
      }
    }
    org.junit.Assert.assertNotNull("could not locate the build script", script);

    assertTrue("minifyEnabled is no longer false. That is not a defect - but the ProGuard rules "
            + "have never been applied by an ordinary build, and REVIVAL's evidence that they work "
            + "is one throwaway build taken when the rule set was younger. Re-run it, then update "
            + "this test.",
        script.contains("minifyEnabled false"));
  }
}
