package com.amnesica.kryptey.inputmethod.signalprotocol.helper;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;

import com.amnesica.kryptey.inputmethod.signalprotocol.Account;
import com.amnesica.kryptey.inputmethod.signalprotocol.ProtocolIdentifier;
import com.amnesica.kryptey.inputmethod.signalprotocol.SignalProtocolMain;
import com.amnesica.kryptey.inputmethod.signalprotocol.storage.GcmCryptoBox;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.util.ArrayList;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

/**
 * A stored value that is present and will not open must not be replaced by a default.
 *
 * <p>Values are sealed per key, so one can fail to open while every other reads fine — the codebase
 * says so in two places and built {@code contactsWereUnreadable} for exactly it. A sealed value
 * that will not decrypt reads back as null, which is indistinguishable from one that was never
 * stored, so the loader substituted a default and the write-back that follows every load persisted
 * the substitution over ciphertext that may well have been recoverable.
 *
 * <p>The contact list has had a guard against this since a review round found it. These are the two
 * that did not: the display-tag secret and the retired display names.
 *
 * <p>Per key rather than a whole-store refusal, and the difference matters. Copying the contact
 * list's treatment would turn one corrupt row into a permanently read-only app — no contact added,
 * no key pinned, no rejection recorded, and nothing to repair it. {@code putAll} clears nothing, so
 * omitting one key leaves its bytes alone.
 */
@RunWith(RobolectricTestRunner.class)
public class AnunreadableValueIsNotWrittenOverTest {

  private static final SecretKey KEY = new SecretKeySpec(new byte[32], "AES");

  private Context context;

  private StorageHelper helper() {
    return new StorageHelper(context, (ctx, hasExistingData) -> new GcmCryptoBox() {
      @Override
      protected SecretKey key() {
        return KEY;
      }
    });
  }

  private SharedPreferences store() {
    return context.getSharedPreferences("protocol", Context.MODE_PRIVATE);
  }

  @Before
  public void setUp() {
    context = RuntimeEnvironment.getApplication();
    store().edit().clear().commit();
    SignalProtocolMain.resetForTest();
    SignalProtocolMain.testIsRunning = true;
    SignalProtocolMain.initialize(null);
  }

  @After
  public void tearDown() {
    SignalProtocolMain.resetForTest();
    SignalProtocolMain.testIsRunning = false;
  }

  private Account storedAccount() {
    final Account account = SignalProtocolMain.getInstance().getAccount();
    account.setMessageLogLoader(ArrayList::new);
    account.retireDisplayName("Bob", "Jones", "bob-uuid.1");
    assertTrue("fixture: a retired name must have been recorded",
        !account.getRetiredDisplayNames().isEmpty());
    assertNotNull("fixture: a tag secret must exist", account.getDisplayTagSecret());
    assertTrue(helper().storeAllInformationInSharedPreferences(account));
    return account;
  }

  /** The tag secret: unreadable, so a fresh one is minted — and the stored bytes must survive. */
  @Test
  public void anunreadableTagSecretIsNotOverwritten() {
    final Account before = storedAccount();
    final byte[] secretBefore = before.getDisplayTagSecret();
    final String key = String.valueOf(ProtocolIdentifier.DISPLAY_TAG_SECRET);
    final String sealedBefore = store().getString(key, null);
    assertNotNull("fixture: the secret must be on disk", sealedBefore);

    // One flipped value: present, and it will not open. No attacker cooperation needed.
    store().edit().putString(key, "not a sealed secret").commit();

    final Account loaded = helper().getAccountFromSharedPreferences();
    assertNotNull("the store must still load - one bad value is not a lost account", loaded);
    loaded.setMessageLogLoader(ArrayList::new);
    // This used to assert the opposite - that a FRESH secret was minted here, "which is the
    // substitution". That was the behaviour, and it was a defect rather than a property: the
    // session's secret was never persisted (this test's whole point is that the ciphertext is not
    // written over), so every raise minted another one and every contact's tag moved with it. The
    // secret is derived from the identity private key now, so it is the same one, and the stored
    // bytes are still not touched - which is what the rest of this test checks.
    assertEquals("the secret must not depend on when the account was loaded, or the tag it keys "
            + "changes on every keyboard raise",
        java.util.Arrays.toString(secretBefore),
        java.util.Arrays.toString(loaded.getDisplayTagSecret()));

    // The write-back that follows every load.
    assertTrue(helper().storeAllInformationInSharedPreferences(loaded));

    assertEquals("the stored bytes must be exactly as they were. Written over, every contact's tag "
            + "changes at once and can never come back, and a tag is only useful because it is the "
            + "same next time you look",
        "not a sealed secret", store().getString(key, null));
  }

