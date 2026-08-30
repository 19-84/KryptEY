/*
 * Copyright (C) 2012 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.amnesica.kryptey.inputmethod.latin.settings;

import android.app.ActionBar;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.preference.PreferenceActivity;
import android.util.Log;
import android.view.MenuItem;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;

import com.amnesica.kryptey.inputmethod.R;
import com.amnesica.kryptey.inputmethod.latin.utils.FragmentUtils;

public class SettingsActivity extends PreferenceActivity {
  private static final String DEFAULT_FRAGMENT = SettingsFragment.class.getName();
  private static final String TAG = SettingsActivity.class.getSimpleName();

  /**
   * Points the settings intent at a system handler when one can be identified.
   *
   * <p>{@code ACTION_INPUT_METHOD_SETTINGS} is an implicit action, and any installed app may
   * declare an {@code intent-filter} for it and appear in the chooser. That matters here more than
   * it would anywhere else in the app: this dialog is not cancelable, it fires on first run before
   * the keyboard is enabled, and it has just told the user to go and enable a keyboard. A
   * convincing clone of the input-method settings screen arriving at that moment is being handed a
   * user who has been primed to say yes - to enabling an IME, which is a complete keylogger, or an
   * accessibility service.
   *
   * <p>Resolved rather than hard-coded. {@code setPackage("com.android.settings")} is the obvious
   * one-liner and is wrong: the settings package name is not guaranteed, and on a device where it
   * differs the OK button becomes dead while {@code setCancelable(false)} keeps the dialog up on
   * every {@code onStart} - so the user's only route to enabling the keyboard silently disappears,
   * on exactly the devices least likely to be tested.
   *
   * <p>So this narrows the intent only when it finds a handler flagged as part of the system image,
   * and otherwise leaves it implicit. Leaving it implicit is not a fix, and is deliberately better
   * than a dead button: the chooser at least still contains the real settings app.
   */
  private static void aimAtTheSystemSettingsIfWeCan(final Context context, final Intent intent) {
    try {
      // MATCH_DEFAULT_ONLY, because that is what startActivity will resolve with.
      //
      // The first version queried with flags 0, which does not apply the CATEGORY_DEFAULT filter.
      // So it could select a system activity whose filter lacks that category, pin the intent to
      // its package, and then startActivity would find no default match and throw - caught,
      // swallowed, dialog dismissed, and the same non-cancelable dialog back on the next onStart
      // with the same dead OK button. That is precisely the failure this method's javadoc says
      // hard-coding the package would cause, reintroduced by another route.
      final java.util.List<android.content.pm.ResolveInfo> candidates =
          context.getPackageManager().queryIntentActivities(
              intent, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY);

      String only = null;
      for (final android.content.pm.ResolveInfo candidate : candidates) {
        if (candidate.activityInfo == null || candidate.activityInfo.applicationInfo == null) {
          continue;
        }
        final int flags = candidate.activityInfo.applicationInfo.flags;
        final boolean system = (flags & android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
            || (flags & android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0;
        if (!system) continue;
        if (only != null && !only.equals(candidate.activityInfo.packageName)) {
          // Two system handlers, and no way here to tell which is the real settings app.
          //
          // FLAG_SYSTEM means preinstalled, not trustworthy: every OEM, carrier and partner app
          // carries it, and queryIntentActivities returns results ordered by a filter priority the
          // declaring app chooses. Picking the first would let a preinstalled lookalike take the
          // intent with the chooser REMOVED - turning "the attacker is one row in a list" into "the
          // attacker is the only destination", which is worse than doing nothing.
          //
          // So ambiguity falls back to the implicit intent. The user sees a chooser containing the
          // real settings app, which is exactly where they were before this method existed.
          return;
        }
        only = candidate.activityInfo.packageName;
      }
      if (only != null) intent.setPackage(only);
    } catch (final RuntimeException e) {
      // A package-manager failure must not stop the user reaching the settings screen.
      Log.e(TAG, "Could not resolve a system handler for the input-method settings", e);
    }
  }

  @Override
  protected void onStart() {
    super.onStart();

    boolean enabled = false;
    try {
      enabled = isInputMethodOfThisImeEnabled();
    } catch (Exception e) {
      Log.e(TAG, "Exception in check if input method is enabled", e);
    }

    if (!enabled) {
      final Context context = this;
      AlertDialog.Builder builder = new AlertDialog.Builder(this);
      builder.setMessage(R.string.setup_message);
      builder.setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
        public void onClick(DialogInterface dialog, int id) {
          final Intent intent =
              new Intent(android.provider.Settings.ACTION_INPUT_METHOD_SETTINGS);
          intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
          aimAtTheSystemSettingsIfWeCan(context, intent);
          try {
            context.startActivity(intent);
          } catch (final android.content.ActivityNotFoundException noSettings) {
            // Better a logged failure than a crash on the one screen that tells the user how to
            // enable the keyboard. The dialog is not cancelable, so a crash here would leave them
            // with no route at all.
            Log.e(TAG, "No activity could handle ACTION_INPUT_METHOD_SETTINGS");
          }
          dialog.dismiss();
        }
      });
      builder.setNegativeButton(android.R.string.cancel, new DialogInterface.OnClickListener() {
        public void onClick(DialogInterface dialog, int id) {
          finish();
        }
      });
      builder.setCancelable(false);

      builder.create().show();
    }
  }

  /**
   * Check if this IME is enabled in the system.
   *
   * @return whether this IME is enabled in the system.
   */
  private boolean isInputMethodOfThisImeEnabled() {
    final InputMethodManager imm =
        (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
    final String imePackageName = getPackageName();
    for (final InputMethodInfo imi : imm.getEnabledInputMethodList()) {
      if (imi.getPackageName().equals(imePackageName)) {
        return true;
      }
    }
    return false;
  }

  @Override
  protected void onCreate(final Bundle savedState) {
    super.onCreate(savedState);
    final ActionBar actionBar = getActionBar();
    if (actionBar != null) {
      actionBar.setDisplayHomeAsUpEnabled(true);
      actionBar.setHomeButtonEnabled(true);
    }
  }

  @Override
  public boolean onOptionsItemSelected(final MenuItem item) {
    if (item.getItemId() == android.R.id.home) {
      super.onBackPressed();
      return true;
    }
    return super.onOptionsItemSelected(item);
  }

  @Override
  public Intent getIntent() {
    final Intent intent = super.getIntent();
    final String fragment = intent.getStringExtra(EXTRA_SHOW_FRAGMENT);
    if (fragment == null) {
      intent.putExtra(EXTRA_SHOW_FRAGMENT, DEFAULT_FRAGMENT);
    }
    intent.putExtra(EXTRA_NO_HEADERS, true);
    return intent;
  }

  @Override
  public boolean isValidFragment(final String fragmentName) {
    return FragmentUtils.isValidFragment(fragmentName);
  }
}
