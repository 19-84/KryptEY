package com.amnesica.kryptey.inputmethod.signalprotocol;

import static org.junit.Assert.assertTrue;

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
}
