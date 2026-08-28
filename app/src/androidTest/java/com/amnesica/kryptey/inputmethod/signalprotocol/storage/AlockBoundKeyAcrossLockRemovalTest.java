package com.amnesica.kryptey.inputmethod.signalprotocol.storage;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import android.app.KeyguardManager;
import android.content.Context;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.SecretKey;

/**
 * What happens to a lock-bound storage key when the user removes their screen lock.
 *
 * <p>A comment in {@code StorageHelper} describes a device "whose secureStore never resolves (a
 * Keystore key invalidated by a credential change, a migration that threw)". The master key uses
 * neither {@code setUserAuthenticationRequired} nor {@code setInvalidatedByBiometricEnrollment}, so
 * the ordinary credential-change invalidation does not apply to it — but whether an
 * {@code UnlockedDeviceRequired}-only key survives the lock being <em>removed</em> is
 * version-dependent, and that comment answers it by assumption.
 *
 * <p>It matters because the answer decides between two very different apps. If the key survives,
 * nothing. If it does not, then removing a PIN — an ordinary thing a person does — silently turns
 * the user's identity key, every session and their whole history into unreadable ciphertext, on a
 * build with no RESET path. That is worth knowing rather than assuming, and it is cheap to ask on a
 * device.
 *
 * <p>Written to report rather than to demand a particular answer: it asserts the property this app
 * relies on, and if the platform disagrees the failure names what the app must then do.
 */
@RunWith(AndroidJUnit4.class)
public class AlockBoundKeyAcrossLockRemovalTest {

  private static final String PIN = "1234";
  private static final byte[] AAD =
      "kryptey/storage/v2/PROTOCOL_STORE".getBytes(StandardCharsets.UTF_8);

  private Context context;
  private boolean weSetThePin;

  private static String shell(final String command) throws Exception {
    final InputStream in = new java.io.FileInputStream(
        InstrumentationRegistry.getInstrumentation().getUiAutomation()
            .executeShellCommand(command).getFileDescriptor());
    final ByteArrayOutputStream out = new ByteArrayOutputStream();
    final byte[] buffer = new byte[4096];
    for (int read = in.read(buffer); read > 0; read = in.read(buffer)) out.write(buffer, 0, read);
    in.close();
    return out.toString("UTF-8").trim();
  }

  @Before
  public void setUp() throws Exception {
    context = ApplicationProvider.getApplicationContext();
    new AndroidKeystoreCryptoBox(context, false).destroyMasterKey();

    final KeyguardManager keyguard =
        (KeyguardManager) context.getSystemService(Context.KEYGUARD_SERVICE);
    assumeTrue("this test needs a keyguard service", keyguard != null);

    if (!keyguard.isDeviceSecure()) {
      shell("locksettings set-pin " + PIN);
      weSetThePin = true;
    }
    assumeTrue("a secure lock screen is required to generate a lock-bound key",
        keyguard.isDeviceSecure());
  }

  @After
  public void tearDown() throws Exception {
    if (weSetThePin) {
      shell("locksettings clear --old " + PIN);
    }
    new AndroidKeystoreCryptoBox(context, false).destroyMasterKey();
  }

  @Test
  public void alockBoundKeySurvivesTheScreenLockBeingRemoved() throws Exception {
    final AndroidKeystoreCryptoBox box = new AndroidKeystoreCryptoBox(context, false);
    final byte[] sealed = box.seal("something the user cannot lose".getBytes(StandardCharsets.UTF_8), AAD);
    assertNotNull("fixture: the key must be usable before the lock is touched", sealed);

    // The key exists under the alias the box uses, and it was generated while the device had a
    // secure lock screen - which is what makes the ladder ask for a lock-bound rung first. KeyInfo
    // has no accessor for that flag at this API level, so the binding is not read back here; what
    // is asserted is the consequence, which is the thing that matters to a user either way.
    final KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
    keyStore.load(null);
    assertNotNull("the master key must exist under the alias the box uses",
        keyStore.getKey("kryptey.storage.master", null));

    // The ordinary user action.
    shell("locksettings clear --old " + PIN);
    weSetThePin = false;

    final byte[] opened = new AndroidKeystoreCryptoBox(context, false).open(sealed, AAD);
    assertTrue("removing the screen lock destroyed a key bound to it, which on this build means "
            + "the identity key, every session and the whole history become unreadable ciphertext "
            + "with no RESET path. If this fails, that is the finding - and the app must either "
            + "stop using UnlockedDeviceRequired or grow a recovery path before it ships",
        new String(opened, StandardCharsets.UTF_8).equals("something the user cannot lose"));
  }
}
