package com.amnesica.kryptey.inputmethod;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;

/**
 * The binary that runs every build, and the checksum it is supposed to honour.
 *
 * <p>Dependency verification covers the artifacts a build downloads;
 * {@code distributionSha256Sum} covers the Gradle distribution itself. Neither covers
 * {@code gradle-wrapper.jar} - a 47 KB executable committed to this repository, which runs before
 * any of that and is the code that reads {@code distributionSha256Sum} in the first place. A
 * swapped wrapper can ignore the checksum it is asked to enforce, and everything downstream -
 * dependency verification, the reproducible build, this suite - runs inside whatever it started.
 *
 * <p>Nothing noticed if it changed. It appears in a diff as a binary blob, which is the thing
 * reviewers skim and forges collapse by default. This pins it, so a change has to be acknowledged
 * by updating a line of source next to a sentence explaining why.
 *
 * <p><b>This guard cannot be fully controlled here, and that is a property of what it guards.</b>
 * The ordinary check - change the file, watch the test go red - is impossible: the jar is what runs
 * the test. Flipping one bit gives "Error: Invalid or corrupt jarfile" and nothing executes;
 * appending sixteen bytes does the same, because {@code java -jar} rejects trailing data. So every
 * modification either changes the digest AND prevents the guard from running, or does neither.
 *
 * <p>What was controlled is the comparison: with {@code PINNED} set to a value the jar cannot have,
 * this fails and prints the real digest, so the assertion is reading the file rather than agreeing
 * with itself. That is weaker than a mutation and it is the strongest available from inside a build
 * this artifact starts. A reviewer wanting more has to verify the jar against the Gradle release
 * from outside the repository, which is the same act this pin exists to make someone perform.
 *
 * <p>Not a claim that the current jar is trustworthy - it came with the fork and this test cannot
 * attest to its provenance. It is a claim that it is the same one the branch has been building and
 * testing with, which is a weaker and checkable thing.
 */
public class TheWrapperThatRunsEverythingIsPinnedTest {

  /**
   * SHA-256 of {@code gradle/wrapper/gradle-wrapper.jar} as this branch has always built with it.
   *
   * <p>Changed by a legitimate wrapper upgrade, which is exactly when someone should have to come
   * here, run {@code sha256sum} themselves and write the new value down.
   */
  private static final String PINNED =
      "7a9ce74cff467ca1bf60a4fcd9f05185acceda4d0f382434d393e17864262c5d";

  private static Path repoFile(final String name) {
    for (final String candidate : new String[] {name, "../" + name}) {
      final Path path = Paths.get(candidate);
      if (Files.exists(path)) return path;
    }
    throw new IllegalStateException("could not locate " + name + " from "
        + Paths.get("").toAbsolutePath());
  }

  @Test
  public void thewrapperJarIsTheOneThisBranchHasAlwaysUsed() throws Exception {
    final byte[] jar = Files.readAllBytes(repoFile("gradle/wrapper/gradle-wrapper.jar"));
    assertTrue("the wrapper jar must actually be a jar; a stub would make this pin meaningless",
        jar.length > 10_000);

    final StringBuilder hex = new StringBuilder();
    for (final byte b : MessageDigest.getInstance("SHA-256").digest(jar)) {
      hex.append(String.format("%02x", b));
    }

    assertEquals("gradle-wrapper.jar has changed. It runs before dependency verification and before "
            + "the distribution checksum - it is the code that ENFORCES that checksum - so a "
            + "substitution here is upstream of every other guard in this repository. If this is a "
            + "deliberate wrapper upgrade, verify it against the Gradle release and update PINNED "
            + "with the reason. If it is not, do not run another build in this checkout.",
        PINNED, hex.toString());
  }

  /**
   * And the distribution checksum must still be there for it to honour.
   *
   * <p>Deleting one line from a properties file turns distribution verification off silently: the
   * build keeps working, downloads keep succeeding, and nothing anywhere reports that the zip is no
   * longer checked. Same shape as {@code continue-on-error} in the workflow - the visible outcome is
   * unchanged and the guarantee is gone.
   */
  @Test
  public void thedistributionChecksumIsStillDemanded() throws IOException {
    final String properties = new String(
        Files.readAllBytes(repoFile("gradle/wrapper/gradle-wrapper.properties")),
        StandardCharsets.UTF_8);

    assertTrue("distributionSha256Sum is gone from gradle-wrapper.properties, so the Gradle "
            + "distribution is downloaded and run unverified. Nothing else reports this: the build "
            + "succeeds exactly as before",
        properties.contains("distributionSha256Sum="));
    assertTrue("the distribution must still be fetched over https - an http URL would make the "
            + "checksum the only thing standing between this build and the network",
        properties.contains("https\\://") || properties.contains("https://"));
  }
}
