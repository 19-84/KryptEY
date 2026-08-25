package com.amnesica.kryptey.inputmethod.signalprotocol;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;

import com.amnesica.kryptey.inputmethod.signalprotocol.chat.Contact;
import com.amnesica.kryptey.inputmethod.signalprotocol.chat.StorageMessage;
import com.amnesica.kryptey.inputmethod.signalprotocol.encoding.EnvelopeCodec;
import com.amnesica.kryptey.inputmethod.signalprotocol.helper.StorageHelper;
import com.amnesica.kryptey.inputmethod.signalprotocol.storage.CryptoBox;
import com.amnesica.kryptey.inputmethod.signalprotocol.storage.GcmCryptoBox;
import com.amnesica.kryptey.inputmethod.signalprotocol.util.ProtocolAddresses;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.signal.libsignal.protocol.SignalProtocolAddress;

import java.security.NoSuchAlgorithmException;
import java.util.List;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;

/**
 * Sending a message advances the session and writes the plaintext into the user's own history, both
 * in memory, and then makes one call to persist them.
 *
 * <p>Measured: deleting that call left the whole suite green. What it costs is two things at once.
 * The user's own sent messages disappear from their history on the next raise - the log is written
 * by this call and by no other on the send path. And the ratchet goes back, so the next message the
 * user sends carries a chain index the recipient has already seen and is rejected as a duplicate;
 * the session looks fine on both sides and simply stops delivering.
 *
 * <p>{@code reloadAccount} runs on every {@code setInputView}, and pressing send is immediately
 * followed by leaving the keyboard, so the reload is not a rare event here - it is the next thing
 * that happens.
 */
@RunWith(RobolectricTestRunner.class)
public class SentMessageSurvivesTheNextRaiseTest {

  private Context context;
  private SecretKey key;
  private SignalProtocolAddress bobAddress;

  private static SecretKey newKey() {
    try {
      final KeyGenerator generator = KeyGenerator.getInstance("AES");
      generator.init(256);
      return generator.generateKey();
    } catch (NoSuchAlgorithmException e) {
      throw new AssertionError(e);
    }
  }

  @Before
  public void setUp() {
    SignalProtocolMain.resetForTest();
    context = RuntimeEnvironment.getApplication();
    final SharedPreferences preferences =
        context.getSharedPreferences("protocol", Context.MODE_PRIVATE);
    preferences.edit().clear().commit();
    key = newKey();
    final CryptoBox box = new GcmCryptoBox() {
      @Override
      protected SecretKey key() {
        return key;
      }
    };
    SignalProtocolMain.setStorageHelperFactoryForTest(
        ctx -> new StorageHelper(ctx, (c, hasExistingData) -> box));
    SignalProtocolMain.testIsRunning = true;
    assertTrue("precondition: the account must be created and persisted",
        SignalProtocolMain.initialize(context));
  }

  @After
  public void tearDown() {
    SignalProtocolMain.resetForTest();
  }

  private Contact bob() {
    for (final Contact candidate : SignalProtocolMain.getContactList()) {
      if (candidate.getSignalProtocolAddress().equals(bobAddress)) return candidate;
    }
    throw new AssertionError("Bob is not in the reloaded contact list");
  }

  @Test
  public void asentMessageAndTheRatchetItAdvancedAreOnDiskBeforeTheNextRaise() throws Exception {
    // Bob, with no storage of his own, and the invite the user opens.
    final Account alice = SignalProtocolMain.getInstance().getAccount();
    SignalProtocolMain.getInstance().setStorageHelperForTest(null);
    SignalProtocolMain.initialize(null);
    final Account bobAccount = SignalProtocolMain.getInstance().getAccount();
    bobAddress = ProtocolAddresses.of(bobAccount.getSignalProtocolAddress().getName(),
        bobAccount.getDeviceId());
    final String bobInvite = SignalProtocolMain.exportOwnKeyBundle();

    SignalProtocolMain.getInstance().setAccount(alice);
    SignalProtocolMain.reloadAccount(context);

    SignalProtocolMain.addContact("Bob", "Jones", bobAddress.getName(),
        bobAddress.getDeviceId());
    assertTrue("precondition: the session must be built",
        SignalProtocolMain.processPreKeyResponseMessage(
            EnvelopeCodec.fromWire(bobInvite), bobAddress));

    final MessageEnvelope sent =
        SignalProtocolMain.encryptMessage("the meeting is at nine", bobAddress);
    assertNotNull("precondition: the message must encrypt", sent);

    // The user leaves the keyboard, which is what pressing send is followed by.
    SignalProtocolMain.reloadAccount(context);
    assertNotNull("precondition: the account must reload", SignalProtocolMain.getInstance()
        .getAccount());

    final List<StorageMessage> log = SignalProtocolMain.getUnencryptedMessagesList(bob());
    assertEquals("the message the user sent is not in their history after one raise: the send path "
            + "writes the chat log in memory and persists it exactly once, and nothing asserted "
            + "that call happened", 1, log.size());
    assertEquals("the meeting is at nine", log.get(0).getUnencryptedMessage());

    // And the ratchet went with it: the second message must not reuse the first one's index.
    final MessageEnvelope second =
        SignalProtocolMain.encryptMessage("and bring the file", bobAddress);
    assertNotNull("precondition: the second message must encrypt", second);

    SignalProtocolMain.getInstance().setAccount(bobAccount);
    assertEquals("precondition: Bob reads the first message", "the meeting is at nine",
        SignalProtocolMain.decryptMessage(sent, ProtocolAddresses.of(
            alice.getSignalProtocolAddress().getName(), alice.getDeviceId())));
    assertEquals("the second message carries an index Bob has already seen, so it is rejected as a "
            + "replay: the ratchet the first send advanced never reached disk and the reload put "
            + "it back", "and bring the file",
        SignalProtocolMain.decryptMessage(second, ProtocolAddresses.of(
            alice.getSignalProtocolAddress().getName(), alice.getDeviceId())));
  }
}
