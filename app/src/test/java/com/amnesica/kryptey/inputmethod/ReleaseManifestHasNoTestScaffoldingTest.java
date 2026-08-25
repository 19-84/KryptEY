package com.amnesica.kryptey.inputmethod;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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
 * The test scaffolding must not be able to reach a release build.
 *
 * <p>{@code EditableFieldActivity} exists so an instrumentation test can make a real editable field
 * ask for input, which is the only way to observe the system binding an input method. It has to be
 * part of the app under test rather than the test APK — instrumentation runs inside the target
 * process and cannot launch an activity belonging to another one — and "part of the app" is exactly
 * the property that makes it dangerous. An activity that puts an unrestricted {@code EditText} on
 * screen is not something to ship.
 *
 * <p>{@code src/debug} is the mechanism: AGP compiles that source set into debug variants only.
 * This test is the check that the mechanism is still what is being relied on, because the failure
 * mode is silent — moving the file to {@code src/main} to fix an IDE red squiggle would ship it, and
 * nothing else in the build would say a word.
 *
 * <p>It asserts a source-tree property rather than reading a release APK on purpose: a test that
 * needed {@code assembleRelease} to have run first would be skipped in exactly the situation where
 * it matters, and would pass on a stale artifact the rest of the time.
 */
public class ReleaseManifestHasNoTestScaffoldingTest {

  /** Anything named here is scaffolding: useful in a debug build, unacceptable in a shipped one. */
  private static final String[] SCAFFOLDING = {
      "EditableFieldActivity",
  };

  /**
   * Paths are written as literals rather than assembled with {@code resolve}, deliberately.
   *
   * <p>{@code EveryFileATestReadsIsATaskInputTest} finds what a test reads by scanning for string
   * literals, so a path built out of fragments is invisible to it — the file would go untracked and
   * this test would replay a stale pass, which is the exact failure it exists to prevent. Writing
   * them out keeps the two guards able to see each other.
   */
  private static final String MAIN_SOURCES = "src/main/java";
  private static final String MAIN_MANIFEST = "src/main/AndroidManifest.xml";
  private static final String DEBUG_SOURCES = "src/debug/java";
  private static final String DEBUG_MANIFEST = "src/debug/AndroidManifest.xml";

  /** Resolves a module-relative path whether the run starts in the module or the repo root. */
  private static Path at(final String modulePath) {
    final Path direct = Paths.get(modulePath);
    if (Files.exists(direct)) return direct;
    return Paths.get("app").resolve(modulePath);
  }

  private static List<Path> filesUnder(final Path root) throws IOException {
    if (!Files.isDirectory(root)) return new ArrayList<>();
    try (Stream<Path> walk = Files.walk(root)) {
      return walk.filter(Files::isRegularFile).collect(Collectors.toList());
    }
  }

  /**
   * The scaffolding lives in {@code src/debug} and nowhere a release build would look.
   *
   * <p>{@code src/main} is compiled into every variant and {@code src/release} into the shipped one,
   * so a reference from either is the whole defect.
   */
  @Test
  public void noscaffoldingIsDeclaredOutsideTheDebugSourceSet() throws IOException {
    final List<String> offenders = new ArrayList<>();
    int filesScanned = 0;

    // src/main is the whole risk surface: it compiles into every variant, release included. There
    // is no src/release in this project, so scanning for one would assert nothing while looking
    // like it asserted something.
    final List<Path> candidates = new ArrayList<>(filesUnder(at(MAIN_SOURCES)));
    candidates.add(at(MAIN_MANIFEST));

    for (final Path file : candidates) {
      if (!Files.exists(file)) continue;
      final String name = file.getFileName().toString();
      if (!name.endsWith(".java") && !name.endsWith(".xml")) continue;
      filesScanned++;
      final String text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
      for (final String scaffold : SCAFFOLDING) {
        if (text.contains(scaffold)) offenders.add(file + " mentions " + scaffold);
      }
    }

    assertTrue("this test scans the main source set; scanning nothing means it has stopped testing "
        + "anything", filesScanned >= 50);
    assertEquals("test scaffolding is referenced from the source set that ships. src/debug is what "
        + "keeps EditableFieldActivity out of a release build, and moving it defeats that "
        + "silently:\n" + String.join("\n", offenders), 0, offenders.size());
  }

  /**
   * And it really is in {@code src/debug}, so the check above is not passing by absence.
   *
   * <p>Without this, deleting the activity outright would leave the first test green and this file
   * asserting nothing at all — the same shape as a scan whose pattern stopped matching.
   */
  @Test
  public void thescaffoldingExistsWhereItIsSupposedTo() throws IOException {
    final Path debugSources = at(DEBUG_SOURCES);
    assertTrue(DEBUG_SOURCES + " must exist; this is where the scaffolding is kept",
        Files.isDirectory(debugSources));

    final List<Path> files = filesUnder(debugSources);
    for (final String scaffold : SCAFFOLDING) {
      boolean found = false;
      for (final Path file : files) {
        if (file.getFileName().toString().startsWith(scaffold)) found = true;
      }
      assertTrue(scaffold + " is named as scaffolding but does not exist under " + DEBUG_SOURCES
          + ". Either it moved - in which case the other test in this file is now checking "
          + "nothing - or it was deleted and should be removed from SCAFFOLDING.", found);
    }

    final Path manifest = at(DEBUG_MANIFEST);
    assertTrue("the debug manifest must declare the scaffolding, or it cannot be launched",
        Files.exists(manifest));
    final String text = new String(Files.readAllBytes(manifest), StandardCharsets.UTF_8);
    assertTrue("the debug manifest must declare EditableFieldActivity",
        text.contains("EditableFieldActivity"));
    assertFalse("the scaffolding must not be exported - it is an unrestricted text field",
        text.contains("android:exported=\"true\""));
  }
}
