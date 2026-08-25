package com.amnesica.kryptey.inputmethod.signalprotocol;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;

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
import java.util.Arrays;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;

/**
 * A one-time pre-key handed out in an invite must not be handed out again after the keyboard is
 * raised.
 *
 * <p>{@code getPreKeyBundle} does two things that have to agree on disk: it allocates and mints the
 * key material for this bundle, and it records that the id has now been spent.
 * {@code PreKeyStoreImpl.loadPreKey} is what sets the spent flag - and it is called from the
 * {@code new PreKeyBundle(...)} argument list, which is <em>after</em> the persist. So the
 * allocation reaches disk and the spent flag does not.
 *
 * <p>The gap only closes by accident: the next unrelated account save flushes the previous bundle's
 * flag. Whatever the last handed-out bundle spent is still marked unused on disk, and
 * {@code reloadAccount} runs on every {@code setInputView} - a rotation, a theme change, or simply
 * putting the keyboard away and raising it again. After that reload
 * {@code findUnusedPreKeyId} hands the same id, backed by the same key material, to the next person
 * invited.
 *
 * <p>That is the exact failure the per-bundle allocator was written to prevent, arriving through
 * the durability boundary instead of through the allocator: see {@code OneTimePreKeyTest}, which
 * pins the allocator in isolation and never reloads, and {@code AbandonedInviteRetentionTest},
 * which issues every invite inside one process.
 */
@RunWith(RobolectricTestRunner.class)
public class InviteAcrossReloadTest {

  private Context context;
  private SecretKey key;

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

  private static int preKeyIdOf(final MessageEnvelope envelope) {
    return envelope.getPreKeyResponse().getDevices().get(0).getPreKey().getKeyId();
  }

  private static byte[] preKeyOf(final MessageEnvelope envelope) {
    return envelope.getPreKeyResponse().getDevices().get(0).getPreKey().getPublicKey().serialize();
  }

  private static void activate(final Account account) {
    SignalProtocolMain.getInstance().setAccount(account);
  }

  private static SignalProtocolAddress addressOf(final Account account) {
    return ProtocolAddresses.of(account.getSignalProtocolAddress().getName(),
        account.getDeviceId());
  }

  /**
   * The bookkeeping property, stated directly: an id spent by one invite is not offered to the
   * next one after a reload.
   */
  @Test
  public void anidSpentByOneInviteIsNotHandedOutAgainAfterTheKeyboardIsRaised() {
    final MessageEnvelope first = SignalProtocolMain.getPreKeyResponseMessage();
    assertNotNull("precondition: the first invite must be produced", first);

    // The keyboard is put away and raised again. This is all setInputView does to the account.
    SignalProtocolMain.reloadAccount(context);

    final MessageEnvelope second = SignalProtocolMain.getPreKeyResponseMessage();
    assertNotNull("precondition: the second invite must be produced", second);

    assertFalse("the one-time pre key spent by the first invite was handed out again to the "
            + "second: getPreKeyBundle persists before loadPreKey marks the id spent, so the mark "
            + "never reaches disk and the reload brings the id back as unused",
        preKeyIdOf(first) == preKeyIdOf(second)
            && Arrays.equals(preKeyOf(first), preKeyOf(second)));
  }

  /**
   * And what that costs the second person invited.
   *
   * <p>Both invitees build a session against the same one-time pre-key. The first to reply is read
   * normally, and decrypting their message regenerates that id in place - so the second invitee's
   * opening message references key material this device no longer holds, and can never be read.
   * Nothing on either side reports anything but an opaque decryption failure.
   */
  @Test
  public void thesecondInviteesFirstMessageIsStillReadableAfterTheFirstReplies() throws Exception {
    final String inviteForBob = EnvelopeCodec.toWire(SignalProtocolMain.getPreKeyResponseMessage());

    SignalProtocolMain.reloadAccount(context);

    final String inviteForCarol =
        EnvelopeCodec.toWire(SignalProtocolMain.getPreKeyResponseMessage());
    final Account alice = SignalProtocolMain.getInstance().getAccount();
    assertNotNull(alice);

    // Bob and Carol are other devices; they need no storage of their own here.
    SignalProtocolMain.getInstance().setStorageHelperForTest(null);
    SignalProtocolMain.initialize(null);
    final Account bob = SignalProtocolMain.getInstance().getAccount();
    SignalProtocolMain.initialize(null);
    final Account carol = SignalProtocolMain.getInstance().getAccount();
    assertNotNull(bob);
    assertNotNull(carol);

    activate(bob);
    assertTrue("precondition: Bob must be able to open his invite",
        SignalProtocolMain.processPreKeyResponseMessage(
            EnvelopeCodec.fromWire(inviteForBob), addressOf(alice)));
    final String fromBob = EnvelopeCodec.toWire(
        SignalProtocolMain.encryptMessage("hello from bob", addressOf(alice)));

    activate(carol);
    assertTrue("precondition: Carol must be able to open her invite",
        SignalProtocolMain.processPreKeyResponseMessage(
            EnvelopeCodec.fromWire(inviteForCarol), addressOf(alice)));
    final String fromCarol = EnvelopeCodec.toWire(
        SignalProtocolMain.encryptMessage("hello from carol", addressOf(alice)));

    activate(alice);
    assertEquals("precondition: the first reply must be readable", "hello from bob",
        SignalProtocolMain.decryptMessage(EnvelopeCodec.fromWire(fromBob), addressOf(bob)));

    String recovered;
    try {
      recovered = SignalProtocolMain.decryptMessage(
          EnvelopeCodec.fromWire(fromCarol), addressOf(carol));
    } catch (Exception e) {
      recovered = e.getClass().getSimpleName() + ": " + e.getMessage();
    }
    assertEquals("the second invitee's opening message can never be read: both invites carried the "
            + "same one-time pre key, and decrypting the first reply regenerated that id in place. "
            + "The app's own advice on a decryption failure is to delete and re-invite, which is "
            + "the only thing that recovers - and it discards the message",
        "hello from carol", recovered);
  }
}
