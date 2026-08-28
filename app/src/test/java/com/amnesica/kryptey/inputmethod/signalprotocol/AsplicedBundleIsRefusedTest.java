package com.amnesica.kryptey.inputmethod.signalprotocol;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.amnesica.kryptey.inputmethod.signalprotocol.encoding.EnvelopeCodec;
import com.amnesica.kryptey.inputmethod.signalprotocol.prekey.PreKeyResponse;
import com.amnesica.kryptey.inputmethod.signalprotocol.prekey.PreKeyResponseItem;
import com.amnesica.kryptey.inputmethod.signalprotocol.util.ProtocolAddresses;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.signal.libsignal.protocol.SignalProtocolAddress;

import java.util.ArrayList;
import java.util.List;

/**
 * A bundle whose fields came from two different invites must be refused.
 *
 * <p>libsignal signs the signed pre-key and the Kyber pre-key individually and signs nothing that
 * ties a bundle's fields to each other; every other field is checked for presence alone. So a relay
 * that kept an invite the same person issued last week could swap that invite's one-time pre-key
 * into the current one, and both libsignal signatures still verified. Measured before the fix:
 * accepted.
 *
 * <p>What it bought was not impersonation — the identity key is unchanged and the pin still refuses
 * a substitution. It was <b>one-time pre-key reuse</b>: two peers negotiating against the same
 * one-time key, so one record covers both their opening messages and a single seizure inside its
 * lifetime reads both, while whichever peer's first message arrives second can never be decrypted
 * at all. Splicing the previous rotation's signed or Kyber key is the same move against forward
 * secrecy, the post-quantum half included.
 *
 * <p>Bundles now carry a signature over their own canonical encoding, made with the identity key
 * they name. It says nothing about <em>who</em> that identity is — a bundle from an attacker's own
 * identity verifies perfectly, and trust-on-first-use and the safety number remain the only answer
 * to that question. It answers the narrower one the format could not: were these fields issued
 * together.
 */
public class AsplicedBundleIsRefusedTest {

  private SignalProtocolAddress inviterAddress;
  private String firstInvite;
  private String secondInvite;

  @Before
  public void setUp() throws Exception {
    SignalProtocolMain.resetForTest();
    SignalProtocolMain.testIsRunning = true;

    SignalProtocolMain.initialize(null);
    final Account inviter = SignalProtocolMain.getInstance().getAccount();
    inviterAddress = ProtocolAddresses.of(inviter.getSignalProtocolAddress().getName(),
        inviter.getDeviceId());

    // Two invites from one person, which is ordinary: every invite mints a fresh one-time key.
    firstInvite = SignalProtocolMain.exportOwnKeyBundle();
    secondInvite = SignalProtocolMain.exportOwnKeyBundle();
  }

  @After
  public void tearDown() {
    SignalProtocolMain.resetForTest();
    SignalProtocolMain.testIsRunning = false;
  }

  /** The current invite with the older one's one-time pre-key put back in. */
  private MessageEnvelope spliced() throws Exception {
    final PreKeyResponse older = EnvelopeCodec.fromWire(firstInvite).getPreKeyResponse();
    final PreKeyResponse current = EnvelopeCodec.fromWire(secondInvite).getPreKeyResponse();
    assertNotEquals("fixture: the two invites must carry different one-time keys, or there is "
            + "nothing to splice",
        older.getDevices().get(0).getPreKey().getKeyId(),
        current.getDevices().get(0).getPreKey().getKeyId());

    final PreKeyResponseItem device = current.getDevices().get(0);
    final List<PreKeyResponseItem> devices = new ArrayList<>();
    devices.add(new PreKeyResponseItem(device.getDeviceId(), device.getRegistrationId(),
        device.getSignedPreKey(), older.getDevices().get(0).getPreKey(), device.getKyberPreKey()));

    // The relay forwards the signature it was given: it can copy and edit, and cannot sign.
    return BundleSigning.asEditedInTransit(EnvelopeCodec.fromWire(secondInvite),
        new MessageEnvelope(new PreKeyResponse(current.getIdentityKey(), devices),
            inviterAddress.getName(), inviterAddress.getDeviceId()));
  }

  @Test
  public void abundleMixingTwoInvitesFromOnePersonIsRefused() throws Exception {
    final MessageEnvelope tampered =
        EnvelopeCodec.fromWire(EnvelopeCodec.toWire(spliced()));

    SignalProtocolMain.initialize(null);   // a fresh recipient
    assertFalse("a bundle whose fields were not issued together must be refused: accepted, it puts "
            + "two peers on one one-time pre-key, and the second of them can never be decrypted",
        SignalProtocolMain.processPreKeyResponseMessage(tampered, inviterAddress));
    assertFalse("and nothing may be pinned by it",
        SignalProtocolMain.hasPinnedKey(inviterAddress));
  }

