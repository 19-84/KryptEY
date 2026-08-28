package com.amnesica.kryptey.inputmethod.signalprotocol.storage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;

/**
 * How the master key is resolved - the decisions, not the Keystore calls.
 *
 * <p>{@code AndroidKeystoreCryptoBox} was executed by zero tests. Eleven instrumentation tests are
 * written and compile, but need hardware or a KVM runner, so nothing here ran at all. The Keystore
 * calls genuinely do need a device; what did NOT need one, and was untestable only because it sat
 * welded to them, is the decision logic around them - and that is the part that can destroy a
 * user's identity:
 *
 * <ul>
 *   <li>The alias is gone but ciphertext remains. Generating a replacement would make every stored
 *       blob permanently undecryptable while looking like a clean start-up, and the caller would
 *       then overwrite the identity key with a fresh account. This must refuse.
 *   <li>The ladder must be walked strongest-first, so a device that can do StrongBox gets it.
 *   <li>A candidate that fails its self-test must have its alias cleared before the next attempt,
 *       or the half-created alias shadows it and every subsequent rung fails too.
 * </ul>
 */
public class KeyResolutionTest {

  private static SecretKey aKey() {
    try {
      final KeyGenerator generator = KeyGenerator.getInstance("AES");
      generator.init(256);
      return generator.generateKey();
    } catch (NoSuchAlgorithmException e) {
      throw new AssertionError(e);
    }
  }

  /** Records what was asked of the Keystore, in order. */
  private static final class RecordingOps implements AndroidKeystoreCryptoBox.KeystoreOps {
    SecretKey existing;
    boolean deviceSecure = true;
    boolean deviceLocked = false;
    /** Candidates that will fail their self-test, as "strongBox/requireUnlocked". */
    final List<String> selfTestFailures = new ArrayList<>();
    /** Candidates whose generate() will throw. */
    final List<String> generateFailures = new ArrayList<>();

    final List<String> generated = new ArrayList<>();
    final List<String> selfTested = new ArrayList<>();
    final List<String> events = new ArrayList<>();
    int deletions;

    private static String name(final boolean strongBox, final boolean requireUnlocked) {
      return strongBox + "/" + requireUnlocked;
    }

    @Override
    public SecretKey load() {
      return existing;
    }

    @Override
    public boolean isDeviceSecure() {
      return deviceSecure;
    }

    @Override
    public boolean isDeviceLocked() {
      return deviceLocked;
    }

    @Override
    public SecretKey generate(final boolean strongBox, final boolean requireUnlocked)
        throws Exception {
      final String which = name(strongBox, requireUnlocked);
      generated.add(which);
      events.add("generate:" + which);
      if (generateFailures.contains(which)) throw new IllegalStateException("cannot generate");
      return aKey();
    }

    @Override
    public void selfTest(final SecretKey candidate) throws Exception {
      final String which = generated.get(generated.size() - 1);
      selfTested.add(which);
      events.add("selfTest:" + which);
      if (selfTestFailures.contains(which)) {
        throw new IllegalStateException("self-test failed for " + which);
      }
    }

    @Override
    public void deleteAlias() {
      deletions++;
      events.add("delete");
    }
  }

  // ---------------------------------------------------------- the dangerous case

  /**
   * The one that permanently destroys a user's identity if it goes wrong. An absent key with
   * ciphertext still on disk must refuse, not generate.
   */
  @Test
  public void anabsentKeyWithExistingDataRefusesToGenerate() {
    final RecordingOps ops = new RecordingOps();
    ops.existing = null;

    final StorageCryptoException refused = assertThrows(StorageCryptoException.class,
        () -> AndroidKeystoreCryptoBox.resolve(ops, true));

    assertTrue("the refusal must say why, so the caller can tell it from an ordinary failure: "
            + refused.getMessage(),
        refused.getMessage().contains("refusing to generate"));
    assertTrue("nothing may be generated when data exists: " + ops.generated,
        ops.generated.isEmpty());
    assertEquals("and nothing may be deleted either", 0, ops.deletions);
  }

  /** With no existing data, the same absent key is a first run and must generate. */
  @Test
  public void anabsentKeyWithNoDataGenerates() throws Exception {
    final RecordingOps ops = new RecordingOps();

    assertTrue("a first run must produce a key",
        AndroidKeystoreCryptoBox.resolve(ops, false) != null);
    assertFalse(ops.generated.isEmpty());
  }

  /** An existing key is returned untouched, whatever the data flag says. */
  @Test
  public void anexistingKeyIsReturnedWithoutGenerating() throws Exception {
    for (final boolean hasData : new boolean[] {true, false}) {
      final RecordingOps ops = new RecordingOps();
      ops.existing = aKey();

      assertSame("the stored key must be returned as-is",
          ops.existing, AndroidKeystoreCryptoBox.resolve(ops, hasData));
      assertTrue("an existing key must not be regenerated", ops.generated.isEmpty());
      assertEquals(0, ops.deletions);
    }
  }

  // ---------------------------------------------------------- the ladder

  @Test
  public void thestrongestCandidateIsTriedFirst() throws Exception {
    final RecordingOps ops = new RecordingOps();

    AndroidKeystoreCryptoBox.resolve(ops, false);

    assertEquals("the first thing asked for must be StrongBox with unlocked-device-required",
        "true/true", ops.generated.get(0));
    assertEquals("and nothing weaker may be tried once it succeeds", 1, ops.generated.size());
  }

  @Test
  public void afailedSelfTestFallsThroughToTheNextRung() throws Exception {
    final RecordingOps ops = new RecordingOps();
    ops.selfTestFailures.add("true/true");

    AndroidKeystoreCryptoBox.resolve(ops, false);

    assertEquals("expected the ladder to step down exactly one rung",
        List.of("true/true", "false/true"), ops.generated);
  }

