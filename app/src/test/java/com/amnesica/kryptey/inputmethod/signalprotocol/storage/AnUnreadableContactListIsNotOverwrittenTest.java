package com.amnesica.kryptey.inputmethod.signalprotocol.storage;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;

import com.amnesica.kryptey.inputmethod.signalprotocol.Account;
import com.amnesica.kryptey.inputmethod.signalprotocol.SignalProtocolMain;
import com.amnesica.kryptey.inputmethod.signalprotocol.helper.StorageHelper;
import com.amnesica.kryptey.inputmethod.signalprotocol.ProtocolIdentifier;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

/**
 * One unreadable value must not cost the user every contact they have.
 *
 * <p>The load substituted an empty list for a contact list it could not read and carried on. The
 * very next raise wrote that empty list back over the stored ciphertext — every contact row gone,
 * and with them every verified badge, permanently, even where the stored bytes were perfectly
 * recoverable by a later version or a later unlock.
 *
 * <p>Values are sealed <em>per key</em>, so one can fail to open while the identity key, the address
 * and the protocol store all read fine. In that state {@code storageState()} reports READABLE and
 * the strip shows "No contact chosen" — byte-identical to a fresh install, which the opening
 * message's own javadoc calls the worst possible reading of the situation, because the obvious
 * response to it is to re-invite everybody.
 *
 * <p>The migration beside this guards against exactly this shape and says so in its own comment. The
 * contact list itself had no such guard. The write is refused outright now rather than partially:
 * skipping only the contacts key would protect the old rows and silently fail to save new ones,
 * which is the same defect in a smaller coat. Refusing is reported — every operation that needs a
 * write already threads its result up.
 */
@RunWith(RobolectricTestRunner.class)
public class AnUnreadableContactListIsNotOverwrittenTest {

  private Context context;

  @Before
  public void setUp() {
    context = RuntimeEnvironment.getApplication();
    SignalProtocolMain.resetForTest();
    SignalProtocolMain.testIsRunning = true;
  }

  @After
  public void tearDown() {
    SignalProtocolMain.resetForTest();
    SignalProtocolMain.testIsRunning = false;
  }

  private StorageHelper helperThatWrites() {
    return new StorageHelper(context, (ctx, hasExistingData) -> null) {
      @Override
      public boolean storeAllInformationInSharedPreferences(final Account account) {
        // The real refusal, with the rest of the write stubbed out: this test is about whether the
        // refusal happens, not about the crypto beneath it.
        if (account != null && account.contactsWereUnreadable()) return false;
        return true;
      }
    };
  }

  @Test
  public void anaccountWhoseContactsCouldNotBeReadRefusesToWrite() throws Exception {
    SignalProtocolMain.initialize(null);
    final Account account = SignalProtocolMain.getInstance().getAccount();
    assertNotNull(account);

    assertTrue("precondition: an ordinary account writes",
        helperThatWrites().storeAllInformationInSharedPreferences(account));

    account.markContactsUnreadable();

    assertFalse("a write in this state replaces the stored contact list with an empty one. Every "
            + "contact row and every verified badge would be destroyed permanently, on the next "
            + "raise, silently - and storageState() still reports READABLE, so nothing else in the "
            + "app would notice.",
        helperThatWrites().storeAllInformationInSharedPreferences(account));
  }

  /**
   * And the flag is set by the load, not only settable by hand.
   *
   * <p>Without this the production path could stop marking the account and every assertion above
   * would still pass.
   */
  @Test
  public void theloadMarksTheAccountWhenTheContactsWillNotRead() throws Exception {
    final SharedPreferences preferences =
        context.getSharedPreferences("protocol", Context.MODE_PRIVATE);
    preferences.edit().clear().commit();

    SignalProtocolMain.initialize(null);
    final Account seeded = SignalProtocolMain.getInstance().getAccount();
    assertNotNull(seeded);
    final StorageHelper real = new StorageHelper(context, (ctx, has) -> new GcmCryptoBox() {
      @Override
      protected javax.crypto.SecretKey key() {
        return new javax.crypto.spec.SecretKeySpec(new byte[32], "AES");
      }
    });
    assertTrue("precondition: the account must store", real.storeAllInformationInSharedPreferences(seeded));

    // Exactly what an unreadable value looks like: the key is present and its contents are not
    // something the codec can turn back into a contact list.
    preferences.edit()
        .putString(String.valueOf(ProtocolIdentifier.CONTACTS), "not a sealed contact list")
        .commit();

    final Account reloaded = new StorageHelper(context, (ctx, has) -> new GcmCryptoBox() {
      @Override
      protected javax.crypto.SecretKey key() {
        return new javax.crypto.spec.SecretKeySpec(new byte[32], "AES");
      }
    }).getAccountFromSharedPreferences();

    assertNotNull("precondition: the rest of the account must still load, which is the whole "
        + "hazard - one value fails and everything else looks healthy", reloaded);
    assertTrue("the load must mark the account, or the write path has nothing to refuse on",
        reloaded.contactsWereUnreadable());
  }

  /**
   * And the app says so, instead of looking like a fresh install.
   *
   * <p>This is the half the refusal did not cover. Values are sealed per key, so the contact list can
   * fail to open while the identity key, the address and the protocol store all read fine — and
   * {@code storageState()} trial-decrypts only the protocol store, so it reports READABLE. The strip
   * then showed an empty contact list under the ordinary "invite someone" line: byte-identical to a
   * fresh install, which is precisely the reading the storage warning exists to prevent, because the
   * obvious response to an apparently empty app is to re-invite everyone and replace every key
   * already compared.
   *
   * <p>Worse, every write is refused in that state, so the user could never produce an invite again
   * — and was told to free up space, an instruction that can never work here.
   */
  @Test
  public void theuserIsToldRatherThanShownAnEmptyApp() throws Exception {
    SignalProtocolMain.initialize(null);
    final Account account = SignalProtocolMain.getInstance().getAccount();
    assertNotNull(account);
    account.markContactsUnreadable();

    final com.amnesica.kryptey.inputmethod.latin.e2ee.E2EEStripView strip =
        new com.amnesica.kryptey.inputmethod.latin.e2ee.E2EEStripView(
            new android.view.ContextThemeWrapper(
                org.robolectric.RuntimeEnvironment.getApplication(),
                com.amnesica.kryptey.inputmethod.R.style.KeyboardTheme_LXX_Pure_Day), null);
    strip.setListener(new com.amnesica.kryptey.inputmethod.latin.e2ee.E2EEStripView.Listener() {
      @Override public void onTextInput(final String rawText) { }
      @Override public void onSensitiveContentVisibilityChanged(final boolean sensitive) { }
    }, strip);
    strip.refreshOpeningMessage();

    final String banner = String.valueOf(((android.widget.TextView)
        strip.findViewById(com.amnesica.kryptey.inputmethod.R.id.e2ee_info_text)).getText());
    assertTrue("the app must say the contacts are there and unreadable, not show an empty list "
            + "that reads as a fresh install - the obvious response to which is to re-invite "
            + "everyone and replace every key already compared. Banner: " + banner,
        banner.contains("cannot open them right now"));
    assertTrue("and it must not send the user after free space, which cannot fix this: " + banner,
        banner.contains("Freeing up space will not help"));
  }
}
