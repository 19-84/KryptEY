/*
 * Copyright (C) 2014 The Android Open Source Project
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

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.os.Bundle;
import android.preference.Preference;
import android.preference.PreferenceScreen;

import com.amnesica.kryptey.inputmethod.R;
import com.amnesica.kryptey.inputmethod.keyboard.KeyboardTheme;
import com.amnesica.kryptey.inputmethod.latin.settings.RadioButtonPreference.OnRadioButtonClickedListener;

/**
 * "Keyboard theme" settings sub screen.
 */
public final class ThemeSettingsFragment extends SubScreenFragment
    implements OnRadioButtonClickedListener {
  private int mSelectedThemeId;

  static class KeyboardThemePreference extends RadioButtonPreference {
    final int mThemeId;

    KeyboardThemePreference(final Context context, final String name, final int id) {
      super(context);
      setTitle(name);
      mThemeId = id;
    }
  }

  static void updateKeyboardThemeSummary(final Preference pref) {
    final Context context = pref.getContext();
    final Resources res = context.getResources();
    final KeyboardTheme keyboardTheme = KeyboardTheme.getKeyboardTheme(context);
    final String[] keyboardThemeNames = res.getStringArray(R.array.keyboard_theme_names);
    final int[] keyboardThemeIds = res.getIntArray(R.array.keyboard_theme_ids);
    for (int index = 0; index < keyboardThemeIds.length; index++) {
      if (keyboardTheme.mThemeId == keyboardThemeIds[index]) {
        pref.setSummary(keyboardThemeNames[index]);
        return;
      }
    }
  }

  @Override
  public void onCreate(final Bundle icicle) {
    super.onCreate(icicle);
    addPreferencesFromResource(R.xml.prefs_screen_theme);
    final PreferenceScreen screen = getPreferenceScreen();
    final Context context = getActivity();
    final Resources res = getResources();
    final String[] keyboardThemeNames = res.getStringArray(R.array.keyboard_theme_names);
    final int[] keyboardThemeIds = res.getIntArray(R.array.keyboard_theme_ids);
    for (int index = 0; index < keyboardThemeIds.length; index++) {
      final KeyboardThemePreference pref = new KeyboardThemePreference(
          context, keyboardThemeNames[index], keyboardThemeIds[index]);
      screen.addPreference(pref);
      pref.setOnRadioButtonClickedListener(this);
    }
    final KeyboardTheme keyboardTheme = KeyboardTheme.getKeyboardTheme(context);
    mSelectedThemeId = keyboardTheme.mThemeId;
  }

  @Override
  public void onRadioButtonClicked(final RadioButtonPreference preference) {
    if (preference instanceof KeyboardThemePreference) {
      final KeyboardThemePreference pref = (KeyboardThemePreference) preference;
      mSelectedThemeId = pref.mThemeId;
      mUserPickedAtheme = true;
      updateSelected();
    }
  }

  @Override
  public void onResume() {
    super.onResume();
    updateSelected();
  }

  /** Set only by {@link #onRadioButtonClicked}, which is the only thing that is a user choosing. */
  private boolean mUserPickedAtheme;

  /** Records a pick without a radio button, for tests that drive the lifecycle directly. */
  void markThemePickedForTest(final int themeId) {
    mSelectedThemeId = themeId;
    mUserPickedAtheme = true;
  }

  /**
   * Persists the choice, and only when there was one.
   *
   * <p>This wrote both keys unconditionally, and {@code SettingsActivity} is the app's one exported
   * component: any app on the device can launch it with an {@code EXTRA_SHOW_FRAGMENT} extra naming
   * this fragment and take the foreground back, so {@code onPause} runs with no human having
   * touched anything. Both writes are inert today — {@code PREF_KEYBOARD_COLOR} can never have been
   * set, because the control that writes it is permanently disabled, and the theme id written is
   * the one just read — but that is a property of what those two keys currently mean, not of the
   * entry point. Enabling the colour picker would turn the second write into a drive-by delete.
   *
   * <p>The flag is set in {@code onRadioButtonClicked} and cleared nowhere else, deliberately.
   * Clearing it in {@code onResume} would look right and break the genuine path: that callback runs
   * again after a dialog is dismissed, so a user's pick would be forgotten before it was saved, and
   * the theme reverting on the next raise reads as a rendering bug rather than as this.
   */
  @Override
  public void onPause() {
    super.onPause();
    persistIfTheUserChose(getSharedPreferences());
  }

  /**
   * The decision itself, separated from the callback so it can be driven with a real store.
   *
   * <p>Package-private rather than private: a test that re-implements this would be testing its own
   * copy, and the whole point is what happens on a pause nobody asked for.
   */
  void persistIfTheUserChose(final SharedPreferences prefs) {
    if (!mUserPickedAtheme || prefs == null) return;
    KeyboardTheme.saveKeyboardThemeId(mSelectedThemeId, prefs);
    Settings.removeKeyboardColor(prefs);
  }

  private void updateSelected() {
    final PreferenceScreen screen = getPreferenceScreen();
    final int count = screen.getPreferenceCount();
    for (int index = 0; index < count; index++) {
      final Preference preference = screen.getPreference(index);
      if (preference instanceof KeyboardThemePreference) {
        final KeyboardThemePreference pref = (KeyboardThemePreference) preference;
        final boolean selected = (mSelectedThemeId == pref.mThemeId);
        pref.setSelected(selected);
      }
    }
  }
}
