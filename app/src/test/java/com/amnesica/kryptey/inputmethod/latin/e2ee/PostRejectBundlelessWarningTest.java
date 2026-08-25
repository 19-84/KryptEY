package com.amnesica.kryptey.inputmethod.latin.e2ee;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.view.ContextThemeWrapper;
import android.widget.TextView;

import com.amnesica.kryptey.inputmethod.R;
import com.amnesica.kryptey.inputmethod.signalprotocol.Account;
import com.amnesica.kryptey.inputmethod.signalprotocol.MessageEnvelope;
import com.amnesica.kryptey.inputmethod.signalprotocol.SignalProtocolMain;
import com.amnesica.kryptey.inputmethod.signalprotocol.chat.Contact;
import com.amnesica.kryptey.inputmethod.signalprotocol.encoding.EnvelopeCodec;
import com.amnesica.kryptey.inputmethod.signalprotocol.encoding.RawEncoder;
import com.amnesica.kryptey.inputmethod.signalprotocol.helper.StorageHelper;
import com.amnesica.kryptey.inputmethod.signalprotocol.util.ProtocolAddresses;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.shadows.ShadowToast;
import org.signal.libsignal.protocol.SignalProtocolAddress;

import java.util.ArrayList;

/**
 * Whether the peer is warned about is decided by a field the peer chooses to include.
 *
 * <p>{@link PostRejectWarningReachTest} closed the bundle arm: a key bundle re-delivered to an
 * address the user rejected is pinned by trust-on-first-use - nothing can prevent that, the address
 * really is unpinned - and the point of {@code rejectedAddresses} is that the pin is a
 * <em>warned</em> event rather than a silent first sighting. {@code warnIfKeyWasRejected} is now
 * called from {@code processPreKeyResponse} and {@code processUpdatedPreKeyResponse}, and its
 * javadoc says why it is deliberately not called from the third arm:
 *
 * <blockquote>Deliberately not called from the plain signal-message arm above it: that path carries
 * no bundle, so nothing is pinned there and a warning would be noise on an ordinary
 * message.</blockquote>
 *
 * <p>The premise is false, and the file that contradicts it says so in its own comment:
 * "a PreKeySignalMessage carries its own identity key and needs no attached bundle". {@code
 * SignalProtocolMain.decrypt} takes the {@code PREKEY_TYPE} arm on {@code ciphertextType} alone and
 * calls {@code sessionCipher.decrypt(preKeySignalMessage)}, which pins the key the message carries
 * whenever nothing is pinned for that address - and nothing is pinned for that address is exactly
 * what a completed rejection leaves behind.
 *
 * <p>So which of the three arms an incoming envelope takes is chosen by the sender - {@code
 * getMessageType} reads field presence and nothing else - and two of them ask about the rejection
 * while the one an attacker can reach by simply omitting the bundle does not. Same pin, same
 * address, same record on disk, no warning.
 *
 * <p>Harness copied from {@link PostRejectWarningReachTest} and the forged-message construction
 * from {@code DecryptPathSubstitutionTest}.
 */
@RunWith(RobolectricTestRunner.class)
public class PostRejectBundlelessWarningTest {

  private E2EEStripView strip;
  private Account victim;
  private Account realPeer;
  private Account attacker;
  private SignalProtocolAddress peerAddress;

  @Before
  public void setUp() throws Exception {
    SignalProtocolMain.resetForTest();
    SignalProtocolMain.testIsRunning = true;

    SignalProtocolMain.initialize(null);
    victim = SignalProtocolMain.getInstance().getAccount();
    SignalProtocolMain.initialize(null);
    realPeer = SignalProtocolMain.getInstance().getAccount();
    SignalProtocolMain.initialize(null);
    attacker = SignalProtocolMain.getInstance().getAccount();

    peerAddress = ProtocolAddresses.of(realPeer.getSignalProtocolAddress().getName(),
        realPeer.getDeviceId());

    activate(realPeer);
    final String genuine = SignalProtocolMain.exportOwnKeyBundle();
    activate(victim);
    assertTrue("precondition: the genuine peer must be pinned first",
        SignalProtocolMain.processPreKeyResponseMessage(
            EnvelopeCodec.fromWire(genuine), peerAddress));

    final ArrayList<Contact> contacts = new ArrayList<>();
    contacts.add(new Contact("Bob", "Jones", peerAddress.getName(), peerAddress.getDeviceId(),
        false));
    victim.setContactList(contacts);

    strip = new E2EEStripView(new ContextThemeWrapper(RuntimeEnvironment.getApplication(),
        R.style.KeyboardTheme_LXX_Pure_Day), null);
    strip.setListener(new E2EEStripView.Listener() {
      @Override public void onTextInput(final String rawText) { }
      @Override public void onSensitiveContentVisibilityChanged(final boolean sensitive) { }
    }, strip);
    SignalProtocolMain.setStorageStateForTest(StorageHelper.StorageState.READABLE);
    ShadowToast.reset();
  }

