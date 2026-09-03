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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A package query the platform silently filters is a defence that does not run.
 *
 * <p>From {@code targetSdk} 30 an app sees only the packages it has declared an interest in.
 * {@code queryIntentActivities} still returns - it returns a filtered list, usually empty, with no
 * exception and no log line. Code that reads that list and decides "no system handler here, fall
 * back" behaves exactly as it would on a device that genuinely has none, so the fallback becomes
 * the only path and nothing anywhere says so.
 *
 * <p>That is what had happened to {@code SettingsActivity.aimAtTheSystemSettingsIfWeCan}. It
 * queries for handlers of {@code ACTION_INPUT_METHOD_SETTINGS} to narrow an implicit intent onto
 * the real settings app, and its javadoc explains why that matters here more than elsewhere: the
 * dialog is not cancelable, it fires on first run, and it has just told the user to go and enable
 * a keyboard - a user primed to say yes to installing an IME, which is a complete keylogger. With
 * the query filtered, the narrowing never happened on any device from Android 11 onward.
 *
 * <p>Lint says {@code QueryPermissionsNeeded} for this, as a warning, outside the baseline, where
 * it sat unread. So this test is deliberately not "the manifest contains a queries element": it
 * ties each query in the source to a declaration, and a second query added later without one fails
 * here rather than joining it.
 */
public class OnlyTheSettingsIntentIsQueriedTest {

  private static String read(final String relative) throws IOException {
    final Path path = Paths.get(relative);
    assertTrue(path + " does not exist", Files.exists(path));
    return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
  }

  /**
   * The manifest with its comments removed.
   *
   * <p>Because the first version of this test failed against a correct manifest: the comment on
   * the {@code <queries>} element explains why {@code QUERY_ALL_PACKAGES} was NOT used, and the
   * assertion forbidding that permission matched the word in the explanation. A scanner that reads
   * prose as declaration is this repo's most repeated defect and it is worth one method to stop.
   */
  private static String withoutComments(final String xml) {
    return xml.replaceAll("(?s)<!--.*?-->", "");
  }

  /** Every package-visibility-filtered PackageManager call in production source. */
  private static final Pattern FILTERED_CALLS = Pattern.compile(
      "\\b(queryIntentActivities|queryIntentServices|queryBroadcastReceivers|queryIntentContentProviders"
          + "|resolveActivity|resolveService|getPackageInfo|getApplicationInfo|getInstalledPackages"
          + "|getInstalledApplications)\\s*\\(");

  /**
   * Calls on this app's OWN package are never filtered, and neither is anything reached through a
   * Context rather than the PackageManager. Matching by name alone would flag both.
   */
  private static boolean reachesThePackageManager(final String body, final int at) {
    final int from = Math.max(0, at - 80);
    final String before = body.substring(from, at);
    return before.contains("getPackageManager()") || before.contains("packageManager")
        || before.contains("PackageManager")
        || before.endsWith(".") && before.contains("pm");
  }

  @Test
  public void theManifestDeclaresTheIntentTheAppActuallyQueriesFor() throws IOException {
    final String manifest = withoutComments(read("src/main/AndroidManifest.xml"));
    assertTrue(
        "The manifest has no <queries> element. At targetSdk 30+ every package query in this app "
            + "is filtered to nothing and its result is indistinguishable from a device that has "
            + "no handler.",
        manifest.contains("<queries>"));
    final int start = manifest.indexOf("<queries>");
    final int end = manifest.indexOf("</queries>");
    assertTrue("malformed <queries> element", end > start);
    final String queries = manifest.substring(start, end);
    assertTrue(
        "The declared query is not the input-method settings action. That is the only intent this "
            + "app resolves, and declaring anything else makes the narrowing inert while looking "
            + "like it is declared.",
        queries.contains("android.settings.INPUT_METHOD_SETTINGS"));
  }

  @Test
  public void theQueryIsNarrowRatherThanEverything() throws IOException {
    final String manifest = withoutComments(read("src/main/AndroidManifest.xml"));
    assertFalse(
        "QUERY_ALL_PACKAGES is not what this needs. One action is queried; asking to see every "
            + "installed package to resolve it is a policy problem at every store and buys nothing.",
        manifest.contains("QUERY_ALL_PACKAGES"));
    assertFalse(
        "A bare <package> or <provider> entry would widen visibility past the one action this app "
            + "resolves.",
        manifest.contains("<package android:name") || manifest.contains("<provider android:"));
  }

  @Test
  public void noSecondQueryArrivedWithoutADeclaration() throws IOException {
    // Three calls exist and each is accounted for. SettingsActivity's is the filtered one, and
    // the declaration above is what makes it work. ApplicationUtils' two are getPackageInfo on
    // context.getPackageName() - an app's own package is never hidden from it, so visibility does
    // not apply and they need no entry.
    //
    // Named individually rather than matched by a rule that excludes self-package calls: such a
    // rule has to recognise the argument expression, and one written loosely enough to pass these
    // two would also pass a call on a package name that came from somewhere else. A fourth call
    // arriving fails here and has to be classified by whoever adds it, which is the point.
    final List<String> found = new ArrayList<>();
    final Path source = Paths.get("src/main/java");
    Files.walk(source)
        .filter(p -> p.toString().endsWith(".java"))
        .sorted()
        .forEach(p -> {
          final String body;
          try {
            body = new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
          } catch (final IOException e) {
            throw new AssertionError("could not read " + p, e);
          }
          final Matcher m = FILTERED_CALLS.matcher(body);
          while (m.find()) {
            if (!reachesThePackageManager(body, m.start())) continue;
            found.add(p.getFileName() + ":" + m.group(1));
          }
        });
    java.util.Collections.sort(found);
    assertEquals(
        "Package-visibility-filtered PackageManager calls found: " + found + ". At targetSdk 30+ "
            + "each returns a list filtered to what the manifest's <queries> declares. If a new "
            + "one is legitimate, add its intent to <queries> and add it here; if it is not, it "
            + "will silently see nothing.",
        "[ApplicationUtils.java:getPackageInfo, ApplicationUtils.java:getPackageInfo, "
            + "SettingsActivity.java:queryIntentActivities]",
        found.toString());
  }
}
