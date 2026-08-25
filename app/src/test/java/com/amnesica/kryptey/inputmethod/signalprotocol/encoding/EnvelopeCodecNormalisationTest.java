package com.amnesica.kryptey.inputmethod.signalprotocol.encoding;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;

import com.amnesica.kryptey.inputmethod.signalprotocol.MessageEnvelope;
import com.amnesica.kryptey.inputmethod.signalprotocol.SignalProtocolMain;

import org.junit.Before;
import org.junit.Test;

import java.io.IOException;

/**
 * What the wire format will and will not let an envelope say.
 *
 * <p>Written after a fuzz of the decrypt path found that libsignal raises a plain
 * {@code RuntimeException} — "JNI error Null pointer in get_array_elements" — when handed a null
 * ciphertext under a non-zero ciphertext type. That escapes every catch between it and the Decrypt
 * button's click listener and kills the keyboard process.
 *
 * <p>The reason it is not an exploit is entirely down to this codec: it refuses an envelope that
 * carries nothing at all, and it does not carry a ciphertext type for an envelope with no
 * ciphertext. So the shape cannot come off the wire, and a messenger editing a legitimate invite
 * cannot build one.
 *
 * <p>That is a load-bearing property of one class holding up a guarantee about another, which is
 * the kind of coupling this project keeps finding broken by an unrelated change. It is asserted
 * here so that if the codec ever starts round-tripping the ciphertext type independently of the
 * ciphertext, this fails rather than the keyboard.
 */
public class EnvelopeCodecNormalisationTest {

  private MessageEnvelope invite;

  @Before
  public void setUp() {
    SignalProtocolMain.testIsRunning = true;
    SignalProtocolMain.initialize(null);
    invite = SignalProtocolMain.getPreKeyResponseMessage();
    assertNotNull("precondition: the fixture must produce a real invite", invite);
    assertNotNull(invite.getPreKeyResponse());
  }

  /** An envelope with neither a bundle nor a ciphertext is refused rather than serialised. */
  @Test
  public void anenvelopeCarryingNothingIsRefused() {
    assertThrows("an envelope with no bundle and no ciphertext says nothing and must not serialise",
        IOException.class,
        () -> EnvelopeCodec.toWire(new MessageEnvelope(null, 2, "peer-uuid", 7)));
  }

  /**
   * A bundle-only envelope does not carry a ciphertext type, whatever the sender set.
   *
   * <p>This is the one that matters. A messenger takes a legitimate invite, sets a ciphertext type
   * on it, and re-sends: if that type survived the round trip, the receiving side would dispatch to
   * decryption with no ciphertext to decrypt.
   */
  @Test
  public void abundleOnlyEnvelopeCannotCarryAciphertextType() throws Exception {
    final MessageEnvelope hostile =
        new MessageEnvelope(invite.getPreKeyResponse(), "peer-uuid", 7);
    hostile.setCiphertextType(2);

    final MessageEnvelope back = EnvelopeCodec.fromWire(EnvelopeCodec.toWire(hostile));

    assertNotNull("the envelope should still round-trip - it carries a bundle", back);
    assertNull("and must come back with no ciphertext", back.getCiphertextMessage());
    assertEquals("the ciphertext type must not survive without a ciphertext: a non-zero type here "
            + "dispatches the receiver into libsignal with a null byte array, which raises an "
            + "unchecked JNI exception and kills the keyboard process",
        0, back.getCiphertextType());
  }

  /** And the bundle itself survives, so the normalisation is not simply dropping everything. */
  @Test
  public void thebundleSurvivesThatNormalisation() throws Exception {
    final MessageEnvelope hostile =
        new MessageEnvelope(invite.getPreKeyResponse(), "peer-uuid", 7);
    hostile.setCiphertextType(2);

    final MessageEnvelope back = EnvelopeCodec.fromWire(EnvelopeCodec.toWire(hostile));

    assertNotNull("precondition: dropping the bundle too would make the assertion above vacuous",
        back.getPreKeyResponse());
    assertEquals("peer-uuid", back.getSignalProtocolAddressName());
    assertEquals(7, back.getDeviceId());
  }

  /**
   * Nothing the messenger sends reaches a general-purpose object deserialiser.
   *
   * <p>This app has two deserialisers. Jackson reads the stored account — data the app wrote itself,
   * sealed under a Keystore key and authenticated before it is parsed. {@code BinaryEnvelope} reads
   * the wire — every byte of which the messenger chose. They must not swap places, and the reason
   * that matters is not hypothetical: the base64 class on this same path shipped a
   * {@code decodeToObject} that fed {@code ObjectInputStream.readObject()}, dead but one call from
   * the wire, until it was deleted.
   *
   * <p>Jackson without polymorphic typing is not a gadget the way {@code readObject} is, so this is
   * a boundary assertion rather than an alarm: the wire path decodes base64 and hands the bytes to a
   * hand-written binary parser, and if it ever starts handing them to a JSON mapper instead, that is
   * a change of kind and should be a deliberate one.
   */
  @Test
  public void thewirePathNeverReachesAgeneralPurposeDeserialiser() throws Exception {
    final java.nio.file.Path root =
        java.nio.file.Files.isDirectory(java.nio.file.Paths.get("src/main/java"))
            ? java.nio.file.Paths.get("src/main/java")
            : java.nio.file.Paths.get("app/src/main/java");
    final java.nio.file.Path codec = root.resolve(
        "com/amnesica/kryptey/inputmethod/signalprotocol/encoding/EnvelopeCodec.java");

    final String text = new String(java.nio.file.Files.readAllBytes(codec),
        java.nio.charset.StandardCharsets.UTF_8)
        .replaceAll("(?s)/\\*.*?\\*/", "")
        .replaceAll("(?m)//.*$", "");

    assertTrue("the wire path must decode to bytes and parse them with the hand-written binary "
        + "codec", text.contains("BinaryEnvelope.decode(bytes)"));
    assertFalse("the wire path must not hand messenger-chosen bytes to Jackson: " + codec,
        text.contains("JsonUtil") || text.contains("ObjectMapper"));

    // And polymorphic typing stays off wherever Jackson IS used, since that is what turns a JSON
    // mapper into something closer to readObject.
    try (java.util.stream.Stream<java.nio.file.Path> walk = java.nio.file.Files.walk(root)) {
      for (final java.nio.file.Path source : walk
          .filter(f -> f.toString().endsWith(".java"))
          .collect(java.util.stream.Collectors.toList())) {
        final String body = new String(java.nio.file.Files.readAllBytes(source),
            java.nio.charset.StandardCharsets.UTF_8);
        assertFalse("polymorphic deserialisation must stay off: " + source,
            body.contains("enableDefaultTyping") || body.contains("activateDefaultTyping"));
      }
    }
  }
}
