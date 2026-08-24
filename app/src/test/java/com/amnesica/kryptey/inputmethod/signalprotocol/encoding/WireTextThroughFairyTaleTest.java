package com.amnesica.kryptey.inputmethod.signalprotocol.encoding;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.amnesica.kryptey.inputmethod.signalprotocol.SignalProtocolMain;
import com.amnesica.kryptey.inputmethod.signalprotocol.util.ProtocolAddresses;

import org.junit.Before;
import org.junit.Test;

/**
 * The FairyTale pipeline against the payload production actually sends.
 *
 * <p>Deliberately NOT a Robolectric test. The property under test is about the wire alphabet, not
 * about the decoy corpus, so the sentences come from {@code initForTest} and the encoder is called
 * with a null context. That matters for more than tidiness: {@code initializeStorageHelper} returns
 * early on a null context without clearing {@code mStorageHelper}, so a Robolectric class that ran
 * earlier in the same JVM leaves a live storage helper behind, {@code initialize} then takes its
 * "protocol data already exists" branch, and the reload hands back a null account. These tests
 * failed exactly that way when they lived next to the resource-backed ones.
 */
public class WireTextThroughFairyTaleTest {

  private static final String STORY_ONE =
      "Once upon a time there was a keyboard. It kept its own counsel. Nobody read its mail.";
  private static final String STORY_TWO =
      "The miller had a daughter. She spun straw. The straw was never gold, only straw.";

  @Before
  public void loadDecoySentences() {
    FairyTaleEncoder.initForTest(STORY_ONE, STORY_TWO);
    SignalProtocolMain.testIsRunning = true;
  }

  /**
   * The production payload, not a JSON stand-in.
   *
   * <p>Every other round-trip test in the suite feeds this pipeline hand-written JSON, but Phase 3
   * moved the wire format to a binary envelope: what {@code E2EEStripView} actually hands
   * {@code encode} is {@code EnvelopeCodec.toWire(...)} output. That matters because the pipeline
   * rewrites JSON keys on the way back - {@code deSimplifyJsonKeys} turns {@code "pR"} into
   * {@code "preKeyResponse"} and so on - and it does that by string replacement over whatever it is
   * given, with no check that the payload is JSON at all.
   *
   * <p>It is safe, and for a reason worth pinning rather than trusting: the replacements are all
   * quoted tokens, and the wire alphabet has no quote character. If the wire encoding ever grows
   * one, this fails instead of silently corrupting key bundles.
   */
  @Test
  public void realWireTextSurvivesTheFairyTalePipelineUnchanged() throws Exception {
    SignalProtocolMain.testIsRunning = true;
    SignalProtocolMain.initialize(null);

    final String wire =
        SignalProtocolMain.exportOwnKeyBundle();
    assertNotNull("precondition: a real key bundle must serialise", wire);
    assertTrue("precondition: a PQXDH bundle is not small", wire.length() > 1000);

    final String encoded = FairyTaleEncoder.encode(wire, null);
    assertNotNull("real wire text did not encode", encoded);

    assertEquals("real wire text did not survive the FairyTale round trip - a key bundle sent with "
            + "this encoder would arrive corrupted", wire, FairyTaleEncoder.decode(encoded));
  }

  /**
   * And the same for an ordinary encrypted message, which is the common case and a different
   * length class from a bundle.
   */
  @Test
  public void anEncryptedMessageSurvivesTheFairyTalePipelineUnchanged() throws Exception {
    SignalProtocolMain.testIsRunning = true;
    SignalProtocolMain.initialize(null);
    final var alice =
        SignalProtocolMain.getInstance()
            .getAccount();
    SignalProtocolMain.initialize(null);
    final String aliceBundle;
    {
      SignalProtocolMain.getInstance()
          .setAccount(alice);
      aliceBundle =
          SignalProtocolMain.exportOwnKeyBundle();
    }
    final var aliceAddress =
        ProtocolAddresses.of(
            alice.getSignalProtocolAddress().getName(), alice.getDeviceId());

    // Bob builds a session to Alice and encrypts.
    SignalProtocolMain.initialize(null);
    assertTrue(com.amnesica.kryptey.inputmethod.signalprotocol.SignalProtocolMain
        .processPreKeyResponseMessage(EnvelopeCodec.fromWire(aliceBundle), aliceAddress));
    final String wire = EnvelopeCodec.toWire(
        SignalProtocolMain.encryptMessage(
            "the quick brown fox", aliceAddress));

    assertEquals("an encrypted message did not survive the FairyTale round trip",
        wire, FairyTaleEncoder.decode(FairyTaleEncoder.encode(wire, null)));
  }
}
