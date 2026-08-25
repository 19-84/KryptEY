package com.amnesica.kryptey.inputmethod;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.content.pm.ServiceInfo;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The shipped manifest is a security artefact and nothing had ever read it.
 *
 * <p>Everything else in this branch defends the message once it is inside the app. What decides
 * whether the app has any way to move it off the device at all is the manifest, and that is
 * enforced by an <b>absence</b>: there is no {@code INTERNET} permission, so there is nothing to
 * check and nothing that could have gone wrong today. An absence is exactly what a mutation sweep
 * cannot mutate and a reviewer reading code cannot see.
 *
 * <p>This app is a keyboard. It sees every character typed in every application on the device, and
 * it holds the user's identity key, their contact list and their decrypted conversations. The
 * store description opens with "Communicate securely and independent" and says the protocol
 * "work[s] without a server, thus it enables a highly independent use"; {@code KRYPTEY.md} says it
 * "does not require a server for the key exchange"; the whole out-of-band trust story rests on
 * there being no channel except the one the user chooses by choosing an app. None of that is
 * enforced anywhere.
 *
 * <p>It holds today by construction - measured, not assumed: the merged manifest requests
 * {@code VIBRATE} and nothing else, and no dependency contributes a permission. Three ordinary
 * events break it silently, and none of them would have been caught: a permission added to
 * {@code AndroidManifest.xml}; a library whose own manifest declares one, since the merge is
 * transitive and this file is not what ships; and the QR-code dependency REVIVAL.md records as an
 * open decision, which is exactly the kind of library that arrives with permissions attached.
 *
 * <p>Asked of {@code PackageManager} rather than of the source file on purpose. Robolectric is
 * given {@code packaged_manifests/debugUnitTest/.../AndroidManifest.xml}, the output of the full
 * merge, so a permission arriving from a library manifest fails this as loudly as one typed here.
 *
 * <p>The rest of the assertions cover the other manifest facts this app's threat model rests on,
 * for the same reason: they are correct today and nothing was watching them.
 */
@RunWith(RobolectricTestRunner.class)
public class NothingLeavesTheDeviceTest {

  /** The only permission this app has any business holding. Vibration is key feedback. */
  private static final Set<String> ALLOWED_PERMISSIONS =
      new LinkedHashSet<>(Arrays.asList("android.permission.VIBRATE"));

  /**
   * Permissions that would give a keyboard a way to move what it sees off the device, named
   * separately so the failure says what is wrong rather than only that a set changed.
   */
  private static final Set<String> EGRESS_PERMISSIONS =
      new LinkedHashSet<>(Arrays.asList(
          "android.permission.INTERNET",
          "android.permission.ACCESS_NETWORK_STATE",
          "android.permission.ACCESS_WIFI_STATE",
          "android.permission.CHANGE_WIFI_STATE",
          "android.permission.BLUETOOTH",
          "android.permission.BLUETOOTH_CONNECT",
          "android.permission.NFC",
          "android.permission.SEND_SMS",
          "android.permission.WRITE_EXTERNAL_STORAGE",
          "android.permission.READ_EXTERNAL_STORAGE",
          "android.permission.MANAGE_EXTERNAL_STORAGE",
          "android.permission.ACCESS_FINE_LOCATION",
          "android.permission.ACCESS_COARSE_LOCATION",
          "android.permission.RECORD_AUDIO",
          "android.permission.CAMERA",
          "android.permission.READ_CONTACTS",
          "android.permission.FOREGROUND_SERVICE"));

  private static Context context() {
    return RuntimeEnvironment.getApplication();
  }

  private static PackageInfo merged(final int flags) {
    try {
      return context().getPackageManager()
          .getPackageInfo(context().getPackageName(), flags);
    } catch (final PackageManager.NameNotFoundException e) {
      throw new AssertionError(e);
    }
  }

  private static List<String> requestedPermissions() {
    final String[] requested = merged(PackageManager.GET_PERMISSIONS).requestedPermissions;
    return requested == null ? new ArrayList<>() : Arrays.asList(requested);
  }

  /**
   * Anti-vacuity. If the merged manifest were not reaching this test, every assertion below would
   * pass on an empty list - which is the shape of failure this branch has already been caught by
   * six times, a check that passes because it is looking at nothing.
   */
  @Test
  public void aaThemergedManifestReallyReachesThisTest() {
    assertTrue("the merged manifest must carry the one permission this app really does declare, "
            + "or this test is reading nothing: " + requestedPermissions(),
        requestedPermissions().contains("android.permission.VIBRATE"));
    assertNotNull("and its components", merged(PackageManager.GET_SERVICES).services);
  }

  /** The finding: a keyboard that cannot talk to the network, said out loud. */
  @Test
  public void thekeyboardHoldsNoPermissionThatWouldLetItSendAnythingOffTheDevice() {
    final List<String> offending = new ArrayList<>();
    for (final String permission : requestedPermissions()) {
      if (EGRESS_PERMISSIONS.contains(permission)) offending.add(permission);
    }

    assertEquals("this keyboard sees every character typed in every app on the device and holds "
            + "the user's identity key, contacts and decrypted conversations. It has no way to "
            + "move any of that off the device, and that is a property of the manifest rather "
            + "than of any code - so nothing but this test can notice it changing. A dependency's "
            + "own manifest is merged in transitively, so this can be broken without editing "
            + "AndroidManifest.xml at all. If a permission here is genuinely wanted, add it to "
            + "ALLOWED_PERMISSIONS deliberately and say why:\n" + offending,
        0, offending.size());
  }

