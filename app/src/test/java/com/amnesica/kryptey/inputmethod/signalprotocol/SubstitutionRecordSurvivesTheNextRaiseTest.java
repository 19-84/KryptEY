package com.amnesica.kryptey.inputmethod.signalprotocol;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import android.content.Context;
import android.content.SharedPreferences;
import com.amnesica.kryptey.inputmethod.signalprotocol.chat.Contact;
import com.amnesica.kryptey.inputmethod.signalprotocol.encoding.EnvelopeCodec;
import com.amnesica.kryptey.inputmethod.signalprotocol.helper.StorageHelper;
import com.amnesica.kryptey.inputmethod.signalprotocol.storage.CryptoBox;
import com.amnesica.kryptey.inputmethod.signalprotocol.storage.GcmCryptoBox;
import com.amnesica.kryptey.inputmethod.signalprotocol.util.ProtocolAddresses;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.signal.libsignal.protocol.SignalProtocolAddress;
import java.security.NoSuchAlgorithmException;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import static org.junit.Assert.assertThrows;
import org.signal.libsignal.protocol.UntrustedIdentityException;

/**
 * A substitution refused on the decrypt path must still be refused after the next raise.
 *
 * <p>{@code decrypt}'s {@code UntrustedIdentityException} arm does two things and then persists
 * them: it records the offered key as a pending change, and it strips the verified badge. Both are
 * in memory until that write lands, and {@code reloadAccount} runs on every {@code setInputView} —
 * a theme flip, a rotation, the next raise at all.
 *
 * <p>Deleting that one persist left the whole suite green, which is how this test came to exist. It
 * is the sibling of an arm that IS pinned — the bundle-carrying substitution path — and this is the
 * one an attacker would actually choose, because a {@code PreKeySignalMessage} carries its own
 * identity key and needs no bundle attached.
 *
 * <p>The badge is the half that bites hardest. Losing the pending record costs a warning; losing the
 * cleared verification puts the green tick back on a contact whose key was just substituted, so the
 * app reassures the user at the exact moment it holds evidence against them.
 */
@RunWith(RobolectricTestRunner.class)
public class SubstitutionRecordSurvivesTheNextRaiseTest {

  private Context context;
  private SignalProtocolAddress peerAddress;
  private Account victim;
  private Account attacker;

  @Before
  public void setUp() {
    SignalProtocolMain.resetForTest();
    context = RuntimeEnvironment.getApplication();
    final SharedPreferences preferences =
        context.getSharedPreferences("protocol", Context.MODE_PRIVATE);
    preferences.edit().clear().commit();
    final SecretKey key = new javax.crypto.spec.SecretKeySpec(new byte[32], "AES");
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
  }

  private void raiseTheKeyboardAgain() {
    SignalProtocolMain.reloadAccount(context);
    assertNotNull("precondition: the account must reload from disk",
        SignalProtocolMain.getInstance().getAccount());
  }

  private Contact storedContact() {
    for (final Contact candidate : SignalProtocolMain.getContactList()) {
      if (candidate.getSignalProtocolAddress().equals(peerAddress)) return candidate;
    }
    throw new AssertionError("the contact is not in the reloaded list");
  }

  private SignalProtocolAddress victimAddress() {
    return ProtocolAddresses.of(victim.getSignalProtocolAddress().getName(), victim.getDeviceId());
  }

  /** The victim pins and verifies a genuine peer, all of it persisted. */
  private void pinAndVerifyTheRealPeer() throws Exception {
    victim = SignalProtocolMain.getInstance().getAccount();

    SignalProtocolMain.getInstance().setStorageHelperForTest(null);
    SignalProtocolMain.initialize(null);
    final Account peer = SignalProtocolMain.getInstance().getAccount();
    peerAddress = ProtocolAddresses.of(peer.getSignalProtocolAddress().getName(),
        peer.getDeviceId());
    final String bundle = SignalProtocolMain.exportOwnKeyBundle();

    SignalProtocolMain.initialize(null);
    attacker = SignalProtocolMain.getInstance().getAccount();

    SignalProtocolMain.getInstance().setAccount(victim);
    raiseTheKeyboardAgain();

    SignalProtocolMain.addContact("Real", "Peer", peerAddress.getName(),
        peerAddress.getDeviceId());
    assertTrue("precondition: the bundle must pin",
        SignalProtocolMain.processPreKeyResponseMessage(EnvelopeCodec.fromWire(bundle),
            peerAddress));
    assertTrue("precondition: the user must have compared and confirmed the number",
        SignalProtocolMain.verifyContact(storedContact()));
    raiseTheKeyboardAgain();
    assertTrue("precondition: the badge must be on disk",
        SignalProtocolMain.isContactKeyTrustworthy(storedContact()));
  }

