package com.amnesica.kryptey.inputmethod.signalprotocol;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.amnesica.kryptey.inputmethod.signalprotocol.encoding.EnvelopeCodec;
import com.amnesica.kryptey.inputmethod.signalprotocol.util.ProtocolAddresses;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.signal.libsignal.protocol.SignalProtocolAddress;

import java.util.ArrayList;

/**
 * How large a session record a peer can make this app store, measured.
 *
 * <p>A round asking what grows without bound over a long-lived install left this one open: the
 * Double Ratchet retains message keys for messages it has not seen yet, so that they can still be
 * decrypted when they arrive out of order. The peer decides how many that is — they simply send,
 * and this app stores whatever libsignal serialises.
 *
 * <p>This app cannot configure the limit: the caps live inside libsignal's own implementation, not
 * in any Java the fork owns. So the question is not "is there a cap" but "what does the record
 * actually weigh when a peer pushes on it", and that is answerable here rather than by reading.
 *
 * <p>Recorded rather than guarded. A cap of this app's own would mean discarding keys libsignal
 * expects to hold, which breaks out-of-order delivery — the exact thing the retention is for — and
 * an out-of-order message is ordinary on a messenger, not an attack.
 */
@RunWith(RobolectricTestRunner.class)
public class HowFarAsessionRecordCanGrowTest {

  private Account peer;
  private Account victim;
  private SignalProtocolAddress peerAddress;
  private SignalProtocolAddress victimAddress;

  @Before
  public void setUp() throws Exception {
    SignalProtocolMain.resetForTest();
    SignalProtocolMain.testIsRunning = true;

    SignalProtocolMain.initialize(null);
    peer = SignalProtocolMain.getInstance().getAccount();
    peer.setMessageLogLoader(ArrayList::new);
    peerAddress = ProtocolAddresses.of(peer.getSignalProtocolAddress().getName(),
        peer.getDeviceId());
    final String peerBundle = SignalProtocolMain.exportOwnKeyBundle();

    SignalProtocolMain.initialize(null);
    victim = SignalProtocolMain.getInstance().getAccount();
    victim.setMessageLogLoader(ArrayList::new);
    victimAddress = ProtocolAddresses.of(victim.getSignalProtocolAddress().getName(),
        victim.getDeviceId());
    assertTrue(SignalProtocolMain.processPreKeyResponseMessage(
        EnvelopeCodec.fromWire(peerBundle), peerAddress));
  }

  @After
  public void tearDown() {
    SignalProtocolMain.resetForTest();
    SignalProtocolMain.testIsRunning = false;
  }

  private void activate(final Account account) {
    SignalProtocolMain.getInstance().setAccount(account);
  }

  private int victimsSessionBytes() {
    final var record = victim.getSignalProtocolStore().loadSession(peerAddress);
    assertNotNull("fixture: the victim must have a session with the peer", record);
    return record.serialize().length;
  }

  @Test
  public void skippedMessagesGrowTheStoredSessionAndTheGrowthIsBounded() throws Exception {
    // Establish the session in both directions with one delivered message.
    activate(victim);
    final String victimBundle = SignalProtocolMain.exportOwnKeyBundle();
    activate(peer);
    assertTrue(SignalProtocolMain.processPreKeyResponseMessage(
        EnvelopeCodec.fromWire(victimBundle), victimAddress));
    final MessageEnvelope first = SignalProtocolMain.encryptMessage("one", victimAddress);
    activate(victim);
    assertNotNull(SignalProtocolMain.decryptMessage(first, peerAddress));

    final int settled = victimsSessionBytes();

    // The peer now sends a run of messages that never arrive, then one that does. Everything in
    // between becomes a retained message key on the victim's side.
    final int skipped = 300;
    activate(peer);
    MessageEnvelope last = null;
    for (int i = 0; i < skipped; i++) {
      last = SignalProtocolMain.encryptMessage("skipped " + i, victimAddress);
    }
    assertNotNull(last);
    activate(victim);
    assertNotNull("the last message must still decrypt - retaining the skipped keys is what makes "
        + "out-of-order delivery work, and that is the feature, not the defect",
        SignalProtocolMain.decryptMessage(last, peerAddress));

    final int afterSkips = victimsSessionBytes();
    System.out.println("MEASURED session record: settled=" + settled + " bytes, after " + skipped
        + " skipped=" + afterSkips + " bytes, per skipped key="
        + ((afterSkips - settled) / (double) skipped));

    assertTrue("skipping " + skipped + " messages must grow the stored session - if it did not, "
            + "the keys are not being retained and out-of-order delivery is broken, which would "
            + "make the rest of this measurement meaningless",
        afterSkips > settled);

    // The bound this app relies on is libsignal's, not its own. Pinned generously: what matters is
    // that one peer sending a few hundred messages the user never receives cannot put megabytes
    // into the store, not the exact figure, which is the library's to change.
    assertTrue("a peer who sends " + skipped + " unreceived messages made the stored session "
            + afterSkips + " bytes. This app sets no cap - the retention limits are inside "
            + "libsignal - so this is a measurement of what the library allows, and a figure this "
            + "large would mean the store grows by more than the messages themselves would",
        afterSkips < 200_000);
  }
}
