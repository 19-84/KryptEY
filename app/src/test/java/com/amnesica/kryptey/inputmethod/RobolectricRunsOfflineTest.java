package com.amnesica.kryptey.inputmethod;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;

/**
 * Robolectric's Android framework comes from jars dependency verification checked, not from a
 * download it made on its own.
 *
 * <p>Without offline mode Robolectric fetches {@code android-all-instrumented} from Maven Central at
 * test time, through its own fetcher, into {@code ~/.m2/repository} - outside Gradle's resolution
 * and so outside {@code verification-metadata.xml} - and uses a jar already there unchecked. That
 * jar is the framework every Robolectric test runs against, in the CI job that builds the release
 * APK. REVIEW-SETTLED.md once recorded the fetch as
 * absent; re-measured with {@code --network none}, every Robolectric class failed with
 * {@code UnknownHostException}.
 *
 * <p>Asserted from inside the test JVM rather than by reading app/build.gradle, so it checks the
 * setting the suite actually ran with. Removing the property does not make a single Robolectric test
 * fail while the network is up - the fetch just quietly comes back - which is why this exists.
 */
public class RobolectricRunsOfflineTest {

  @Test
  public void robolectricIsInOfflineMode() {
    assertEquals("robolectric.offline is not set for this test JVM, so Robolectric downloads its "
            + "android-all jars itself, unverified. See the offline-mode note in app/build.gradle",
        "true", System.getProperty("robolectric.offline"));
  }

  @Test
  public void theJarsItReadsAreTheOnesGradleResolved() {
    final String dir = System.getProperty("robolectric.dependency.dir");
    assertNotNull("robolectric.dependency.dir is unset, so offline mode has nowhere to read from",
        dir);
    final File[] jars = new File(dir).listFiles(
        (d, name) -> name.startsWith("android-all-instrumented-") && name.endsWith(".jar"));
    assertNotNull(dir + " does not exist; syncRobolectricAndroidAll did not run", jars);
    assertTrue(dir + " holds no android-all jars; syncRobolectricAndroidAll copied nothing",
        jars.length > 0);
  }
}
