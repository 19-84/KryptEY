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
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The seams tests use to drive this app must never be switched on in a build users get.
 *
 * <p>Most of them are inert if they leak: a package-private {@code showContactListForTest} shows a
 * screen the user could have reached anyway. One is not. {@code SignalProtocolMain.testIsRunning}
 * changes what {@code storeUnencryptedMessageInMap} does — with it set, an incoming message is filed
 * against a <b>fabricated contact</b> instead of one looked up in the contact list, so plaintext
 * from an address the user has never added is accepted and stored rather than raising
 * {@code InvalidContactException}. In a threat model where the adversary chooses the sender address
 * on every envelope, that is the difference between "unknown sender, refused" and "unknown sender,
 * decrypted and written to disk".
 *
 * <p>It is a {@code public static} field with a {@code false} initialiser and no production writer.
 * Nothing enforced either of those. This does, by reading the source rather than the field: tests
 * in this very suite set the flag to true, so its runtime value at any moment says nothing about
 * what a release build would do.
 */
public class TestSeamsStayOutOfProductionTest {

  private static Path mainSources() {
    for (final String candidate : new String[] {"src/main/java", "app/src/main/java"}) {
      final Path path = Paths.get(candidate);
      if (Files.isDirectory(path)) return path;
    }
    throw new IllegalStateException("could not locate the main source tree from "
        + Paths.get("").toAbsolutePath());
  }

  private static List<Path> javaSources() throws IOException {
    try (Stream<Path> walk = Files.walk(mainSources())) {
      return walk.filter(p -> p.toString().endsWith(".java")).collect(Collectors.toList());
    }
  }

  /**
   * No production source turns the flag on.
   *
   * <p>A single assignment anywhere in {@code main} would ship it, and it would not look alarming in
   * review — the name reads like a diagnostic.
   */
  @Test
  public void nothingInProductionSetsTestIsRunning() throws IOException {
    final Pattern assignment =
        Pattern.compile("testIsRunning\\s*=\\s*(?!false\\s*;)([A-Za-z0-9_.]+)\\s*;");
    final List<String> offenders = new ArrayList<>();
    int filesScanned = 0;

    for (final Path source : javaSources()) {
      filesScanned++;
      final String text = new String(Files.readAllBytes(source), StandardCharsets.UTF_8);
      final Matcher matcher = assignment.matcher(text);
      while (matcher.find()) {
        offenders.add(source + ": " + matcher.group().trim());
      }
    }

    assertTrue("this test scans source; scanning nothing means it has stopped testing anything",
        filesScanned >= 50);
    assertEquals("production code must never switch on a test seam. With testIsRunning set, an "
        + "incoming message is filed against a fabricated contact instead of one the user added, "
        + "so plaintext from an unknown address is stored rather than refused:\n"
        + String.join("\n", offenders), 0, offenders.size());
  }

  /** And the declared default is off, which is what a release build compiles in. */
  @Test
  public void thedeclaredDefaultIsOff() throws IOException {
    final Path file = mainSources().resolve(
        "com/amnesica/kryptey/inputmethod/signalprotocol/SignalProtocolMain.java");
    assertTrue("expected to find " + file, Files.exists(file));

    final String text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    final Matcher matcher = Pattern.compile(
        "boolean\\s+testIsRunning\\s*=\\s*([A-Za-z0-9_.]+)\\s*;").matcher(text);

    assertTrue("testIsRunning must still exist and be initialised explicitly", matcher.find());
    assertEquals("the seam must default to off - the runtime value cannot be asserted here because "
        + "tests in this suite set it, which is exactly why this reads the source",
        "false", matcher.group(1));
  }

  /**
   * The seam's effect is real, so that the tests above are guarding something.
   *
   * <p>Asserting only "the flag is false" would pass just as well if the branch it controls had been
   * deleted, at which point the guard protects nothing and nobody notices. This pins the branch's
   * existence, so removing the risk deliberately is a visible change rather than a silent one.
   */
  @Test
  public void theseamStillChangesWhatTheAppDoes() throws IOException {
    final Path file = mainSources().resolve(
        "com/amnesica/kryptey/inputmethod/signalprotocol/SignalProtocolMain.java");
    final String text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);

    assertTrue("storeUnencryptedMessageInMap must still branch on the seam. If this branch has "
            + "gone, that is good news and these tests should be deleted with it - but it should "
            + "not go quietly, because the guard above would keep passing over nothing",
        text.contains("if (testIsRunning) {"));
  }
}
