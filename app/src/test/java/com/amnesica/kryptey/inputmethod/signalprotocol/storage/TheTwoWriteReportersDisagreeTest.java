package com.amnesica.kryptey.inputmethod.signalprotocol.storage;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.amnesica.kryptey.inputmethod.signalprotocol.Account;
import com.amnesica.kryptey.inputmethod.signalprotocol.SignalProtocolMain;
import com.amnesica.kryptey.inputmethod.signalprotocol.chat.Contact;
import com.amnesica.kryptey.inputmethod.signalprotocol.encoding.EnvelopeCodec;
import com.amnesica.kryptey.inputmethod.signalprotocol.helper.StorageHelper;
import com.amnesica.kryptey.inputmethod.signalprotocol.util.ProtocolAddresses;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.signal.libsignal.protocol.SignalProtocolAddress;

import java.util.ArrayList;

/**
 * With no storage helper, the app says a creation was saved and a deletion was not.
 *
 * <p>Same reality — nothing is written, because there is nothing to write with — and two opposite
 * answers, because two helpers answer the question:
 *
 * <ul>
 *   <li>{@code accountWriteSucceeded()} returns <b>true</b> on a null helper. Contact creation,
 *       verification and key rejection report through it.</li>
 *   <li>{@code storeAllAccountInformationInSharedPreferences()} returns <b>false</b> on a null
 *       helper, and {@code removeContact} returns its result directly.</li>
 * </ul>
 *
 * <p><b>Why this is pinned rather than fixed.</b> The true-on-null branch fails <em>open</em>: every
 * trust decision the user makes would be reported as saved while nothing was stored. It is
 * unreachable in production today — {@code LatinIME.setInputView} always passes a real context, so
 * the helper always exists — but "unreachable today" is precisely the status this document has been
 * wrong about before. Making it fail closed is one line and turns <b>46</b> existing tests red,
 * because that many fixtures quietly depend on the fail-open. That is a deliberate migration, not a
 * drive-by, and it is recorded in REVIVAL.md as owed.
 *
 * <p>Until then this asymmetry is a live trap for anyone writing a test: in the ordinary fixture a
 * created contact reports success and a deleted one reports failure, so a test can assert
 * post-deletion state that only holds because the write was reported lost. One had. It is the reason
 * this file exists.
 */
@RunWith(RobolectricTestRunner.class)
public class TheTwoWriteReportersDisagreeTest {

  private Account account;
  private Contact bob;

  @Before
  public void setUp() throws Exception {
    SignalProtocolMain.resetForTest();
    SignalProtocolMain.testIsRunning = true;

    SignalProtocolMain.initialize(null);
    final Account peer = SignalProtocolMain.getInstance().getAccount();
    final SignalProtocolAddress peerAddress = ProtocolAddresses.of(
        peer.getSignalProtocolAddress().getName(), peer.getDeviceId());
    final String peerBundle = SignalProtocolMain.exportOwnKeyBundle();

    SignalProtocolMain.initialize(null);
    account = SignalProtocolMain.getInstance().getAccount();
    account.setMessageLogLoader(ArrayList::new);
    assertTrue(SignalProtocolMain.processPreKeyResponseMessage(
        EnvelopeCodec.fromWire(peerBundle), peerAddress));

    bob = new Contact("Bob", "Jones", peerAddress.getName(), peerAddress.getDeviceId(), false);
    final ArrayList<Contact> contacts = new ArrayList<>();
    contacts.add(bob);
    account.setContactList(contacts);
    SignalProtocolMain.setStorageStateForTest(StorageHelper.StorageState.READABLE);
  }

  @After
  public void tearDown() {
    SignalProtocolMain.resetForTest();
    SignalProtocolMain.testIsRunning = false;
  }

  /** Creation, through the reporter that fails open. */
  @Test
  public void acreationReportsSuccessWithNoStorageHelperAtAll() throws Exception {
    assertNotNull(SignalProtocolMain.addContact("Carol", "Smith",
        bob.getSignalProtocolAddressName(), bob.getDeviceId() + 1));

    assertTrue("accountWriteSucceeded returns true on a null helper, so creation reports saved "
            + "while nothing was written. If this ever goes FALSE the fail-open has been closed - "
            + "which is the intended direction, and the 46 fixtures that depend on it are the "
            + "migration REVIVAL.md records as owed.",
        SignalProtocolMain.lastContactWriteReachedDisk());
  }

  /** Deletion, through the reporter that fails closed, in the very same fixture. */
  @Test
  public void adeletionReportsFailureInTheSameFixture() {
    assertFalse("removeContact returns storeAllAccountInformationInSharedPreferences directly, "
            + "which returns false on a null helper - the opposite answer to the one creation gave "
            + "about the same store, one line above in the same test class",
        SignalProtocolMain.removeContactFromContactListAndProtocol(bob));
  }

  /**
   * And the disagreement is about the reporters, not about the store.
   *
   * <p>With a helper that really writes, both answer the same way — which is what makes the two
   * results above an inconsistency rather than two different facts.
   */
  @Test
  public void withArealHelperTheyAgree() throws Exception {
    SignalProtocolMain.getInstance().setStorageHelperForTest(
        new StorageHelper(org.robolectric.RuntimeEnvironment.getApplication(), (ctx, has) -> null) {
          @Override
          public boolean storeAllInformationInSharedPreferences(final Account account) {
            return true;
          }
        });

    assertNotNull(SignalProtocolMain.addContact("Carol", "Smith",
        bob.getSignalProtocolAddressName(), bob.getDeviceId() + 1));
    assertTrue("creation agrees", SignalProtocolMain.lastContactWriteReachedDisk());
    assertTrue("and so does deletion, so the split above is in the reporting and not in the store",
        SignalProtocolMain.removeContactFromContactListAndProtocol(bob));
  }
}
