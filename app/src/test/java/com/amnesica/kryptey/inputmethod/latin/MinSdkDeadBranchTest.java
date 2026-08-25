package com.amnesica.kryptey.inputmethod.latin;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * An {@code SDK_INT} comparison whose answer is fixed by {@code minSdk} is not a compatibility
 * check. It is dead code with a version number on it.
 *
 * <p>{@code minSdk} is 26, so {@code SDK_INT >= N} (24) is true on every device that can install
 * this app, and {@code SDK_INT <= KITKAT} (19) is false on all of them. The guarded branch in the
 * second case can never execute, and {@code minifyEnabled} is {@code false} — this project already
 * records that dead code ships — so it is carried into the APK and read by anyone auditing it as
 * though it ran.
 *
 * <p>This matters for the reason the whole {@code SDK_INT} question mattered elsewhere: Robolectric
 * runs at {@code compileSdk}, so branches are easy to believe covered when nothing enters them. The
 * difference here is that no {@code @Config} can help — these are not untested branches, they are
 * unreachable ones, and a test that entered them would be testing a device configuration that
 * cannot exist.
 *
 * <p>The eight below are inherited from AOSP, where {@code minSdk} was lower. They are named rather
 * than deleted: removing them is a mechanical change across settings and IME files that no test
 * covers, which is a poor trade against code that is inert by construction. What this test buys is
 * that the ninth fails.
 */
public class MinSdkDeadBranchTest {

  /** From app/build.gradle. If this rises, more of the list below becomes deletable. */
  private static final int MIN_SDK = 26;

  private static final Map<String, Integer> API_LEVELS = new HashMap<>();

  static {
    API_LEVELS.put("KITKAT", 19);
    API_LEVELS.put("KITKAT_WATCH", 20);
    API_LEVELS.put("LOLLIPOP", 21);
    API_LEVELS.put("LOLLIPOP_MR1", 22);
    API_LEVELS.put("M", 23);
    API_LEVELS.put("N", 24);
    API_LEVELS.put("N_MR1", 25);
    API_LEVELS.put("O", 26);
    API_LEVELS.put("O_MR1", 27);
    API_LEVELS.put("P", 28);
    API_LEVELS.put("Q", 29);
    API_LEVELS.put("R", 30);
    API_LEVELS.put("S", 31);
    API_LEVELS.put("S_V2", 32);
    API_LEVELS.put("TIRAMISU", 33);
    API_LEVELS.put("UPSIDE_DOWN_CAKE", 34);
    API_LEVELS.put("VANILLA_ICE_CREAM", 35);
  }

  /**
   * Inherited from AOSP and inert at {@code minSdk} 26. Keyed by file and the comparison itself, not
   * by line, so ordinary edits above them do not churn this list.
   */
  private static final Set<String> INHERITED_FROM_AOSP = new HashSet<>(Arrays.asList(
      // Always false: the guarded branch cannot run on any supported device.
      "RichInputMethodManager.java: SDK_INT <= KITKAT",
      "TwoStatePreferenceHelper.java: SDK_INT <= KITKAT",
      "PreferencesSettingsFragment.java: SDK_INT <= KITKAT",
      "ViewOutlineProviderCompatUtils.java: SDK_INT < LOLLIPOP",
      // Always true: the check is redundant and any else-branch is unreachable.
      "PreferenceManagerCompat.java: SDK_INT >= N",
      "EditorInfoCompatUtils.java: SDK_INT >= N",
      "SubScreenFragment.java: SDK_INT >= N",
      "LocaleUtils.java: SDK_INT >= N"));

  private static Path mainSources() {
    for (final String candidate : new String[] {"src/main/java", "app/src/main/java"}) {
      final Path path = Paths.get(candidate);
      if (Files.isDirectory(path)) return path;
    }
    throw new IllegalStateException("could not locate the main source tree");
  }

  /** Whether this comparison has the same answer on every device that can install the app. */
  private static String constantOutcome(final String operator, final int level) {
    switch (operator) {
      case ">=":
        return level <= MIN_SDK ? "always true" : null;
      case ">":
        return level < MIN_SDK ? "always true" : null;
      case "<=":
        return level < MIN_SDK ? "always false" : null;
      case "<":
        return level <= MIN_SDK ? "always false" : null;
      default:
        return null; // == and != are legitimate point checks
    }
  }

  @Test
  public void nosdkComparisonIsDecidedByMinSdkUnlessItIsAKnownAospLeftover() throws IOException {
    final Pattern comparison = Pattern.compile(
        "SDK_INT\\s*(>=|<=|>|<)\\s*Build\\.VERSION_CODES\\.([A-Z_0-9]+)");

    final List<String> found = new ArrayList<>();
    final List<String> unexpected = new ArrayList<>();
    int scanned = 0;

    try (Stream<Path> walk = Files.walk(mainSources())) {
      for (final Path source : walk.filter(p -> p.toString().endsWith(".java"))
          .collect(Collectors.toList())) {
        scanned++;
        final String text = new String(Files.readAllBytes(source), StandardCharsets.UTF_8);
        final Matcher matcher = comparison.matcher(text);
        while (matcher.find()) {
          final Integer level = API_LEVELS.get(matcher.group(2));
          if (level == null) continue;
          final String outcome = constantOutcome(matcher.group(1), level);
          if (outcome == null) continue;

          final String key = source.getFileName() + ": SDK_INT " + matcher.group(1) + " "
              + matcher.group(2);
          found.add(key);
          if (!INHERITED_FROM_AOSP.contains(key)) {
            unexpected.add(key + "  (" + outcome + " at minSdk " + MIN_SDK + ")");
          }
        }
      }
    }

    assertTrue("this test walks the source tree; scanning almost nothing means it has stopped "
        + "testing anything", scanned >= 100);
    assertEquals("this SDK_INT check has the same answer on every device that can install the app, "
        + "so one side of it is unreachable. minifyEnabled is false, so it ships and reads to an "
        + "auditor like code that runs. Either drop the check or raise minSdk:\n"
        + String.join("\n", unexpected), 0, unexpected.size());
  }

  /**
   * And the allowlist cannot outlive the code it excuses.
   *
   * <p>Without this, deleting one of the eight would leave a name here claiming a leftover exists
   * that does not — the same rot this project has now corrected in a comment naming a method that
   * had never existed, and in a mutation ledger listing mutants that had been fixed.
   */
  @Test
  public void theallowlistNamesOnlyComparisonsThatStillExist() throws IOException {
    final Pattern comparison = Pattern.compile(
        "SDK_INT\\s*(>=|<=|>|<)\\s*Build\\.VERSION_CODES\\.([A-Z_0-9]+)");
    final Set<String> present = new HashSet<>();

    try (Stream<Path> walk = Files.walk(mainSources())) {
      for (final Path source : walk.filter(p -> p.toString().endsWith(".java"))
          .collect(Collectors.toList())) {
        final String text = new String(Files.readAllBytes(source), StandardCharsets.UTF_8);
        final Matcher matcher = comparison.matcher(text);
        while (matcher.find()) {
          present.add(source.getFileName() + ": SDK_INT " + matcher.group(1) + " "
              + matcher.group(2));
        }
      }
    }

    final List<String> stale = INHERITED_FROM_AOSP.stream()
        .filter(entry -> !present.contains(entry))
        .collect(Collectors.toList());

    assertEquals("the AOSP-leftover list names comparisons that are no longer in the source. Remove "
        + "them, rather than leaving them to suggest dead code that has already been cleaned up:\n"
        + String.join("\n", stale), 0, stale.size());
  }
}
