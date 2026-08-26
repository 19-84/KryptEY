package com.amnesica.kryptey.inputmethod.signalprotocol;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.amnesica.kryptey.inputmethod.signalprotocol.MessageEnvelope;
import com.amnesica.kryptey.inputmethod.signalprotocol.Account;
import com.amnesica.kryptey.inputmethod.signalprotocol.chat.Contact;
import com.amnesica.kryptey.inputmethod.signalprotocol.encoding.EnvelopeCodec;
import com.amnesica.kryptey.inputmethod.signalprotocol.prekey.PreKeyResponse;
import com.amnesica.kryptey.inputmethod.signalprotocol.prekey.PreKeyResponseItem;
import com.amnesica.kryptey.inputmethod.signalprotocol.util.ProtocolAddresses;

import org.junit.Before;
import org.junit.Test;
import org.signal.libsignal.protocol.SignalProtocolAddress;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * A relay that deletes one optional-looking field from an invite must not be obeyed.
 *
 * <p>Both fields here are unsigned: the one-time pre-key and the Kyber pre-key are covered by
 * neither the signed-pre-key signature nor each other's, so removing either costs a relay one byte
 * and leaves every signature verifying and the base64 canonical. Nothing upstream can notice — the
 * wire format's own guards are about structure, and a bundle with a field absent is structurally
 * perfect.
 *
 * <p>libsignal permits an absent one-time pre-key: {@code PreKeyBundle} marks it {@code @Nullable}
 * and {@code NULL_PRE_KEY_ID} is -1, because on Signal a server's pre-key pool can legitimately run
 * dry. <b>This app has no server.</b> {@code getPreKeyBundle} allocates a one-time pre-key or
 * throws, so a bundle without one is not a depleted pool — it is a modified invite, and the
 * inherited permission is a hole rather than a feature.
 *
 * <p>What acceptance costs is forward secrecy against later device seizure: the PQXDH secret loses
 * its DH against the one-time key, and everything remaining derives from material that stays on the
 * device for up to 32 days. The session builds, the UI advances exactly as for a good invite, and
 * nothing is logged — a silent downgrade, which is the failure mode the Kyber check four lines
 * below already refuses in so many words. This pins both, because the Kyber half had no test
 * either: deleting its check left the suite green.
 */
public class StrippedBundleFieldsAreRefusedTest {

  private PreKeyResponse genuine;
  private SignalProtocolAddress peerAddress;
  private Account victim;

  @Before
  public void setUp() throws Exception {
    SignalProtocolMain.resetForTest();
    SignalProtocolMain.testIsRunning = true;

    SignalProtocolMain.initialize(null);
    final Account peer = SignalProtocolMain.getInstance().getAccount();
    peerAddress = ProtocolAddresses.of(peer.getSignalProtocolAddress().getName(),
        peer.getDeviceId());
    genuine = EnvelopeCodec.fromWire(SignalProtocolMain.exportOwnKeyBundle()).getPreKeyResponse();

    // The recipient.
    SignalProtocolMain.initialize(null);
    victim = SignalProtocolMain.getInstance().getAccount();
  }

  /**
   * The positive control, without which "refuse every bundle" passes this entire file.
   *
   * <p>The precondition below asserts the exported bundle CARRIES the fields; it says nothing about
   * whether a genuine one is still accepted. Only other files would have caught that mutant, which
   * makes this file's green misleading on its own terms.
   */
  @Test
  public void agenuineBundleIsStillAccepted() throws Exception {
    assertNotNull("a genuine, unmodified bundle must still build - otherwise these refusals are "
        + "indistinguishable from refusing everything",
        SignalProtocolMain.getInstance().createPreKeyBundle(genuine));
  }

  /** Precondition for everything else: this app never emits a bundle without a one-time key. */
  @Test
  public void thisappalwaysSendsAoneTimePreKey() {
    assertNotNull("a genuine invite from this app must carry a one-time pre-key, or 'absent means "
            + "tampered' is not a rule this app is entitled to enforce",
        genuine.getDevices().get(0).getPreKey());
    assertNotNull("and a Kyber pre-key", genuine.getDevices().get(0).getKyberPreKey());
  }

  private PreKeyResponse without(final boolean preKey, final boolean kyber) {
    return without(preKey, kyber, false);
  }

