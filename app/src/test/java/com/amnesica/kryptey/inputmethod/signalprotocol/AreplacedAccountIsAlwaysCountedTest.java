package com.amnesica.kryptey.inputmethod.signalprotocol;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import com.amnesica.kryptey.inputmethod.signalprotocol.helper.StorageHelper;
import com.amnesica.kryptey.inputmethod.signalprotocol.storage.CryptoBox;
import com.amnesica.kryptey.inputmethod.signalprotocol.storage.GcmCryptoBox;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

/**
 * Both paths that replace the account with the stored copy must say so.
 *
 * <p>{@code mAccountReloads} is not a statistic. Its single reader is
 * {@code clearAstoreNoticeThatHasBeenResolved}, and that guard's argument is that a reload
 * <em>voids</em> the rule rather than satisfying it: after one, the in-memory log is the stored copy,
 * which still holds the entries a failed prune never removed. The next landed write then persists
 * the orphaned plaintext instead of erasing it — so retiring the notice on that write takes the
 * sentence down at the precise moment the leak becomes permanent.
 *
 * <p>There are two paths that do the replacing. {@code reloadAccount} counted itself.
 * {@code reloadAccountIfStorageRecovered} did not, and it is the <b>more frequent</b> of the two:
 * the file's own javadoc says it runs on every keyboard raise while a store fault stands, where the
 * other runs on a theme change. So the guard was blind to the reload most likely to happen, and
 * blind in the one direction that matters — a review round reported this as a false comment on a
 * counter, having been unable to construct a harm; the harm is the sequence above.
 *
 * <p>Asserted on the counter rather than on the notice because the notice lives in the strip and
 * this is a property of the protocol layer. The strip-side consequence is pinned by
 * {@code AstoreNoticeMustOutliveThereloadThatUndoesItsPremiseTest}.
 */
@RunWith(RobolectricTestRunner.class)
public class AreplacedAccountIsAlwaysCountedTest {

  private Context context;

  @Before
  public void setUp() {
    context = RuntimeEnvironment.getApplication();
    context.getSharedPreferences("protocol", Context.MODE_PRIVATE).edit().clear().commit();
    SignalProtocolMain.resetForTest();

    final SecretKey key = new SecretKeySpec(new byte[32], "AES");
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
    SignalProtocolMain.setStorageHelperFactoryForTest(null);
    SignalProtocolMain.resetForTest();
    SignalProtocolMain.testIsRunning = false;
  }

  /** The theme-change path, which already counted itself. Here as the control for the one below. */
  @Test
  public void reloadAccountCountsItself() {
    final long before = SignalProtocolMain.accountReloads();
    SignalProtocolMain.reloadAccount(context);
    assertEquals("reloadAccount replaces the account with the stored copy and must count it",
        before + 1, SignalProtocolMain.accountReloads());
  }

  /** The keyboard-raise path, which did not. */
  @Test
  public void thereloadOnAkeyboardRaiseCountsItselfToo() {
    final long before = SignalProtocolMain.accountReloads();

    assertTrue("precondition: the reload must actually have happened, or this measures nothing - "
            + "the method returns false when there is no readable stored account and the counter "
            + "would then be right to stay where it is",
        SignalProtocolMain.reloadAccountIfStorageRecovered(context));

    assertEquals("this path replaces the account with the stored copy exactly as reloadAccount "
            + "does, and runs far more often - on every keyboard raise while a store fault stands. "
            + "Uncounted, the guard that refuses to retire a store notice after a reload cannot see "
            + "it, and retires the notice at the moment the orphaned plaintext becomes permanent",
        before + 1, SignalProtocolMain.accountReloads());
  }
}
