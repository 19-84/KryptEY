package com.amnesica.kryptey.inputmethod.latin.e2ee;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.view.ContextThemeWrapper;
import android.widget.EditText;
import android.widget.TextView;

import com.amnesica.kryptey.inputmethod.R;
import com.amnesica.kryptey.inputmethod.signalprotocol.Account;
import com.amnesica.kryptey.inputmethod.signalprotocol.MessageEnvelope;
import com.amnesica.kryptey.inputmethod.signalprotocol.SignalProtocolMain;
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
import org.signal.libsignal.protocol.SignalProtocolAddress;

/**
 * The inviter's side of every KryptEY conversation, which pins a key and said nothing about it.
 *
 * <p>The app's own help describes this flow: <em>"Your chat partner has to add you to their contact
 * list and then send you an encrypted message… The contact is now automatically selected."</em> So
 * this is not an exotic path — it is what happens to whoever sent the invite, in every conversation
 * this app has ever set up.
 *
 * <p>What arrives is a message with <b>no bundle beside it</b>. `getMessageType` branches on field
 * presence alone, so omitting one field costs the relay nothing and chooses this arm; and `decrypt`
 * takes its PreKey branch on the ciphertext type, where `isTrustedIdentity` returns true whenever
 * nothing is pinned. The key is therefore pinned by trust-on-first-use — a key that reached the user
 * through the messenger, which is the party this app treats as the adversary.
 *
 * <p>The sibling arm posts a caution for exactly this, and its comment says why it is the most
 * important of the four outcomes there: it <em>"fires because it noticed nothing, which is exactly
 * what pinning a key the messenger chose looks like when the substitution succeeded."</em> This arm
 * pinned the same way and posted nothing.
 *
 * <p>Two further consequences follow from the same silence, and they are asserted here because they
 * are what the user actually sees: the banner is left reading "No contact chosen" while a contact IS
 * chosen and Encrypt aims at them, and — because that sentence is what `disablesActionButtons`
 * matches on — Encrypt and Decrypt stay dark. The help says the contact is "automatically selected";
 * the screen says nothing is chosen and offers no way to reply.
 */
@RunWith(RobolectricTestRunner.class)
public class TheInviterSideIsToldToCompareTheNumberTest {

  private E2EEStripView strip;
  private Account inviter;
  private SignalProtocolAddress peerAddress;

  @Before
  public void setUp() throws Exception {
    final Application app = RuntimeEnvironment.getApplication();

    SignalProtocolMain.resetForTest();
    SignalProtocolMain.testIsRunning = true;

    // The inviter: the one who exported a bundle and is waiting to be written to.
    SignalProtocolMain.initialize(null);
    inviter = SignalProtocolMain.getInstance().getAccount();
    inviter.setMessageLogLoader(java.util.ArrayList::new);
    final SignalProtocolAddress inviterAddress = ProtocolAddresses.of(
        inviter.getSignalProtocolAddress().getName(), inviter.getDeviceId());
    final String inviterBundle = SignalProtocolMain.exportOwnKeyBundle();

    // The other side - genuine or not, the app cannot tell, which is the whole point.
    SignalProtocolMain.initialize(null);
    final Account peer = SignalProtocolMain.getInstance().getAccount();
    peer.setMessageLogLoader(java.util.ArrayList::new);
    peerAddress = ProtocolAddresses.of(peer.getSignalProtocolAddress().getName(),
        peer.getDeviceId());
    assertTrue(SignalProtocolMain.processPreKeyResponseMessage(
        EnvelopeCodec.fromWire(inviterBundle), inviterAddress));
    final MessageEnvelope reply = SignalProtocolMain.encryptMessage("got your invite",
        inviterAddress);
    assertNotNull(reply);

    SignalProtocolMain.getInstance().setAccount(inviter);

    // Stripped to ciphertext alone, which is what an ordinary first message looks like on arrival.
    final MessageEnvelope ciphertextOnly = EnvelopeCodec.fromWire(EnvelopeCodec.toWire(
        new MessageEnvelope(reply.getCiphertextMessage(), reply.getCiphertextType(),
            peerAddress.getName(), peerAddress.getDeviceId())));

    strip = new E2EEStripView(
        new ContextThemeWrapper(app, R.style.KeyboardTheme_LXX_Pure_Day), null);
    strip.setListener(new E2EEStripView.Listener() {
      @Override public void onTextInput(final String rawText) { }
      @Override public void onSensitiveContentVisibilityChanged(final boolean sensitive) { }
    }, strip);
    SignalProtocolMain.setStorageStateForTest(StorageHelper.StorageState.READABLE);

    // Through the real clipboard and the real Decrypt button, so the banner and the button states
    // are the ones the flow actually produces rather than ones this test arranged.
    final ClipboardManager clipboard =
        (ClipboardManager) app.getSystemService(Context.CLIPBOARD_SERVICE);
    clipboard.setPrimaryClip(ClipData.newPlainText("",
        RawEncoder.encode(EnvelopeCodec.toWire(ciphertextOnly))));
    strip.findViewById(R.id.e2ee_button_decrypt).performClick();

    // The app has sent the user to the add screen. They name the sender and press Add.
    ((EditText) strip.findViewById(R.id.e2ee_add_contact_first_name_input_field)).setText("Bob");
    ((EditText) strip.findViewById(R.id.e2ee_add_contact_last_name_input_field)).setText("Jones");
    strip.addContactForTest(ciphertextOnly);
  }

