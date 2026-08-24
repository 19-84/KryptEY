package com.amnesica.kryptey.inputmethod.signalprotocol;

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
   * The baseline is "Bob Jones" with an ordinary space, deliberately.
   *
   * <p>A marker character would defeat the sweep: the separators this exists to catch all render AS
   * a space, so against a baseline containing anything else they render differently and are never
   * examined. A first version used "BobXJones" and passed even with the known divergence
   * reintroduced. The baseline has to be what the hostile input renders as.
   */
  private static final String BASELINE = "Bob Jones";

  private Account account;
  private SignalProtocolAddress elsewhere;

  @Before
  public void setUp() {
    SignalProtocolMain.testIsRunning = true;
    SignalProtocolMain.initialize(null);
    account = SignalProtocolMain.getInstance().getAccount();
    elsewhere = ProtocolAddresses.of("attacker-uuid", 7);
    seedBaseline();
  }

  private void seedBaseline() {
    final ArrayList<Contact> list = new ArrayList<>();
    list.add(new Contact(BASELINE, "", "peer-uuid", 7, false));
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
    final String baselineRender = rendered(BASELINE);
    final List<String> divergences = new ArrayList<>();

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
      if (rendersTheSame && !matchesTheSame) {
        divergences.add(String.format("U+%04X rendersSame=%b matchesSame=%b",
            cp, rendersTheSame, matchesTheSame));
      }
    }

    assertTrue("code points that render identically to the baseline but do not match it - each one "
            + "is a warning that will not fire, and an impostor row that looks the same:\n  "
            + String.join("\n  ", divergences.subList(0, Math.min(30, divergences.size())))
            + (divergences.size() > 30
                ? "\n  ... and " + (divergences.size() - 30) + " more" : ""),
        divergences.isEmpty());
  }
}