  /**
   * The alias must be cleared between attempts. A half-created alias shadows the next generate, so
   * without this the first failure makes every remaining rung fail too - the ladder collapses to a
   * single attempt while still looking like it tried them all.
   */
  @Test
  public void afailedCandidateClearsTheAliasBeforeTheNextAttempt() throws Exception {
    final RecordingOps ops = new RecordingOps();
    ops.selfTestFailures.add("true/true");

    AndroidKeystoreCryptoBox.resolve(ops, false);

    final int failure = ops.events.indexOf("selfTest:true/true");
    final int delete = ops.events.indexOf("delete");
    final int nextGenerate = ops.events.indexOf("generate:false/true");

    assertTrue("a delete must happen after the failure", delete > failure);
    assertTrue("and before the next generate: " + ops.events, delete < nextGenerate);
  }

  @Test
  public void agenerateFailureAlsoStepsDownAndClears() throws Exception {
    final RecordingOps ops = new RecordingOps();
    ops.generateFailures.add("true/true");

    AndroidKeystoreCryptoBox.resolve(ops, false);

    assertEquals(List.of("true/true", "false/true"), ops.generated);
    assertEquals("the alias must be cleared even when generate itself failed", 1, ops.deletions);
  }

  /**
   * A locked device makes the ladder DEFER, not degrade.
   *
   * <p>The two rungs that bind the key to the screen lock are certified by a real seal/open, and
   * that is exactly the operation such a key refuses while the device is locked. So generating the
   * first key on a locked device failed both of them and stepped down to a rung with no lock
   * binding at all - permanently, because the ladder is walked once, at generation, and never
   * revisited. A device that fully supports lock-bound storage would spend the rest of the install
   * with storage readable whenever the app runs, and the only trace was a log line.
   *
   * <p>Refusing is safe at this point and nowhere else: the ladder is reached only when the alias is
   * absent and there is no existing data, so deferring costs a keyboard raise. The app already
   * renders that state and tells the user it clears after an unlock. Repairing a weak key later
   * would mean deleting it, which is the destructive direction with no way back.
   */
  @Test
  public void alockedDeviceDefersRatherThanFallingBackToAnunboundKey() {
    final RecordingOps ops = new RecordingOps();
    ops.deviceLocked = true;
    ops.selfTestFailures.add("true/true");
    ops.selfTestFailures.add("false/true");

    final StorageCryptoException refused = assertThrows("a locked device must defer",
        StorageCryptoException.class, () -> AndroidKeystoreCryptoBox.resolve(ops, false));

    assertTrue("the refusal must say it is deferring rather than sounding like a broken device: "
        + refused.getMessage(), refused.getMessage().contains("locked"));
    assertEquals("and it must not have tried a rung with no lock binding",
        List.of("true/true"), ops.generated);
  }

  /**
   * But an UNLOCKED device that genuinely cannot honour the strong rungs still steps down.
   *
   * <p>The floor. Without it the test above is satisfied by a build that refuses to generate a key
   * at all when anything fails, which would leave a device that simply lacks StrongBox with no
   * storage rather than with weaker storage.
   */
  @Test
  public void anunlockedDeviceThatCannotHonourArungStillStepsDown() throws Exception {
    final RecordingOps ops = new RecordingOps();
    ops.deviceLocked = false;
    ops.selfTestFailures.add("true/true");

    AndroidKeystoreCryptoBox.resolve(ops, false);

    assertEquals("a device that cannot do StrongBox must still get a lock-bound key",
        List.of("true/true", "false/true"), ops.generated);
  }

  /**
   * A device with no secure lock screen cannot honour unlocked-device-required, so those rungs are
   * skipped rather than attempted and failed - attempting them would delete and recreate the alias
   * for nothing.
   */
  @Test
  public void aninsecureDeviceSkipsTheUnlockedRungsEntirely() throws Exception {
    final RecordingOps ops = new RecordingOps();
    ops.deviceSecure = false;

    AndroidKeystoreCryptoBox.resolve(ops, false);

    assertEquals("the first candidate on an insecure device must not require unlocking",
        "true/false", ops.generated.get(0));
    for (final String attempted : ops.generated) {
      assertFalse("an insecure device must never be asked for unlocked-device-required: "
          + ops.generated, attempted.endsWith("/true"));
    }
  }

  @Test
  public void everyCandidateFailingThrowsAndCarriesTheLastCause() {
    final RecordingOps ops = new RecordingOps();
    for (final boolean strongBox : new boolean[] {true, false}) {
      for (final boolean unlocked : new boolean[] {true, false}) {
        ops.selfTestFailures.add(strongBox + "/" + unlocked);
      }
    }

    final StorageCryptoException thrown = assertThrows(StorageCryptoException.class,
        () -> AndroidKeystoreCryptoBox.resolve(ops, false));

    assertTrue("the failure must name the situation: " + thrown.getMessage(),
        thrown.getMessage().contains("no usable Keystore configuration"));
    assertTrue("and must carry why the last candidate was rejected, or the user is told nothing",
        thrown.getCause() != null && thrown.getCause().getMessage().contains("key candidate"));
    assertEquals("every rung must have been tried", 4, ops.generated.size());
  }

  /** Every candidate that is generated must also be self-tested before it is trusted. */
  @Test
  public void everyGeneratedCandidateIsSelfTested() throws Exception {
    final RecordingOps ops = new RecordingOps();
    ops.selfTestFailures.add("true/true");
    ops.selfTestFailures.add("false/true");

    AndroidKeystoreCryptoBox.resolve(ops, false);

    assertEquals("a key must never be returned without a round trip through the real seal/open "
            + "path first", ops.generated, ops.selfTested);
  }
}
