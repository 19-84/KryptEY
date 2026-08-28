package com.amnesica.kryptey.inputmethod.signalprotocol;

import static org.junit.Assert.assertFalse;

import org.junit.Test;
import org.signal.libsignal.protocol.IdentityKeyPair;
import org.signal.libsignal.protocol.ecc.ECPublicKey;

/**
 * A signature of the wrong length must be answered, not thrown about.
 *
 * <p>The wire format prefixes the bundle signature with a byte, so a relay chooses its length, and
 * the verifier checks only that it is non-empty before handing it to libsignal. On that path only
 * {@code IOException} is caught, and the path starts at the Decrypt button - so an unchecked throw
 * would take the input method down in whatever app the user is typing in, repeatably, on a paste the
 * messenger chose. That is this codebase's named worst failure mode.
 *
 * <p>It does not throw: measured against libsignal 0.86.5, every length returns false. This is a
 * property of the library rather than of this code, which is why it is pinned here - an upgrade that
 * started throwing would turn a refused invite into a dead keyboard, and nothing else would notice.
 *
 * <p>Deliberately not fixed by a length check in the verifier: hard-coding a curve's signature size
 * into a format whose whole point is a version byte makes a future key type fail with a message
 * about a length.
 */
public class AwrongLengthSignatureIsRefusedRatherThanThrownTest {

  @Test
  public void everyWrongSignatureLengthIsAnsweredFalse() {
    final ECPublicKey key = IdentityKeyPair.generate().getPublicKey().getPublicKey();
    final byte[] message = new byte[32];

    for (final int length : new int[] {1, 2, 31, 63, 64, 65, 100, 200, 255}) {
      assertFalse("a " + length + "-byte signature must be refused rather than throwing: the "
              + "caller catches IOException only, and it is a click listener in an input method",
          key.verifySignature(message, new byte[length]));
    }
  }
}