  /** The attacker sends a PreKeySignalMessage relabelled as the peer's. No bundle attached. */
  private MessageEnvelope forgedMessageFromAttacker() throws Exception {
    final String victimBundle = SignalProtocolMain.exportOwnKeyBundle();

    SignalProtocolMain.getInstance().setStorageHelperForTest(null);
    SignalProtocolMain.getInstance().setAccount(attacker);
    assertTrue(SignalProtocolMain.processPreKeyResponseMessage(
        EnvelopeCodec.fromWire(victimBundle), victimAddress()));
    final MessageEnvelope forged =
        SignalProtocolMain.encryptMessage("trust me", victimAddress());
    assertNotNull(forged);

    SignalProtocolMain.getInstance().setAccount(victim);
    SignalProtocolMain.setStorageHelperFactoryForTest(
        ctx -> new StorageHelper(ctx, (c, hasExistingData) -> new GcmCryptoBox() {
          @Override
          protected SecretKey key() {
            return new javax.crypto.spec.SecretKeySpec(new byte[32], "AES");
          }
        }));
    SignalProtocolMain.reloadAccount(context);

    return new MessageEnvelope(forged.getCiphertextMessage(), forged.getCiphertextType(),
        peerAddress.getName(), peerAddress.getDeviceId());
  }

  /**
   * A substitution refused on the decrypt path must still be refused after the next raise.
   *
   * <p>{@code decrypt}'s {@code UntrustedIdentityException} arm does two things and then persists
   * them: it records the offered key as a pending change, and it strips the verified badge. Both are
   * in-memory until that write lands, and {@code reloadAccount} runs on every {@code setInputView} -
   * a theme flip, a rotation, the next raise at all.
   *
   * <p>Deleting that one persist left the whole suite green, which is how this test came to exist.
   * It is the sibling of an arm that IS pinned: the bundle-carrying substitution path. This is the
   * one an attacker would actually choose, because a {@code PreKeySignalMessage} carries its own
   * identity key and needs no bundle attached.
   *
   * <p>The badge is the half that bites hardest. Losing the pending record costs a warning; losing
   * the cleared verification puts the green tick back on a contact whose key was just substituted,
   * so the user is reassured by the app at the exact moment it has evidence against them.
   */
  @Test
  public void asubstitutionRefusedOnTheDecryptPathIsStillRefusedAfterTheNextRaise()
      throws Exception {
    pinAndVerifyTheRealPeer();
    final MessageEnvelope forged = forgedMessageFromAttacker();

    assertThrows("precondition: a substituted identity must not decrypt",
        UntrustedIdentityException.class,
        () -> SignalProtocolMain.decryptMessage(forged, peerAddress));

    assertTrue("precondition: the change must be recorded in memory",
        SignalProtocolMain.hasUnacceptedIdentityChange(peerAddress));
    assertFalse("precondition: the badge must be gone in memory",
        SignalProtocolMain.isContactKeyTrustworthy(storedContact()));

    raiseTheKeyboardAgain();

    assertTrue("the pending identity change must survive the next raise, or the warning that "
            + "depends on it never appears again and the substitution is silent",
        SignalProtocolMain.hasUnacceptedIdentityChange(peerAddress));
    assertFalse("and the verified badge must stay gone. If the reload restores it, the app puts a "
            + "green tick back on a contact whose key it has just refused - reassuring the user at "
            + "the exact moment it holds evidence against them",
        SignalProtocolMain.isContactKeyTrustworthy(storedContact()));
  }
}