  private PreKeyResponse without(final boolean preKey, final boolean kyber,
      final boolean signed) {
    final PreKeyResponseItem device = genuine.getDevices().get(0);
    final List<PreKeyResponseItem> devices = new ArrayList<>();
    devices.add(new PreKeyResponseItem(device.getDeviceId(), device.getRegistrationId(),
        signed ? null : device.getSignedPreKey(),
        preKey ? null : device.getPreKey(),
        kyber ? null : device.getKyberPreKey()));
    final PreKeyResponse rebuilt = new PreKeyResponse(genuine.getIdentityKey(), devices);
    // The signed pre-key is mandatory on the wire, so a bundle without one cannot survive the round
    // trip below - BinaryEnvelope wraps it in nonNull on encode. That case is therefore checked in
    // memory, and the difference is stated rather than hidden: it is hardening against a future
    // format change, not a live wire attack, and pretending otherwise would overclaim.
    if (signed) return rebuilt;
    try {
      // Through the codec, so this is the object a relay's edit actually produces rather than one
      // assembled in memory that merely resembles it. It also pins the wire format's own optional
      // markers: if `hasPreKey` stopped being expressible, the stripped bundle would not survive
      // the round trip and this would fail rather than quietly testing something else.
      return EnvelopeCodec.fromWire(EnvelopeCodec.toWire(new MessageEnvelope(rebuilt,
          peerAddress.getName(), peerAddress.getDeviceId()))).getPreKeyResponse();
    } catch (final Exception e) {
      throw new AssertionError("the stripped bundle must survive a wire round trip - that is what "
          + "makes it a relay's edit rather than a hand-built object", e);
    }
  }

  /**
   * The defect: the one-time pre-key removed, and the session built anyway.
   *
   * <p>Asserted at {@code createPreKeyBundle} rather than through the UI because that is where the
   * decision is, and because the consequence — a weaker root key — is invisible everywhere above
   * it. A session that builds successfully is exactly what the attack produces.
   */
  @Test
  public void abundleStrippedOfItsOneTimePreKeyIsRefused() {
    try {
      SignalProtocolMain.getInstance().createPreKeyBundle(without(true, false));
      fail("a bundle with no one-time pre-key was accepted. libsignal permits this because a "
          + "Signal server's pool can run dry; this app has no server and always sends one, so an "
          + "absent one-time key means the invite was modified in transit. Accepting it drops the "
          + "DH against that key from the PQXDH secret, leaving a root key derivable from "
          + "long-lived material - so a recorded conversation becomes readable on later device "
          + "seizure, which is the whole property the one-time key provides.");
    } catch (final IOException expected) {
      assertTrue("the refusal must say what happened: " + expected.getMessage(),
          expected.getMessage().contains("one-time pre key"));
    }
  }

  /**
   * The third sibling, added with the other two and left without a test.
   *
   * <p>Deleting its check left the whole suite green, which is the same gap this file was written
   * to close for the first two.
   */
  @Test
  public void abundleStrippedOfItsSignedPreKeyIsRefused() {
    try {
      SignalProtocolMain.getInstance().createPreKeyBundle(without(false, false, true));
      fail("a bundle with no signed pre-key was accepted. libsignal's PreKeyBundle rejects a null "
          + "there with an UNCHECKED exception, out of a method declared to throw IOException - "
          + "which processPreKeyResponse catches by type, so it would escape a click listener and "
          + "kill the keyboard in whatever app the user is in");
    } catch (final IOException expected) {
      assertTrue("the refusal must say what happened: " + expected.getMessage(),
          expected.getMessage().contains("signed pre key"));
    }
  }

  /**
   * A refused bundle must still leave the substitution it carried on the record.
   *
   * <p>The most serious thing about the refusal, and it points the other way from everything else
   * here. {@code buildSession}'s {@code UntrustedIdentityException} arm is — by its own comment —
   * the ONLY place a bundle-borne identity change is ever recorded, and {@code createPreKeyBundle}
   * throws before {@code buildSession} is reached. So the refusal added to catch a relay's edit
   * handed that same relay a way to switch off the app's only recovery from a successful
   * substitution: strip one unsigned byte from every re-invite the real contact sends, and the
   * identity-change warning never fires again.
   *
   * <p>The change is therefore recorded before any structural refusal. It grants an attacker
   * nothing — a bundle carrying a different identity already reached this record through
   * buildSession — it only removes their ability to suppress it.
   */
  @Test
  public void arefusedBundleStillRecordsTheSubstitutionItCarried() throws Exception {
    assertTrue("precondition: the genuine peer must be pinned first",
        SignalProtocolMain.processPreKeyResponseMessage(
            new MessageEnvelope(genuine, peerAddress.getName(), peerAddress.getDeviceId()),
            peerAddress));

    // A third party's bundle, relabelled with the peer's address and stripped of its one-time key.
    SignalProtocolMain.initialize(null);
    final PreKeyResponse impostor =
        EnvelopeCodec.fromWire(SignalProtocolMain.exportOwnKeyBundle()).getPreKeyResponse();
    SignalProtocolMain.getInstance().setAccount(victim);

    final PreKeyResponseItem device = impostor.getDevices().get(0);
    final List<PreKeyResponseItem> devices = new ArrayList<>();
    devices.add(new PreKeyResponseItem(device.getDeviceId(), device.getRegistrationId(),
        device.getSignedPreKey(), null, device.getKyberPreKey()));
    final PreKeyResponse strippedSubstitution =
        new PreKeyResponse(impostor.getIdentityKey(), devices);

    assertFalse("precondition: the stripped bundle must be refused",
        SignalProtocolMain.processPreKeyResponseMessage(new MessageEnvelope(strippedSubstitution,
            peerAddress.getName(), peerAddress.getDeviceId()), peerAddress));

    assertTrue("a substitution must be recorded even when the bundle carrying it is refused. "
            + "Otherwise a relay deletes one unsigned byte from every re-invite the real contact "
            + "sends and the identity-change warning - the app's only recovery from a successful "
            + "substitution - never fires again.",
        SignalProtocolMain.hasUnacceptedIdentityChange(peerAddress));
  }

