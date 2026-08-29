package com.amnesica.kryptey.inputmethod.latin.e2ee;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Context;
import android.view.ContextThemeWrapper;

import com.amnesica.kryptey.inputmethod.R;
import com.amnesica.kryptey.inputmethod.signalprotocol.Account;
import com.amnesica.kryptey.inputmethod.signalprotocol.SignalProtocolMain;
import com.amnesica.kryptey.inputmethod.signalprotocol.chat.Contact;
import com.amnesica.kryptey.inputmethod.signalprotocol.chat.StorageMessage;
import com.amnesica.kryptey.inputmethod.signalprotocol.helper.StorageHelper;
import com.amnesica.kryptey.inputmethod.signalprotocol.storage.GcmCryptoBox;
import com.amnesica.kryptey.inputmethod.signalprotocol.util.ProtocolAddresses;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.signal.libsignal.protocol.SignalProtocolAddress;

import java.time.Instant;
import java.util.ArrayList;

/**
 * The notice about plaintext a failed deletion left behind must not retire on a reload's account.
 *
 * <p>{@code INFO_DELETED_BUT_MESSAGES_REMAIN} tells the user their deleted contact's messages are
 * still on the device and advises: <em>"Try deleting another contact once the device has free
 * space, which rewrites the same file."</em> It retires itself when a later message-log write
 * lands, and {@code clearAstoreNoticeThatHasBeenResolved} states the premise that makes that a real
 * resolution rather than a timeout:
 *
 * <blockquote>The orphaned entries were already removed from the in-memory log by the deletion;
 * only the write failed. So the next message-log write that does land persists the pruned log, and
 * the plaintext the notice is about is gone.</blockquote>
 *
 * <p>That premise is true of the process that performed the deletion, and a reload ends it.
 * {@code SignalProtocolMain.reloadAccount} replaces the account with the on-disk copy, whose log is
 * the <em>un-pruned</em> one - the write failed, so disk never lost those entries. The pruned list
 * existed only in the account that was just discarded.
 *
 * <p>A previous round found half of this and the counter's own comment records it: the write-back
 * inside {@code reloadAccount} reported a landed log write having touched nothing, so the notice
 * cleared on the first theme change. The fix was the {@code messageLogIsLoaded()} guard, and it
 * closes the bump <em>during</em> the reload, while the log is still deferred. It does not close
 * the one after: once anything reads the log it is loaded, un-pruned, and the next genuine write
 * persists it and advances the counter.
 *
 * <p>So this pins both halves of what the user is actually told. The reload is not exotic - it runs
 * from {@code LatinIME.setInputView} on every theme change, night-mode switch and rotation, which
 * the strip's own comment calls ordinary, and the notice is deliberately carried across that
 * rebuild.
 */
@RunWith(RobolectricTestRunner.class)
public class AstoreNoticeMustOutliveThereloadThatUndoesItsPremiseTest {

  private static final javax.crypto.SecretKey KEY =
      new javax.crypto.spec.SecretKeySpec(new byte[32], "AES");

  private Context context;
  private E2EEStripView strip;
  private Contact bob;
  private SignalProtocolAddress bobAddress;
  private static final String SECRET = "the plaintext a failed deletion left behind";

  /** A real store, so the two files genuinely hold what the test asks them about. */
  private StorageHelper.CryptoBoxFactory box() {
    return (ctx, hasExistingData) -> new GcmCryptoBox() {
      @Override
      protected javax.crypto.SecretKey key() {
        return KEY;
      }
    };
  }

  private StorageHelper realHelper() {
    return new StorageHelper(context, box());
  }

  @Before
  public void setUp() throws Exception {
    final Application app = RuntimeEnvironment.getApplication();
    context = app;
    context.getSharedPreferences("protocol", Context.MODE_PRIVATE).edit().clear().commit();
    context.getSharedPreferences("protocol_messages", Context.MODE_PRIVATE).edit().clear().commit();

    SignalProtocolMain.resetForTest();
    SignalProtocolMain.testIsRunning = true;
    SignalProtocolMain.initialize(null);

    final Account victim = SignalProtocolMain.getInstance().getAccount();
    bobAddress = ProtocolAddresses.of("bob-address-name", 1);
    bob = new Contact("Bob", "Jones", bobAddress.getName(), bobAddress.getDeviceId(), false);
    final ArrayList<Contact> contacts = new ArrayList<>();
    contacts.add(bob);
    victim.setContactList(contacts);

    final ArrayList<StorageMessage> log = new ArrayList<>();
    log.add(new StorageMessage(
        StorageMessage.chatLogKey(bobAddress.getName(), bobAddress.getDeviceId()),
        bobAddress.getName(), "me", Instant.now(), SECRET));
    victim.setUnencryptedMessages(log);

    // The FACTORY, not just the instance: reloadAccount calls initializeStorageHelper, which
    // rebuilds the helper from this factory. Setting only the instance leaves the reload building
    // a production helper over an Android Keystore box that has no JVM implementation, so it reads
    // back nothing and the test measures the fixture instead of the code.
    SignalProtocolMain.setStorageHelperFactoryForTest(ctx -> new StorageHelper(ctx, box()));
    SignalProtocolMain.getInstance().setStorageHelperForTest(realHelper());
    assertTrue("fixture: the seeded store must be writable",
        realHelper().storeAllInformationInSharedPreferences(victim));

    strip = new E2EEStripView(
        new ContextThemeWrapper(app, R.style.KeyboardTheme_LXX_Pure_Day), null);
    strip.setListener(new E2EEStripView.Listener() {
      @Override public void onTextInput(final String rawText) { }
      @Override public void onSensitiveContentVisibilityChanged(final boolean sensitive) { }
    }, strip);
    SignalProtocolMain.setStorageStateForTest(StorageHelper.StorageState.READABLE);
  }

