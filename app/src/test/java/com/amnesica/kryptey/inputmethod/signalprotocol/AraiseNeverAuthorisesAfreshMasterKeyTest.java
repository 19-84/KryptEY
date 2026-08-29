package com.amnesica.kryptey.inputmethod.signalprotocol;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;

import com.amnesica.kryptey.inputmethod.signalprotocol.helper.StorageHelper;
import com.amnesica.kryptey.inputmethod.signalprotocol.storage.CryptoBox;
import com.amnesica.kryptey.inputmethod.signalprotocol.storage.GcmCryptoBox;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.signal.libsignal.protocol.IdentityKey;

import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;

/**
 * What the per-raise re-read authorises, now that it runs on every raise while a fault stands.
 *
 * <p>{@code reloadAccountIfStorageRecovered} begins by calling {@code initializeStorageHelper},
 * which builds a <em>new</em> {@code StorageHelper} and installs it. That discards the cached
 * secure store, so the next store access rebuilds it — and rebuilding it asks the crypto box
 * factory for a box, passing a boolean that is, in the production box, <b>the sole input to its
 * refusal to mint a replacement master key</b>. Both stores share one Keystore alias, so a box
 * built with that boolean false over a device that has an identity would authorise a fresh key on
 * top of the user's whole store: identity, sessions, pinned keys and chat log, all unreadable at
 * once.
 *
 * <p>That path was reached once per condition-warning raise before, and is reached on every raise
 * while the fault stands now that the gate asks the fact rather than the banner. So the question
 * stops being incidental. The factory here records what it was asked, which is the same input the
 * real box decides on, and the assertion is that the answer is never "no existing data" once there
 * is an identity on disk.
 *
 * <p>It also counts the boxes, because the cost claim in {@code theStoreMustBeRereadOnThisRaise}'s
 * javadoc should be a measurement rather than an assurance.
 */
@RunWith(RobolectricTestRunner.class)
public class AraiseNeverAuthorisesAfreshMasterKeyTest {

  private Context context;
  private SecretKey key;
  private final List<Boolean> asked = new ArrayList<>();

  private static SecretKey newKey() {
    try {
      final KeyGenerator generator = KeyGenerator.getInstance("AES");
      generator.init(256);
      return generator.generateKey();
    } catch (NoSuchAlgorithmException e) {
      throw new AssertionError(e);
    }
  }

  /**
   * A box that records the one boolean the production box refuses on.
   *
   * <p>It does not itself refuse — the refusal lives in the Android Keystore box, which needs a
   * device. What is testable here is the INPUT, and the input is what the caller controls.
   */
  private StorageHelper helper() {
    return new StorageHelper(context, (ctx, hasExistingData) -> {
      asked.add(hasExistingData);
      return new GcmCryptoBox() {
        @Override
        protected SecretKey key() {
          return key;
        }
      };
    });
  }

  @Before
  public void setUp() {
    context = RuntimeEnvironment.getApplication();
    context.getSharedPreferences("protocol", Context.MODE_PRIVATE).edit().clear().commit();
    context.getSharedPreferences("protocol_messages", Context.MODE_PRIVATE).edit().clear()
        .commit();
    SignalProtocolMain.resetForTest();
    SignalProtocolMain.testIsRunning = true;
    key = newKey();
    SignalProtocolMain.setStorageHelperFactoryForTest(ctx -> helper());
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

  private void sealTheStoredContactList() {
    store().edit().putString(String.valueOf(ProtocolIdentifier.CONTACTS),
        "not a sealed contact list").commit();
  }

  /**
   * Twenty raises against a store that cannot be opened must never say "this device has nothing".
   *
   * <p>The messenger chooses when raises happen — presenting a text field is enough — so twenty is
   * not an unusual number, it is a minute of use.
   */
  @Test
  public void norepeatedRaiseEverAsksForAboxOverAnEmptyDevice() {
    final Account account = SignalProtocolMain.getInstance().getAccount();
    assertNotNull(account);
    helper().storeAllInformationInSharedPreferences(account);
    final IdentityKey identityBefore = account.getIdentityKeyPair().getPublicKey();

    sealTheStoredContactList();
    account.markContactsUnreadable();
    asked.clear();

    for (int raise = 0; raise < 20; raise++) {
      SignalProtocolMain.reloadAccountIfStorageRecovered(context);
    }

    assertTrue("the raise must ask for a box, or this test is measuring nothing",
        asked.size() > 0);
    assertEquals("a raise asked for a crypto box over a device it described as having no existing "
            + "data. Both stores share one Keystore alias and that boolean is the only thing "
            + "stopping the production box minting a replacement master key - which would leave "
            + "the identity, every session, every pinned key and the whole chat log unreadable at "
            + "once, on a raise the messenger triggers by presenting a field. Answers were: "
            + asked, 0, asked.stream().filter(a -> !a).count());

    assertEquals("and the identity must be untouched after twenty raises", identityBefore,
        SignalProtocolMain.getInstance().getAccount().getIdentityKeyPair().getPublicKey());
  }

  /**
   * And the store still opens afterwards, which is the property the boolean exists to protect.
   *
   * <p>Asserted separately from the input above because they can fail apart: the input being right
   * every time and the store still ending up unreadable would mean the danger is somewhere else.
   */
  @Test
  public void thestoreStillOpensAfterAlongRunOfRaises() {
    final Account account = SignalProtocolMain.getInstance().getAccount();
    helper().storeAllInformationInSharedPreferences(account);
    sealTheStoredContactList();
    account.markContactsUnreadable();

    for (int raise = 0; raise < 20; raise++) {
      SignalProtocolMain.reloadAccountIfStorageRecovered(context);
    }

    assertEquals("twenty raises against a sealed contact list must leave every other value "
            + "readable - the fault is one value, and a raise must not widen it",
        StorageHelper.StorageState.READABLE, helper().storageState());
  }

  /**
   * What a raise actually costs, measured rather than asserted.
   *
   * <p>{@code theStoreMustBeRereadOnThisRaise}'s javadoc says this is not a new class of cost. This
   * is the number behind that: how many crypto boxes one raise builds while a fault stands. Pinned
   * with room above it so an ordinary change does not fail, and low enough that a rewrite turning
   * one box per raise into one per stored value would.
   *
   * <p>Measured: <b>one</b> box per raise while the fault stands. The bound is four so that the
   * recovered path, which opens the message store as well, is not a failure.
   */
  @Test
  public void araiseBuildsAboundedNumberOfCryptoBoxes() {
    final Account account = SignalProtocolMain.getInstance().getAccount();
    helper().storeAllInformationInSharedPreferences(account);
    sealTheStoredContactList();
    account.markContactsUnreadable();
    asked.clear();

    SignalProtocolMain.reloadAccountIfStorageRecovered(context);

    assertTrue("one raise built " + asked.size() + " crypto boxes. On a device each is an Android "
            + "Keystore operation on the IME main thread, and this now runs on every raise while a "
            + "fault stands rather than only while its banner is up, so the number is part of the "
            + "decision to gate on the fact", asked.size() <= 4);
  }
}
