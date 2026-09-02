/*
 * Copyright (C) 2008 The Android Open Source Project
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

package com.amnesica.kryptey.inputmethod.keyboard;

import android.content.Context;
import android.content.res.Resources;
import android.os.Build;
import android.util.Log;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.inputmethod.EditorInfo;

import com.amnesica.kryptey.inputmethod.R;
import com.amnesica.kryptey.inputmethod.event.Event;
import com.amnesica.kryptey.inputmethod.keyboard.internal.KeyboardState;
import com.amnesica.kryptey.inputmethod.keyboard.internal.KeyboardTextsSet;
import com.amnesica.kryptey.inputmethod.latin.InputView;
import com.amnesica.kryptey.inputmethod.latin.LatinIME;
import com.amnesica.kryptey.inputmethod.latin.RichInputMethodManager;
import com.amnesica.kryptey.inputmethod.latin.settings.Settings;
import com.amnesica.kryptey.inputmethod.latin.settings.SettingsValues;
import com.amnesica.kryptey.inputmethod.latin.utils.CapsModeUtils;
import com.amnesica.kryptey.inputmethod.latin.utils.LanguageOnSpacebarUtils;
import com.amnesica.kryptey.inputmethod.latin.utils.RecapitalizeStatus;
import com.amnesica.kryptey.inputmethod.latin.utils.ResourceUtils;

public final class KeyboardSwitcher implements KeyboardState.SwitchActions {
  private static final String TAG = KeyboardSwitcher.class.getSimpleName();

  private InputView mCurrentInputView;
  private int mCurrentUiMode;
  private int mCurrentTextColor = 0x0;
  private View mMainKeyboardFrame;
  private MainKeyboardView mKeyboardView;
  private LatinIME mLatinIME;
  private RichInputMethodManager mRichImm;

  private KeyboardState mState;

  private KeyboardLayoutSet mKeyboardLayoutSet;
  // TODO: The following {@link KeyboardTextsSet} should be in {@link KeyboardLayoutSet}.
  private final KeyboardTextsSet mKeyboardTextsSet = new KeyboardTextsSet();

  private KeyboardTheme mKeyboardTheme;
  private Context mThemeContext;

  private static final KeyboardSwitcher sInstance = new KeyboardSwitcher();

  public static KeyboardSwitcher getInstance() {
    return sInstance;
  }

  private KeyboardSwitcher() {
    // Intentional empty constructor for singleton.
  }

  public static void init(final LatinIME latinIme) {
    sInstance.initInternal(latinIme);
  }

  private void initInternal(final LatinIME latinIme) {
    mLatinIME = latinIme;
    mRichImm = RichInputMethodManager.getInstance();
    mState = new KeyboardState(this);
  }

  public void updateKeyboardTheme(final int uiMode) {
    final boolean themeUpdated = updateKeyboardThemeAndContextThemeWrapper(
        mLatinIME, KeyboardTheme.getKeyboardTheme(mLatinIME), uiMode);
    if (themeUpdated && mKeyboardView != null) {
      mLatinIME.setInputView(onCreateInputView(uiMode));
    }
  }

  private boolean updateKeyboardThemeAndContextThemeWrapper(final Context context,
                                                            final KeyboardTheme keyboardTheme, final int uiMode) {
    int newTextColor = 0x0;
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      newTextColor = context.getResources().getColor(R.color.key_text_color_lxx_system);
    }

    if (mThemeContext == null
        || !keyboardTheme.equals(mKeyboardTheme)
        || mCurrentUiMode != uiMode
        || newTextColor != mCurrentTextColor) {
      mKeyboardTheme = keyboardTheme;
      mCurrentUiMode = uiMode;
      mCurrentTextColor = newTextColor;
      mThemeContext = new ContextThemeWrapper(context, keyboardTheme.mStyleId);
      KeyboardLayoutSet.onKeyboardThemeChanged();
      return true;
    }
    return false;
  }

  public void loadKeyboard(final EditorInfo editorInfo, final SettingsValues settingsValues,
                           final int currentAutoCapsState, final int currentRecapitalizeState) {
    final KeyboardLayoutSet.Builder builder = new KeyboardLayoutSet.Builder(
        mThemeContext, editorInfo);
    final Resources res = mThemeContext.getResources();
    final int keyboardWidth = mLatinIME.getMaxWidth();
    final int keyboardHeight = ResourceUtils.getKeyboardHeight(res, settingsValues);
    builder.setKeyboardTheme(mKeyboardTheme.mThemeId);
    builder.setKeyboardGeometry(keyboardWidth, keyboardHeight);
    builder.setSubtype(mRichImm.getCurrentSubtype());
    builder.setLanguageSwitchKeyEnabled(mLatinIME.shouldShowLanguageSwitchKey());
    builder.setShowSpecialChars(!settingsValues.mHideSpecialChars);
    builder.setShowNumberRow(settingsValues.mShowNumberRow);
    mKeyboardLayoutSet = builder.build();
    // No catch here, and the type it used to catch is gone.
    //
    // This caught KeyboardLayoutSetException and logged it, which read as "a failed keyboard build
    // is a logged, survivable condition". Nothing has ever thrown that type: the AOSP wrapper that
    // converted a RuntimeException from builder.load into it is not in this tree, and never was -
    // the initial commit has no wrapper either. So an unchecked throw from the build killed the
    // input-method process while a handler sat here implying otherwise.
    //
    // The dead type and its catch are removed rather than the wrapper restored. Restoring it would
    // make a genuinely broken layout SILENT: loadKeyboard would log and return, the view would keep
    // the previous keyboard or none, and the user would get a blank or stale keyboard with no
    // signal. Every reachable cause of a build failure is this app's own XML, which is signed into
    // the APK - the parser's input is never host-supplied, which a review round established by
    // enumeration - so failing loudly during development is the behaviour worth having.
    mState.onLoadKeyboard(currentAutoCapsState, currentRecapitalizeState);
    mKeyboardTextsSet.setLocale(mRichImm.getCurrentSubtype().getLocaleObject(), mThemeContext);
  }

  public void saveKeyboardState() {
    if (getKeyboard() != null) {
      mState.onSaveKeyboardState();
    }
  }

  public void onHideWindow() {
    if (mKeyboardView != null) {
      mKeyboardView.onHideWindow();
    }
  }

  private void setKeyboard(
      final int keyboardId,
      final KeyboardSwitchState toggleState) {
    final SettingsValues currentSettingsValues = Settings.getInstance().getCurrent();
    setMainKeyboardFrame(currentSettingsValues, toggleState);
    // TODO: pass this object to setKeyboard instead of getting the current values.
    final MainKeyboardView keyboardView = mKeyboardView;
    if (mKeyboardLayoutSet == null) {
      // The layout set only. A "view is null" half used to sit here too and could never fire:
      // setMainKeyboardFrame, called on the line above, dereferences mKeyboardView first, so the
      // NPE this guard claimed to prevent had already been thrown. That check now lives where the
      // dereference is, and this one says only what it can actually decide.
      //
      // Reached through LatinIME's "restarting and the input type did not change" branch, which
      // can run before any layout set exists if the first onStartInputView bailed early. Its
      // sibling requestUpdatingShiftState survives the same state only because
      // KeyboardState.updateAlphabetShiftState happens to open with the inverse guard - nothing in
      // this class arranges that.
      //
      // Logged rather than swallowed: there is no keyboard to install without a layout set, so
      // returning is the only option, and a silent one would make a missing keyboard a mystery.
      Log.w(TAG, "setKeyboard with no layout set; leaving the keyboard as it is");
      return;
    }
    final Keyboard oldKeyboard = keyboardView.getKeyboard();
    final Keyboard newKeyboard = mKeyboardLayoutSet.getKeyboard(keyboardId);
    keyboardView.setKeyboard(newKeyboard);
    mCurrentInputView.setKeyboardTopPadding((int) newKeyboard.mTopPadding);
    keyboardView.setKeyPreviewPopupEnabled(
        currentSettingsValues.mKeyPreviewPopupOn,
        currentSettingsValues.mKeyPreviewPopupDismissDelay);
    final boolean subtypeChanged = (oldKeyboard == null)
        || !newKeyboard.mId.mSubtype.equals(oldKeyboard.mId.mSubtype);
    final int languageOnSpacebarFormatType = LanguageOnSpacebarUtils
        .getLanguageOnSpacebarFormatType(newKeyboard.mId.mSubtype);
    keyboardView.startDisplayLanguageOnSpacebar(subtypeChanged, languageOnSpacebarFormatType);
  }

  public Keyboard getKeyboard() {
    if (mKeyboardView != null) {
      return mKeyboardView.getKeyboard();
    }
    return null;
  }

  // TODO: Remove this method. Come up with a more comprehensive way to reset the keyboard layout
  // when a keyboard layout set doesn't get reloaded in LatinIME.onStartInputViewInternal().
  public void resetKeyboardStateToAlphabet(final int currentAutoCapsState,
                                           final int currentRecapitalizeState) {
    mState.onResetKeyboardStateToAlphabet(currentAutoCapsState, currentRecapitalizeState);
  }

  public void onPressKey(final int code, final boolean isSinglePointer,
                         final int currentAutoCapsState, final int currentRecapitalizeState) {
    mState.onPressKey(code, isSinglePointer, currentAutoCapsState, currentRecapitalizeState);
  }

  public void onReleaseKey(final int code, final boolean withSliding,
                           final int currentAutoCapsState, final int currentRecapitalizeState) {
    mState.onReleaseKey(code, withSliding, currentAutoCapsState, currentRecapitalizeState);
  }

  public void onFinishSlidingInput(final int currentAutoCapsState,
                                   final int currentRecapitalizeState) {
    mState.onFinishSlidingInput(currentAutoCapsState, currentRecapitalizeState);
  }

  // Implements {@link KeyboardState.SwitchActions}.
  @Override
  public void setAlphabetKeyboard() {
    if (DEBUG_ACTION) {
      Log.d(TAG, "setAlphabetKeyboard");
    }
    setKeyboard(KeyboardId.ELEMENT_ALPHABET, KeyboardSwitchState.OTHER);
  }

  // Implements {@link KeyboardState.SwitchActions}.
  @Override
  public void setAlphabetManualShiftedKeyboard() {
    if (DEBUG_ACTION) {
      Log.d(TAG, "setAlphabetManualShiftedKeyboard");
    }
    setKeyboard(KeyboardId.ELEMENT_ALPHABET_MANUAL_SHIFTED, KeyboardSwitchState.OTHER);
  }

  // Implements {@link KeyboardState.SwitchActions}.
  @Override
  public void setAlphabetAutomaticShiftedKeyboard() {
    if (DEBUG_ACTION) {
      Log.d(TAG, "setAlphabetAutomaticShiftedKeyboard");
    }
    setKeyboard(KeyboardId.ELEMENT_ALPHABET_AUTOMATIC_SHIFTED, KeyboardSwitchState.OTHER);
  }

  // Implements {@link KeyboardState.SwitchActions}.
  @Override
  public void setAlphabetShiftLockedKeyboard() {
    if (DEBUG_ACTION) {
      Log.d(TAG, "setAlphabetShiftLockedKeyboard");
    }
    setKeyboard(KeyboardId.ELEMENT_ALPHABET_SHIFT_LOCKED, KeyboardSwitchState.OTHER);
  }

  // Implements {@link KeyboardState.SwitchActions}.
  @Override
  public void setSymbolsKeyboard() {
    if (DEBUG_ACTION) {
      Log.d(TAG, "setSymbolsKeyboard");
    }
    setKeyboard(KeyboardId.ELEMENT_SYMBOLS, KeyboardSwitchState.OTHER);
  }

  // Implements {@link KeyboardState.SwitchActions}.
  @Override
  public void setSymbolsShiftedKeyboard() {
    if (DEBUG_ACTION) {
      Log.d(TAG, "setSymbolsShiftedKeyboard");
    }
    setKeyboard(KeyboardId.ELEMENT_SYMBOLS_SHIFTED, KeyboardSwitchState.SYMBOLS_SHIFTED);
  }

  public boolean isImeSuppressedByHardwareKeyboard(
      final SettingsValues settingsValues,
      final KeyboardSwitchState toggleState) {
    return settingsValues.mHasHardwareKeyboard && toggleState == KeyboardSwitchState.HIDDEN;
  }

  private void setMainKeyboardFrame(
      final SettingsValues settingsValues,
      final KeyboardSwitchState toggleState) {
    final int visibility = isImeSuppressedByHardwareKeyboard(settingsValues, toggleState)
        ? View.GONE : View.VISIBLE;
    // Guarded here because this is the FIRST dereference, which is the whole point.
    //
    // setKeyboard used to carry a "view is null" check one line below its call to this method, so
    // the NPE it named had already been thrown by the line above it. Review caught that: a handler
    // that looks like it covers the code above it, which is the same shape this branch fixed in
    // ListAdapterContacts.getItem a few commits earlier and then committed again here.
    if (mKeyboardView == null || mMainKeyboardFrame == null) return;
    mKeyboardView.setVisibility(visibility);
    // The visibility of {@link #mKeyboardView} must be aligned with {@link #MainKeyboardFrame}.
    // @see #getVisibleKeyboardView() and
    // @see LatinIME#onComputeInset(android.inputmethodservice.InputMethodService.Insets)
    mMainKeyboardFrame.setVisibility(visibility);
  }

  public enum KeyboardSwitchState {
    HIDDEN(-1),
    SYMBOLS_SHIFTED(KeyboardId.ELEMENT_SYMBOLS_SHIFTED),
    OTHER(-1);

    final int mKeyboardId;

    KeyboardSwitchState(int keyboardId) {
      mKeyboardId = keyboardId;
    }
  }

  public KeyboardSwitchState getKeyboardSwitchState() {
    boolean hidden = mKeyboardLayoutSet == null
        || mKeyboardView == null
        || !mKeyboardView.isShown();
    if (hidden) {
      return KeyboardSwitchState.HIDDEN;
    } else if (isShowingKeyboardId(KeyboardId.ELEMENT_SYMBOLS_SHIFTED)) {
      return KeyboardSwitchState.SYMBOLS_SHIFTED;
    }
    return KeyboardSwitchState.OTHER;
  }

  // Future method for requesting an updating to the shift state.
  @Override
  public void requestUpdatingShiftState(final int autoCapsFlags, final int recapitalizeMode) {
    if (DEBUG_ACTION) {
      Log.d(TAG, "requestUpdatingShiftState: "
          + " autoCapsFlags=" + CapsModeUtils.flagsToString(autoCapsFlags)
          + " recapitalizeMode=" + RecapitalizeStatus.modeToString(recapitalizeMode));
    }
    mState.onUpdateShiftState(autoCapsFlags, recapitalizeMode);
  }

  // Implements {@link KeyboardState.SwitchActions}.
  @Override
  public void startDoubleTapShiftKeyTimer() {
    if (DEBUG_TIMER_ACTION) {
      Log.d(TAG, "startDoubleTapShiftKeyTimer");
    }
    final MainKeyboardView keyboardView = getMainKeyboardView();
    if (keyboardView != null) {
      keyboardView.startDoubleTapShiftKeyTimer();
    }
  }

  // Implements {@link KeyboardState.SwitchActions}.
  @Override
  public void cancelDoubleTapShiftKeyTimer() {
    if (DEBUG_TIMER_ACTION) {
      Log.d(TAG, "setAlphabetKeyboard");
    }
    final MainKeyboardView keyboardView = getMainKeyboardView();
    if (keyboardView != null) {
      keyboardView.cancelDoubleTapShiftKeyTimer();
    }
  }

  // Implements {@link KeyboardState.SwitchActions}.
  @Override
  public boolean isInDoubleTapShiftKeyTimeout() {
    if (DEBUG_TIMER_ACTION) {
      Log.d(TAG, "isInDoubleTapShiftKeyTimeout");
    }
    final MainKeyboardView keyboardView = getMainKeyboardView();
    return keyboardView != null && keyboardView.isInDoubleTapShiftKeyTimeout();
  }

  /**
   * Updates state machine to figure out when to automatically switch back to the previous mode.
   */
  public void onEvent(final Event event, final int currentAutoCapsState,
                      final int currentRecapitalizeState) {
    mState.onEvent(event, currentAutoCapsState, currentRecapitalizeState);
  }

  public boolean isShowingKeyboardId(int... keyboardIds) {
    // getKeyboard(), not just the view.
    //
    // getKeyboardSwitchState treats a non-null mKeyboardLayoutSet as proof that the view holds a
    // Keyboard, and that does not follow: onCreateInputView replaces mKeyboardView wholesale while
    // the layout set is left alone, so a fresh view can be shown with no keyboard on it and the
    // caller's guard satisfied. Nothing in LatinIME's IMS callbacks catches an NPE out of here.
    //
    // Not reachable by anything the host app chooses - the route runs through
    // isImeSuppressedByHardwareKeyboard, which short-circuits unless a hardware keyboard is
    // attached and visible - which is why this is a guard and not a bug report. A view with no
    // keyboard is not showing any of the ids asked about, so false is the answer, not an accident.
    if (mKeyboardView == null || !mKeyboardView.isShown()
        || mKeyboardView.getKeyboard() == null) {
      return false;
    }
    int activeKeyboardId = mKeyboardView.getKeyboard().mId.mElementId;
    for (int keyboardId : keyboardIds) {
      if (activeKeyboardId == keyboardId) {
        return true;
      }
    }
    return false;
  }

  /**
   * Whether a more-keys panel is up.
   *
   * <p>Null-checked like the seven accessors around it. Its one caller happens to early-return on
   * this same field first, so the omission was safe by the caller's shape rather than by anything
   * this class does - and a class where seven of eight accessors guard is one where the eighth
   * reads as an oversight to anyone adding a second caller.
   */
  public boolean isShowingMoreKeysPanel() {
    return mKeyboardView != null && mKeyboardView.isShowingMoreKeysPanel();
  }

  public View getVisibleKeyboardView() {
    return mKeyboardView;
  }

  public MainKeyboardView getMainKeyboardView() {
    return mKeyboardView;
  }

  public void deallocateMemory() {
    if (mKeyboardView != null) {
      mKeyboardView.cancelAllOngoingEvents();
      mKeyboardView.deallocateMemory();
    }
  }

  public View onCreateInputView(final int uiMode) {
    if (mKeyboardView != null) {
      mKeyboardView.closing();
    }

    updateKeyboardThemeAndContextThemeWrapper(
        mLatinIME, KeyboardTheme.getKeyboardTheme(mLatinIME /* context */), uiMode);
    mCurrentInputView = (InputView) LayoutInflater.from(mThemeContext).inflate(
        R.layout.input_view, null);
    mMainKeyboardFrame = mCurrentInputView.findViewById(R.id.main_keyboard_frame);

    mKeyboardView = mCurrentInputView.findViewById(R.id.keyboard_view);
    mKeyboardView.setKeyboardActionListener(mLatinIME);
    return mCurrentInputView;
  }
}