  /**
   * And an untouched invite from the same person is still accepted.
   *
   * <p>The floor. Without it the test above passes just as well against a build that refuses every
   * bundle, which would be a denial of the whole feature rather than a defence.
   */
  @Test
  public void anuneditedInviteIsStillAccepted() throws Exception {
    SignalProtocolMain.initialize(null);
    assertTrue("a genuine invite must still work",
        SignalProtocolMain.processPreKeyResponseMessage(
            EnvelopeCodec.fromWire(secondInvite), inviterAddress));
    assertNotNull("and it must pin the identity it carries",
        SignalProtocolMain.getInstance().getAccount().getSignalProtocolStore()
            .getIdentityKeyStore().getIdentity(inviterAddress));
  }

  /**
   * A splice between two DIFFERENT people is refused as well - by libsignal, before this check.
   *
   * <p>The sentence here used to claim that at a fresh address "there is nothing else to catch
   * this", which is wrong and the mutant says so: delete the issuing-signature check and this test
   * stays green while its sibling goes red. libsignal verifies the signed pre-key's signature
   * against the identity key the bundle carries, so device material issued by B under identity A
   * fails inside {@code buildSession} regardless.
   *
   * <p>Kept as defence in depth, with the claim corrected. The case the issuing signature is
   * genuinely alone on is the sibling above: one issuer, every libsignal signature intact, fields
   * taken from two different invites.
   */
  @Test
  public void abundleMixingTwoIdentitiesIsRefusedAtAfreshAddress() throws Exception {
    final PreKeyResponse mine = EnvelopeCodec.fromWire(secondInvite).getPreKeyResponse();

    SignalProtocolMain.initialize(null);
    final Account stranger = SignalProtocolMain.getInstance().getAccount();
    final String strangerInvite = SignalProtocolMain.exportOwnKeyBundle();
    final SignalProtocolAddress strangerAddress = ProtocolAddresses.of(
        stranger.getSignalProtocolAddress().getName(), stranger.getDeviceId());

    // The stranger's keys under the inviter's identity: every libsignal signature inside still
    // verifies, because each covers only its own key.
    final MessageEnvelope mixed = BundleSigning.asEditedInTransit(
        EnvelopeCodec.fromWire(strangerInvite),
        new MessageEnvelope(new PreKeyResponse(mine.getIdentityKey(),
            EnvelopeCodec.fromWire(strangerInvite).getPreKeyResponse().getDevices()),
            strangerAddress.getName(), strangerAddress.getDeviceId()));

    SignalProtocolMain.initialize(null);   // a recipient who has never seen either of them
    assertFalse("a bundle assembled from two identities must be refused, whichever check gets "
            + "there first",
        SignalProtocolMain.processPreKeyResponseMessage(
            EnvelopeCodec.fromWire(EnvelopeCodec.toWire(mixed)), strangerAddress));
  }

  /**
   * What the signature does NOT close: replaying a whole earlier invite.
   *
   * <p>The splice is the expensive way to get one-time pre-key reuse. The cheap way needs no edit at
   * all: the relay withholds the current invite and delivers an earlier, entirely genuine one that
   * somebody else already has. Every signature verifies because nothing was touched, and two people
   * negotiate against the same one-time key — which is the whole harm the splice was reported for.
   *
   * <p>So this is written down as a test rather than left implied by a commit message. REVIVAL.md
   * records bundle replay as a deferred hazard and measures it for one recipient; the two-recipient
   * consequence is the part that was not measured, and it is the part that matters, because the
   * issuer's one-time record is consumed by whichever opening message arrives first.
   */
  @Test
  public void replayingAwholeInviteStillPutsTwoPeersOnOneOneTimeKey() throws Exception {
    final int idFirstRecipientGets = EnvelopeCodec.fromWire(secondInvite).getPreKeyResponse()
        .getDevices().get(0).getPreKey().getKeyId();

    SignalProtocolMain.initialize(null);
    assertTrue("the first recipient accepts the genuine invite",
        SignalProtocolMain.processPreKeyResponseMessage(
            EnvelopeCodec.fromWire(secondInvite), inviterAddress));

    SignalProtocolMain.initialize(null);
    assertTrue("and so does a second recipient handed the very same bytes - nothing was edited, so "
            + "the issuing signature has nothing to object to",
        SignalProtocolMain.processPreKeyResponseMessage(
            EnvelopeCodec.fromWire(secondInvite), inviterAddress));

    assertEquals("both negotiated against the same one-time pre-key, which is the reuse the splice "
            + "was reported for. The signature binds a bundle's fields together; it says nothing "
            + "about whether this bundle has been handed out before",
        idFirstRecipientGets,
        EnvelopeCodec.fromWire(secondInvite).getPreKeyResponse().getDevices().get(0)
            .getPreKey().getKeyId());
  }
}
