package com.amnesica.kryptey.inputmethod.signalprotocol;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;

import com.amnesica.kryptey.inputmethod.signalprotocol.chat.Contact;
import com.amnesica.kryptey.inputmethod.signalprotocol.helper.StorageHelper;
import com.amnesica.kryptey.inputmethod.signalprotocol.storage.CryptoBox;
import com.amnesica.kryptey.inputmethod.signalprotocol.storage.GcmCryptoBox;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;

/**
 * The re-read that runs on every keyboard raise must not throw away the session's work.
 *
 * <p>{@code LatinIME.onStartInputViewInternal} re-reads the store while one of the two storage
 * warnings is standing, so that a warning raised from a condition can be lowered when the condition
 * ends. The plain {@code reloadAccount} is wrong at that cadence, and only on one arm: when just
 * the CONTACTS value cannot be opened, everything else reads fine, so a whole replacement account is
 * built - with the contact list substituted empty - and installed. Every write has been refused
 * since the fault began, so anything the session did exists in memory only and is destroyed.
 *
 * <p>On a rebuild that happened once per forced configuration change. On every raise it happens
 * whenever the messenger cares to lower and raise the keyboard, which it does by presenting a field.
 *
 * <p>So the account is adopted only when the reload recovers something. What that costs when it DOES
 * recover - the session's memory-only rows - is deliberate and is asserted below too: the in-memory
 * list is the empty substitution plus whatever was added on top, and keeping it would mean the next
 * successful write replaces the user's real contacts with it.
 */
@RunWith(RobolectricTestRunner.class)
public class PerRaiseReloadOnlyAdoptsArecoveredStoreTest {

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
    SignalProtocolMain.initialize(context);
  }

  @After
  public void tearDown() {
    SignalProtocolMain.resetForTest();
    SignalProtocolMain.testIsRunning = false;
  }

  private SharedPreferences store() {
    return context.getSharedPreferences("protocol", Context.MODE_PRIVATE);
  }

  private String storedContactList() {
    return store().getString(
        String.valueOf(com.amnesica.kryptey.inputmethod.signalprotocol.ProtocolIdentifier.CONTACTS),
        null);
  }

  /**
   * The one failure this is about: the CONTACTS value present and unopenable.
   *
   * <p>Everything else in the store still reads, which is what makes this arm different from the
   * whole-store failure - the load succeeds, substitutes an empty list, and marks the account.
   */
  private void sealTheStoredContactList() {
    store().edit().putString(
        String.valueOf(com.amnesica.kryptey.inputmethod.signalprotocol.ProtocolIdentifier.CONTACTS),
        "not a sealed contact list").commit();
  }

  private void restoreStoredContactList(final String sealed) {
    store().edit().putString(
        String.valueOf(com.amnesica.kryptey.inputmethod.signalprotocol.ProtocolIdentifier.CONTACTS),
        sealed).commit();
  }

  /** Writes the account through a helper holding this test's key. */
  private void storeTheAccount(final Account account) {
    new StorageHelper(context, (c, hasExistingData) -> new GcmCryptoBox() {
      @Override
      protected SecretKey key() {
        return key;
      }
    }).storeAllInformationInSharedPreferences(account);
  }

  /** A contact this session added, which the refused writes never got to disk. */
  private Contact addInMemoryOnly(final Account account, final String first) {
    final Contact added = new Contact(first, "Jones", "peer-" + first, 7, false);
    final ArrayList<Contact> contacts = account.getContactList() == null
        ? new ArrayList<>() : new ArrayList<>(account.getContactList());
    contacts.add(added);
    account.setContactList(contacts);
    return added;
  }

  /**
   * While the fault is still there, the re-read changes nothing.
   *
   * <p>This is the whole point: the raise happens, the question is asked again, the answer is still
   * "no", and the account the user is working with is left alone. A plain reload would replace it
   * here - with an emptied contact list - for no gain at all, since nothing has recovered.
   */
  @Test
  public void afailedRereadLeavesTheInMemoryAccountAlone() {
    final Account before = SignalProtocolMain.getInstance().getAccount();
    assertNotNull(before);
    storeTheAccount(before);
    sealTheStoredContactList();
    // Both halves of the production state: the stored value is unopenable, and the account being
    // held is the one the failed load produced, so it carries the mark and every write is refused.
    before.markContactsUnreadable();
    final Contact addedThisSession = addInMemoryOnly(before, "Bob");

    final boolean recovered = SignalProtocolMain.reloadAccountIfStorageRecovered(context);

    assertFalse("nothing recovered, so nothing may be adopted", recovered);
    assertTrue("the account object itself must be the same one, not a replacement",
        before == SignalProtocolMain.getInstance().getAccount());
    assertEquals("the row this session added is memory-only because every write is refused; a "
            + "re-read on every keyboard raise must not be what destroys it", 1,
        SignalProtocolMain.getInstance().getAccount().getContactList().stream()
            .filter(c -> c.getFirstName().equals(addedThisSession.getFirstName())).count());
    assertTrue("and the condition must still be reported, or the warning comes down while it is "
        + "still true", SignalProtocolMain.contactsAreUnreadable());
  }

  /**
   * And when the store recovers, the stored account wins - including over the session's own rows.
   *
   * <p>The losing side is stated rather than hidden: those rows are gone. They have to be. The
   * in-memory list is the empty substitution the failed load left plus whatever was added on top of
   * it, so keeping it and letting the next successful write persist it would replace the user's
   * real contact list with a list that never had their contacts in it.
   */
  @Test
  public void arecoveredRereadAdoptsTheStoredAccount() {
    final Account working = SignalProtocolMain.getInstance().getAccount();
    working.setContactList(new ArrayList<>());
    storeTheAccount(working);
    final String sealed = storedContactList();

    // The fault, a raise while it stands, and a row added in the meantime.
    sealTheStoredContactList();
    working.markContactsUnreadable();
    addInMemoryOnly(working, "Bob");
    assertFalse("fixture: the raise while the fault stands must adopt nothing",
        SignalProtocolMain.reloadAccountIfStorageRecovered(context));

    // The device is unlocked and the value opens again.
    restoreStoredContactList(sealed);

    final boolean recovered = SignalProtocolMain.reloadAccountIfStorageRecovered(context);

    assertTrue("the stored account reads fine, so the condition has ended and must be adopted",
        recovered);
    assertFalse("the reloaded account must not still claim the contacts are unreadable, or the "
            + "warning it was read to lower stays up",
        SignalProtocolMain.contactsAreUnreadable());
    assertEquals("the stored list wins over the substituted one, which is the direction that does "
            + "not overwrite the user's real contacts on the next save", 0,
        SignalProtocolMain.getInstance().getAccount().getContactList().size());
  }
}
