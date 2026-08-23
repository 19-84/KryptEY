package com.amnesica.kryptey.inputmethod.signalprotocol;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.amnesica.kryptey.inputmethod.signalprotocol.chat.Contact;
import com.amnesica.kryptey.inputmethod.signalprotocol.encoding.EnvelopeCodec;
import com.amnesica.kryptey.inputmethod.signalprotocol.util.JsonUtil;
import com.amnesica.kryptey.inputmethod.signalprotocol.util.ProtocolAddresses;

import com.fasterxml.jackson.core.type.TypeReference;

import org.junit.Before;
import org.junit.Test;
import org.signal.libsignal.protocol.SignalProtocolAddress;

import java.util.ArrayList;

/**
 * Out-of-band key exchange, which is the only thing that closes the first-contact gap.
 *
 * <p>Trust-on-first-use cannot detect a hostile messenger substituting keys at first contact —
 * there is no earlier key to have changed, so the identity-change machinery has nothing to fire on.
 * The assurance has to come from the bundle never travelling through that messenger, and since the
 * two are indistinguishable afterwards from the stored key alone, provenance is recorded at import.
 */
public class OutOfBandExchangeTest {

  private Account alice;
  private Account bob;

  @Before
  public void createBothParties() {
    SignalProtocolMain.testIsRunning = true;
    SignalProtocolMain.initialize(null);
    alice = SignalProtocolMain.getInstance().getAccount();
    SignalProtocolMain.initialize(null);
    bob = SignalProtocolMain.getInstance().getAccount();
  }

  private void activate(final Account account) {
    SignalProtocolMain.getInstance().setAccount(account);
  }

  private static SignalProtocolAddress addressOf(final Account a) {
    return ProtocolAddresses.of(a.getSignalProtocolAddress().getName(), a.getDeviceId());
  }

  // --------------------------------------------------------------- mechanism

  @Test
  public void aBundleCanBeExportedAsTransferableText() throws Exception {
    activate(alice);
    final String exported = SignalProtocolMain.exportOwnKeyBundle();

    assertNotNull(exported);
    assertTrue("export should be non-trivial", exported.length() > 100);
    assertNotNull("the export must parse back as a bundle",
        EnvelopeCodec.fromWire(exported).getPreKeyResponse());
  }

  /** The full out-of-band flow: Alice hands Bob her bundle by some trusted channel. */
  @Test
  public void aSessionCanBeBuiltEntirelyOutOfBand() throws Exception {
    activate(alice);
    final String aliceBundle = SignalProtocolMain.exportOwnKeyBundle();

    activate(bob);
    assertTrue("out-of-band import failed",
        SignalProtocolMain.importOutOfBandKeyBundle(aliceBundle, addressOf(alice)));

    // And the resulting session actually works.
    final MessageEnvelope message =
        SignalProtocolMain.encryptMessage("hello out of band", addressOf(alice));
    assertNotNull(message);

    activate(alice);
    assertEquals("hello out of band", SignalProtocolMain.decryptMessage(
        EnvelopeCodec.fromWire(EnvelopeCodec.toWire(message)), addressOf(bob)));
  }

  @Test
  public void importRejectsTextThatIsNotABundle() {
    activate(bob);
    assertFalse(SignalProtocolMain.importOutOfBandKeyBundle("not a bundle", addressOf(alice)));
    assertFalse(SignalProtocolMain.importOutOfBandKeyBundle(null, addressOf(alice)));
    assertFalse(SignalProtocolMain.importOutOfBandKeyBundle("", addressOf(alice)));
  }

  /** A plain message is valid wire text but is not a key bundle; importing it must fail. */
  @Test
  public void importRejectsAMessageEnvelope() throws Exception {
    activate(alice);
    final String aliceBundle = SignalProtocolMain.exportOwnKeyBundle();
    activate(bob);
    SignalProtocolMain.importOutOfBandKeyBundle(aliceBundle, addressOf(alice));
    final String plainMessage =
        EnvelopeCodec.toWire(SignalProtocolMain.encryptMessage("hi", addressOf(alice)));

    assertFalse("a message envelope is not a key bundle",
        SignalProtocolMain.importOutOfBandKeyBundle(plainMessage, addressOf(alice)));
  }

