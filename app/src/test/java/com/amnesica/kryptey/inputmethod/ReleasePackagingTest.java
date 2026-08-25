package com.amnesica.kryptey.inputmethod;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * The release APK must not carry libsignal's desktop builds.
 *
 * <p>The libsignal jar ships macOS {@code .dylib} and Windows {@code .dll} binaries at its root, each
 * in a normal and a {@code _testing_} flavour. They are java resources rather than {@code jniLibs},
 * so the existing {@code jniLibs} exclusion — which correctly drops the Android
 * {@code libsignal_jni_testing.so} — never saw them, and all six were packaged into every release
 * APK: <b>42.6 MB of the artifact</b>, none of it loadable by Android.
 *
 * <p>Measured rather than estimated: excluding them took the arm64 APK from 115 MB to 74 MB and the
 * armeabi-v7a one from 109 MB to 68 MB, with the real {@code lib/arm64-v8a/libsignal_jni.so} and
 * {@code classes.dex} untouched and the unit suite unchanged at 882 tests.
 *
 * <p>Two of the six are <em>testing</em> builds of the crypto library. Inert on a phone, since
 * nothing can load them — but a test build of libsignal inside a shipped artifact is not something to
 * carry by accident, and "it cannot run" is a weaker argument than not shipping it.
 *
 * <p>They must stay on the <b>test</b> classpath: Robolectric runs on the JVM, where libsignal loads
 * the desktop build, so the fix belongs in packaging rather than in the dependency. That is the trap
 * this test also guards — removing the dependency instead would break 882 tests, and "fixing" it by
 * excluding the jar would look like the same change.
 *
 * <p>This asserts the build script rather than the APK, because no APK exists during a unit-test run.
 * It is a weaker check than opening the archive, and it is honest about that: what it catches is the
 * exclusion being deleted or narrowed, which is the realistic regression.
 */
public class ReleasePackagingTest {

  private static String buildScript() throws IOException {
    for (final String candidate : new String[] {"build.gradle", "app/build.gradle"}) {
      final Path path = Paths.get(candidate);
      if (Files.exists(path)) {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
      }
    }
    throw new IllegalStateException("could not locate the module build script from "
        + Paths.get("").toAbsolutePath());
  }

  @Test
  public void desktopNativesAreExcludedFromPackaging() throws IOException {
    final String script = buildScript();

    assertTrue("the build script must have a packaging block", script.contains("packaging {"));
    assertTrue("the exclusions must live in a resources block: these are java resources, not "
            + "jniLibs, which is exactly why the existing jniLibs exclusion never caught them",
        script.contains("resources {"));

    // Only the excludes STATEMENTS, never the prose around them. The first version of this test
    // asked whether ".dylib" appeared anywhere in the script and passed on its own comment - a
    // control that emptied the exclusion list survived it.
    final StringBuilder statements = new StringBuilder();
    for (final String line : script.split("\n")) {
      final String trimmed = line.trim();
      if (trimmed.startsWith("//")) continue;
      if (trimmed.contains("excludes")) statements.append(trimmed).append('\n');
    }

    assertTrue("packaging must exclude libsignal's macOS builds - six desktop binaries at the jar "
            + "root added 42.6MB to every release APK, none of it loadable on Android. Found these "
            + "exclusion statements:\n" + statements,
        statements.toString().contains(".dylib"));
    assertTrue("and its Windows builds, for the same reason:\n" + statements,
        statements.toString().contains(".dll"));
  }

  /**
   * And the Android native library must NOT be excluded along with them.
   *
   * <p>The failure mode on the other side. A broad exclusion that swept up {@code libsignal_jni.so}
   * would produce a small, clean-looking APK that cannot start: every cryptographic operation in the
   * app is that library.
   */
  @Test
  public void thereAlNativeLibraryIsNotExcluded() throws IOException {
    final String script = buildScript();
    final int packaging = script.indexOf("packaging {");
    assertTrue(packaging > 0);
    final String block = script.substring(packaging,
        Math.min(script.length(), packaging + 2000));

    assertTrue("libsignal_jni.so must not be excluded - it is every cryptographic operation the app "
            + "performs, and an APK without it installs and then cannot start",
        !block.contains("'**/libsignal_jni.so'") && !block.contains("\"**/libsignal_jni.so\""));
    assertTrue("the test-only Android native must still be excluded, which is the exclusion that "
            + "was already correct", block.contains("libsignal_jni_testing.so"));
  }
}