  /**
   * And it takes the verified badge with it.
   *
   * <p>Named by a sweep as a coverage gap rather than a defect: deleting {@code clearVerificationFor}
   * from the new recording left the whole suite green, because no test combined a VERIFIED contact
   * with a stripped substituted bundle. The consequence of losing it is second-order but real —
   * {@code dismissIdentityChange} deliberately does not restore {@code Contact.verified}, so a
   * stale verified badge would come back on dismissal without any fresh comparison.
   */
  @Test
  public void arefusedSubstitutionAlsoTakesTheVerifiedBadge() throws Exception {
    assertTrue(SignalProtocolMain.processPreKeyResponseMessage(
        new MessageEnvelope(genuine, peerAddress.getName(), peerAddress.getDeviceId()),
        peerAddress));
    final Contact bob = new Contact("Bob", "Jones", peerAddress.getName(),
        peerAddress.getDeviceId(), false);
    final ArrayList<Contact> contacts = new ArrayList<>();
    contacts.add(bob);
    victim.setContactList(contacts);
    SignalProtocolMain.verifyContact(bob);
    assertTrue("precondition: the contact must be verified by comparison first",
        SignalProtocolMain.isContactKeyTrustworthy(victim.getContactList().get(0)));

    SignalProtocolMain.initialize(null);
    final PreKeyResponse impostor =
        EnvelopeCodec.fromWire(SignalProtocolMain.exportOwnKeyBundle()).getPreKeyResponse();
    SignalProtocolMain.getInstance().setAccount(victim);

    final PreKeyResponseItem device = impostor.getDevices().get(0);
    final List<PreKeyResponseItem> devices = new ArrayList<>();
    devices.add(new PreKeyResponseItem(device.getDeviceId(), device.getRegistrationId(),
        device.getSignedPreKey(), null, device.getKyberPreKey()));

    assertFalse("precondition: the stripped substitution must be refused",
        SignalProtocolMain.processPreKeyResponseMessage(
            new MessageEnvelope(new PreKeyResponse(impostor.getIdentityKey(), devices),
                peerAddress.getName(), peerAddress.getDeviceId()), peerAddress));

    // Asserted AFTER dismissing the change, which is what makes this test discriminate at all.
    // While the change is pending, isContactKeyTrustworthy answers false on the strength of the
    // pending record alone - so the obvious version of this assertion passes whether or not the
    // verification was cleared, and a control proved exactly that. The consequence lives on the
    // other side of the dismissal: dismissIdentityChange deliberately does not restore
    // Contact.verified, so if the badge was never cleared it comes back here, with no fresh
    // comparison anywhere in the story.
    assertTrue("precondition: the change must be dismissable",
        SignalProtocolMain.dismissIdentityChange(peerAddress));

    assertFalse("a refused substitution must take the verified badge away, not merely mask it "
            + "while the change is pending. Dismissing is a tap; it does not re-compare anything, "
            + "and a badge that returns after it tells the user a number was checked when the last "
            + "thing that happened at this address was an impostor's key being refused.",
        SignalProtocolMain.isContactKeyTrustworthy(victim.getContactList().get(0)));
  }

  /** The sibling check, which was equally untested. */
  @Test
  public void abundleStrippedOfItsKyberPreKeyIsRefused() {
    try {
      SignalProtocolMain.getInstance().createPreKeyBundle(without(false, true));
      fail("a bundle with no Kyber pre-key was accepted, which is a silent downgrade out of PQXDH");
    } catch (final IOException expected) {
      assertTrue("the refusal must say what happened: " + expected.getMessage(),
          expected.getMessage().contains("kyber"));
    }
  }

  /**
   * And the refusal reaches the caller as a refusal, rather than being swallowed into "accepted".
   *
   * <p>Without this, a fix that threw in the right place but was caught somewhere above would still
   * leave the user looking at a contact that appears connected.
   */
  @Test
  public void astrippedBundleDoesNotProduceAsession() throws Exception {
    final boolean accepted = SignalProtocolMain.processPreKeyResponseMessage(
        new MessageEnvelope(without(true, false), peerAddress.getName(),
            peerAddress.getDeviceId()), peerAddress);

    assertTrue("a stripped bundle must be reported as refused, not merely fail internally",
        !accepted);
    assertNull("and nothing may be pinned for that address, or the user has a contact whose key "
            + "arrived on an invite the app knows was modified",
        SignalProtocolMain.getInstance().getAccount().getSignalProtocolStore()
            .getIdentityKeyStore().getIdentity(peerAddress));
  }
}
