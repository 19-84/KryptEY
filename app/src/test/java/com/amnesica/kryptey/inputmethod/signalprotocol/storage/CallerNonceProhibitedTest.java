package com.amnesica.kryptey.inputmethod.signalprotocol.storage;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.security.AlgorithmParameters;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.Key;
import java.security.Provider;
import java.security.SecureRandom;
import java.security.Security;
import java.security.spec.AlgorithmParameterSpec;

import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.CipherSpi;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.ShortBufferException;

/**
 * Makes the one Keystore behaviour that a desktop JVM does not share reproducible here.
 *
 * <p>An Android Keystore AES key is created with {@code randomizedEncryptionRequired = true}, so it
 * rejects a caller-supplied IV outright with {@code CALLER_NONCE_PROHIBITED}. A desktop JCE key
 * accepts one happily. That single difference already shipped a bug once: {@code seal()} passed its
 * own {@code GCMParameterSpec} and would have thrown on every call on every real device, while all
 * of the JVM crypto tests passed — and the class's own self-test missed it too, because it used a
 * call shape production never used.
 *
 * <p>The instrumentation tests that would catch a recurrence cannot run in this environment: there
 * is no {@code /dev/kvm} and the host CPU exposes no virtualisation extensions at all, so an
 * emulator is not merely slow but impossible. Rather than leave the property untested, this
 * registers a JCE provider that imposes the Keystore's rule — reject any {@code ENCRYPT_MODE} init
 * that carries parameters — and runs the real {@code seal}/{@code open} against it.
 *
 * <p>So a regression that reintroduces a caller-supplied nonce now fails on the JVM, where it is
 * cheap to notice, instead of only on a device nobody can run here.
 */
public class CallerNonceProhibitedTest {

  private static final String TRANSFORM = "AES/GCM/NoPadding";
  private static final byte[] AAD = "kryptey/storage/v2/PROTOCOL_STORE".getBytes(
      StandardCharsets.UTF_8);

  private Provider installed;
  private static Provider platform;

  /**
   * A key built from raw bytes. {@code KeyGenerator.getInstance("AES")} lives in SunJCE, which this
   * test de-registers, so the usual helper is unavailable here — and a {@code SecretKeySpec} needs
   * no provider at all.
   */
  private static javax.crypto.SecretKey rawKey() {
    final byte[] material = new byte[32];
    new SecureRandom().nextBytes(material);
    return new javax.crypto.spec.SecretKeySpec(material, "AES");
  }

  private final java.util.List<Provider> displaced = new java.util.ArrayList<>();

  /**
   * Installs the Keystore-like provider as the ONLY one able to serve AES/GCM.
   *
   * <p>Displacing the others is not tidiness, it is the whole mechanism. The JCE uses delayed
   * provider selection: with no provider named, {@code Cipher.getInstance} defers the choice to
   * {@code init()}, and {@code Cipher.chooseProvider} catches {@code Exception} from a candidate's
   * init and moves on to the next one. So while any other AES/GCM provider is registered, a refusal
   * here — checked or unchecked — is silently treated as "try someone else", SunJCE accepts the
   * caller nonce, and the test passes while proving nothing.
   *
   * <p>On a device there is no fallback: an AndroidKeyStore key can only be used by the
   * AndroidKeyStore provider. Removing the alternatives is what reproduces that here.
   */
  @Before
  public void installKeystoreLikeProvider() {
    if (platform == null) platform = Security.getProvider("SunJCE");
    assertNotNull("no SunJCE to delegate to", platform);

    // Check BOTH registration shapes. A provider may register the full transformation
    // ("Cipher.AES/GCM/NoPadding") or just the algorithm ("Cipher.AES") with supported modes and
    // paddings. SunJCE does the latter, so matching only the transformation displaces nothing and
    // leaves the fallback intact - which is exactly how the first version of this test managed to
    // pass while proving nothing.
    for (final Provider p : Security.getProviders()) {
      if (p.getService("Cipher", TRANSFORM) != null || p.getService("Cipher", "AES") != null) {
        displaced.add(p);
        Security.removeProvider(p.getName());
      }
    }
    assertTrue("no AES/GCM provider was displaced; the fallback would still be reachable",
        !displaced.isEmpty());
    installed = new KeystoreLikeProvider();
    Security.insertProviderAt(installed, 1);
  }

  @After
  public void restoreProviders() {
    if (installed != null) Security.removeProvider(installed.getName());
    for (final Provider p : displaced) Security.addProvider(p);
    displaced.clear();
  }

