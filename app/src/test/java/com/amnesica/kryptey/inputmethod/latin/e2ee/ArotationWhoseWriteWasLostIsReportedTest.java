package com.amnesica.kryptey.inputmethod.latin.e2ee;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.view.ContextThemeWrapper;
import android.widget.TextView;

import com.amnesica.kryptey.inputmethod.R;
import com.amnesica.kryptey.inputmethod.signalprotocol.Account;
import com.amnesica.kryptey.inputmethod.signalprotocol.MessageEnvelope;
import com.amnesica.kryptey.inputmethod.signalprotocol.SignalProtocolMain;
import com.amnesica.kryptey.inputmethod.signalprotocol.chat.Contact;
import com.amnesica.kryptey.inputmethod.signalprotocol.encoding.EnvelopeCodec;
import com.amnesica.kryptey.inputmethod.signalprotocol.helper.StorageHelper;
import com.amnesica.kryptey.inputmethod.signalprotocol.storage.TestStores;
import com.amnesica.kryptey.inputmethod.signalprotocol.util.ProtocolAddresses;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.signal.libsignal.protocol.SignalProtocolAddress;

import java.util.ArrayList;

/**
 * A key rotation from a contact the user already has, whose write did not land.
 *
 * <p>{@code buildSession} records whether its write reached disk, and that record had exactly one
 * reader — inside {@code addContact}. So a rotation from an <em>existing</em> contact was written
 * down and never reported: the new key and the session built from it exist in memory only, the user
 * is told the contact was detected, and the next reload restores the old session while the peer has
 * moved on. Every message after that fails to decrypt, and this app's standard advice for a failed
 * decrypt is delete-and-re-invite — which is the key-substitution window the whole file exists to
 * keep shut.
 *
 * <p>The same "delivered but not recorded" silence the message paths were fixed for, about a key
 * instead of a message.
 */
@RunWith(RobolectricTestRunner.class)
public class ArotationWhoseWriteWasLostIsReportedTest {

  private E2EEStripView strip;
  private Contact bob;
  private SignalProtocolAddress peerAddress;
  private String rotatedBundle;

  @Before
  public void setUp() throws Exception {
    final Application app = RuntimeEnvironment.getApplication();

    SignalProtocolMain.resetForTest();
    SignalProtocolMain.testIsRunning = true;

    SignalProtocolMain.initialize(null);
    final Account peer = SignalProtocolMain.getInstance().getAccount();
    peer.setMessageLogLoader(ArrayList::new);
    peerAddress = ProtocolAddresses.of(peer.getSignalProtocolAddress().getName(),
        peer.getDeviceId());
    final String firstBundle = SignalProtocolMain.exportOwnKeyBundle();
    // A second bundle from the same account: a fresh signed pre-key at the same identity, which is
    // what an ordinary rotation looks like on the wire.
    rotatedBundle = SignalProtocolMain.exportOwnKeyBundle();

    SignalProtocolMain.initialize(null);
    final Account victim = SignalProtocolMain.getInstance().getAccount();
    victim.setMessageLogLoader(ArrayList::new);
    TestStores.writesLand();
    assertTrue("precondition: the first bundle must establish a session",
        SignalProtocolMain.processPreKeyResponseMessage(
            EnvelopeCodec.fromWire(firstBundle), peerAddress));

    bob = new Contact("Bob", "Jones", peerAddress.getName(), peerAddress.getDeviceId(), false);
    final ArrayList<Contact> contacts = new ArrayList<>();
    contacts.add(bob);
    victim.setContactList(contacts);

    strip = new E2EEStripView(
        new ContextThemeWrapper(app, R.style.KeyboardTheme_LXX_Pure_Day), null);
    strip.setListener(new E2EEStripView.Listener() {
      @Override public void onTextInput(final String rawText) { }
      @Override public void onSensitiveContentVisibilityChanged(final boolean sensitive) { }
    }, strip);
    SignalProtocolMain.setStorageStateForTest(StorageHelper.StorageState.READABLE);
    strip.selectContact(bob);
  }

  @After
  public void tearDown() {
    SignalProtocolMain.resetForTest();
    SignalProtocolMain.testIsRunning = false;
  }

  private String banner() {
    return String.valueOf(((TextView) strip.findViewById(R.id.e2ee_info_text)).getText());
  }

  @Test
  public void arotationThatDidNotReachDiskIsSaidOutLoud() throws Exception {
    SignalProtocolMain.getInstance().setStorageHelperForTest(
        new StorageHelper(RuntimeEnvironment.getApplication(), (ctx, has) -> null) {
          @Override
          public boolean storeAllInformationInSharedPreferences(final Account account) {
            return false;
          }
        });

    final MessageEnvelope rotation = EnvelopeCodec.fromWire(rotatedBundle);
    assertNotNull(rotation.getPreKeyResponse());
    strip.processPreKeyResponseForTest(rotation, bob);

    assertTrue("a rotation whose write was lost must be reported. The new key and its session are "
            + "in memory only; the next reload restores the old session while the peer has moved "
            + "on, every message then fails to decrypt, and the advice for that is "
            + "delete-and-re-invite. Banner: " + banner(),
        banner().contains("could not be saved"));
  }

  /** And a rotation that landed is not reported as lost. */
  @Test
  public void arotationThatLandedSaysNothingAboutStorage() throws Exception {
    final MessageEnvelope rotation = EnvelopeCodec.fromWire(rotatedBundle);
    strip.processPreKeyResponseForTest(rotation, bob);

    assertTrue("a healthy rotation must not claim a storage failure: " + banner(),
        !banner().contains("could not be saved"));
  }

  /**
   * And the messenger cannot move the envelope to an arm with no reader.
   *
   * <p>{@code getMessageType} dispatches on field presence alone, so appending arbitrary bytes as a
   * ciphertext moves a bundle from the bundle-only arm to the combined one. The reader lived on the
   * bundle-only arm, so one appended field made the notice disappear — and the combined arm is the
   * <em>ordinary</em> shape for a signed-pre-key rotation, which means the arm with no reader was
   * the common one.
   */
  @Test
  public void appendingAciphertextDoesNotSilenceTheReport() throws Exception {
    SignalProtocolMain.getInstance().setStorageHelperForTest(
        new StorageHelper(RuntimeEnvironment.getApplication(), (ctx, has) -> null) {
          @Override
          public boolean storeAllInformationInSharedPreferences(final Account account) {
            return false;
          }
        });

    // The same rotation, with bytes stapled on. One field.
    final MessageEnvelope rotation = EnvelopeCodec.fromWire(rotatedBundle);
    rotation.setCiphertextMessage(new byte[] {3, 9, 9, 9, 9, 9, 9, 9});
    rotation.setCiphertextType(2);
    final MessageEnvelope combined =
        EnvelopeCodec.fromWire(EnvelopeCodec.toWire(rotation));

    strip.processUpdatedPreKeyResponseForTest(combined, bob);

    assertTrue("the notice must not depend on which arm the messenger routed the envelope to. "
            + "Appending one field moved it to the arm with no reader, and that arm is the "
            + "ordinary shape for a rotation. Banner: " + banner(),
        banner().contains("could not be saved"));
  }
}
