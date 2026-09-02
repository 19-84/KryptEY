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
import java.util.stream.Stream;

/**
 * The E2EE and protocol code runs on one thread, and that is load-bearing.
 *
 * <p>REVIEW-SETTLED records it as a finding: a sweep of {@code latin/e2ee} and
 * {@code signalprotocol} for {@code Thread}, {@code Executor}, {@code AsyncTask},
 * {@code HandlerThread}, {@code runOnUiThread} and {@code .post(} returns nothing, every
 * {@code SharedPreferences} write is {@code commit()} rather than {@code apply()}, and the only
 * re-entrancy available is synchronous. The ledger calls that "a strong result: it removes an
 * entire class of finding from this codebase".
 *
 * <p>It is also the assumption every unsynchronised field in those packages rests on - and those
 * fields hold the pinned identity keys, the session store and decrypted plaintext.
 * {@code SignalProtocolMain} is a mutable singleton with no locking anywhere. One
 * {@code Executors.newSingleThreadExecutor()} added for a plausible reason - "the store write is
 * slow on a big log, let us move it off the main thread" - turns every one of those fields into a
 * race, and nothing in the suite would have noticed.
 *
 * <p>Scoped exactly as the finding is scoped. {@code RichInputMethodManager} does use an executor,
 * to call {@code setInputMethodAndSubtype} off the main thread; it is in {@code latin/}, outside
 * both packages, and touches no protocol state. Widening this scan to all of {@code latin/} would
 * make it fail today and teach whoever meets it to add an exemption rather than to think.
 */
public class NothingRunsOffTheImeMainThreadTest {

  private static final String[] PACKAGES = {
      "com/amnesica/kryptey/inputmethod/latin/e2ee",
      "com/amnesica/kryptey/inputmethod/signalprotocol",
  };

  /**
   * The primitives the finding swept for.
   *
   * <p>{@code .post(} and {@code .postDelayed(} are included even though they run ON the main
   * thread: they defer work past the end of the current callback, so a field read afterwards is no
   * longer the field the caller reasoned about. The finding's own sentence about re-entrancy being
   * "traceable by reading" stops being true the moment work is deferred.
   */
  private static final Pattern OFF_THREAD = Pattern.compile(
      "new\\s+Thread\\s*\\(|ExecutorService|Executors\\s*\\.|AsyncTask|HandlerThread"
          + "|runOnUiThread|\\.post\\s*\\(|\\.postDelayed\\s*\\(");

  /** Durable by design: apply() is asynchronous, which is the same hazard by another name. */
  private static final Pattern ASYNC_WRITE = Pattern.compile("\\.apply\\s*\\(\\s*\\)");

  private static Path mainSources() {
    for (final String candidate : new String[] {"src/main/java", "app/src/main/java"}) {
      final Path path = Paths.get(candidate);
      if (Files.isDirectory(path)) return path;
    }
    throw new IllegalStateException("could not locate the main source tree");
  }

  private static String withoutCommentsAndStrings(final String source) {
    return source.replaceAll("(?s)/\\*.*?\\*/", " ")
        .replaceAll("(?m)//[^\n]*", " ")
        .replaceAll("\"(?:[^\"\\\\]|\\\\.)*\"", "\"\"");
  }

  private static List<String> scan(final Pattern pattern) throws IOException {
    final List<String> found = new ArrayList<>();
    int filesRead = 0;
    for (final String pkg : PACKAGES) {
      final Path root = mainSources().resolve(pkg);
      assertTrue("the scan must find " + pkg + "; if it moved, this guard is checking nothing",
          Files.isDirectory(root));
      try (Stream<Path> files = Files.walk(root)) {
        for (final Path file : (Iterable<Path>) files
            .filter(p -> p.toString().endsWith(".java"))::iterator) {
          filesRead++;
          final Matcher matcher = pattern.matcher(withoutCommentsAndStrings(
              new String(Files.readAllBytes(file), StandardCharsets.UTF_8)));
          while (matcher.find()) {
            found.add(file.getFileName() + ": " + matcher.group().trim());
          }
        }
      }
    }
    assertTrue("this scan must read both packages; it saw " + filesRead + " files",
        filesRead >= 40);
    return found;
  }

  @Test
  public void noworkIsMovedOffTheThreadTheseFieldsAreReadOn() throws IOException {
    final List<String> found = scan(OFF_THREAD);
    assertEquals("something in the E2EE or protocol code now defers or moves work. Every field in "
            + "these packages is unsynchronised - SignalProtocolMain is a mutable singleton with no "
            + "locking - and they hold the pinned identity keys, the session store and decrypted "
            + "plaintext. If this is deliberate, the single-thread finding in REVIEW-SETTLED is no "
            + "longer true and the fields it protects need revisiting first: " + found,
        0, found.size());
  }

  /**
   * And every durable write in these packages stays synchronous.
   *
   * <p>{@code apply()} is the same hazard wearing different clothes: the write returns before it
   * has landed, so a process death between the return and the flush loses it - which for this
   * storage layer means losing a pinned key or a rejection the user recorded. The storage code
   * says {@code commit()} everywhere and explains why at each call; this keeps that true.
   */
  @Test
  public void everyDurableWriteInThesePackagesIsSynchronous() throws IOException {
    final List<String> found = scan(ASYNC_WRITE);
    assertEquals("apply() returns before the write has landed, so a process death in between loses "
            + "it. In these packages that is a pinned key, a rejection the user recorded, or the "
            + "chat log. The storage layer uses commit() everywhere and says why at each call: "
            + found, 0, found.size());
  }
}