  /** Sanity: the provider really is the one being used, and it really does forbid caller nonces. */
  @Test
  public void theProviderIsInstalledAndRejectsACallerSuppliedNonce() throws Exception {
    final Cipher cipher = Cipher.getInstance(TRANSFORM);
    assertTrue("the Keystore-like provider is not being selected; this test would prove nothing",
        cipher.getProvider() instanceof KeystoreLikeProvider);

    final javax.crypto.SecretKey key = rawKey();
    final byte[] nonce = new byte[12];
    new SecureRandom().nextBytes(nonce);

    try {
      cipher.init(Cipher.ENCRYPT_MODE, key, new javax.crypto.spec.GCMParameterSpec(128, nonce));
      org.junit.Assert.fail("a caller-supplied nonce must be refused, as on a Keystore key");
    } catch (InvalidAlgorithmParameterException expected) {
      assertTrue(expected.getMessage(), expected.getMessage().contains("CALLER_NONCE_PROHIBITED"));
    }
  }

  /**
   * The actual guard: the production seal/open path must work against a key that forbids caller
   * nonces. If someone reintroduces a {@code GCMParameterSpec} on the encrypt path, this fails.
   */
  @Test
  public void sealAndOpenWorkAgainstAKeyThatForbidsCallerNonces() throws Exception {
    final GcmCryptoBox box = new JvmGcmCryptoBox(rawKey());
    final byte[] plaintext = "the identity key pair".getBytes(StandardCharsets.UTF_8);

    final byte[] sealed = box.seal(plaintext, AAD);

    assertNotNull(sealed);
    assertArrayEquals("round trip failed against a Keystore-like key",
        plaintext, box.open(sealed, AAD));
  }

  /** And the nonce is still fresh per seal - the provider must supply one, not omit it. */
  @Test
  public void theProviderSuppliedNonceIsStillFreshPerSeal() throws Exception {
    final GcmCryptoBox box = new JvmGcmCryptoBox(rawKey());
    final java.util.Set<String> nonces = new java.util.HashSet<>();

    for (int i = 0; i < 64; i++) {
      final byte[] sealed = box.seal(new byte[] {1, 2, 3}, AAD);
      final byte[] nonce = java.util.Arrays.copyOfRange(sealed, 1, 1 + GcmCryptoBox.NONCE_BYTES);
      assertTrue("nonce repeated at seal " + i + " when the provider chose it",
          nonces.add(java.util.Arrays.toString(nonce)));
    }
  }

  // ------------------------------------------------------------- the provider

  /** A provider whose AES/GCM cipher imposes the Android Keystore's caller-nonce rule. */
  private static final class KeystoreLikeProvider extends Provider {
    KeystoreLikeProvider() {
      super("KryptEYKeystoreLike", 1.0d, "Imposes CALLER_NONCE_PROHIBITED for tests");
      put("Cipher." + TRANSFORM, NoCallerNonceCipherSpi.class.getName());
    }
  }

  /**
   * Delegates everything to the platform implementation, except that an {@code ENCRYPT_MODE} init
   * carrying parameters is refused — which is what a {@code randomizedEncryptionRequired} Keystore
   * key does.
   */
  public static final class NoCallerNonceCipherSpi extends CipherSpi {

    private final Cipher delegate;

    public NoCallerNonceCipherSpi() {
      try {
        // Bind to the captured Provider INSTANCE. Looking it up by name would fail, because it has
        // been de-registered so that nothing can fall back to it - and looking it up without a
        // provider would re-enter this class.
        delegate = Cipher.getInstance(TRANSFORM, platform);
      } catch (Exception e) {
        throw new IllegalStateException("no platform AES/GCM to delegate to", e);
      }
    }

