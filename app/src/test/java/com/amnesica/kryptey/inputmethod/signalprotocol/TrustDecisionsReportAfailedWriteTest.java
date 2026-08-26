package com.amnesica.kryptey.inputmethod.signalprotocol;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;

import com.amnesica.kryptey.inputmethod.signalprotocol.chat.Contact;
import com.amnesica.kryptey.inputmethod.signalprotocol.encoding.EnvelopeCodec;
import com.amnesica.kryptey.inputmethod.signalprotocol.helper.StorageHelper;
import com.amnesica.kryptey.inputmethod.signalprotocol.storage.CryptoBox;
import com.amnesica.kryptey.inputmethod.signalprotocol.storage.GcmCryptoBox;
import com.amnesica.kryptey.inputmethod.signalprotocol.storage.StorageCryptoException;
import com.amnesica.kryptey.inputmethod.signalprotocol.util.ProtocolAddresses;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.signal.libsignal.protocol.SignalProtocolAddress;

import java.util.ArrayList;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

/**
 * A trust decision that does not reach disk must be reported, not assumed.
 *
 * <p>Both controls here were writing and returning constants. {@code verifyContactInContactList}
 * ended {@code storeAll...(); return true;} while its own javadoc defines false as "verification
 * could not be recorded" — so the one failure that most plausibly means exactly that was the one it
 * could not report. {@code rejectContactKey} discarded the result outright, and the strip then
 * printed "Forgot the stored key for %s" over a key still pinned on disk.
 *
 * <p>The failure is quiet by construction: SharedPreferences restores the previous file from its
 * .bak while the in-memory map keeps the new value, so reads keep succeeding and nothing looks
 * wrong. The next {@code setInputView} calls {@code reloadAccount}, the on-disk copy replaces the
 * account, and the rejected key is pinned again with no record that it was ever rejected — the
 * silent trust-on-first-use that {@code markKeyRejected} exists to prevent.
 */
@RunWith(RobolectricTestRunner.class)
public class TrustDecisionsReportAfailedWriteTest {

  private static final SecretKey KEY = new SecretKeySpec(new byte[32], "AES");

  private Context context;
  private boolean writesFail;
  private Contact bob;

  private CryptoBox box() {
    return new GcmCryptoBox() {
      @Override
      protected SecretKey key() {
        return KEY;
      }
    };
  }

  /** A helper whose writes can be made to fail the way a full disk makes them fail. */
  private StorageHelper helper() {
    return new StorageHelper(context, (ctx, hasExistingData) -> box()) {
      @Override
      public boolean storeAllInformationInSharedPreferences(final Account account) {
        if (writesFail) return false;
        return super.storeAllInformationInSharedPreferences(account);
      }
    };
  }

  @Before
  public void setUp() throws Exception {
    context = RuntimeEnvironment.getApplication();
    final SharedPreferences preferences =
        context.getSharedPreferences("protocol", Context.MODE_PRIVATE);
    preferences.edit().clear().commit();
    SignalProtocolMain.resetForTest();
    SignalProtocolMain.testIsRunning = true;
    writesFail = false;

    SignalProtocolMain.setStorageHelperFactoryForTest(ctx -> helper());

    SignalProtocolMain.initialize(null);
    final Account peer = SignalProtocolMain.getInstance().getAccount();
    final SignalProtocolAddress peerAddress = ProtocolAddresses.of(
        peer.getSignalProtocolAddress().getName(), peer.getDeviceId());
    final String peerBundle = SignalProtocolMain.exportOwnKeyBundle();

    SignalProtocolMain.initialize(context);
    assertTrue(SignalProtocolMain.processPreKeyResponseMessage(
        EnvelopeCodec.fromWire(peerBundle), peerAddress));

    bob = new Contact("Bob", "Jones", peerAddress.getName(), peerAddress.getDeviceId(), false);
    final ArrayList<Contact> contacts = new ArrayList<>();
    contacts.add(bob);
    SignalProtocolMain.getInstance().getAccount().setContactList(contacts);
  }

  @After
  public void tearDown() {
    SignalProtocolMain.resetForTest();
    SignalProtocolMain.testIsRunning = false;
  }

  /** Verifying reports success when the badge really was written. */
  @Test
  public void verifyingReportsSuccessWhenTheWriteLands() throws Exception {
    assertTrue("a verification that reached disk must report success",
        SignalProtocolMain.verifyContact(bob));
  }

  /** And reports failure when it did not, which is what the strip renders as unavailable. */
  @Test
  public void verifyingReportsFailureWhenTheWriteDoesNot() throws Exception {
    writesFail = true;
    assertFalse("a verification that did not reach disk must say so: the badge would appear and "
        + "then be taken away by the next reload, with the user believing they had compared the "
        + "number", SignalProtocolMain.verifyContact(bob));
  }

  /** Rejecting reports that its record landed. */
  @Test
  public void rejectingReportsThatTheRecordLanded() {
    assertTrue("precondition: there must be a key to forget",
        SignalProtocolMain.rejectContactKey(bob));
    assertTrue("a rejection that reached disk must say so",
        SignalProtocolMain.lastRejectionReachedDisk());
  }

  /** And reports that it did not, separately from whether there was a key to forget. */
  @Test
  public void rejectingReportsAfailedWriteSeparatelyFromWhetherAkeyExisted() {
    writesFail = true;
    final boolean hadPin = SignalProtocolMain.rejectContactKey(bob);

    assertTrue("the return value answers 'was there a key to forget', which is unchanged by the "
        + "write failing", hadPin);
    assertFalse("and the write failing must be reported through its own channel, or the strip "
        + "prints 'Forgot the stored key' over a key that is still pinned on disk and will be back "
        + "on the next raise", SignalProtocolMain.lastRejectionReachedDisk());
  }

  /**
   * The flag must describe THIS rejection, not the last one that got as far as writing.
   *
   * <p>{@code rejectContactKey} returns early when there is no account, and that path used to leave
   * the flag holding a previous outcome - so a caller asking "did this one land" was told about a
   * different one.
   */
  @Test
  public void afailedWriteIsNotRememberedIntoTheNextRejection() {
    writesFail = true;
    SignalProtocolMain.rejectContactKey(bob);
    assertFalse("precondition: the first rejection must have failed to write",
        SignalProtocolMain.lastRejectionReachedDisk());

    // A call that does nothing at all: no contact.
    SignalProtocolMain.rejectContactKey(null);

    assertTrue("a rejection that never happened must not report the previous one's failure",
        SignalProtocolMain.lastRejectionReachedDisk());
  }
}
