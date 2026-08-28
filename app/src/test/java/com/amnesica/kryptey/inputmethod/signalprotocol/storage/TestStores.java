package com.amnesica.kryptey.inputmethod.signalprotocol.storage;

import com.amnesica.kryptey.inputmethod.signalprotocol.Account;
import com.amnesica.kryptey.inputmethod.signalprotocol.SignalProtocolMain;
import com.amnesica.kryptey.inputmethod.signalprotocol.helper.StorageHelper;

import org.robolectric.RuntimeEnvironment;

/**
 * A store whose writes land, stated by the fixtures that need one.
 *
 * <p>There is no Keystore under Robolectric, so `initialize(null)` leaves {@code SignalProtocolMain}
 * with no storage helper at all — the one state in which nothing can possibly have been written.
 * {@code accountWriteSucceeded} used to answer <b>true</b> there and
 * {@code storeAllAccountInformationInSharedPreferences} answered <b>false</b>, so a created contact
 * reported saved and a deleted one reported lost about the same store. Every fixture inherited that
 * split without saying so, and three of them ended up asserting state that held only because the
 * write had been reported one way rather than the other.
 *
 * <p>Now both fail closed, and a fixture that depends on a write landing says so with one line. That
 * is the whole point of this class: not to make tests pass, but to make the difference between "the
 * store worked" and "the store was never there" a thing each test states rather than inherits.
 *
 * <p>Call it <b>after</b> the last {@code initialize}, which is what installs (or fails to install)
 * the real helper.
 */
public final class TestStores {

  private TestStores() { }

  /** Installs a helper that writes, so operations depending on a landed write report success. */
  public static void writesLand() {
    SignalProtocolMain.getInstance().setStorageHelperForTest(
        new StorageHelper(RuntimeEnvironment.getApplication(), (ctx, hasExistingData) -> null) {
          @Override
          public boolean storeAllInformationInSharedPreferences(final Account account) {
            return true;
          }
        });
  }
}