  /**
   * And the tag the user compares must not change on every keyboard raise.
   *
   * <p>The other direction of the preservation above, and the one it opened. Skipping the key
   * protects the stored ciphertext; it also means the secret this session is actually using is
   * never persisted. {@code Account}'s constructor mints a fresh random one on every load, and
   * {@code reloadAccount} runs from {@code LatinIME.setInputView} - so every raise HMACs the same
   * contact under a different secret.
   *
   * <p>Within one raise two rows are still comparable, so the duplicate-name defence still works.
   * Across raises the tag is noise, permanently and with no repair path, which is the cries-wolf
   * failure the preservation comment beside it names: <em>"a tag is only useful because it is the
   * same next time you look"</em>. This asserts the property that sentence claims.
   */
  @Test
  public void thetagMustNotChangeOnEveryRaiseWhileTheSecretIsUnreadable() {
    storedAccount();
    final String key = String.valueOf(ProtocolIdentifier.DISPLAY_TAG_SECRET);
    store().edit().putString(key, "not a sealed secret").commit();

    final Account firstRaise = helper().getAccountFromSharedPreferences();
    assertNotNull(firstRaise);
    firstRaise.setMessageLogLoader(ArrayList::new);
    assertTrue(helper().storeAllInformationInSharedPreferences(firstRaise));
    final String tagFirst =
        java.util.Arrays.toString(firstRaise.getDisplayTagSecret());

    final Account secondRaise = helper().getAccountFromSharedPreferences();
    assertNotNull(secondRaise);
    secondRaise.setMessageLogLoader(ArrayList::new);
    final String tagSecond =
        java.util.Arrays.toString(secondRaise.getDisplayTagSecret());

    assertEquals("the secret changed between two raises, so every contact's tag did too. The tag "
            + "is the anti-impersonation disambiguator between two rows the user cannot otherwise "
            + "tell apart, and one that differs every time the keyboard comes up is noise rather "
            + "than a comparison",
        tagFirst, tagSecond);
  }

  /** And the retired names, whose loss is a security control rather than a comparison aid. */
  @Test
  public void anunreadableRetiredNameListIsNotOverwritten() {
    storedAccount();
    final String key = String.valueOf(ProtocolIdentifier.RETIRED_DISPLAY_NAMES);
    assertNotNull("fixture: the retired names must be on disk", store().getString(key, null));

    store().edit().putString(key, "not a sealed list").commit();

    final Account loaded = helper().getAccountFromSharedPreferences();
    assertNotNull(loaded);
    loaded.setMessageLogLoader(ArrayList::new);
    assertTrue("precondition: the substitution is an empty list",
        loaded.getRetiredDisplayNames().isEmpty());

    assertTrue(helper().storeAllInformationInSharedPreferences(loaded));

    assertEquals("the stored list must survive. Emptied and written back, an invite carrying a "
            + "deleted contact's name arrives unwarned - which is the hole retiredDisplayNames was "
            + "added to close",
        "not a sealed list", store().getString(key, null));
  }

  /** An absent value is still an absent value: the guard must not freeze a first save. */
  @Test
  public void anabsentValueIsStillWritten() {
    final Account account = SignalProtocolMain.getInstance().getAccount();
    account.setMessageLogLoader(ArrayList::new);
    account.retireDisplayName("Carol", "Danvers", "carol-uuid.1");
    assertTrue(helper().storeAllInformationInSharedPreferences(account));

    assertNotNull("a value that was never stored must be written normally, or telling absent from "
            + "unreadable has broken the ordinary case",
        store().getString(String.valueOf(ProtocolIdentifier.RETIRED_DISPLAY_NAMES), null));
    assertNotNull(store().getString(String.valueOf(ProtocolIdentifier.DISPLAY_TAG_SECRET), null));
  }
}
