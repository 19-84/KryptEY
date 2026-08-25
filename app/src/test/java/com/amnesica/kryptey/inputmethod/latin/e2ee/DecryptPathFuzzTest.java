package com.amnesica.kryptey.inputmethod.latin.e2ee;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.amnesica.kryptey.inputmethod.signalprotocol.Account;
import com.amnesica.kryptey.inputmethod.signalprotocol.MessageEnvelope;
import com.amnesica.kryptey.inputmethod.signalprotocol.SignalProtocolMain;
import com.amnesica.kryptey.inputmethod.signalprotocol.chat.Contact;
import com.amnesica.kryptey.inputmethod.signalprotocol.encoding.EnvelopeCodec;
import com.amnesica.kryptey.inputmethod.signalprotocol.util.ProtocolAddresses;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.signal.libsignal.protocol.SignalProtocolAddress;

import java.util.ArrayList;
import java.util.Random;

/**
 * The decrypt path, fed ciphertext the messenger has tampered with.
 *
 * <p>{@code E2EEStrip.decryptMessage} catches ten checked exceptions and returns null. Everything
 * else escapes: an unchecked exception or an {@code Error} out of libsignal's JNI travels up
 * through {@code decryptMessageAndShowMessageInMainInputField} into the Decrypt button's click
 * listener, whose only {@code catch} is for {@code IOException} — and out of a click listener there
 * is nothing left to catch it. The keyboard process dies, in whatever app the user is in.
 *
 * <p>The bytes are the messenger's. It can flip, truncate, extend, zero or replace any part of a
 * ciphertext, and it can label the envelope with any of the message types. None of that is exotic:
 * a corrupted paste is what happens when a messenger reflows text, and this app's whole premise is
 * that the transport is hostile.
 *
 * <p>{@code ClipboardFuzzTest} covers the codec that runs before this, and stops at the point where
 * an envelope parses. This starts there, against a real session, so what is being exercised is
 * libsignal's own reaction to damaged input rather than the parser's.
 *
 * <p><b>Scope, stated precisely.</b> Most of this corpus is wire-reachable: any messenger can flip,
 * truncate or extend ciphertext bytes. Two shapes are not — a null ciphertext, and a ciphertext type
 * attached to an envelope carrying no ciphertext — because the codec refuses an empty envelope and
 * normalises a bundle-only envelope's type to zero ({@code EnvelopeCodecNormalisationTest} pins
 * both). They are fuzzed anyway, and the null one found a real unchecked throw out of libsignal.
 * That makes the guard it prompted defence in depth rather than a fix for a live exploit, and it is
 * worth saying so plainly rather than letting the file imply a severity it has not got.
 */
@RunWith(RobolectricTestRunner.class)
public class DecryptPathFuzzTest {

  private E2EEStrip strip;
  private Account victim;
  private Contact peerContact;
  private SignalProtocolAddress victimAddress;
  private MessageEnvelope genuine;

  @Before
  public void setUp() throws Exception {
    SignalProtocolMain.resetForTest();
    SignalProtocolMain.testIsRunning = true;
    strip = new E2EEStrip(RuntimeEnvironment.getApplication());

    SignalProtocolMain.initialize(null);
    victim = SignalProtocolMain.getInstance().getAccount();
    victimAddress = ProtocolAddresses.of(victim.getSignalProtocolAddress().getName(),
        victim.getDeviceId());
    final String victimBundle = SignalProtocolMain.exportOwnKeyBundle();

    SignalProtocolMain.initialize(null);
    final Account peer = SignalProtocolMain.getInstance().getAccount();
    final SignalProtocolAddress peerAddress = ProtocolAddresses.of(
        peer.getSignalProtocolAddress().getName(), peer.getDeviceId());

    // The peer builds a session to the victim and sends one real message.
    assertTrue(SignalProtocolMain.processPreKeyResponseMessage(
        EnvelopeCodec.fromWire(victimBundle), victimAddress));
    genuine = SignalProtocolMain.encryptMessage("the genuine message", victimAddress);
    assertNotNull("precondition: the fixture must produce a real ciphertext", genuine);

    // Back to the victim, who will be the one decrypting.
    SignalProtocolMain.getInstance().setAccount(victim);
    peerContact = new Contact("Peer", "Account", peerAddress.getName(), peerAddress.getDeviceId(),
        false);
    final ArrayList<Contact> contacts = new ArrayList<>();
    contacts.add(peerContact);
    victim.setContactList(contacts);
  }

