package com.amnesica.kryptey.inputmethod.signalprotocol;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Nothing may log a message, a ciphertext, or a contact's identity.
 *
 * <p>An input method sees everything the user types, so what it writes to logcat matters more than
 * it would in an ordinary app: logcat is readable over adb, is captured in bug reports, and on a
 * rooted or debuggable device is readable by other software. No plaintext was ever logged - that
 * held when audited. What WAS logged, unconditionally and since the initial commit, was the
 * complete wire envelope on every send and decode, and every contact's first name, last name,
 * device id and protocol UUID.
 *
 * <p>Those survived twenty-four adversarial review rounds, including one that edited
 * {@code FairyTaleEncoder} to fix a decode bug and left the {@code Log.d} printing the ciphertext
 * beside it. Nobody was looking at logging, so this is a source check rather than a behavioural one:
 * it cannot prove a string is harmless, but it can stop the obvious shapes coming back.
 */
public class NoSensitiveLoggingTest {

  /** Log statements that interpolate something this project must not write out. */
  private static final List<Pattern> FORBIDDEN = List.of(
      // A whole contact: toString() carries names, device id, UUID and the verified flag.
      Pattern.compile("Log\\.[dviwe]\\([^)]*\\b\\w*[Cc]ontact\\.toString\\(\\)"),
      // Names, which identify who the user talks to.
      Pattern.compile("Log\\.[dviwe]\\([^)]*getFirstName\\(\\)"),
      Pattern.compile("Log\\.[dviwe]\\([^)]*getLastName\\(\\)"),
      // Message bodies and envelopes.
      Pattern.compile("Log\\.[dviwe]\\([^)]*getUnencryptedMessage\\(\\)"),
      Pattern.compile("Log\\.[dviwe]\\([^)]*\\bencryptedMessage\\b"),
      Pattern.compile("Log\\.[dviwe]\\([^)]*\\bdecryptedMessage\\b"),
      Pattern.compile("Log\\.[dviwe]\\([^)]*\\bbinaryMessage\\b"),
      Pattern.compile("Log\\.[dviwe]\\([^)]*\\bminifiedJson\\b"));

  @Test
  public void nothingLogsAmessageOrAcontactsIdentity() throws IOException {
    final Path root = Paths.get("src/main/java");
    assertTrue("this test must run from the module directory; looked in "
        + root.toAbsolutePath(), Files.isDirectory(root));

    final List<String> offences = new ArrayList<>();
    final List<Path> sources = new ArrayList<>();
    try (Stream<Path> files = Files.walk(root)) {
      files.filter(f -> f.toString().endsWith(".java")).forEach(sources::add);
    }
    assertTrue("no sources were scanned, so this proved nothing", sources.size() > 50);

    for (final Path file : sources) {
      final String[] lines = new String(Files.readAllBytes(file),
          java.nio.charset.StandardCharsets.UTF_8).split("\n");
      for (int i = 0; i < lines.length; i++) {
        for (final Pattern forbidden : FORBIDDEN) {
          if (forbidden.matcher(lines[i]).find()) {
            offences.add(root.relativize(file) + ":" + (i + 1) + "  " + lines[i].trim());
          }
        }
      }
    }

    assertTrue("these log statements write out a message, an envelope, or a contact's identity:\n  "
        + String.join("\n  ", offences), offences.isEmpty());
  }
}
