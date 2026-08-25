package com.amnesica.kryptey.inputmethod.latin.e2ee;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * Clipboard clearing on the oldest supported API, which no test had ever run.
 *
 * <p>Robolectric runs at {@code compileSdk} unless told otherwise, and this project's is 35. Its
 * {@code minSdk} is 26. Every {@code SDK_INT < …} branch in the app is therefore code that real
 * users on API 26–32 execute and that no test has ever entered — including this one, which is a
 * security behaviour rather than a compatibility detail: the decrypt path calls
 * {@code clearClipboard()} on every exit, so ciphertext the user pasted does not sit in a buffer
 * every app on the device can read.
 *
 * <p>{@code clearPrimaryClip()} arrived in API 28. Below that the app overwrites the clip with an
 * empty one instead. That is a different call with a different failure mode, and "the newer branch
 * works" says nothing about it.
 *
 * <p>Both branches are asserted here, by the same assertions, so neither can be broken alone.
 */
@RunWith(RobolectricTestRunner.class)
public class LegacyApiClipboardTest {

  private static final String CIPHERTEXT = "SGVsbG8gZnJvbSB0aGUgbWVzc2VuZ2Vy-pasted-ciphertext";

  private E2EEStrip strip;
  private ClipboardManager clipboard;

  @Before
  public void setUp() {
    final Context context = RuntimeEnvironment.getApplication();
    strip = new E2EEStrip(context);
    clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
  }

  private void putCiphertextOnTheClipboard() {
    clipboard.setPrimaryClip(ClipData.newPlainText("", CIPHERTEXT));
    assertTrue("precondition: the ciphertext must be on the clipboard to begin with",
        clipboardHolds(CIPHERTEXT));
  }

  private boolean clipboardHolds(final String text) {
    final ClipData clip = clipboard.getPrimaryClip();
    if (clip == null || clip.getItemCount() == 0) return false;
    final CharSequence item = clip.getItemAt(0).getText();
    return item != null && item.toString().contains(text);
  }

  /** API 26: the oldest the app supports, and below {@code clearPrimaryClip}. */
  @Test
  @Config(sdk = 26)
  public void theciphertextIsClearedOnTheOldestSupportedApi() {
    putCiphertextOnTheClipboard();

    strip.clearClipboard();

    assertFalse("pasted ciphertext must not be left on the clipboard for every app on the device "
        + "to read - on API 26 there is no clearPrimaryClip, so this is the overwrite branch",
        clipboardHolds(CIPHERTEXT));
  }

  /** API 27: the last version before {@code clearPrimaryClip} exists. */
  @Test
  @Config(sdk = 27)
  public void theciphertextIsClearedOnTheLastApiBeforeClearPrimaryClip() {
    putCiphertextOnTheClipboard();

    strip.clearClipboard();

    assertFalse(clipboardHolds(CIPHERTEXT));
  }

  /** API 28: the first version with {@code clearPrimaryClip}, so the other branch. */
  @Test
  @Config(sdk = 28)
  public void theciphertextIsClearedOnTheFirstApiWithClearPrimaryClip() {
    putCiphertextOnTheClipboard();

    strip.clearClipboard();

    assertFalse(clipboardHolds(CIPHERTEXT));
  }

  /** And at the SDK every other test in this suite runs at, so the comparison is like for like. */
  @Test
  @Config(sdk = 35)
  public void theciphertextIsClearedOnTheCompileSdk() {
    putCiphertextOnTheClipboard();

    strip.clearClipboard();

    assertFalse(clipboardHolds(CIPHERTEXT));
  }
}
