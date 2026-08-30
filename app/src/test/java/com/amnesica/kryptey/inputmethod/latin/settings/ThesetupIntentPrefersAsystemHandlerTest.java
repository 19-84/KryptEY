package com.amnesica.kryptey.inputmethod.latin.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.ResolveInfo;
import android.provider.Settings;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;

import java.lang.reflect.Method;

/**
 * The first-run setup dialog must not hand the user to whoever asked for the action.
 *
 * <p>{@code ACTION_INPUT_METHOD_SETTINGS} is implicit, and any installed app may declare an
 * {@code intent-filter} for it. The dialog that fires it is not cancelable, appears on first run
 * before the keyboard is enabled, and has just told the user to go and enable a keyboard - so a
 * clone of the input-method settings screen arriving there is handed a user primed to say yes, to
 * enabling an IME (a complete keylogger) or an accessibility service.
 *
 * <p>Two assertions, and the second is the one that keeps the fix honest. Hard-coding
 * {@code com.android.settings} would satisfy the first and dead-button the dialog on any device
 * whose settings package differs - and {@code setCancelable(false)} means the user would have no
 * route to enabling the keyboard at all, on exactly the devices least likely to be tested.
 */
@RunWith(RobolectricTestRunner.class)
public class ThesetupIntentPrefersAsystemHandlerTest {

  private static Intent aimed(final ResolveInfo... handlers) throws Exception {
    final Context context = RuntimeEnvironment.getApplication();
    final Intent intent = new Intent(Settings.ACTION_INPUT_METHOD_SETTINGS);
    for (final ResolveInfo handler : handlers) {
      Shadows.shadowOf(context.getPackageManager())
          .addResolveInfoForIntent(intent, handler);
    }
    final Method aim = SettingsActivity.class.getDeclaredMethod(
        "aimAtTheSystemSettingsIfWeCan", Context.class, Intent.class);
    aim.setAccessible(true);
    aim.invoke(null, context, intent);
    return intent;
  }

  private static ResolveInfo handler(final String packageName, final boolean system) {
    final ResolveInfo info = new ResolveInfo();
    info.activityInfo = new ActivityInfo();
    info.activityInfo.packageName = packageName;
    info.activityInfo.name = packageName + ".SettingsLikeActivity";
    info.activityInfo.applicationInfo = new ApplicationInfo();
    info.activityInfo.applicationInfo.packageName = packageName;
    info.activityInfo.applicationInfo.flags = system ? ApplicationInfo.FLAG_SYSTEM : 0;
    return info;
  }

  @Test
  public void asystemHandlerIsPreferredOverAnimpostor() throws Exception {
    final Intent intent = aimed(
        handler("com.evil.lookalike", false),
        handler("com.android.settings", true));

    assertEquals("with a system settings app present, the intent must be aimed at it. Left "
            + "implicit, the chooser that appears at the one moment the user has been told to "
            + "enable a keyboard also contains whatever else declared the action",
        "com.android.settings", intent.getPackage());
  }

  /**
   * And the setup dialog must actually call it.
   *
   * <p>The two tests around this one drive the helper directly, so deleting the call from the OK
   * button leaves them green - measured, not assumed: that mutant passed both. A decision nothing
   * invokes is not a guard, and this is the cheapest thing that can tell the difference. It reads
   * the source and says plainly that it checks application rather than behaviour; the behaviour is
   * what the other two check.
   */
  @Test
  public void thesetupDialogAimsTheIntentBeforeStartingIt() throws Exception {
    java.nio.file.Path here = java.nio.file.Paths.get("").toAbsolutePath();
    while (here != null && !java.nio.file.Files.exists(
        here.resolve("gradle/verification-metadata.xml"))) {
      here = here.getParent();
    }
    org.junit.Assert.assertNotNull("the repository root must be findable", here);
    final String source = new String(java.nio.file.Files.readAllBytes(here.resolve(
        "app/src/main/java/com/amnesica/kryptey/inputmethod/latin/settings/SettingsActivity.java")),
        java.nio.charset.StandardCharsets.UTF_8)
        .replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("//[^\n]*", " ");

    org.junit.Assert.assertTrue("the first-run setup dialog starts ACTION_INPUT_METHOD_SETTINGS "
            + "without narrowing it to a system handler first. That action is implicit, any "
            + "installed app may declare it, and the dialog fires at the one moment the user has "
            + "been told to go and enable a keyboard",
        source.contains("aimAtTheSystemSettingsIfWeCan(context, intent)"));
  }

  /**
   * The half that stops the fix becoming a worse bug: no system handler, no narrowing.
   *
   * <p>A device whose settings live under another package name, or a test image with none, must
   * still reach the chooser. An intent aimed at a package that cannot handle it is a dead OK button
   * on a dialog the user cannot dismiss.
   */
  @Test
  public void withNoSystemHandlerTheIntentIsLeftImplicit() throws Exception {
    final Intent intent = aimed(handler("com.evil.lookalike", false));

    assertNull("with no system handler resolvable the intent must stay implicit rather than being "
            + "aimed at a package that may not handle it - the alternative is a dead button on a "
            + "dialog that cannot be cancelled, and no route to enabling the keyboard at all",
        intent.getPackage());
  }
}