  // -------------------------------------------------------------- provenance

  @Test
  public void aContactDefaultsToInBand() {
    final Contact contact = new Contact("A", "B", "peer-uuid", 42, false);
    assertEquals(Contact.KeyOrigin.IN_BAND, contact.getKeyOrigin());
    assertFalse("an in-band unverified contact is not trustworthy", contact.isTrustworthy());
  }

  @Test
  public void anOutOfBandContactIsTrustworthyWithoutASeparateVerification() {
    final Contact contact = new Contact("A", "B", "peer-uuid", 42, false,
        Contact.KeyOrigin.OUT_OF_BAND);

    assertTrue("obtaining the key through a trusted channel is itself the assurance",
        contact.isTrustworthy());
    assertFalse("but it is not the same as an explicit safety-number comparison",
        contact.isVerified());
  }

  @Test
  public void anExplicitlyVerifiedInBandContactIsAlsoTrustworthy() {
    final Contact contact = new Contact("A", "B", "peer-uuid", 42, true);
    assertEquals(Contact.KeyOrigin.IN_BAND, contact.getKeyOrigin());
    assertTrue(contact.isTrustworthy());
  }

  /**
   * Provenance has to participate in equality, or updating it silently no-ops: the contact list is
   * updated by matching on equals.
   */
  @Test
  public void provenanceParticipatesInEquality() {
    final Contact inBand = new Contact("A", "B", "peer-uuid", 42, false);
    final Contact outOfBand = new Contact("A", "B", "peer-uuid", 42, false,
        Contact.KeyOrigin.OUT_OF_BAND);

    assertFalse("contacts differing only in provenance compared equal", inBand.equals(outOfBand));
  }

  // ------------------------------------------------------------ persistence

  @Test
  public void provenanceSurvivesSerialization() throws Exception {
    final ArrayList<Contact> contacts = new ArrayList<>();
    contacts.add(new Contact("A", "B", "peer-uuid", 42, false, Contact.KeyOrigin.OUT_OF_BAND));

    final ArrayList<Contact> restored = JsonUtil.fromJson(
        JsonUtil.toJson(contacts), new TypeReference<ArrayList<Contact>>() {});

    assertEquals(Contact.KeyOrigin.OUT_OF_BAND, restored.get(0).getKeyOrigin());
    assertTrue(restored.get(0).isTrustworthy());
  }

  /**
   * A contact stored before this field existed has no {@code keyOrigin} in its JSON. It must load,
   * and it must default to IN_BAND — the safe direction, since it under-claims trust rather than
   * over-claiming it.
   */
  @Test
  public void aLegacyContactWithoutProvenanceLoadsAsInBand() throws Exception {
    final String legacyJson = "[{\"firstName\":\"Ada\",\"lastName\":\"Lovelace\","
        + "\"signalProtocolAddressName\":\"peer-uuid\",\"deviceId\":42,\"verified\":false}]";

    final ArrayList<Contact> restored =
        JsonUtil.fromJson(legacyJson, new TypeReference<ArrayList<Contact>>() {});

    assertEquals(1, restored.size());
    assertEquals("a contact predating this field must not be assumed out-of-band",
        Contact.KeyOrigin.IN_BAND, restored.get(0).getKeyOrigin());
    assertFalse(restored.get(0).isTrustworthy());
  }

  @Test
  public void markingProvenanceIsSafeWhenThereIsNoAccount() {
    SignalProtocolMain.getInstance().setAccount(null);
    SignalProtocolMain.markContactKeyAsOutOfBand(
        new Contact("A", "B", "peer-uuid", 42, false)); // must not throw
    SignalProtocolMain.markContactKeyAsOutOfBand(null);
  }
}