  /** Runs the production wrapper and reports anything that comes back out of it. */
  private void feed(final MessageEnvelope envelope, final String what) {
    try {
      // Contract: returns the plaintext, or null. Never throws.
      strip.decryptMessage(envelope, peerContact);
    } catch (Throwable escaped) {
      fail("a " + escaped.getClass().getName() + " escaped the decrypt path for " + what
          + " - this reaches the Decrypt button's click listener, whose only catch is for "
          + "IOException, and kills the keyboard process."
          + "\n  message: " + escaped.getMessage());
    }
  }

  private MessageEnvelope withCiphertext(final byte[] ciphertext, final int type) {
    return new MessageEnvelope(ciphertext, type,
        peerContact.getSignalProtocolAddressName(), peerContact.getDeviceId());
  }

  // ------------------------------------------------------------------- tests

  /** The fixture itself decrypts, so the corruption below is corruption of something real. */
  @Test
  public void thegenuineMessageDecrypts() {
    final CharSequence plaintext = strip.decryptMessage(
        withCiphertext(genuine.getCiphertextMessage(), genuine.getCiphertextType()), peerContact);

    org.junit.Assert.assertEquals("precondition: an untampered message must decrypt, or every "
        + "assertion below is about a fixture that never worked", "the genuine message",
        String.valueOf(plaintext));
  }

  @Test
  public void singleBitFlipsNeverEscape() {
    final byte[] original = genuine.getCiphertextMessage();
    final int type = genuine.getCiphertextType();

    for (int index = 0; index < original.length; index++) {
      for (int bit = 0; bit < 8; bit += 3) {
        final byte[] damaged = original.clone();
        damaged[index] ^= (byte) (1 << bit);
        feed(withCiphertext(damaged, type), "a single flipped bit at byte " + index);
      }
    }
  }

  @Test
  public void truncationsAndExtensionsNeverEscape() {
    final byte[] original = genuine.getCiphertextMessage();
    final int type = genuine.getCiphertextType();

    for (int length = 0; length <= original.length; length++) {
      final byte[] shorter = new byte[length];
      System.arraycopy(original, 0, shorter, 0, length);
      feed(withCiphertext(shorter, type), "a ciphertext truncated to " + length + " bytes");
    }

    for (final int extra : new int[] {1, 7, 64, 4096}) {
      final byte[] longer = new byte[original.length + extra];
      System.arraycopy(original, 0, longer, 0, original.length);
      feed(withCiphertext(longer, type), "a ciphertext with " + extra + " bytes appended");
    }
  }

  @Test
  public void arbitraryBytesUnderEveryMessageTypeNeverEscape() {
    final long seed = 0xdec0deL;
    final Random random = new Random(seed);

    // Well past the range the codec accepts, because the type reaches libsignal before anything
    // narrows it, and a value nobody wrote a branch for is exactly what a messenger would try.
    for (int type = -2; type <= 6; type++) {
      for (int attempt = 0; attempt < 40; attempt++) {
        final byte[] noise = new byte[random.nextInt(200)];
        random.nextBytes(noise);
        feed(withCiphertext(noise, type), "random bytes labelled type " + type
            + " (seed " + seed + ")");
      }
      feed(withCiphertext(new byte[0], type), "an empty ciphertext of type " + type);
      feed(withCiphertext(null, type), "a null ciphertext of type " + type);
    }
  }

  /**
   * The genuine message relabelled as every other type.
   *
   * <p>Well-formed bytes read under the wrong parser is a different failure from noise: the length
   * fields are plausible, so a parser can be walked further into itself before it gives up.
   */
  @Test
  public void thegenuineCiphertextUnderTheWrongTypeNeverEscapes() {
    final byte[] original = genuine.getCiphertextMessage();
    for (int type = -2; type <= 6; type++) {
      if (type == genuine.getCiphertextType()) continue;
      feed(withCiphertext(original, type), "a real ciphertext relabelled as type " + type);
    }
  }

  /** And a replay of the same message, which is the cheapest thing a messenger can do. */
  @Test
  public void areplayNeverEscapes() {
    final MessageEnvelope envelope =
        withCiphertext(genuine.getCiphertextMessage(), genuine.getCiphertextType());

    assertNotNull("precondition: the first delivery must decrypt",
        strip.decryptMessage(envelope, peerContact));

    for (int i = 0; i < 5; i++) {
      feed(envelope, "delivery number " + (i + 2) + " of the same message");
    }

    assertNull("a replayed message must not decrypt a second time",
        strip.decryptMessage(envelope, peerContact));
  }
}
