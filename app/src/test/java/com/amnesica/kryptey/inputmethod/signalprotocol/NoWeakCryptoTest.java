package com.amnesica.kryptey.inputmethod.signalprotocol;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * No weak or hand-rolled cipher construction may ship.
 *
 * <p>A dead class called {@code AESCrypt} shipped in the release APK: {@code Cipher.getInstance
 * ("AES")}, which on Android means ECB, with the password's bytes used directly as the key - no
 * derivation, no IV, no authentication. It was {@code @Deprecated}, commented "for testing", called
 * from nowhere, and included anyway because {@code minifyEnabled} is false, so nothing strips
 * unreachable code from the build.
 *
 * <p>It survived twenty-four adversarial review rounds because it is in the part of the tree nobody
 * had read. Unreachable code in a shipped APK is not harmless: it is a ready-made primitive for
 * whoever edits this next, sitting under a name that sounds like the right thing to use.
 *
 * <p>This is a source check. It cannot prove a cipher is used correctly - that is what the storage
 * tests do - but it stops the shapes that are wrong however they are used.
 */
public class NoWeakCryptoTest {

  /** Transformations that are wrong regardless of how they are used. */
  private static final List<String> FORBIDDEN = List.of(
      // Bare "AES" resolves to ECB on Android: identical plaintext blocks give identical
      // ciphertext, and nothing detects tampering.
      "Cipher.getInstance(\"AES\")",
      "Cipher.getInstance(\"AES/ECB",
      "getInstance(\"DES",
      "getInstance(\"RC4",
      // No-padding-no-mode variants of the same mistake.
      "Cipher.getInstance(ALGORITHM)");

  @Test
  public void noWeakCipherConstructionInMainSource() throws IOException {
    final Path root = Paths.get("src/main/java");
    assertTrue("this test must run from the module directory", Files.isDirectory(root));

    final List<Path> sources = new ArrayList<>();
    try (Stream<Path> files = Files.walk(root)) {
      files.filter(f -> f.toString().endsWith(".java")).forEach(sources::add);
    }
    assertTrue("no sources were scanned, so this proved nothing", sources.size() > 50);

    final List<String> offences = new ArrayList<>();
    for (final Path file : sources) {
      final String[] lines = new String(Files.readAllBytes(file),
          java.nio.charset.StandardCharsets.UTF_8).split("\n");
      for (int i = 0; i < lines.length; i++) {
        for (final String forbidden : FORBIDDEN) {
          if (lines[i].contains(forbidden)) {
            offences.add(root.relativize(file) + ":" + (i + 1) + "  " + lines[i].trim());
          }
        }
      }
    }

    assertTrue("weak cipher construction in shipped source - minifyEnabled is false, so anything "
        + "here reaches the APK whether or not it is called:\n  "
        + String.join("\n  ", offences), offences.isEmpty());
  }

  /**
   * No Java deserialisation entry point in the code that handles the wire format.
   *
   * <p>The vendored {@code Base64} shipped {@code decodeToObject}, which fed base64 straight into
   * {@code ObjectInputStream.readObject()}, alongside five methods that read and wrote arbitrary
   * file paths. Nothing called any of them — and they were in the one class every envelope the
   * messenger relays passes through, since the wire format is base64. {@code minifyEnabled} is
   * false, so dead code ships.
   *
   * <p>They are gone. This is the guard against them coming back, or appearing elsewhere: a
   * deserialisation sink one call away from untrusted input is not made safe by nobody having made
   * that call yet, and the distance between "unused" and "used" is one line in a future commit.
   */
  @Test
  public void nothingDeserialisesJavaObjectsInTheProtocolCode() throws IOException {
    final List<String> offenders = new ArrayList<>();
    int scanned = 0;

    try (Stream<Path> walk = Files.walk(Paths.get("src/main/java").toFile().exists()
        ? Paths.get("src/main/java") : Paths.get("app/src/main/java"))) {
      for (final Path source : walk.filter(p -> p.toString().endsWith(".java"))
          .collect(Collectors.toList())) {
        scanned++;
        // Comments stripped first. The comment recording why these methods were deleted names
        // them, and a scanner that flags its own explanation is a false positive that invites the
        // next reader to weaken it rather than heed it.
        final String text = new String(Files.readAllBytes(source), StandardCharsets.UTF_8)
            .replaceAll("(?s)/\\*.*?\\*/", "")
            .replaceAll("(?m)//.*$", "");
        if (text.contains("ObjectInputStream") || text.contains("readObject(")) {
          offenders.add(source.toString());
        }
      }
    }

    assertTrue("this test scans source; scanning nothing means it tests nothing", scanned >= 50);
    assertEquals("Java deserialisation has appeared in the app. Every envelope the messenger sends "
        + "is parsed by this code, and readObject on attacker-controlled bytes is arbitrary code "
        + "execution:\n" + String.join("\n", offenders), 0, offenders.size());
  }
}
