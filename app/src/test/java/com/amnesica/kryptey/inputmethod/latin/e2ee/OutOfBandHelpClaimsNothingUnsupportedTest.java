package com.amnesica.kryptey.inputmethod.latin.e2ee;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import com.amnesica.kryptey.inputmethod.R;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.util.Arrays;
import java.util.List;

/**
 * The out-of-band guidance must not promise something the app cannot do.
 *
 * <p>Exchanging an invite outside the messenger already works and always did — the invite button
 * commits into whichever app has the cursor, and a bundle pasted from anywhere is handled by the
 * ordinary path. What was missing was telling anyone.
 *
 * <p>The trap in writing it is specific and this project has fallen into its shape repeatedly: the
 * exported bundle is <b>byte-identical</b> whichever route it took, so the app can never observe
 * provenance. {@code markKeyOutOfBand} exists and nothing reads it, deliberately, because a stamp
 * for "this arrived safely" would be a claim the code cannot support. Help text that implied
 * otherwise would reintroduce exactly that false assurance in prose instead of in a flag.
 */
@RunWith(RobolectricTestRunner.class)
public class OutOfBandHelpClaimsNothingUnsupportedTest {

  private String help;

  @Before
  public void setUp() {
    final Context context = RuntimeEnvironment.getApplication();
    help = context.getString(R.string.e2ee_help_view_text);
  }

  /** The section exists, or the rest of this asserts nothing. */
  @Test
  public void thehelpExplainsThatInvitesCanTravelAnotherWay() {
    assertTrue("the help must tell the user this is possible at all - the capability existed and "
        + "nothing mentioned it", help.contains("without going through the messenger"));
  }

  /** And says plainly that the app cannot tell, which is the part that must not be softened. */
  @Test
  public void thehelpSaysTheAppCannotTellWhichRouteAninviteTook() {
    assertTrue("the help must state that the app cannot distinguish the routes. The bundle is "
            + "byte-identical either way, so any wording that suggests the app knows is a promise "
            + "the code cannot keep - which is why markKeyOutOfBand exists and is deliberately read "
            + "by nothing.",
        help.contains("cannot tell which route"));
  }

  /**
   * No phrasing that implies the app detects, marks or rewards an out-of-band invite.
   *
   * <p>A list rather than a judgement, because the failure here is a sentence someone adds later
   * that reads as reassurance. Each phrase is one an author reaching for encouragement would write.
   */
  @Test
  public void nowordingImpliesTheAppCanSeeHowAninviteArrived() {
    final List<String> forbidden = Arrays.asList(
        "marked as verified automatically",
        "the app will know",
        "will be shown as safe",
        "trusted automatically",
        "no need to compare");

    for (final String phrase : forbidden) {
      assertFalse("the help implies the app can see how an invite arrived, or that an out-of-band "
              + "one needs no comparison: \"" + phrase + "\". The bundle is identical either way; "
              + "the user's knowledge of the route is not available to the code.",
          help.toLowerCase().contains(phrase));
    }
  }

  /** And it still sends the user to the voice comparison, which is the check that does work. */
  @Test
  public void thehelpStillPointsAtTheVoiceComparison() {
    assertTrue("the out-of-band advice must not read as an alternative to comparing the number - "
        + "it is a way to avoid one exposure, not a replacement for the check",
        help.contains("comparing the security number by voice"));
  }
}
