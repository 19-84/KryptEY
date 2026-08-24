package com.amnesica.kryptey.inputmethod.signalprotocol.storage;

import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.security.NoSuchAlgorithmException;
import java.security.Provider;
import java.security.Security;
import java.util.ArrayList;
import java.util.List;

import javax.crypto.Cipher;
import javax.crypto.CipherSpi;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;

/**
 * A provider that chooses a GCM nonce length this envelope cannot carry must be refused.
 *
 * <p>{@code GcmCryptoBox.seal} checks {@code cipher.getIV().length} against the fixed-width nonce
 * field in the envelope. Nothing exercised it - no fixture built a provider returning a
 * wrong-length IV, so deleting the guard left the whole suite green.
 *
 * <p>It is not an equivalent mutant. Without the check a short IV throws
 * {@code ArrayIndexOutOfBoundsException} out of the envelope assembly, and a long one is silently
 * truncated into the fixed field - producing blobs this class can never open again. Vendor Android
 * providers are exactly where a surprising IV length comes from, and the failure would arrive as
 * unreadable storage on one make of phone.
 */
public class ShortNonceRefusedTest {

  private static final String TRANSFORM = "AES/GCM/NoPadding";

  private static Provider platform;
  private Provider installed;
  private final List<Provider> displaced = new ArrayList<>();

  private SecretKey key;

  @Before
  public void installShortNonceProvider() {
    if (platform == null) platform = Security.getProvider("SunJCE");

    // Before displacing anything: removing the providers that offer AES/GCM also removes the one
    // that offers the AES KeyGenerator, so a key generated afterwards throws
    // NoSuchAlgorithmException and the test fails for a reason that has nothing to do with nonces.
    key = aKey();

    // Displace every provider offering this transform, so nothing can fall back past ours. The
    // Cipher API defers provider selection until init(), and chooseProvider swallows exceptions
    // from a candidate - so merely inserting at position 1 is not enough.
    for (final Provider p : Security.getProviders()) {
      if (p.getService("Cipher", TRANSFORM) != null) {
        displaced.add(p);
        Security.removeProvider(p.getName());
      }
    }
    installed = new ShortNonceProvider();
    Security.insertProviderAt(installed, 1);
  }

  @After
  public void restoreProviders() {
    if (installed != null) Security.removeProvider(installed.getName());
    for (final Provider p : displaced) Security.addProvider(p);
  }

  private static SecretKey aKey() {
    try {
      final KeyGenerator generator = KeyGenerator.getInstance("AES");
      generator.init(256);
      return generator.generateKey();
    } catch (NoSuchAlgorithmException e) {
      throw new AssertionError(e);
    }
  }

  @Test
  public void asealUnderAshortNonceProviderIsRefused() {
    final GcmCryptoBox box = new GcmCryptoBox() {
      @Override
      protected SecretKey key() {
        return key;
      }
    };

    final StorageCryptoException refused = assertThrows(
        "a provider choosing a nonce this envelope cannot carry must be refused, not truncated",
        StorageCryptoException.class,
        () -> box.seal("secret".getBytes(java.nio.charset.StandardCharsets.UTF_8),
            "aad".getBytes(java.nio.charset.StandardCharsets.UTF_8)));

    assertTrue("the refusal must say what happened: " + refused.getMessage(),
        refused.getMessage() != null && refused.getMessage().contains("nonce length"));
  }

  private static final class ShortNonceProvider extends Provider {
    ShortNonceProvider() {
      super("KryptEYShortNonce", 1.0d, "Returns an 8-byte GCM IV, for tests");
      put("Cipher." + TRANSFORM, ShortNonceCipherSpi.class.getName());
    }
  }

  /** Delegates everything except {@code engineGetIV}, which reports a length GCM never uses. */
  public static final class ShortNonceCipherSpi extends CipherSpi {

    private final Cipher delegate;

    public ShortNonceCipherSpi() {
      try {
        // Bind to the captured instance: the provider has been de-registered so nothing can fall
        // back to it, and a lookup without a provider would re-enter this class.
        delegate = Cipher.getInstance(TRANSFORM, platform);
      } catch (Exception e) {
        throw new IllegalStateException("no platform AES/GCM to delegate to", e);
      }
    }

    @Override protected byte[] engineGetIV() {
      return new byte[8];   // GCM's standard is 12; the envelope carries exactly that
    }

    @Override protected void engineSetMode(String mode) { }
    @Override protected void engineSetPadding(String padding) { }
    @Override protected int engineGetBlockSize() { return delegate.getBlockSize(); }
    @Override protected int engineGetOutputSize(int inputLen) {
      return delegate.getOutputSize(inputLen);
    }
    @Override protected java.security.AlgorithmParameters engineGetParameters() {
      return delegate.getParameters();
    }
    @Override protected void engineInit(int opmode, java.security.Key key,
        java.security.SecureRandom random) throws java.security.InvalidKeyException {
      delegate.init(opmode, key, random);
    }
    @Override protected void engineInit(int opmode, java.security.Key key,
        java.security.spec.AlgorithmParameterSpec params, java.security.SecureRandom random)
        throws java.security.InvalidKeyException,
        java.security.InvalidAlgorithmParameterException {
      delegate.init(opmode, key, params, random);
    }
    @Override protected void engineInit(int opmode, java.security.Key key,
        java.security.AlgorithmParameters params, java.security.SecureRandom random)
        throws java.security.InvalidKeyException,
        java.security.InvalidAlgorithmParameterException {
      delegate.init(opmode, key, params, random);
    }
    @Override protected byte[] engineUpdate(byte[] input, int offset, int len) {
      return delegate.update(input, offset, len);
    }
    @Override protected int engineUpdate(byte[] input, int offset, int len, byte[] output,
        int outputOffset) throws javax.crypto.ShortBufferException {
      return delegate.update(input, offset, len, output, outputOffset);
    }
    @Override protected void engineUpdateAAD(byte[] src, int offset, int len) {
      delegate.updateAAD(src, offset, len);
    }
    @Override protected void engineUpdateAAD(java.nio.ByteBuffer src) {
      delegate.updateAAD(src);
    }
    @Override protected byte[] engineDoFinal(byte[] input, int offset, int len)
        throws javax.crypto.IllegalBlockSizeException, javax.crypto.BadPaddingException {
      return delegate.doFinal(input, offset, len);
    }
    @Override protected int engineDoFinal(byte[] input, int offset, int len, byte[] output,
        int outputOffset) throws javax.crypto.ShortBufferException,
        javax.crypto.IllegalBlockSizeException, javax.crypto.BadPaddingException {
      return delegate.doFinal(input, offset, len, output, outputOffset);
    }
  }
}
