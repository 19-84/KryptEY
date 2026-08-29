package com.amnesica.kryptey.inputmethod.signalprotocol;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import com.amnesica.kryptey.inputmethod.signalprotocol.chat.Contact;
import com.amnesica.kryptey.inputmethod.signalprotocol.encoding.EnvelopeCodec;
import com.amnesica.kryptey.inputmethod.signalprotocol.helper.StorageHelper;
import com.amnesica.kryptey.inputmethod.signalprotocol.storage.GcmCryptoBox;
import com.amnesica.kryptey.inputmethod.signalprotocol.util.ProtocolAddresses;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.signal.libsignal.protocol.IdentityKey;
import org.signal.libsignal.protocol.SignalProtocolAddress;

import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;

/**
 * What a rejection that does not reach disk actually costs, measured rather than described.
 *
 * <p>{@code rejectContactKey}'s own comment states the consequence: "the rejected key is pinned
 * again and rejectedAddresses is empty, which is the silent trust-on-first-use markKeyRejected
 * exists to prevent." That is the load-bearing claim behind the app's strongest deliberate
 * refusal, and a review round flagged it as the thing it had not verified. A described consequence
 * is how the size of a defect drifts — which is exactly what happened to the bundle-replay entry
 * this branch already had to re-measure.
 *
 * <p>So this pins it. Not to assert the behaviour is right — it is what it is — but so that a
 * change in either direction fails a test, and so the notice built on top of it is built on a
 * measurement.
 */
@RunWith(RobolectricTestRunner.class)
public class ArejectionThatDidNotLandIsForgottenTest {

  private Context context;
  private SecretKey key;
  private Account victim;
  private Contact bob;
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

  private StorageHelper helper() {
    return new StorageHelper(context, (ctx, hasExistingData) -> new GcmCryptoBox() {
      @Override
      protected SecretKey key() {
        return key;
      }
    });
  }

  /** A helper whose writes all fail, which is the disk-full or locked-device case. */
  private StorageHelper refusingHelper() {
    return new StorageHelper(context, (ctx, hasExistingData) -> null) {
      @Override
      public boolean storeAllInformationInSharedPreferences(final Account account) {
        return false;
      }
    };
  }

  @Before
  public void setUp() throws Exception {
    context = RuntimeEnvironment.getApplication();
    context.getSharedPreferences("protocol", Context.MODE_PRIVATE).edit().clear().commit();
    SignalProtocolMain.resetForTest();
    SignalProtocolMain.testIsRunning = true;
    key = newKey();

    SignalProtocolMain.initialize(null);
    final Account peer = SignalProtocolMain.getInstance().getAccount();
    bobAddress = ProtocolAddresses.of(peer.getSignalProtocolAddress().getName(), peer.getDeviceId());
    final String bobBundle = SignalProtocolMain.exportOwnKeyBundle();

    SignalProtocolMain.setStorageHelperFactoryForTest(ctx -> helper());
    SignalProtocolMain.initialize(context);
    victim = SignalProtocolMain.getInstance().getAccount();
    victim.setMessageLogLoader(ArrayList::new);
    assertTrue(SignalProtocolMain.processPreKeyResponseMessage(
        EnvelopeCodec.fromWire(bobBundle), bobAddress));

    bob = new Contact("Bob", "Jones", bobAddress.getName(), bobAddress.getDeviceId(), false);
    final ArrayList<Contact> contacts = new ArrayList<>();
    contacts.add(bob);
    victim.setContactList(contacts);
  }

  @After
  public void tearDown() {
    SignalProtocolMain.resetForTest();
    SignalProtocolMain.testIsRunning = false;
  }

  /**
   * A rejection that lands survives a reload. The control, so the test below means something.
   */
  @Test
  public void arejectionThatLandsIsRemembered() {
    helper().storeAllInformationInSharedPreferences(victim);
    assertNotNull("precondition: a key must be pinned",
        SignalProtocolMain.pinnedIdentityFor(bobAddress));

    assertTrue(SignalProtocolMain.rejectContactKey(bob));
    assertTrue("precondition: this rejection must have reached disk",
        SignalProtocolMain.lastRejectionReachedDisk());

    SignalProtocolMain.getInstance().setAccount(helper().getAccountFromSharedPreferences());

    assertNull("the refused key must not come back", SignalProtocolMain.pinnedIdentityFor(bobAddress));
    assertTrue("and the address must still be marked, or the next bundle is a clean first sighting",
        SignalProtocolMain.wasKeyRejected(bobAddress));
  }