  @After
  public void tearDown() {
    SignalProtocolMain.resetForTest();
    SignalProtocolMain.testIsRunning = false;
  }

  private String banner() {
    final TextView view = strip.findViewById(R.id.e2ee_info_text);
    assertNotNull(view);
    return String.valueOf(view.getText());
  }

  /** The harness is real: a key really was pinned by this flow. */
  @Test
  public void aakeyWasPinnedByThisFlow() {
    assertNotNull("precondition: this arm must really pin a key, or the caution below would be "
            + "asking for a comparison of nothing",
        inviter.getSignalProtocolStore().getIdentityKeyStore().getIdentity(peerAddress));
    assertNotNull("precondition: the contact must have been created and chosen",
        strip.chosenContactForTest());
  }

  /**
   * The caution. A key that arrived through the messenger, and the app cannot tell whose it is.
   */
  @Test
  public void thepinIsAnnouncedWithSomethingTheUserCanActOn() {
    assertTrue("this arm pinned a key that reached the user through the messenger - the party this "
            + "app treats as the adversary - and said nothing. The sibling arm posts a caution for "
            + "exactly this, and its own comment calls it the most important of the four outcomes "
            + "there because it fires when nothing was noticed, which is what a successful "
            + "substitution looks like. Banner: " + banner(),
        banner().contains("security number"));
  }

  /**
   * And the banner must stop saying nothing is chosen, because something is.
   *
   * <p>Not cosmetic. `disablesActionButtons` matches on this exact sentence, so while it stands
   * Encrypt and Decrypt are dark — the user has been handed a decrypted message and no way to
   * answer it, on the flow the help describes as the contact being "automatically selected".
   */
  @Test
  public void thebannerStopsClaimingNoContactIsChosen() {
    assertFalse("a contact IS chosen and Encrypt aims at them, so a banner reading \"No contact "
            + "chosen\" is false about the thing that matters most on this screen - who the next "
            + "message goes to. Banner: " + banner(),
        banner().startsWith(E2EEStripView.INFO_NO_CONTACT_CHOSEN_TEXT));
  }

  /** Which is the same thing said in the form the user meets it: a reply they can send. */
  @Test
  public void theusercanReply() {
    assertTrue("the help says the contact is now automatically selected; the screen left Encrypt "
            + "and Decrypt disabled, so the inviter reads the message and cannot answer it",
        strip.findViewById(R.id.e2ee_button_encrypt).isEnabled());
  }
}
