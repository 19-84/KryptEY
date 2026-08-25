package com.amnesica.kryptey.inputmethod.signalprotocol.storage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * The API-28 branch itself, rather than the predicate that feeds it.
 *
 * <p>{@code needsApi28} was extracted so the refusal could be tested, and it is: four assertions
 * pin it as a pure function, and a fifth checks it against the ladder. All of that proves the
 * predicate answers correctly. None of it proves anything <em>consults</em> it.
 *
 * <p>That gap was measured, not guessed. Deleting the refusal outright — so that a pre-28 device
 * asking for StrongBox silently received a key with neither StrongBox nor unlocked-device-required
 * — left the full suite at 843 tests and 0 failures, and left {@code needsApi28} a production
 * method called only from tests. The reason is structural: the branch lives in {@code generate},
 * {@code generate} calls the real Keystore provider, and so every ladder test drives a fake
 * {@link AndroidKeystoreCryptoBox.KeystoreOps} and never reaches it. The seam that made the ladder
 * testable is the same seam that put this out of reach.
 *
 * <p>So the branch now takes a builder and is called on the production path.
 * {@code KeyGenParameterSpec.Builder} needs no Keystore, which is what lets these run.
 *
 * <p>Why it matters in the direction it does: a silent downgrade is worse than a failure. The
 * ladder logs the protections it believes it obtained, so the first rung would report success and
 * claim StrongBox for a key held in software — indistinguishable, afterwards, from the good
 * outcome. Refusing sends the ladder down to a rung that is honest about what it is.
 */
@RunWith(RobolectricTestRunner.class)
public class Api28ProtectionWiringTest {

  private static KeyGenParameterSpec.Builder builder() {
    return new KeyGenParameterSpec.Builder("test-alias",
        KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
        .setKeySize(256);
  }

  // ------------------------------------------------------------ below 28: refuse, never downgrade

  /** The case the surviving mutant made silent: StrongBox asked for, and unavailable. */
  @Test
  @Config(sdk = 26)
  public void apre28DeviceRefusesStrongBoxRatherThanQuietlyOmittingIt() {
    try {
      AndroidKeystoreCryptoBox.applyApi28Protections(builder(), true, false);
      fail("a pre-28 device cannot grant StrongBox, so it must refuse this candidate. Returning "
          + "normally hands the ladder a software key while it logs StrongBox - the one outcome "
          + "that is indistinguishable from success");
    } catch (IllegalStateException expected) {
      assertTrue("the refusal must name what could not be granted: " + expected.getMessage(),
          expected.getMessage().contains("API 28"));
    }
  }

  /** And the same for unlocked-device-required, which fails the same way for the same reason. */
  @Test
  @Config(sdk = 27)
  public void apre28DeviceRefusesUnlockedDeviceRequired() {
    try {
      AndroidKeystoreCryptoBox.applyApi28Protections(builder(), false, true);
      fail("unlocked-device-required needs API 28; omitting it silently means the key is usable "
          + "on a locked device while the ladder believes it is not");
    } catch (IllegalStateException expected) {
      // expected
    }
  }

  /**
   * But the last rung must still pass, or a pre-28 device could never mint a key at all.
   *
   * <p>The other half of the refusal. A branch that refused everything below 28 would be just as
   * wrong, and the app would fail to start on every API 26–27 device rather than fall back.
   */
  @Test
  @Config(sdk = 26)
  public void apre28DeviceStillAcceptsTheUnprotectedRung() {
    AndroidKeystoreCryptoBox.applyApi28Protections(builder(), false, false);
    // Reaching here is the assertion: the weakest rung asks for nothing that needs API 28.
  }

  // ------------------------------------------------------- 28 and above: actually apply them

  /**
   * On 28 the protections must reach the spec — which nothing checked either.
   *
   * <p>The mirror of the mutant above: a branch that refused correctly but never called the two
   * setters would leave every device on every rung with a plain key, and no test would have
   * noticed. Asserting on the built spec is what distinguishes "did not throw" from "did the work".
   */
  @Test
  @Config(sdk = 28)
  public void strongBoxAndUnlockedDeviceRequiredReachTheSpecOn28() {
    final KeyGenParameterSpec.Builder spec = builder();
    AndroidKeystoreCryptoBox.applyApi28Protections(spec, true, true);
    final KeyGenParameterSpec built = spec.build();

    assertTrue("StrongBox was requested and must be set on the spec, or the ladder logs a "
        + "protection the key does not have", built.isStrongBoxBacked());
    assertTrue("unlocked-device-required was requested and must be set on the spec",
        built.isUnlockedDeviceRequired());
  }

  /** And a rung that asks for neither must not acquire them by accident. */
  @Test
  @Config(sdk = 28)
  public void theUnprotectedRungGetsNeitherProtection() {
    final KeyGenParameterSpec.Builder spec = builder();
    AndroidKeystoreCryptoBox.applyApi28Protections(spec, false, false);
    final KeyGenParameterSpec built = spec.build();

    assertFalse("the weakest rung must not silently acquire StrongBox - the ladder descends to it "
        + "precisely when the device has refused StrongBox", built.isStrongBoxBacked());
    assertFalse(built.isUnlockedDeviceRequired());
  }

  /** Each protection is wired independently, so one cannot stand in for the other. */
  @Test
  @Config(sdk = 28)
  public void theTwoProtectionsAreWiredSeparately() {
    final KeyGenParameterSpec.Builder onlyStrongBox = builder();
    AndroidKeystoreCryptoBox.applyApi28Protections(onlyStrongBox, true, false);
    assertTrue(onlyStrongBox.build().isStrongBoxBacked());
    assertFalse("asking for StrongBox alone must not also demand an unlocked device",
        onlyStrongBox.build().isUnlockedDeviceRequired());

    final KeyGenParameterSpec.Builder onlyUnlocked = builder();
    AndroidKeystoreCryptoBox.applyApi28Protections(onlyUnlocked, false, true);
    assertTrue(onlyUnlocked.build().isUnlockedDeviceRequired());
    assertFalse("and the reverse - swapping the two setters must fail a test",
        onlyUnlocked.build().isStrongBoxBacked());
  }

  /**
   * The production path calls this, rather than keeping its own copy of the branch.
   *
   * <p>Everything above tests a static method. If {@code generate} stopped calling it and inlined
   * the branch again, all of it would keep passing while production drifted - which is exactly the
   * shape of the defect this file exists for.
   */
  @Test
  public void thegenerateMethodDelegatesToThisBranchRatherThanKeepingItsOwn() throws Exception {
    final String source = new String(java.nio.file.Files.readAllBytes(
        java.nio.file.Paths.get("src/main/java/com/amnesica/kryptey/inputmethod/signalprotocol/"
            + "storage/AndroidKeystoreCryptoBox.java")), java.nio.charset.StandardCharsets.UTF_8);

    final int inGenerate = source.indexOf("private SecretKey generate(");
    final int afterGenerate = source.indexOf("static void applyApi28Protections", inGenerate);
    assertTrue("expected to find generate() and applyApi28Protections in that order",
        inGenerate > 0 && afterGenerate > inGenerate);

    final String body = source.substring(inGenerate, afterGenerate);
    assertTrue("generate() must delegate to applyApi28Protections - the tests above cover that "
        + "method, and an inlined copy of the branch would be covered by nothing",
        body.contains("applyApi28Protections(spec, strongBox, requireUnlocked)"));
    assertEquals("and it must not carry its own SDK_INT branch alongside the delegation", -1,
        body.indexOf("VERSION_CODES.P"));
  }
}