  @After
  public void tearDown() {
    SignalProtocolMain.resetForTest();
    SignalProtocolMain.testIsRunning = false;
  }

  private void activate(final Account account) {
    SignalProtocolMain.getInstance().setAccount(account);
  }

  private Contact bob() {
    return SignalProtocolMain.getInstance().getAccount().getContactList().get(0);
  }

  private SignalProtocolAddress victimAddress() {
    return ProtocolAddresses.of(victim.getSignalProtocolAddress().getName(), victim.getDeviceId());
  }

  private String infoText() {
    final TextView view = strip.findViewById(R.id.e2ee_info_text);
    assertNotNull(view);
    return view.getText().toString();
  }

  /**
   * The attacker builds a session to the victim under their own identity and sends one
   * PreKeySignalMessage labelled with the peer's address. No bundle attached - the two envelope
   * fields that carry the address are unsigned plaintext, which is the whole of the relabelling.
   */
  private MessageEnvelope forgedBundlelessMessage() throws Exception {
    activate(victim);
    final String victimBundle = SignalProtocolMain.exportOwnKeyBundle();

    activate(attacker);
    assertTrue(SignalProtocolMain.processPreKeyResponseMessage(
        EnvelopeCodec.fromWire(victimBundle), victimAddress()));
    final MessageEnvelope forged = SignalProtocolMain.encryptMessage("hello", victimAddress());
    assertNotNull(forged);

    activate(victim);
    return new MessageEnvelope(forged.getCiphertextMessage(), forged.getCiphertextType(),
        peerAddress.getName(), peerAddress.getDeviceId());
  }

  private void paste(final MessageEnvelope envelope) throws Exception {
    final ClipboardManager clipboard = (ClipboardManager) RuntimeEnvironment.getApplication()
        .getSystemService(Context.CLIPBOARD_SERVICE);
    clipboard.setPrimaryClip(ClipData.newPlainText("",
        RawEncoder.encode(EnvelopeCodec.toWire(envelope))));
  }

  @Test
  public void abundlelessPreKeyMessageAtArejectedAddressIsWarnedAboutOnScreen() throws Exception {
    final MessageEnvelope forged = forgedBundlelessMessage();
    assertNull("precondition: this envelope carries no key bundle - that is what routes it to the "
        + "arm with no rejection check", forged.getPreKeyResponse());

    assertTrue("precondition: a pinned key must have been forgotten",
        SignalProtocolMain.rejectContactKey(bob()));
    assertTrue("precondition: the rejection must be on record",
        SignalProtocolMain.wasKeyRejected(peerAddress));
    assertNull("precondition: nothing must be pinned for this address any more",
        SignalProtocolMain.getInstance().getAccount().getSignalProtocolStore()
            .getIdentityKeyStore().getIdentity(peerAddress));
    assertFalse("precondition: rejecting keeps the contact row",
        SignalProtocolMain.getInstance().getAccount().getContactList().isEmpty());

    paste(forged);
    strip.findViewById(R.id.e2ee_button_decrypt).performClick();

    assertNotNull("precondition: the message pins the identity key it carries - the address is "
            + "genuinely unpinned, so trust-on-first-use accepts it and nothing can stop that",
        SignalProtocolMain.getInstance().getAccount().getSignalProtocolStore()
            .getIdentityKeyStore().getIdentity(peerAddress));

    final String toast = ShadowToast.getTextOfLatestToast();
    final String shown = infoText() + " | " + (toast == null ? "" : toast);
    assertTrue("a key pinned at an address the user rejected must not be a silent first sighting, "
            + "whether it arrived on a bundle or on the pre-key message that carries its own "
            + "identity key. The sender chooses which. What the user was shown: " + shown,
        shown.contains("previously told the app"));
  }
}
