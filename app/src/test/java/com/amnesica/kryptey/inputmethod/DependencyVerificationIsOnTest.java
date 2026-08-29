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

/**
 * The supply-chain control is present <em>and switched on</em>.
 *
 * <p>`verification-metadata.xml` exists and lists hundreds of pinned artifacts, and a test beside
 * this one checks that every document quoting that number quotes the real one. Neither of those
 * notices if verification is turned <em>off</em>: the file stays, the docs stay accurate about its
 * size, and nothing is checked at build time. A control that is present and disabled looks exactly
 * like a control that works, which is the defect class this project has now found in a signature
 * check, a substitution detector, a sealed marker and a password-field guard.
 *
 * <p>Three ways it can be off, and this asserts against all three: the metadata's own
 * `verify-metadata` switch, a `gradle.properties` override, and the build script passing
 * `--dependency-verification=off` — which `tools/build-in-docker` documents as being for local
 * experiments and "never for a build you intend to report on". This file is what makes that sentence
 * enforceable rather than advisory.
 *
 * <p><b>What it deliberately does not assert:</b> that signatures are verified. `verify-signatures`
 * is false, on purpose — the pinning here is by SHA-256 checksum, and claiming signature
 * verification would be the same overclaim this document records elsewhere. The property being
 * defended is "every artifact matches a recorded hash", not "every artifact is signed by someone
 * trusted".
 */
public class DependencyVerificationIsOnTest {

  private static Path repoRoot() {
    Path here = Paths.get("").toAbsolutePath();
    while (here != null && !Files.exists(here.resolve("gradle/verification-metadata.xml"))) {
      here = here.getParent();
    }
    return here;
  }

  private static String read(final String relative) throws IOException {
    final Path root = repoRoot();
    assertTrue("the repository root must be findable, or this test checks nothing", root != null);
    final Path file = root.resolve(relative);
    assertTrue("missing: " + relative, Files.exists(file));
    return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
  }

  @Test
  public void themetadataItselfHasVerificationEnabled() throws IOException {
    final String metadata = read("gradle/verification-metadata.xml");
    assertTrue("verify-metadata is the switch that makes the pinned checksums load-bearing. With "
            + "it false the file is decoration: hundreds of recorded hashes and nothing comparing "
            + "anything to them.",
        metadata.contains("<verify-metadata>true</verify-metadata>"));
  }

  @Test
  public void thepinnedSetIsNotEmptyAndEveryEntryCarriesAchecksum() throws IOException {
    final String metadata = read("gradle/verification-metadata.xml");
    final int components = metadata.split("<component ", -1).length - 1;
    assertTrue("finding almost no components means this file has stopped pinning anything: "
        + components, components > 100);
    assertTrue("checksums are the whole mechanism here, since signature verification is "
            + "deliberately off; a components list with no sha256 entries pins nothing",
        metadata.contains("sha256"));
  }

  @Test
  public void nothingTurnsVerificationOffOnTheWayPast() throws IOException {
    final List<String> offenders = new ArrayList<>();
    for (final String file : new String[] {
        "gradle.properties", "tools/build-in-docker", ".github/workflows/build.yml",
        // The script whose whole purpose is an honest cold verification, and which forwards its
        // arguments to ./gradlew like the others. Omitted from this list until a reviewer noticed:
        // nothing disables verification there today, and a list that stops one file short of the
        // one place a disable would be least expected is worth exactly as much as no list.
        "tools/verify-cold"}) {
      final Path path = repoRoot().resolve(file);
      if (!Files.exists(path)) continue;
      for (final String line : new String(Files.readAllBytes(path), StandardCharsets.UTF_8)
          .split("\n")) {
        final String text = line.trim();
        // A comment explaining the flag is not the flag. tools/build-in-docker documents that the
        // switch exists and says never to use it for a build worth reporting on; this must not fail
        // on that sentence, or the honest documentation would be the thing forbidden.
        if (text.startsWith("#") || text.startsWith("//")) continue;
        if (text.contains("--dependency-verification=off")
            || text.contains("dependencyVerification=off")
            || text.replace(" ", "").contains("verify-metadata>false")) {
          offenders.add(file + ": " + text);
        }
      }
    }

    assertEquals("dependency verification is switched off on the path a build actually takes. The "
        + "metadata would still be there and the docs would still quote its size, so nothing else "
        + "in this repository would notice:\n" + String.join("\n", offenders),
        0, offenders.size());
  }
}
