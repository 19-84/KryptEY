package com.amnesica.kryptey.inputmethod.latin.e2ee;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The three arms an envelope can take must carry the same controls.
 *
 * <p>{@code SignalProtocolMain.getMessageType} dispatches on <b>field presence alone</b>: a bundle
 * with no message, a message with no bundle, or both. The sender fills the fields, so the sender
 * picks the arm — appending arbitrary bytes as a ciphertext moves an envelope from one to another at
 * no cost and with no signature to forge.
 *
 * <p>That makes a control on one arm not a control. This has now happened four times in this file,
 * each time with a different notice and each time found by a review round rather than by anything
 * here: the post-rejection warning, the lost-write notice, the banner repaint, and the rotation
 * report. The fourth is what prompted this file, and writing it immediately turned up a fifth — the
 * plain signal-message arm was missing the rotation report too, because that arm carries no bundle
 * and it did not look like a rotation. It writes the same session state.
 *
 * <p>So the arms are compared to each other rather than each checked against a list someone
 * maintains. A control added to one and not the others fails here, naming the ones that lack it.
 * Anything genuinely arm-specific goes in {@link #DELIBERATELY_UNEVEN} with the argument.
 */
public class EveryEnvelopeArmCarriesTheSameControlsTest {

  private static final String[] ARMS = {
      "processSignalMessage", "processPreKeyResponse", "processUpdatedPreKeyResponse"};

  /**
   * Controls that genuinely belong to some arms and not others, with the reason.
   *
   * <p>Each entry is "control -> the arms that legitimately lack it".
   */
  private static final java.util.Map<String, Set<String>> DELIBERATELY_UNEVEN =
      new java.util.HashMap<>();

  static {
    // Nothing yet. The list exists so that the next genuine asymmetry is argued in writing rather
    // than achieved by deleting an assertion.
  }

  private static String source() throws IOException {
    Path here = Paths.get("").toAbsolutePath();
    while (here != null && !Files.exists(here.resolve("gradle/verification-metadata.xml"))) {
      here = here.getParent();
    }
    assertTrue("the repository root must be findable", here != null);
    return new String(Files.readAllBytes(here.resolve(
        "app/src/main/java/com/amnesica/kryptey/inputmethod/latin/e2ee/E2EEStripView.java")),
        StandardCharsets.UTF_8);
  }

  private static String bodyOf(final String method) throws IOException {
    final String all = source();
    final int start = all.indexOf("private void " + method + "(");
    assertTrue("arm not found, so this test is passing by absence: " + method, start > 0);
    int depth = 0;
    int i = all.indexOf('{', start);
    final int bodyStart = i;
    while (i < all.length()) {
      if (all.charAt(i) == '{') depth++;
      else if (all.charAt(i) == '}') {
        depth--;
        if (depth == 0) break;
      }
      i++;
    }
    return all.substring(bodyStart, i);
  }

  /**
   * The security controls a body calls.
   *
   * <p>Matched by naming convention, which is what makes this maintainable: every notice helper on
   * this surface is named {@code warnIf…}, {@code reportIf…} or {@code cautionThat…}. A new one
   * following the convention is compared automatically; one that does not is invisible here, which
   * is the honest limit of a scan like this.
   *
   * <p><b>And one that is named by hand, because the convention could not see it.</b> A review round
   * found the plain-message arm giving the recipient back when nothing decrypted while the two
   * bundle arms did not — and pointed out that this scan was structurally blind to it, since
   * the undo matches no prefix. The union was therefore even and the file
   * reported parity it did not have. The naming convention is a heuristic for finding controls, not
   * a definition of one: a control the scan cannot see is exactly the asymmetry an attacker gets to
   * keep, and which arm handles an envelope costs it one appended field to choose.
   */
  private static Set<String> controlsIn(final String body) {
    final Set<String> found = new TreeSet<>();
    final Matcher m = Pattern.compile(
        "\\b(warnIf\\w+|reportIf\\w+|cautionThat\\w+|giveTheRecipientBack)\\s*\\(")
        .matcher(stripComments(body));
    while (m.find()) found.add(m.group(1));
    return found;
  }

  /** Comments describe controls constantly on this surface; only calls count. */
  private static String stripComments(final String body) {
    return body.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("//[^\n]*", " ");
  }

  @Test
  public void everyArmCallsTheControlsTheOthersDo() throws IOException {
    final java.util.Map<String, Set<String>> byArm = new java.util.LinkedHashMap<>();
    for (final String arm : ARMS) byArm.put(arm, controlsIn(bodyOf(arm)));

    final Set<String> union = new LinkedHashSet<>();
    for (final Set<String> controls : byArm.values()) union.addAll(controls);
    assertTrue("no controls were found on any arm; the naming convention this scan relies on has "
        + "changed and it is now checking nothing", union.size() >= 2);

    final Set<String> gaps = new LinkedHashSet<>();
    for (final String control : union) {
      for (final String arm : ARMS) {
        if (byArm.get(arm).contains(control)) continue;
        final Set<String> allowed = DELIBERATELY_UNEVEN.get(control);
        if (allowed != null && allowed.contains(arm)) continue;
        gaps.add(control + " is missing from " + arm);
      }
    }

    assertEquals("a control is on some envelope arms and not others. getMessageType dispatches on "
        + "field presence alone, so the sender chooses the arm - appending bytes as a ciphertext "
        + "moves an envelope from one to another at no cost. A control on one arm is not a "
        + "control. Add it to the others, or add it to DELIBERATELY_UNEVEN with the argument:\n"
        + String.join("\n", gaps), 0, gaps.size());
  }

  /** And the exemption list cannot name arms or controls that no longer exist. */
  @Test
  public void theexemptionListNamesOnlyRealArmsAndControls() throws IOException {
    final Set<String> arms = new HashSet<>(Arrays.asList(ARMS));
    final Set<String> union = new LinkedHashSet<>();
    for (final String arm : ARMS) union.addAll(controlsIn(bodyOf(arm)));

    final Set<String> stale = new LinkedHashSet<>();
    for (final java.util.Map.Entry<String, Set<String>> entry : DELIBERATELY_UNEVEN.entrySet()) {
      if (!union.contains(entry.getKey())) stale.add("control " + entry.getKey());
      for (final String arm : entry.getValue()) {
        if (!arms.contains(arm)) stale.add("arm " + arm);
      }
    }
    assertEquals("the exemption list names something that no longer exists; remove it rather than "
        + "leave it suggesting a decision with no subject:\n" + String.join("\n", stale),
        0, stale.size());
  }
}
