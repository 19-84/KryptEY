package com.amnesica.kryptey.inputmethod.signalprotocol;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
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

import java.security.NoSuchAlgorithmException;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;

/**
 * A bundle handed to a contact must be backed by private keys that are on disk.
 *
 * <p>{@code reloadAccount} runs on every {@code setInputView}, so anything the invite path
 * generated in memory is replaced by whatever is on disk the next time the keyboard is raised.
 */
@RunWith(RobolectricTestRunner.class)
public class BundleKeysReachDiskTest {

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
  }

  @Test
  public void therotatedKeysInAnInviteAreOnDiskBeforeTheInviteLeaves() {
    final Account me = SignalProtocolMain.getInstance().getAccount();
    assertNotNull("precondition: the account must load with a real storage helper", me);

    // Thirty days have gone by since this signed pre key was minted.
    me.getMetadataStore().setNextSignedPreKeyRefreshTime(System.currentTimeMillis() - 1);
    final int signedBefore = me.getMetadataStore().getActiveSignedPreKeyId();

    // The user presses invite. This is the whole of what the strip's invite button does.
    final MessageEnvelope envelope = SignalProtocolMain.getPreKeyResponseMessage();
    assertNotNull("the invite must be produced", envelope);

    final int signedHandedOut = me.getMetadataStore().getActiveSignedPreKeyId();
    final int kyberHandedOut = me.getMetadataStore().getActiveKyberPreKeyId();
    assertNotEquals("precondition: the signed pre key must have rotated",
        signedBefore, signedHandedOut);
    assertEquals("precondition: the bundle carries the rotated signed pre key", signedHandedOut,
        envelope.getPreKeyResponse().getDevices().get(0).getSignedPreKey().getKeyId());

    // The keyboard is put away and raised again.
    SignalProtocolMain.reloadAccount(context);
    final Account after = SignalProtocolMain.getInstance().getAccount();
    assertNotNull("the account must reload", after);

    assertTrue("the signed pre key handed to a contact is not on disk: the next setInputView "
            + "replaces the account and the private half is gone, so that contact's first message "
            + "can never be decrypted",
        after.getSignalProtocolStore().containsSignedPreKey(signedHandedOut));
    assertTrue("the kyber pre key handed to a contact is not on disk",
        after.getSignalProtocolStore().containsKyberPreKey(kyberHandedOut));
  }
}
