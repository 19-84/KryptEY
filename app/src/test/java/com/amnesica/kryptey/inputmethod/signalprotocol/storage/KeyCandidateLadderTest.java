package com.amnesica.kryptey.inputmethod.signalprotocol.storage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

/**
 * The order in which key protections are requested.
 *
 * <p>This is the security property of {@code AndroidKeystoreCryptoBox}'s ladder: ask for the
 * strongest protections the device will grant, and degrade only when it refuses. Getting the order
 * backwards would silently mint a weaker key on hardware that could have done better, and nothing
 * would report it - the log line says what was created, not what was possible.
 *
 * <p>It was previously nested loops welded to the Keystore calls, so no test could see it without a
 * device. The Keystore calls still need one; the ordering no longer does.
 *
 * <p>What this does NOT establish: that a key created with these flags actually has them. That
 * needs {@code KeyInfo} on real hardware, and the eleven instrumentation tests that would check it
 * have never executed anywhere - there is no {@code /dev/kvm} here and the CPU exposes no
 * virtualisation extensions, so an emulator cannot run at all.
 */
public class KeyCandidateLadderTest {

  @Test
  public void theStrongestCandidateIsTriedFirst() {
    final List<AndroidKeystoreCryptoBox.KeyCandidate> ladder =
        AndroidKeystoreCryptoBox.candidateLadder(true);

    assertTrue("the ladder must not be empty", ladder.size() > 0);
    assertTrue("StrongBox with unlocked-device-required must be tried first",
        ladder.get(0).strongBox && ladder.get(0).requireUnlocked);
  }

  @Test
  public void theWeakestCandidateIsTriedLast() {
    final List<AndroidKeystoreCryptoBox.KeyCandidate> ladder =
        AndroidKeystoreCryptoBox.candidateLadder(true);
    final AndroidKeystoreCryptoBox.KeyCandidate last = ladder.get(ladder.size() - 1);

    assertFalse("the last resort must be the weakest, or degradation is not a last resort",
        last.strongBox || last.requireUnlocked);
  }

  /** Unlocked-device-required is never surrendered to get StrongBox. */
  @Test
  public void unlockedDeviceRequiredIsGivenUpOnlyAfterStrongBox() {
    final List<AndroidKeystoreCryptoBox.KeyCandidate> ladder =
        AndroidKeystoreCryptoBox.candidateLadder(true);

    int firstWithoutUnlocked = -1;
    for (int i = 0; i < ladder.size(); i++) {
      if (!ladder.get(i).requireUnlocked) {
        firstWithoutUnlocked = i;
        break;
      }
    }

    assertTrue("some candidate must drop it eventually", firstWithoutUnlocked > 0);
    for (int i = 0; i < firstWithoutUnlocked; i++) {
      assertTrue("every earlier candidate must still require an unlocked device",
          ladder.get(i).requireUnlocked);
    }
    assertTrue("and StrongBox must have been tried without it first",
        ladder.get(firstWithoutUnlocked - 1).requireUnlocked);
  }

  /**
   * On a device with no secure lock screen, the unlocked-device rungs are skipped rather than
   * attempted. Attempting them deletes and recreates the alias for nothing, and the flag is
   * meaningless without a lock screen anyway.
   */
  @Test
  public void anInsecureDeviceSkipsTheUnlockedRungsEntirely() {
    final List<AndroidKeystoreCryptoBox.KeyCandidate> ladder =
        AndroidKeystoreCryptoBox.candidateLadder(false);

    for (final AndroidKeystoreCryptoBox.KeyCandidate candidate : ladder) {
      assertFalse("no candidate may require an unlocked device when there is no lock screen: "
          + candidate, candidate.requireUnlocked);
    }
    assertTrue("but StrongBox must still be tried first", ladder.get(0).strongBox);
    assertEquals("and only the two StrongBox variants remain", 2, ladder.size());
  }

  @Test
  public void everyCombinationIsTriedOnASecureDevice() {
    final List<AndroidKeystoreCryptoBox.KeyCandidate> ladder =
        AndroidKeystoreCryptoBox.candidateLadder(true);

    assertEquals("all four combinations must be attempted before giving up", 4, ladder.size());
    for (final boolean strongBox : new boolean[] {true, false}) {
      for (final boolean unlocked : new boolean[] {true, false}) {
        assertTrue("missing candidate strongBox=" + strongBox + " unlocked=" + unlocked,
            ladder.stream().anyMatch(c -> c.strongBox == strongBox
                && c.requireUnlocked == unlocked));
      }
    }
  }
}
