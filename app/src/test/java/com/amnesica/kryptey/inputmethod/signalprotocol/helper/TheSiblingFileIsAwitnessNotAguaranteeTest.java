package com.amnesica.kryptey.inputmethod.signalprotocol.helper;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import com.amnesica.kryptey.inputmethod.signalprotocol.ProtocolIdentifier;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import com.amnesica.kryptey.inputmethod.signalprotocol.storage.GcmCryptoBox;

/**
 * How far the two-file check actually gets, measured, because its javadoc read as if it went
 * further.
 *
 * <p>{@code hasExistingProtocolData} asks both files because they can be lost independently: a
 * corrupt {@code protocol.xml} reads as "no data" — {@code SharedPreferencesImpl.loadFromDisk}
 * swallows a parse failure and installs an empty map — while the chat log's file is untouched.
 * Asking only the account's file answers "fresh install", a new identity is generated, and its empty
 * log is written over the history that survived.
 *
 * <p>The sibling closes that for a user who has one. It is a witness, not a guarantee: a user who
 * has an identity and has never exchanged a message has no message file, and for them the corrupt
 * account file still reads as a fresh install. That is the case with the least on disk to lose and
 * the least chance of noticing, and this pins both halves so the limit is a measured one rather
 * than a sentence someone has to re-derive from the code.
 */
@RunWith(RobolectricTestRunner.class)
public class TheSiblingFileIsAwitnessNotAguaranteeTest {

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

  @Before
  public void setUp() {
    context = RuntimeEnvironment.getApplication();
    context.getSharedPreferences("protocol", Context.MODE_PRIVATE).edit().clear().commit();
    context.getSharedPreferences("protocol_messages", Context.MODE_PRIVATE).edit().clear()
        .commit();
  }

  /** With a chat log on disk, a lost account file is still recognised as an existing install. */
  @Test
  public void asurvivingChatLogKeepsTheAppFromReadingAlostAccountAsFresh() {
    context.getSharedPreferences("protocol_messages", Context.MODE_PRIVATE).edit()
        .putString(String.valueOf(ProtocolIdentifier.UNENCRYPTED_MESSAGES), "sealed history")
        .commit();

    assertTrue("the account file is empty and the log's is not; generating a new identity here "
            + "would write an empty log straight over the history that survived",
        helper().hasExistingProtocolData());
  }

  /**
   * And without one, it is not — which is the limit the javadoc now states.
   *
   * <p>Not a fix, a measurement. The honest fix is a durable "an identity exists here" marker
   * outside both files, which is a schema change with its own migration; what this test buys is
   * that the limit cannot quietly be forgotten, and that anyone who does add such a marker has a
   * failing test telling them where it was needed.
   */
  @Test
  public void withoutAchatLogAlostAccountFileStillReadsAsAfreshInstall() {
    assertFalse("no account file and no message file is indistinguishable from a fresh install, "
            + "and for a user who has never exchanged a message that is exactly the state a "
            + "corrupt account file leaves behind",
        helper().hasExistingProtocolData());
  }
}
