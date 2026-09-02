package com.amnesica.kryptey.inputmethod;

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
 * {@code tools/README.md} describes the build image; {@code tools/Dockerfile} defines it.
 *
 * <p>That README exists because "which JDK, which Android platform, which build-tools" has been a
 * source of confusion at least once - its own opening sentence says so. It names Temurin 21, both
 * build-tools versions and the exact NDK, each with a paragraph on why that version and not
 * another. Nothing checked those against the file that installs them.
 *
 * <p>The drift is one-directional and quiet: somebody bumps a version in the Dockerfile for a good
 * reason, the image is rebuilt under a new tag, everything keeps working, and the document that
 * exists to end the confusion now describes an image nobody is running. Every reader after that is
 * being told something false by the one file written to be authoritative.
 *
 * <p>Checked in the direction that matters. A version in the Dockerfile must appear in the README -
 * so an upgrade forces the prose to be updated. Not the reverse: the README discusses versions it
 * does NOT install (17 as the minimum AGP accepts, 35.0.0 alongside 36.0.0) and that is the
 * explanation, not a contradiction.
 */
public class TheImageDescriptionMatchesTheImageTest {

  private static String read(final String name) throws IOException {
    for (final String candidate : new String[] {name, "../" + name}) {
      final Path path = Paths.get(candidate);
      if (Files.exists(path)) {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
      }
    }
    throw new IllegalStateException("could not locate " + name);
  }

  @Test
  public void everyVersionTheImageInstallsIsDescribedInTheReadme() throws IOException {
    final String dockerfile = read("tools/Dockerfile");
    final String readme = read("tools/README.md");
    final List<String> undocumented = new ArrayList<>();

    // The JDK the image is built on.
    final java.util.regex.Matcher base = java.util.regex.Pattern
        .compile("(?m)^FROM\\s+eclipse-temurin:(\\d+)").matcher(dockerfile);
    assertTrue("the Dockerfile no longer starts FROM eclipse-temurin, so this scan has stopped "
        + "matching the file it checks", base.find());
    if (!readme.contains("Temurin " + base.group(1))) {
      undocumented.add("JDK " + base.group(1));
    }

    // Every SDK component sdkmanager is asked to install, by exact version.
    final java.util.regex.Matcher component = java.util.regex.Pattern
        .compile("\"(build-tools|ndk|platforms);([^\"]+)\"").matcher(dockerfile);
    int found = 0;
    while (component.find()) {
      found++;
      String version = component.group(2);
      // ndk;${NDK_VERSION} - resolve the ARG the way docker would.
      final java.util.regex.Matcher arg = java.util.regex.Pattern
          .compile("\\$\\{?(\\w+)\\}?").matcher(version);
      if (arg.find()) {
        final java.util.regex.Matcher value = java.util.regex.Pattern
            .compile("(?m)^ARG\\s+" + arg.group(1) + "=(\\S+)").matcher(dockerfile);
        if (!value.find()) {
          undocumented.add(component.group(1) + ": unresolved " + version);
          continue;
        }
        version = value.group(1);
      }
      if (!readme.contains(version)) {
        undocumented.add(component.group(1) + " " + version);
      }
    }

    assertTrue("this scan must find the components the image installs; it found " + found,
        found >= 3);

    assertTrue("tools/README.md describes the build image and no longer mentions what the image "
            + "installs. That document exists because which JDK and which build-tools has already "
            + "confused somebody once, and a reader has no other source: " + undocumented,
        undocumented.isEmpty());
  }

  /**
   * And the base image is a moving tag, which is recorded rather than fixed.
   *
   * <p>{@code FROM eclipse-temurin:21-jdk-jammy} resolves to whatever that tag points at today. The
   * SDK components below it are pinned to exact versions; the platform underneath them is not. That
   * is a real difference between this image and a reproducible one, and pinning it to a digest is a
   * decision with a cost - digests have to be updated by hand for every security patch to the base.
   *
   * <p>So this asserts the situation rather than a fix: if the tag ever gains a digest, this test
   * fails and whoever did it can delete the case, having made the decision deliberately.
   */
  @Test
  public void thebaseImageIsStillAtagAndThatIsKnown() throws IOException {
    final String dockerfile = read("tools/Dockerfile");
    assertTrue("the base image now carries a digest. That is stronger than a tag and this test is "
            + "obsolete - delete it, and say in tools/README that the base is pinned so the next "
            + "person knows the digest has to be updated for base-image security patches.",
        !dockerfile.contains("eclipse-temurin@sha256:"));
  }
}