    /**
     * Refuses a caller-supplied nonce, the way a {@code randomizedEncryptionRequired} key does.
     *
     * <p>A real Keystore key throws {@code InvalidAlgorithmParameterException}, and the obvious
     * thing here is to do the same. It does not work, and the reason is worth knowing: when
     * {@code Cipher.getInstance} is called without naming a provider, the JCE uses <b>delayed
     * provider selection</b> — the choice is deferred to {@code init()}, and if the chosen
     * provider's {@code init} throws {@code InvalidKeyException} or
     * {@code InvalidAlgorithmParameterException}, the JCE quietly moves on to the next provider.
     * So a faithful checked exception here just falls through to SunJCE, which accepts the nonce,
     * and the test passes while proving nothing. (The first version of this test did exactly that.
     * Its sanity check passed only because calling {@code getProvider()} forces selection early.)
     *
     * <p>On a device there is no fallback, because the key is bound to the AndroidKeyStore provider
     * and no other provider can use it.
     *
     * <p>The exception thrown here is CHECKED - {@code InvalidAlgorithmParameterException} - which
     * is what a real Keystore raises, and the paragraph above used to claim it was unchecked "on
     * purpose". It is not, and the distinction matters: a checked exception from a candidate
     * provider IS treated as "try someone else". What makes this test work is the displacement loop
     * in {@code installKeystoreLikeProvider}, which removes every other provider offering this
     * transform so there is nobody to fall back to. A maintainer trusting the old wording would
     * have read that loop as belt-and-braces and deleted it, and the test would have gone on
     * passing while proving nothing.
     */
    @SuppressWarnings("unused")
    private static void refuseCallerNonce(final int opmode, final Object params)
        throws InvalidAlgorithmParameterException {
      if (opmode == Cipher.ENCRYPT_MODE && params != null) {
        throw new InvalidAlgorithmParameterException(
            "CALLER_NONCE_PROHIBITED: this key requires a provider-generated IV");
      }
    }

    @Override protected void engineSetMode(final String mode) { }
    @Override protected void engineSetPadding(final String padding) throws NoSuchPaddingException { }
    @Override protected int engineGetBlockSize() { return delegate.getBlockSize(); }
    @Override protected int engineGetOutputSize(final int len) { return delegate.getOutputSize(len); }
    @Override protected byte[] engineGetIV() { return delegate.getIV(); }
    @Override protected AlgorithmParameters engineGetParameters() { return delegate.getParameters(); }

    @Override
    protected void engineInit(final int opmode, final Key key, final SecureRandom random)
        throws InvalidKeyException {
      delegate.init(opmode, key, random);
    }

    @Override
    protected void engineInit(final int opmode, final Key key, final AlgorithmParameterSpec params,
        final SecureRandom random) throws InvalidKeyException, InvalidAlgorithmParameterException {
      refuseCallerNonce(opmode, params);
      delegate.init(opmode, key, params, random);
    }

    @Override
    protected void engineInit(final int opmode, final Key key, final AlgorithmParameters params,
        final SecureRandom random) throws InvalidKeyException, InvalidAlgorithmParameterException {
      refuseCallerNonce(opmode, params);
      delegate.init(opmode, key, params, random);
    }

    @Override protected void engineUpdateAAD(final byte[] src, final int off, final int len) {
      delegate.updateAAD(src, off, len);
    }

    @Override protected void engineUpdateAAD(final java.nio.ByteBuffer src) {
      delegate.updateAAD(src);
    }

    @Override protected byte[] engineUpdate(final byte[] in, final int off, final int len) {
      return delegate.update(in, off, len);
    }

    @Override
    protected int engineUpdate(final byte[] in, final int off, final int len, final byte[] out,
        final int outOff) throws ShortBufferException {
      return delegate.update(in, off, len, out, outOff);
    }

    @Override
    protected byte[] engineDoFinal(final byte[] in, final int off, final int len)
        throws IllegalBlockSizeException, BadPaddingException {
      return delegate.doFinal(in, off, len);
    }

    @Override
    protected int engineDoFinal(final byte[] in, final int off, final int len, final byte[] out,
        final int outOff)
        throws ShortBufferException, IllegalBlockSizeException, BadPaddingException {
      return delegate.doFinal(in, off, len, out, outOff);
    }
  }

  /**
   * The displacement loop is what makes this test work, so its absence must be visible.
   *
   * <p>{@code Cipher} defers provider selection until {@code init()} and
   * {@code chooseProvider} treats an exception from a candidate as "try someone else". Inserting
   * our provider at position 1 is therefore not enough - SunJCE would quietly accept the caller
   * nonce and the test would pass while proving nothing. Removing every other provider offering
   * this transform is the part doing the work.
   */
  @Test
  public void nothingElseOffersThisTransformWhileTheTestRuns() {
    int offering = 0;
    for (final java.security.Provider p : Security.getProviders()) {
      if (p.getService("Cipher", TRANSFORM) != null) offering++;
    }

    assertEquals("exactly one provider may offer " + TRANSFORM + " during this test, or a caller "
            + "nonce would be quietly accepted by the fallback and the refusal never exercised",
        1, offering);
  }
}
