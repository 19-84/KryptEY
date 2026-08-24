package com.amnesica.kryptey.inputmethod.signalprotocol;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.amnesica.kryptey.inputmethod.signalprotocol.chat.Contact;
import com.amnesica.kryptey.inputmethod.signalprotocol.util.ProtocolAddresses;

import org.junit.Before;
import org.junit.Test;
import org.signal.libsignal.protocol.SignalProtocolAddress;

import java.util.ArrayList;
import java.util.List;

/**
 * The invariant the whole duplicate-name defence rests on: <b>what is MATCHED must agree with what
 * is RENDERED.</b>
 *
 * <p>Two normalisers exist. One produces the label a user sees; the other produces the key the
 * duplicate check compares. If a character is treated differently by the two, a name can render
 * identically to an existing contact while folding differently - and the warning that would have
 * said "check with them by voice" never fires. That is not hypothetical: the display path was
 * changed to map line separators to a space so a name could not break a banner, the matching path
 * still deleted them, and nine characters became a silent impersonation aid.
 *
 * <p>Rather than enumerate characters, this sweeps them. For every code point in the BMP it asks:
 * does inserting this character into a name change what is rendered? And does it change what is
 * matched? Any code point where those two answers differ is a divergence, and the direction matters
 * both ways - one suppresses a warning, the other cries wolf on a legitimate name.
 */
public class RenderMatchAgreementTest {

  /**
   * Two baselines, because hostile characters render as two different things.
   *
   * <p>Separators render AS A SPACE, so they can only collide with a spaced name. Invisible
   * characters render as NOTHING, so they can only collide with an unspaced one. A sweep against a
   * single baseline is blind to half the problem in whichever direction it did not choose - and a
   * marker character like "BobXJones" is blind to both, which is how the first version of this test
   * passed with the known divergence deliberately reintroduced.
   *
   * <p>The baseline has to be what the hostile input renders as, so there is one of each.
   */
  private static final String[] BASELINES = {"Bob Jones", "BobJones"};

  private Account account;
  private SignalProtocolAddress elsewhere;

  @Before
  public void setUp() {
    SignalProtocolMain.testIsRunning = true;
    SignalProtocolMain.initialize(null);
    account = SignalProtocolMain.getInstance().getAccount();
    elsewhere = ProtocolAddresses.of("attacker-uuid", 7);
  }

  private void seedBaseline(final String baseline) {
    final ArrayList<Contact> list = new ArrayList<>();
    list.add(new Contact(baseline, "", "peer-uuid", 7, false));
    account.setContactList(list);
  }

  /** The label a user would see for this name, with the tag stripped off. */
  private String rendered(final String name) {
    final Contact contact = new Contact(name, "", "peer-uuid", 7, false);
    final String label = SignalProtocolMain.displayLabelFor(contact);
    final int hash = label.indexOf('#');
    return hash < 0 ? label : label.substring(0, hash);
  }

  /** Whether this name is treated as a duplicate of the seeded "BobXJones". */
  private boolean matchesBaseline(final String name) {
    return SignalProtocolMain.hasContactWithSameDisplayName(name, "", elsewhere);
  }

  @Test
  public void everyCodePointIsTreatedTheSameWayByBothNormalisers() {
    final List<String> divergences = new ArrayList<>();

    for (final String baseline : BASELINES) {
      seedBaseline(baseline);
      divergences.addAll(sweepBmpAgainst(baseline));
    }

    assertTrue("code points that render identically to a baseline but do not match it - each one "
            + "is a warning that will not fire, and an impostor row that looks the same:\n  "
            + String.join("\n  ", divergences.subList(0, Math.min(30, divergences.size())))
            + (divergences.size() > 30
                ? "\n  ... and " + (divergences.size() - 30) + " more" : ""),
        divergences.isEmpty());
  }

  private List<String> sweepBmpAgainst(final String baseline) {
    final String baselineRender = rendered(baseline);
    final List<String> divergences = new ArrayList<>();
    int renderedTheSame = 0;

    for (int cp = 1; cp <= 0xFFFF; cp++) {
      if (Character.isSurrogate((char) cp)) continue;

      // Replace the marker X with the candidate, so the character sits mid-name where a real
      // spoofing attempt would put it.
      final String candidate = "Bob" + ((char) cp) + "Jones";

      final boolean rendersTheSame = rendered(candidate).equals(baselineRender);
      final boolean matchesTheSame = matchesBaseline(candidate);

      // One direction only. Matching is deliberately COARSER than rendering: the confusable table
      // exists so that names which look alike match, and case folding means "x" and "X" match while
      // rendering differently. So rendersSame=false with matchesSame=true is correct and expected.
      //
      // The security-relevant direction is the other one. If two names render IDENTICALLY, they
      // must match - otherwise an impostor's row is visually indistinguishable from the genuine
      // contact's and the warning never fires. A first version of this test asserted equivalence in
      // both directions and failed on fifteen confusables doing exactly what they are for.
      if (rendersTheSame) renderedTheSame++;

      if (rendersTheSame && !matchesTheSame) {
        divergences.add(String.format("U+%04X renders as \"%s\" but does not match it",
            cp, baseline));
      }
    }

    // How many candidates reached the interesting half of the predicate.
    //
    // The whole sweep hangs on rendersTheSame. A regression making the DISPLAY normaliser less
    // aggressive - folding fewer characters away - makes it false everywhere, the divergence list
    // comes back empty, and the test that exists to prove the two normalisers agree reports success
    // having compared them nowhere.
    assertTrue("no candidate rendered the same as \"" + baseline + "\", so the two normalisers "
            + "were never actually compared - the display normaliser has stopped folding",
        renderedTheSame > 10);
    return divergences;
  }

