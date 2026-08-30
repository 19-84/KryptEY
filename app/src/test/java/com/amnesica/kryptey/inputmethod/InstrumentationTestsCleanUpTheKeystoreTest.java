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
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The instrumentation tests share one device-global resource, and this task cannot run them.
 *
 * <p>They are the only coverage of the real Android Keystore — a JVM key and a Keystore key have
 * materially different semantics, which is how one production-only failure hid in the first place.
 * This javadoc used to say they "compile and never execute"; they execute now, under
 * {@code tools/test-on-emulator}, on a software-emulated device. What remains true is that they do
 * not execute in <em>this</em> task: an emulator run costs ten minutes and nobody does it on every
 * edit. The build enforces the compiling half — {@code testDebugUnitTest} depends on compiling
 * {@code androidTest}, so a rename cannot rot them silently — and this file enforces the property
 * a JVM run still cannot observe.
 *
 * <p>This is the half compilation cannot reach. Every one of those tests operates on the same
 * Keystore alias, and several deliberately destroy or regenerate the master key mid-test. They are
 * safe today because {@code @Before} and {@code @After} both destroy the alias, so each starts from
 * a known state whatever order they run in — the same order-independence question the JVM suite was
 * checked for, on a resource that is global to the device rather than to the process.
 *
 * <p>A test added without that contract would corrupt its siblings on a real device, and **nothing
 * in this environment would ever see it**: it would compile, this suite would stay green, and the
 * failure would land on whoever first runs them on hardware — the person least equipped to know it
 * was a harness bug rather than a real one. Reading the file is the only way to catch it, so the
 * reading is written down here.
 */
public class InstrumentationTestsCleanUpTheKeystoreTest {

  private static Path instrumentationSources() {
    for (final String candidate : new String[] {"src/androidTest/java", "app/src/androidTest/java"}) {
      final Path path = Paths.get(candidate);
      if (Files.isDirectory(path)) return path;
    }
    throw new IllegalStateException("could not locate the instrumentation source tree from "
        + Paths.get("").toAbsolutePath());
  }

  private static List<Path> sources() throws IOException {
    try (Stream<Path> walk = Files.walk(instrumentationSources())) {
      return walk.filter(p -> p.toString().endsWith(".java")).collect(Collectors.toList());
    }
  }

  /**
   * Every instrumentation class touching the Keystore must reset the alias before and after.
   *
   * <p>Scoped to classes that actually use it, so a future instrumentation test about something else
   * is not forced into a contract it has no reason to keep.
   */
  @Test
  public void everyKeystoreInstrumentationClassResetsTheAliasBothWays() throws IOException {
    final List<Path> sources = sources();
    assertTrue("this test reads the instrumentation tree and found nothing; it has stopped testing "
        + "anything", sources.size() >= 1);

    final List<String> offenders = new ArrayList<>();
    int examined = 0;

    for (final Path source : sources) {
      final String text = new String(Files.readAllBytes(source), StandardCharsets.UTF_8);
      if (!text.contains("AndroidKeystoreCryptoBox")) continue;
      examined++;

      final int before = text.indexOf("@Before");
      final int after = text.indexOf("@After");
      if (before < 0 || !text.substring(before, Math.min(text.length(), before + 400))
          .contains("destroyMasterKey")) {
        offenders.add(source.getFileName() + ": @Before does not destroy the master key alias");
      }
      if (after < 0 || !text.substring(after, Math.min(text.length(), after + 400))
          .contains("destroyMasterKey")) {
        offenders.add(source.getFileName() + ": @After does not destroy the master key alias");
      }
    }

    assertTrue("no instrumentation class uses AndroidKeystoreCryptoBox, which is the only reason "
        + "these tests exist - the scan has stopped matching", examined >= 1);
    assertEquals("an instrumentation test touches the real Keystore without resetting the shared "
        + "alias on both sides. These tests cannot run in this environment, so a missing reset "
        + "compiles, leaves this suite green, and surfaces as a mysterious failure for whoever "
        + "first runs them on hardware:\n" + String.join("\n", offenders), 0, offenders.size());
  }

  /**
   * And they must still be tests rather than a compiling shell.
   *
   * <p>Cheap, and it catches the way an unrunnable file actually decays: methods kept for the record
   * with their bodies commented out or their assertions removed, which no compiler objects to.
   */
  @Test
  public void theinstrumentationTestsStillAssertSomething() throws IOException {
    int tests = 0;
    int assertions = 0;
    for (final Path source : sources()) {
      final String text = new String(Files.readAllBytes(source), StandardCharsets.UTF_8);
      tests += text.split("@Test", -1).length - 1;
      for (final String line : text.split("\n")) {
        final String trimmed = line.trim();
        if (trimmed.startsWith("//") || trimmed.startsWith("*")) continue;
        if (trimmed.contains("assert") || trimmed.contains("fail(")) assertions++;
      }
    }

    // Raised each time tests land: 11, then 14 (ImeBindsDespiteExportedFalseTest), 17
    // (AutofillDoesNotReachTheKeyboardTest), 20 (ChatLogSplitAgainstARealKeystoreTest), 23
    // (ProtocolRoundTripOnRealHardwareTest), now 29 (TheMigrationSealIsRealOnDeviceTest). A floor
    // that lags the real count is a guard that lets you delete the difference for free, so it is
    // worth the churn of raising it every time.
    assertTrue("expected the 29 instrumentation tests this branch carries; found " + tests,
        tests >= 29);
    assertTrue("the instrumentation tests must still assert something - they compile whether or not "
            + "they do, and nothing here runs them. Found " + assertions + " assertions across "
            + tests + " tests", assertions >= tests);
  }
}