  /**
   * And one that does not land is forgotten completely — the key comes back and the mark does not.
   *
   * <p>This is the sentence the toast makes, measured. Both halves matter and they are different:
   * the key returning means the app is encrypting to it again, and the mark being absent means the
   * next bundle at that address is treated as a first sighting, so nothing warns. The user
   * performed the app's strongest deliberate refusal and, three and a half seconds later, has no
   * way to tell that any of this happened.
   */
  @Test
  public void arejectionThatDidNotLandIsForgottenEntirely() {
    helper().storeAllInformationInSharedPreferences(victim);
    final IdentityKey pinnedBefore = SignalProtocolMain.pinnedIdentityFor(bobAddress);
    assertNotNull("precondition: a key must be pinned", pinnedBefore);

    // The disk is full, or the device is locked.
    SignalProtocolMain.getInstance().setStorageHelperForTest(refusingHelper());
    assertTrue(SignalProtocolMain.rejectContactKey(bob));
    assertFalse("precondition: this rejection must NOT have reached disk",
        SignalProtocolMain.lastRejectionReachedDisk());
    assertNull("in memory it looks like it worked, which is what makes this quiet",
        SignalProtocolMain.pinnedIdentityFor(bobAddress));
    assertTrue("and in memory the mark is there too",
        SignalProtocolMain.wasKeyRejected(bobAddress));

    // The next keyboard raise, which the messenger triggers by presenting a field.
    SignalProtocolMain.getInstance().setStorageHelperForTest(helper());
    SignalProtocolMain.getInstance().setAccount(helper().getAccountFromSharedPreferences());

    assertEquals("the key the user reported as not matching is pinned again", pinnedBefore,
        SignalProtocolMain.pinnedIdentityFor(bobAddress));
    assertFalse("and the address is unmarked, so the next bundle there is a clean first sighting "
            + "and nothing warns - which is the silent trust-on-first-use markKeyRejected exists "
            + "to prevent, arrived at from the other side",
        SignalProtocolMain.wasKeyRejected(bobAddress));
  }

  /**
   * And a later landed write persists it, which is what makes this unlike a failed deletion.
   *
   * <p>The two look like the same failure and are not. {@code removeContact} rolls the row, its
   * messages and its session back into memory when the write fails, so a later write persists the
   * <em>restored</em> contact — nothing a later write does completes the deletion.
   * {@code rejectContactKey} has no rollback: the identity is removed, the address marked and the
   * session deleted in memory, and only the write failed. So the state the user asked for is
   * already there, and the very next account write that lands puts it on disk.
   *
   * <p>That difference decides how the notice about it must be ended, so it is measured rather than
   * argued.
   */
  @Test
  public void alaterLandedWritePersistsArejectionThatDidNotLand() {
    helper().storeAllInformationInSharedPreferences(victim);
    assertNotNull("precondition: a key must be pinned",
        SignalProtocolMain.pinnedIdentityFor(bobAddress));

    SignalProtocolMain.getInstance().setStorageHelperForTest(refusingHelper());
    assertTrue(SignalProtocolMain.rejectContactKey(bob));
    assertFalse("precondition: the rejection must not have reached disk",
        SignalProtocolMain.lastRejectionReachedDisk());

    // The disk frees up, and anything at all writes the account out.
    SignalProtocolMain.getInstance().setStorageHelperForTest(helper());
    assertTrue("precondition: a write must land",
        helper().storeAllInformationInSharedPreferences(
            SignalProtocolMain.getInstance().getAccount()));

    SignalProtocolMain.getInstance().setAccount(helper().getAccountFromSharedPreferences());

    assertNull("the refused key must be gone from disk now - the rejection was already in memory, "
            + "so the write that landed carried it", SignalProtocolMain.pinnedIdentityFor(bobAddress));
    assertTrue("and the mark with it. This is what makes a failed rejection unlike a failed "
            + "deletion, which rolls back and is NOT completed by a later write",
        SignalProtocolMain.wasKeyRejected(bobAddress));
  }

  /**
   * The account's two device ids must agree after a load, because the encoder depends on it.
   *
   * <p>An account carries the id twice: the scalar {@code getDeviceId()} and the one inside
   * {@code getSignalProtocolAddress()}. The two envelope builders read different ones — the message
   * envelope takes the scalar, the invite envelope takes the address's — and
   * {@code BinaryEnvelope.encode} refuses any envelope whose top-level id is outside libsignal's
   * {@code [1,127]}.
   *
   * <p>They agree only because both {@code new Account(...)} sites pass an already-folded value,
   * and nothing said so. The store still writes a {@code DEVICE_ID} row that nothing reads, which
   * on a 0.1.5 store holds the unfolded {@code nextInt(10000)} value — sitting there looking exactly
   * like the field a future third caller would reach for. If one ever does, every migrated user's
   * ordinary send fails inside {@code encode} while the Invite button keeps working, and the caller
   * catches that {@code IOException} and rolls the chat-log entry back, so the user is told
   * "encryption failed" with nothing naming the cause.
   *
   * <p>Asserted after a real load, because the load-time constructor is what makes it true. Written
   * against a fresh in-memory account first, where it passed with the invariant broken — that path
   * never reaches the constructor under test.
   */
  @Test
  public void anaccountsTwoDeviceIdsAgreeAfterAload() {
    helper().storeAllInformationInSharedPreferences(victim);

    final Account loaded = helper().getAccountFromSharedPreferences();
    assertNotNull("precondition: the store must reload", loaded);

    assertEquals("the scalar device id and the one inside the protocol address must be the same "
            + "number: the message envelope is built from one and the invite envelope from the "
            + "other, and encode refuses anything outside libsignal's range",
        loaded.getDeviceId(), loaded.getSignalProtocolAddress().getDeviceId());
    assertTrue("and it must be a value libsignal accepts, or neither envelope can be built: "
            + loaded.getDeviceId(),
        loaded.getDeviceId() >= 1 && loaded.getDeviceId() <= 127);
  }
}
