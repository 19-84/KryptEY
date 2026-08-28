package com.amnesica.kryptey.inputmethod.signalprotocol.storage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import com.amnesica.kryptey.inputmethod.signalprotocol.Account;
import com.amnesica.kryptey.inputmethod.signalprotocol.ProtocolIdentifier;
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
import org.robolectric.RuntimeEnvironment;
import org.signal.libsignal.protocol.SignalProtocolAddress;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

/**
 * "Nothing was deleted" has to be true of the messages too.
 *
 * <p>A save writes the message log to its own file <em>first</em> and the account batch second, and
 * reports only the batch's result. That ordering is deliberate and load-bearing — the batch carries
 * the migration marker, and batch-first would let a kill seal that marker over a log still holding
 * pre-upgrade keys, which is unrecoverable.
 *
 * <p>But it means one pair of outcomes is not covered by the report: the log commit lands and the
 * account batch fails. {@code removeContact} then sees "the deletion did not reach disk", rolls the
 * contact, the session and the messages back <em>in memory</em>, and the strip tells the user "they,
 * their key and their saved messages are all still here, exactly as they were". Measured before the
 * fix: the reload came back with the contact row present and the log <b>empty</b>. The one half of
 * the deletion that had actually happened was the destructive one, and the app said the opposite.
 *
 * <p>The repair uses the same ordering that caused it: the rollback writes again, the log goes out
 * first, and the restored messages reach disk even when the account batch fails a second time.
 */
@RunWith(RobolectricTestRunner.class)
public class AfailedDeletionDoesNotTakeTheMessagesWithItTest {

  private Context context;
  private Account victim;
  private Contact bob;
  private static final SecretKey KEY = new SecretKeySpec(new byte[32], "AES");

  @Before
  public void setUp() throws Exception {
    context = RuntimeEnvironment.getApplication();
    context.getSharedPreferences("protocol", Context.MODE_PRIVATE).edit().clear().commit();
    context.getSharedPreferences("protocol_messages", Context.MODE_PRIVATE).edit().clear().commit();
    SignalProtocolMain.resetForTest();
    SignalProtocolMain.testIsRunning = true;

    SignalProtocolMain.initialize(null);
    final Account peer = SignalProtocolMain.getInstance().getAccount();
    final SignalProtocolAddress peerAddress = ProtocolAddresses.of(
        peer.getSignalProtocolAddress().getName(), peer.getDeviceId());
    final String peerBundle = SignalProtocolMain.exportOwnKeyBundle();

    SignalProtocolMain.initialize(null);
    victim = SignalProtocolMain.getInstance().getAccount();
    assertTrue(SignalProtocolMain.processPreKeyResponseMessage(
        EnvelopeCodec.fromWire(peerBundle), peerAddress));

    bob = new Contact("Bob", "Jones", peerAddress.getName(), peerAddress.getDeviceId(), false);
    final ArrayList<Contact> contacts = new ArrayList<>();
    contacts.add(bob);
    victim.setContactList(contacts);
    assertNotNull("fixture: there must be history to lose",
        SignalProtocolMain.encryptMessage("a message worth keeping", peerAddress));

    SignalProtocolMain.getInstance().setStorageHelperForTest(workingHelper());
    assertTrue("fixture: the account and its log must be on disk first",
        workingHelper().storeAllInformationInSharedPreferences(victim));
  }

  @After
  public void tearDown() {
    SignalProtocolMain.resetForTest();
    SignalProtocolMain.testIsRunning = false;
  }

  private StorageHelper workingHelper() {
    return new StorageHelper(context, (ctx, hasExistingData) -> new GcmCryptoBox() {
      @Override
      protected SecretKey key() {
        return KEY;
      }
    });
  }

  /**
   * A helper whose message log seals fine and whose account batch cannot be sealed at all.
   *
   * <p>That is the pair the report does not cover, and it is an ordinary one: this codebase already
   * argues that a nearly full disk produces exactly such split outcomes.
   */
  private StorageHelper helperRefusingTheAccountBatch() {
    return new StorageHelper(context, (ctx, hasExistingData) -> new GcmCryptoBox() {
      @Override
      protected SecretKey key() {
        return KEY;
      }

      @Override
      public byte[] seal(final byte[] plaintext, final byte[] aad) throws StorageCryptoException {
        if (!new String(aad, StandardCharsets.UTF_8)
            .contains(String.valueOf(ProtocolIdentifier.UNENCRYPTED_MESSAGES))) {
          throw new StorageCryptoException("the account batch cannot be sealed");
        }
        return super.seal(plaintext, aad);
      }
    });
  }

  private Account reloaded() {
    final Account account = workingHelper().getAccountFromSharedPreferences();
    assertNotNull("the store must reload", account);
    return account;
  }

  @Test
  public void adeletionThatDidNotLandLeavesTheMessagesOnDisk() {
    SignalProtocolMain.getInstance().setStorageHelperForTest(helperRefusingTheAccountBatch());

    assertFalse("precondition: the deletion must be reported as not having reached disk - that is "
            + "the state the user is told about",
        SignalProtocolMain.removeContactFromContactListAndProtocol(bob));

    final Account after = reloaded();
    assertEquals("the app said the contact, their key and their saved messages were all still "
            + "here. The messages must actually still be here, or that sentence is false about the "
            + "one half of the deletion that did happen - and it is the destructive half", 1,
        after.getUnencryptedMessages().size());
    assertEquals("and the contact must still be there too", 1, after.getContactList().size());
  }

  /**
   * And a deletion that DOES land still removes them.
   *
   * <p>The floor. Without it the test above passes against a build that never prunes the log at all,
   * which would be a promise broken in the other direction: the help text says a deletion removes
   * the saved messages, and someone deleting a contact to get rid of a conversation has to be right
   * about that.
   */
  @Test
  public void adeletionThatLandsStillRemovesThem() {
    SignalProtocolMain.getInstance().setStorageHelperForTest(workingHelper());

    assertTrue("precondition: this deletion must reach disk",
        SignalProtocolMain.removeContactFromContactListAndProtocol(bob));

    final Account after = reloaded();
    assertEquals("a deletion that landed must take the messages with it", 0,
        after.getUnencryptedMessages().size());
    assertEquals("and the contact", 0, after.getContactList().size());
  }
}
