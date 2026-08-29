package com.amnesica.kryptey.inputmethod.latin.e2ee;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.widget.TextView;

import com.amnesica.kryptey.inputmethod.R;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

/**
 * The verify screen must fit on a landscape phone, banner included.
 *
 * <p>Every strip screen is a non-scrolling vertical {@code LinearLayout} whose first child is the
 * info banner, and the whole input area is bottom-anchored. So when a screen is taller than the
 * space available, what leaves is the TOP — the banner — while the twelve digits and a live
 * confirm button stay on screen and pressable.
 *
 * <p>That matters on this screen more than any other. When an identity change is pending the banner
 * carries {@code INFO_VERIFY_PENDING_CHANGE} followed by {@code INFO_VERIFY_CHANNEL}: someone
 * offered a different key, and the numbers must be compared <em>by voice</em> rather than through
 * the messenger. This file already decided that the channel warning must be carried by every state
 * of this screen. Clipped off the top, it is carried and not shown.
 *
 * <p>Measured with real Skia text measurement ({@code GraphicsMode.NATIVE}) at landscape
 * qualifiers, because the whole question is how many lines the paragraph wraps to.
 */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w640dp-h360dp-land")
public class TheverifyScreenFitsInLandscapeTest {

  private E2EEStripView strip;

  /**
   * The pending-change sentence, read off the strip rather than copied here.
   *
   * <p>Reflection rather than widening the field: a copy in the test would go on measuring the old
   * wording after the real one was edited, which is the drift this whole question is about. The
   * field is private and per-instance, so this is the only way to measure what actually ships.
   */
  private String pendingChangeText() throws Exception {
    final java.lang.reflect.Field f =
        E2EEStripView.class.getDeclaredField("INFO_VERIFY_PENDING_CHANGE");
    f.setAccessible(true);
    return String.format(String.valueOf(f.get(strip)), "Bob Jones");
  }

  @Before
  public void setUp() {
    final Application app = RuntimeEnvironment.getApplication();
    strip = new E2EEStripView(
        new ContextThemeWrapper(app, R.style.KeyboardTheme_LXX_Pure_Day), null);
  }

  /**
   * The worst case this screen can be asked to render, measured rather than estimated.
   *
   * <p>Prints the numbers as well as asserting, because the reviewer that raised this could not
   * measure it and the arithmetic in the report was explicitly an estimate. A test that only says
   * pass or fail here would leave the next reader with the same estimate.
   */
  @Test
  public void theverifyScreenWithApendingChangeFitsAboveTheKeyboard() throws Exception {
    final TextView banner = strip.findViewById(R.id.e2ee_verify_contact_info_text);
    assertNotNull("fixture: the verify screen must inflate", banner);
    final View wrapper = strip.findViewById(R.id.e2ee_verify_contact_wrapper);
    assertNotNull("fixture: the verify wrapper must inflate", wrapper);
    wrapper.setVisibility(View.VISIBLE);

    // The longest thing this banner is ever asked to hold: the pending-change warning followed by
    // the channel warning, which is what the screen shows when a key has actually been displaced.
    banner.setText(pendingChangeText() + "\n\n" + E2EEStripView.INFO_VERIFY_CHANNEL);

    final float density = RuntimeEnvironment.getApplication()
        .getResources().getDisplayMetrics().density;
    final int widthPx = (int) (640 * density);
    wrapper.measure(View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));

    final int wantedDp = (int) (wrapper.getMeasuredHeight() / density);
    final int keyboardDp = (int) (RuntimeEnvironment.getApplication().getResources()
        .getDimensionPixelSize(R.dimen.config_default_keyboard_height) / density);
    final int screenDp = 360;
    final int availableDp = screenDp - keyboardDp;

    System.out.println("MEASURED landscape verify: wants " + wantedDp + "dp, keyboard "
        + keyboardDp + "dp, screen " + screenDp + "dp, available " + availableDp + "dp");

    // The invariant is not "it fits" - it does not, and shortening the warning is not on the table
    // because the channel sentence is carried by every state of this screen on purpose. The
    // invariant is that when it does not fit, the overflow is REACHABLE rather than clipped.
    if (wantedDp > availableDp) {
      boolean scrollable = false;
      for (View v = banner; v != null && v != wrapper.getParent();
           v = v.getParent() instanceof View ? (View) v.getParent() : null) {
        if (v.getParent() instanceof android.widget.ScrollView) {
          scrollable = true;
          break;
        }
      }
      assertTrue("the verify screen wants " + wantedDp + "dp and only " + availableDp + "dp is "
              + "left above a " + keyboardDp + "dp keyboard on a " + screenDp + "dp-tall landscape "
              + "screen, and nothing between the banner and the wrapper scrolls. The strip is a "
              + "non-scrolling vertical stack in a bottom-anchored input area, so the overflow "
              + "leaves at the TOP - which is where 'someone offered a different key' and 'compare "
              + "by voice, not through the messenger' are written. The digits and the confirm "
              + "button stay on screen and pressable",
          scrollable);
    }
  }
}