  /** And nothing else either - the allowlist is the statement, the list above is the reason. */
  @Test
  public void nopermissionIsRequestedThatIsNotOnTheAllowlist() {
    final List<String> unexpected = new ArrayList<>();
    for (final String permission : requestedPermissions()) {
      if (!ALLOWED_PERMISSIONS.contains(permission)) unexpected.add(permission);
    }

    assertEquals("the merged manifest requests a permission this project never decided to hold. "
        + "Permissions arrive through dependency manifests as well as through this app's own:\n"
        + unexpected, 0, unexpected.size());
  }

  /**
   * The identity key is sealed under a device-bound Keystore key, so the app's data is worthless
   * anywhere else - and transferring it produces an install that can never be recovered.
   * {@code data_extraction_rules.xml} spends a paragraph on that; nothing checked either half.
   */
  @Test
  public void theappsDataIsNeitherBackedUpNorTransferredToAnotherDevice() throws IOException {
    final ApplicationInfo app = context().getApplicationInfo();
    assertFalse("allowBackup must stay off: the store is sealed under a Keystore key that cannot "
            + "leave this device, so a restored copy is undecryptable - and the 'first run' flag "
            + "would restore with it, so nothing would re-initialise",
        (app.flags & ApplicationInfo.FLAG_ALLOW_BACKUP) != 0);

    final String rules = dataExtractionRules();
    assertTrue("allowBackup=false does not cover Android 12+ device-to-device transfer; the "
            + "cloud-backup rules must still exclude everything",
        rules.contains("<cloud-backup>") && rules.contains("<device-transfer>"));
    assertEquals("both sections must exclude the whole of root and device_root, and nothing may "
            + "be included back:\n" + rules, 2, countOccurrences(rules, "domain=\"root\""));
    assertEquals(2, countOccurrences(rules, "domain=\"device_root\""));
    assertFalse("an <include> here would put device-bound key material back into a transfer:\n"
        + rules, rules.contains("<include"));
  }

  /**
   * Only the system may bind the input method, and only the launcher entry is reachable from
   * another app.
   *
   * <p>An IME service without {@code BIND_INPUT_METHOD} can be bound by any application, which
   * hands it the keyboard's own interface; an exported component added later would be a second
   * door into a process holding the user's conversations. Both are one manifest attribute away and
   * neither was asserted.
   */
  @Test
  public void onlythesystemMayBindTheKeyboardAndOnlySettingsIsExported() {
    final PackageInfo info = merged(PackageManager.GET_ACTIVITIES | PackageManager.GET_SERVICES
        | PackageManager.GET_RECEIVERS | PackageManager.GET_PROVIDERS);

    final ServiceInfo ime = findService(info,
        "com.amnesica.kryptey.inputmethod.latin.LatinIME");
    assertNotNull("the input method service must be in the merged manifest", ime);
    assertEquals("without this any application could bind the keyboard directly",
        "android.permission.BIND_INPUT_METHOD", ime.permission);

    final List<String> exported = new ArrayList<>();
    if (info.activities != null) {
      for (final ActivityInfo a : info.activities) if (a.exported) exported.add(a.name);
    }
    if (info.services != null) {
      // An IME service is reached through the system, not through an ordinary bind; it declares
      // exported=false and is protected by the permission asserted above.
      for (final ServiceInfo s : info.services) if (s.exported) exported.add(s.name);
    }
    if (info.receivers != null) {
      for (final ActivityInfo r : info.receivers) if (r.exported) exported.add(r.name);
    }
    if (info.providers != null) {
      for (final ProviderInfo p : info.providers) if (p.exported) exported.add(p.name);
    }

    assertEquals("only the settings launcher entry may be reachable from another application; "
            + "everything else here shares a process with the user's identity key and their "
            + "decrypted conversations:\n" + exported,
        java.util.Collections.singletonList(
            "com.amnesica.kryptey.inputmethod.latin.settings.SettingsActivity"),
        exported);
  }

  /**
   * And no content provider at all.
   *
   * <p>Stated separately from the export check because the danger is not only export: a provider
   * merged in from a library runs code in this process at startup, before the keyboard does
   * anything, and today there is not one.
   */
  @Test
  public void nocontentProviderRunsInsideTheKeyboardsProcess() {
    final ProviderInfo[] providers = merged(PackageManager.GET_PROVIDERS).providers;
    final List<String> names = new ArrayList<>();
    if (providers != null) {
      for (final ProviderInfo p : providers) names.add(p.name);
    }
    assertEquals("a content provider arrives through a dependency's manifest and runs in this "
        + "process before anything else does:\n" + names, 0, names.size());
  }

  private static ServiceInfo findService(final PackageInfo info, final String name) {
    if (info.services == null) return null;
    for (final ServiceInfo service : info.services) {
      if (name.equals(service.name)) return service;
    }
    return null;
  }

  private static int countOccurrences(final String haystack, final String needle) {
    int count = 0;
    for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + 1)) count++;
    return count;
  }

  /** {@code src/main/res} is compiled, so Gradle already tracks this file as a task input. */
  private static String dataExtractionRules() throws IOException {
    for (final String candidate : new String[] {
        "src/main/res/xml/data_extraction_rules.xml",
        "app/src/main/res/xml/data_extraction_rules.xml"}) {
      final Path path = Paths.get(candidate);
      if (Files.exists(path)) {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
      }
    }
    throw new IllegalStateException("could not locate data_extraction_rules.xml");
  }
}