  @After
  public void tearDown() {
    SignalProtocolMain.resetForTest();
    SignalProtocolMain.testIsRunning = false;
  }

  /** Bob's plaintext, as a freshly loaded store reports it. */
  private int bobsMessagesOnDisk() {
    final Account onDisk = realHelper().getAccountFromSharedPreferences();
    assertNotNull("fixture: the store must reload", onDisk);
    int found = 0;
    for (final StorageMessage m : onDisk.getUnencryptedMessages()) {
      if (m.belongsTo(bobAddress.getName(), bobAddress.getDeviceId())) found++;
    }
    return found;
  }

  /**
   * The state the notice describes: pruned in memory, still on disk, notice standing.
   *
   * <p>Built the way the failure builds it rather than by calling the deletion with a broken
   * helper, because what matters downstream is exactly this pair - a log the running process has
   * pruned and a file that still holds the entries - and building it directly keeps the test about
   * the retirement rule instead of about the deletion path, which is pinned elsewhere.
   */
  private void adeletionPrunedMemoryButNotDisk() {
    final Account account = SignalProtocolMain.getInstance().getAccount();
    account.removeAllUnencryptedMessages(bob);
    final ArrayList<Contact> empty = new ArrayList<>();
    account.setContactList(empty);
    strip.setStoreNoticeForTest("Bob Jones was removed, but their saved messages could not be "
        + "deleted - the app could not write to its own storage.");
  }

  /**
   * The premise the retirement rule rests on does not survive a reload.
   *
   * <p>This is the whole finding in one assertion. If the reloaded log came back pruned, the rule
   * would be sound and everything below it would be theatre.
   */
  @Test
  public void areloadBringsTheOrphanedPlaintextBackIntoTheLog() {
    adeletionPrunedMemoryButNotDisk();
    assertEquals("precondition: the running process pruned its own copy", 0,
        countBobIn(SignalProtocolMain.getInstance().getAccount()));
    assertEquals("precondition: the write failed, so the file still holds it", 1,
        bobsMessagesOnDisk());

    SignalProtocolMain.reloadAccount(context);

    assertEquals("the retirement rule's premise is that the in-memory log is the pruned one. A "
            + "reload replaces the account with the on-disk copy, whose log was never pruned - so "
            + "after this the app is holding the orphaned plaintext again, and the next write "
            + "persists it", 1,
        countBobIn(SignalProtocolMain.getInstance().getAccount()));
  }

  private int countBobIn(final Account account) {
    int found = 0;
    for (final StorageMessage m : account.getUnencryptedMessages()) {
      if (m.belongsTo(bobAddress.getName(), bobAddress.getDeviceId())) found++;
    }
    return found;
  }

  /**
   * And the notice retires on the write that makes the leak permanent.
   *
   * <p>The user is told the condition is over at the moment it stops being recoverable.
   */
  @Test
  public void thenoticeMustNotRetireWhileTheOrphanedPlaintextIsStillOnDisk() {
    adeletionPrunedMemoryButNotDisk();
    assertNotNull("precondition: the notice is standing", strip.storeNoticeForTest());

    SignalProtocolMain.reloadAccount(context);
    // Anything that reads the log loads it - un-pruned - and the strip reads it constantly.
    countBobIn(SignalProtocolMain.getInstance().getAccount());
    // The user does exactly what the notice advises: deletes another contact, which rewrites the
    // same file. Carol is seeded here rather than in setUp so the earlier test sees the store the
    // failure actually leaves.
    final Contact carol = new Contact("Carol", "Smith", "carol-address-name", 1, false);
    final ArrayList<Contact> list =
        new ArrayList<>(SignalProtocolMain.getInstance().getAccount().getContactList());
    list.add(carol);
    SignalProtocolMain.getInstance().getAccount().setContactList(list);
    assertTrue("fixture: deleting another contact must reach disk",
        SignalProtocolMain.removeContactFromContactListAndProtocol(carol));
    strip.onClipboardChangedForTest();

    assertEquals("fixture: the orphaned plaintext is still on disk - the write that just landed "
        + "wrote it back rather than pruning it, which is what makes retiring the notice wrong",
        1, bobsMessagesOnDisk());
    assertNotNull("the notice retired on the write that persisted the plaintext it is about. The "
            + "user is told to wait for free space and delete another contact; doing so rewrites "
            + "the file with the orphans still in it and takes the notice down, so the one signal "
            + "that the plaintext is there disappears at the moment it becomes permanent",
        strip.storeNoticeForTest());
  }
}
