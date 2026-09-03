package com.amnesica.kryptey.inputmethod;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code targetSdk} is a declaration about which platform behaviour changes apply to this app, and
 * leaving it low is not a neutral choice - it opts the app out of hardening it would otherwise get.
 *
 * <p>It sat at 33 against a {@code compileSdk} of 35 for the whole revival. Nothing failed, because
 * nothing checks: a stale target produces no warning, no lint issue and no test failure. It simply
 * stops being true, quietly, once a year.
 *
 * <p>So this holds the two equal. Raising {@code compileSdk} without raising the target now fails
 * here rather than in a store submission or, worse, nowhere.
 *
 * <p>The second half is the part with teeth. Equality alone would be satisfied by editing one
 * number, and the number is not the change - the behaviour changes it turns on are. Each assertion
 * below names one and checks the code that answers it is still there, so removing the handling
 * fails even though the target still reads 35.
 *
 * <p>What this test cannot do is run any of it. The device suite has a single API 28 image, where
 * every one of these changes is inert by definition. These are source-level checks that the
 * handling exists; that it is correct on a device running 34 or 35 is unverified, and BACKLOG.md
 * records that under "The API-level matrix".
 */
public class TargetSdkIsNotStaleTest {

  private static String read(final String relative) throws IOException {
    final Path path = Paths.get(relative);
    assertTrue(path + " does not exist", Files.exists(path));
    return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
  }

  /** Reads a bare {@code name <int>} setting out of build.gradle, ignoring commented lines. */
  private static int settingIn(final String gradle, final String name) {
    final Matcher m = Pattern.compile("(?m)^\\s*" + name + "\\s+(\\d+)\\s*$").matcher(gradle);
    assertTrue("app/build.gradle declares no " + name, m.find());
    final int value = Integer.parseInt(m.group(1));
    assertTrue("app/build.gradle declares " + name + " more than once", !m.find());
    return value;
  }

  @Test
  public void theTargetTracksWhatWeCompileAgainst() throws IOException {
    final String gradle = read("build.gradle");
    assertEquals(
        "targetSdk must track compileSdk. Raising what we compile against while leaving the "
            + "target behind opts the app out of every behaviour change in between.",
        settingIn(gradle, "compileSdk"),
        settingIn(gradle, "targetSdk"));
  }

  @Test
  public void theTargetIsNotBelowWhatStoresWillTake() throws IOException {
    final int target = settingIn(read("build.gradle"), "targetSdk");
    // Not a guess at any particular store's current floor - it moves, and a test that encoded
    // this year's number would need editing every year to keep saying the same thing. 34 is the
    // level at which the receiver-export change lands, which is the first one this app actually
    // has code for; below it that code is unreachable and this test's other assertions are vacuous.
    assertTrue(
        "targetSdk " + target + " is below 34, where the behaviour changes this app handles begin",
        target >= 34);
  }

  @Test
  public void everyRuntimeReceiverDeclaresWhetherItIsExported() throws IOException {
    // API 34: registerReceiver without an export flag throws SecurityException. A keyboard that
    // throws on a ringer-mode change is a keyboard that dies mid-sentence.
    final Path source = Paths.get("src/main/java");
    final StringBuilder unflagged = new StringBuilder();
    Files.walk(source)
        .filter(p -> p.toString().endsWith(".java"))
        .forEach(p -> {
          final String body;
          try {
            body = new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
          } catch (final IOException e) {
            throw new AssertionError("could not read " + p, e);
          }
          final Matcher call = Pattern.compile("registerReceiver\\s*\\(([^;]*?)\\)\\s*;").matcher(body);
          while (call.find()) {
            final String args = call.group(1);
            // Two arguments is the unflagged overload. Three or more carries the flag - and the
            // two-argument call is still legal below TIRAMISU, so a file that has both is fine.
            final boolean twoArgs = args.split(",").length == 2;
            final boolean flaggedSomewhere = body.contains("RECEIVER_NOT_EXPORTED")
                || body.contains("RECEIVER_EXPORTED");
            if (twoArgs && !flaggedSomewhere) {
              unflagged.append("\n  ").append(p).append(": registerReceiver(").append(args).append(")");
            }
          }
        });
    assertEquals(
        "At targetSdk 34+ a runtime receiver must say whether it is exported or the platform "
            + "throws. These calls pass neither flag anywhere in their file:" + unflagged,
        "",
        unflagged.toString());
  }

  @Test
  public void theSettingsScreenHandlesBeingDrawnEdgeToEdge() throws IOException {
    // API 35: activities go edge-to-edge with no opt-out, and PreferenceActivity has no inset
    // handling of its own. The bottom row ends up behind the navigation bar, which takes the
    // touches - so the last preference cannot be operated, not merely looks wrong.
    final String settings =
        read("src/main/java/com/amnesica/kryptey/inputmethod/latin/settings/SettingsActivity.java");
    assertTrue(
        "SettingsActivity does not listen for window insets. At targetSdk 35 its list draws "
            + "under the status and navigation bars.",
        settings.contains("setOnApplyWindowInsetsListener"));
    assertTrue(
        "SettingsActivity listens for insets but never applies them as padding.",
        settings.contains("setPadding"));
    assertTrue(
        "The inset handling is not gated on the API level. Below 35 the window is already laid "
            + "out inside the bars, so applying the insets again pads the list twice.",
        settings.contains("VANILLA_ICE_CREAM"));
  }

  @Test
  public void noForegroundServiceArrivedWithoutADeclaredType() throws IOException {
    // API 34: a foreground service must declare a type in the manifest and hold its permission.
    // There are none today; this fails if one is added without the manifest catching up.
    final Path source = Paths.get("src/main/java");
    final boolean any = Files.walk(source)
        .filter(p -> p.toString().endsWith(".java"))
        .anyMatch(p -> {
          try {
            return new String(Files.readAllBytes(p), StandardCharsets.UTF_8).contains("startForeground(");
          } catch (final IOException e) {
            throw new AssertionError("could not read " + p, e);
          }
        });
    if (!any) return;
    final String manifest = read("src/main/AndroidManifest.xml");
    assertTrue(
        "A foreground service was added. At targetSdk 34+ it needs android:foregroundServiceType "
            + "in the manifest and the matching FOREGROUND_SERVICE_* permission.",
        manifest.contains("android:foregroundServiceType"));
  }
}
