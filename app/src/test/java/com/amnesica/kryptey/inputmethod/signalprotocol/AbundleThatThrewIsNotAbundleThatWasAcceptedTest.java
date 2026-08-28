package com.amnesica.kryptey.inputmethod.signalprotocol;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.amnesica.kryptey.inputmethod.signalprotocol.encoding.EnvelopeCodec;
import com.amnesica.kryptey.inputmethod.signalprotocol.stores.SignalProtocolStoreImpl;
import com.amnesica.kryptey.inputmethod.signalprotocol.stores.StoredRecordUnreadableException;
import com.amnesica.kryptey.inputmethod.signalprotocol.util.ProtocolAddresses;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.signal.libsignal.protocol.SignalProtocolAddress;
import org.signal.libsignal.protocol.state.SessionRecord;

/**
 * "The attached bundle was refused" has three outcomes and was being read as two.
 *
 * <p>{@code decrypt} clears the flag per attempt and set it only when bundle processing RETURNED
 * false. Processing can also throw: {@code SessionStoreImpl.loadSession} raises
 * {@link StoredRecordUnreadableException} from inside libsignal's own store callback when a stored
 * session record cannot be read back, which that class's javadoc says needs "no key material or
 * attacker cooperation" — one flipped byte on disk.
 *
 * <p>A throw left the flag exactly as the method had just cleared it, so every reader was told the
 * bundle had NOT been refused for a bundle nobody looked at. On the strip that retracts a standing
 * refused-invite warning about that sender and paints "Detected contact: X". This is the defect
 * class the flag itself was introduced to close — a refused invite that looked exactly like an
 * accepted one — with the fact simply having an outcome nobody wrote down.
 */
public class AbundleThatThrewIsNotAbundleThatWasAcceptedTest {

  private SignalProtocolAddress peerAddress;
  private String peerBundle;

  @Before
  public void setUp() throws Exception {
    SignalProtocolMain.resetForTest();
    SignalProtocolMain.testIsRunning = true;

    SignalProtocolMain.initialize(null);
    final Account peer = SignalProtocolMain.getInstance().getAccount();
    peerAddress = ProtocolAddresses.of(peer.getSignalProtocolAddress().getName(),
        peer.getDeviceId());
    peerBundle = SignalProtocolMain.exportOwnKeyBundle();

    SignalProtocolMain.initialize(null);
  }

  @After
  public void tearDown() {
    SignalProtocolMain.resetForTest();
    SignalProtocolMain.testIsRunning = false;
  }

  /**
   * A store that cannot read its own session record back, which is what a corrupted store is.
   *
   * <p>Substituted rather than corrupted byte by byte because the shape is what matters: the throw
   * comes out of a store callback that libsignal invokes in the middle of processing the bundle,
   * which is exactly where the flag's two-outcome reading breaks.
   */
  private void giveTheAccountAstoreThatCannotReadItsSessions() {
    final Account account = SignalProtocolMain.getInstance().getAccount();
    final SignalProtocolStoreImpl unreadable = new SignalProtocolStoreImpl(
        account.getSignalProtocolStore().getIdentityKeyPair(),
        account.getSignalProtocolStore().getLocalRegistrationId()) {
      @Override
      public SessionRecord loadSession(final SignalProtocolAddress address) {
        throw new StoredRecordUnreadableException(
            "a stored session record could not be read back",
            new java.io.IOException("planted"));
      }
    };
    account.setSignalProtocolStore(unreadable);
  }

  @Test
  public void abundleWhoseProcessingThrewIsReportedAsRefused() throws Exception {
    giveTheAccountAstoreThatCannotReadItsSessions();

    final MessageEnvelope withBundle = EnvelopeCodec.fromWire(peerBundle);
    withBundle.setCiphertextMessage(new byte[] {1, 2, 3});

    try {
      SignalProtocolMain.decryptMessage(withBundle, peerAddress);
      fail("fixture: processing this bundle must throw, or the test is asserting about the "
          + "ordinary refusal path that was already covered");
    } catch (final StoredRecordUnreadableException expected) {
      // The throw is the point; what it leaves behind is what is under test.
    }

    assertTrue("a bundle whose processing threw must read as refused. Read as accepted, the strip "
            + "retracts a standing 'that invite was changed on the way here' warning about this "
            + "sender and says 'Detected contact' for a bundle it never looked at",
        SignalProtocolMain.lastAttachedBundleWasRefused());
  }

  /** And an ordinary accepted bundle still reads as accepted, so the fix is not a blanket true. */
  @Test
  public void anacceptedBundleIsStillNotReportedAsRefused() throws Exception {
    final MessageEnvelope withBundle = EnvelopeCodec.fromWire(peerBundle);
    withBundle.setCiphertextMessage(new byte[] {1, 2, 3});

    try {
      SignalProtocolMain.decryptMessage(withBundle, peerAddress);
    } catch (final Exception ignored) {
      // The ciphertext is nonsense and fails after the bundle is accepted; that is the state under
      // test - the BUNDLE was fine.
    }

    assertFalse("the bundle itself was accepted, and reporting it as refused would send the user "
            + "off to ask for a fresh invite over a decrypt that failed for another reason",
        SignalProtocolMain.lastAttachedBundleWasRefused());
  }
}