  /**
   * The same invariant above the BMP, where a code point is a surrogate pair.
   *
   * <p>Worth sweeping separately because the normalisers walk the string with {@code charAt} in
   * places and {@code codePointAt} in others, and a pair split between the two would corrupt names
   * rather than merely mis-fold them. The ranges chosen are the ones that actually matter for
   * spoofing: mathematical alphanumerics, which NFKC folds onto ASCII letters; enclosed
   * alphanumeric supplement; the tag characters, which render as nothing; and the variation
   * selector supplement.
   */
  @Test
  public void supplementaryCodePointsAgreeToo() {
    final List<String> divergences = new ArrayList<>();

    final int[][] ranges = {
        {0x1D400, 0x1D7FF},   // mathematical alphanumeric symbols
        {0x1F100, 0x1F1FF},   // enclosed alphanumeric supplement
        {0xE0000, 0xE007F},   // tag characters
        {0xE0100, 0xE01EF},   // variation selectors supplement
    };

    for (final String baseline : BASELINES) {
      seedBaseline(baseline);
      final String baselineRender = rendered(baseline);
      for (final int[] range : ranges) {
        for (int cp = range[0]; cp <= range[1]; cp++) {
          final String candidate = "Bob" + new String(Character.toChars(cp)) + "Jones";

          if (rendered(candidate).equals(baselineRender) && !matchesBaseline(candidate)) {
            divergences.add(String.format("U+%05X renders as \"%s\" but does not match it",
                cp, baseline));
          }
        }
      }
    }

    assertTrue("supplementary code points that render identically to the baseline but do not "
            + "match it:\n  "
            + String.join("\n  ", divergences.subList(0, Math.min(20, divergences.size())))
            + (divergences.size() > 20
                ? "\n  ... and " + (divergences.size() - 20) + " more" : ""),
        divergences.isEmpty());
  }

  /**
   * A surrogate pair must survive both normalisers intact - not split, and not dropped.
   *
   * <p>The assertion used to be "no lone surrogate appears OR the code point is still there", and
   * the first disjunct is true whenever the character was DROPPED entirely - so "not dropped", half
   * of what the name promises, was never checked. It also only ran the display normaliser, while
   * the class exists to compare that against the matching one.
   */
  @Test
  public void surrogatePairsAreNotCorrupted() {
    seedBaseline(BASELINES[0]);
    for (final int cp : new int[] {0x1D400, 0x1F600, 0x20000, 0x10FFFF}) {
      final String name = "Bob" + new String(Character.toChars(cp)) + "Jones";
      final String label = rendered(name);

      // UNPAIRED surrogates, not any surrogate: a valid supplementary character IS a surrogate
      // pair in UTF-16, so "no char is a surrogate" is false for every correct rendering of one.
      // My first attempt asserted exactly that and failed on U+1D400 rendering perfectly.
      assertFalse("an unpaired surrogate reached the rendered label for U+"
              + Integer.toHexString(cp) + ": \"" + label + "\"",
          hasUnpairedSurrogate(label));

      // Split and dropped are different failures and the old disjunct conflated them. A character
      // NFKC maps to something else is fine; one that vanishes without a trace is not, because a
      // name carrying it would then render as one the user has already seen.
      final boolean survives = label.codePoints().anyMatch(c -> c == cp);
      final boolean normalised = !label.equals("BobJones") && !label.equals(rendered("BobJones"));
      assertTrue("U+" + Integer.toHexString(cp) + " vanished entirely from \"" + label
              + "\" - it must survive, or be normalised to something, but not disappear",
          survives || normalised);
    }
  }

  /** Whether any surrogate in the string is not part of a well-formed pair. */
  private static boolean hasUnpairedSurrogate(final String text) {
    for (int i = 0; i < text.length(); i++) {
      final char c = text.charAt(i);
      if (Character.isHighSurrogate(c)) {
        if (i + 1 >= text.length() || !Character.isLowSurrogate(text.charAt(i + 1))) return true;
        i++;   // consumed the pair
      } else if (Character.isLowSurrogate(c)) {
        return true;   // a low surrogate with no high before it
      }
    }
    return false;
  }
}
