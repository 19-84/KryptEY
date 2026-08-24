package com.amnesica.kryptey.inputmethod.signalprotocol;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.junit.Before;
import org.junit.Test;

/**
 * How a pasted envelope is classified.
 *
 * <p>{@code getMessageType} is the first thing to look at anything arriving from the clipboard, and
 * everything downstream branches on its answer: a bundle builds a session, a message is decrypted, a
 * combined envelope does both. Misclassifying one is not a cosmetic error — it decides whether an
 * attacker-supplied bundle is processed at all.
 *
 * <p>The dispatcher is a chain of {@code != null} tests, and mutation testing found several of them
 * invertible with the whole suite green: the existing tests use envelopes that happen to fall
 * through to the right branch anyway, so no single condition had to carry its own weight. All four
 * combinations are asserted here, which is what makes each condition load-bearing.
 */
public class MessageTypeDispatchTest {

  @Before
  public void setUp() {
    SignalProtocolMain.testIsRunning = true;
    SignalProtocolMain.initialize(null);
  }

  private static MessageEnvelope bundleOnly() {
    final MessageEnvelope envelope = SignalProtocolMain.getPreKeyResponseMessage();
    assertNotNull("fixture: a bundle envelope must carry a bundle", envelope.getPreKeyResponse());
    assertNull("fixture: and no ciphertext", envelope.getCiphertextMessage());
    return envelope;
  }

  private static MessageEnvelope messageOnly() {
    return new MessageEnvelope(new byte[] {1, 2, 3}, 3, "peer-uuid", 42);
  }

  private static MessageEnvelope both() {
    final MessageEnvelope combined = messageOnly();
    combined.setPreKeyResponse(bundleOnly().getPreKeyResponse());
    return combined;
  }

  @Test
  public void aBundleWithACiphertextIsTheCombinedType() {
    assertEquals(MessageType.UPDATED_PRE_KEY_RESPONSE_MESSAGE_AND_SIGNAL_MESSAGE,
        SignalProtocolMain.getMessageType(both()));
  }

  @Test
  public void aBundleAloneIsAPreKeyResponse() {
    assertEquals(MessageType.PRE_KEY_RESPONSE_MESSAGE,
        SignalProtocolMain.getMessageType(bundleOnly()));
  }

  @Test
  public void aCiphertextAloneIsASignalMessage() {
    assertEquals(MessageType.SIGNAL_MESSAGE, SignalProtocolMain.getMessageType(messageOnly()));
  }

  /** An envelope carrying neither is unrecognised, not silently treated as one of the others. */
  @Test
  public void anEnvelopeCarryingNeitherIsUnrecognised() {
    final MessageEnvelope empty = messageOnly();
    empty.setCiphertextMessage(null);
    assertNull("neither a bundle nor a ciphertext must classify as nothing", empty.getPreKeyResponse());
    assertNull(SignalProtocolMain.getMessageType(empty));
  }

  @Test
  public void aNullEnvelopeIsUnrecognisedRatherThanACrash() {
    assertNull(SignalProtocolMain.getMessageType(null));
  }
}
