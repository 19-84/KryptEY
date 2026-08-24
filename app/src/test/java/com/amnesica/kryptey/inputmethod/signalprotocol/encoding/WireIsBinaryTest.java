package com.amnesica.kryptey.inputmethod.signalprotocol.encoding;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.amnesica.kryptey.inputmethod.signalprotocol.MessageEnvelope;
import com.amnesica.kryptey.inputmethod.signalprotocol.SignalProtocolMain;
import com.amnesica.kryptey.inputmethod.signalprotocol.util.Base64;

import org.junit.Before;
import org.junit.Test;

/**
 * That what actually goes on the wire is the binary format, not the JSON it replaced.
 *
 * <p>Phase 3 replaced base64(JSON) with a binary envelope. The golden test pins the bytes
 * {@code BinaryEnvelope.encode} produces, and the round-trip tests pin that
 * {@code toWire}/{@code fromWire} agree with each other - but both would still pass if the codec
 * quietly went back to serialising JSON, because it would agree with itself. Agreement is not the
 * property; the format is.
 *
 * <p>The same shape of gap as the session version: every test confirmed the mechanism worked and
 * none checked that the mechanism chosen was the one in use.
 */
public class WireIsBinaryTest {

  private MessageEnvelope envelope;

  @Before
  public void setUp() {
    SignalProtocolMain.testIsRunning = true;
    SignalProtocolMain.initialize(null);
    envelope = new MessageEnvelope(new byte[] {1, 2, 3, 4, 5}, 3, "peer-uuid", 42);
  }

  @Test
  public void wireTextDecodesToTheBinaryFormatsVersionByte() throws Exception {
    final byte[] raw = Base64.decode(EnvelopeCodec.toWire(envelope));

    assertNotNull(raw);
    assertTrue("wire text must carry a payload", raw.length > 1);
    assertEquals("the first byte must be the binary format's version marker - if this is '{' the "
            + "codec has gone back to JSON and every round-trip test would still pass",
        BinaryEnvelope.VERSION, raw[0]);
  }

  @Test
  public void wireTextIsNotJson() throws Exception {
    final String wire = EnvelopeCodec.toWire(envelope);
    final String decoded = new String(Base64.decode(wire), java.nio.charset.StandardCharsets.UTF_8);

    assertFalse("the wire payload must not be a JSON object", decoded.trim().startsWith("{"));
    assertFalse("nor contain the field names the old format used",
        decoded.contains("signalProtocolAddressName") || decoded.contains("ciphertextMessage"));
  }

  /**
   * And the binary format is materially smaller than the JSON it replaced.
   *
   * <p>Not a large saving - measured at about 1% for a message, because the payload is mostly
   * incompressible key and ciphertext material. Asserted at a deliberately loose threshold so it
   * documents the direction without pretending to a benefit the format does not deliver. The format
   * was justified on strictness and bounded parsing, not on size.
   */
  @Test
  public void theBinaryFormIsNoLargerThanTheJsonItReplaced() throws Exception {
    final String wire = EnvelopeCodec.toWire(envelope);
    final String asJson = com.amnesica.kryptey.inputmethod.signalprotocol.util.JsonUtil
        .toJson(envelope);

    assertTrue("binary=" + wire.length() + " json=" + asJson.length()
            + " - the binary form must not be larger than the JSON it replaced",
        wire.length() <= asJson.length());
  }

  /** A bundle envelope too, which is the larger and more structured of the two shapes. */
  @Test
  public void aBundleEnvelopeAlsoGoesOutAsBinary() throws Exception {
    final MessageEnvelope bundle = SignalProtocolMain.getPreKeyResponseMessage();
    assertNotNull(bundle.getPreKeyResponse());

    final byte[] raw = Base64.decode(EnvelopeCodec.toWire(bundle));
    assertEquals(BinaryEnvelope.VERSION, raw[0]);
  }
}
