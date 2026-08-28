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
 * With no storage helper, both reporters now say the same thing: nothing was written.
 *
 * <p>They did not always. Two helpers answer "did the write land", and they used to disagree in the
 * one state where the answer is unambiguous — there is no storage helper at all, so nothing can
 * possibly have been stored:
 *
 * <ul>
 *   <li>{@code accountWriteSucceeded()} returned <b>true</b> on a null helper. Contact creation,
 *       verification and key rejection report through it.</li>
 *   <li>{@code storeAllAccountInformationInSharedPreferences()} returns <b>false</b> on a null
 *       helper, and {@code removeContact} returns its result directly.</li>
 * </ul>
 *
 * <p>So the app said a created contact was saved and a deleted one was lost, about the same store,
 * in the same breath — and the true-on-null branch was the one that failed <b>open</b>: every trust
 * decision reported as saved while nothing was stored.
 *
 * <p>It was unreachable in production — {@code LatinIME.setInputView} always passes a real context —
 * but every test fixture inherited the split, and three of them ended up asserting state that held
 * only because a write had been reported one way rather than the other. Closing it turned 48 tests
 * red across 17 classes; each now says whether it needs a write to land, via {@link TestStores}.
 *
 * <p>This file is what stops it coming back. Both directions are asserted, so restoring the
 * true-on-null branch fails here rather than quietly changing what a dozen fixtures mean.
 */
@RunWith(RobolectricTestRunner.class)
public class TheTwoWriteReportersAgreeTest {

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

  /** Creation, through the reporter that used to fail open. */
  @Test
  public void acreationReportsFailureWithNoStorageHelperAtAll() throws Exception {
    assertNotNull(SignalProtocolMain.addContact("Carol", "Smith",
        bob.getSignalProtocolAddressName(), bob.getDeviceId() + 1));

    assertFalse("with no storage helper nothing can have been written, so creation must not report "
            + "saved. If this goes TRUE the fail-open is back, and with it a state where every "
            + "trust decision the user makes is reported as stored while none of it is.",
        SignalProtocolMain.lastContactWriteReachedDisk());
  }

  /** Deletion, in the very same fixture, agreeing. */
  @Test
  public void adeletionReportsFailureInTheSameFixture() {
    assertFalse("removeContact returns storeAllAccountInformationInSharedPreferences directly. It "
            + "has always answered false here; the point of the test above it is that creation now "
            + "answers the same way about the same store.",
        SignalProtocolMain.removeContactFromContactListAndProtocol(bob));
  }

  /**
   * And they agree in the other direction too.
   *
   * <p>Without this the pair above would pass on a build where both reporters simply always said
   * "no", which is agreement of the useless kind.
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
