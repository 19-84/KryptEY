package com.amnesica.kryptey.inputmethod.latin.e2ee;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.view.ContextThemeWrapper;
import android.widget.EditText;

import com.amnesica.kryptey.inputmethod.R;
import com.amnesica.kryptey.inputmethod.signalprotocol.Account;
import com.amnesica.kryptey.inputmethod.signalprotocol.MessageEnvelope;
import com.amnesica.kryptey.inputmethod.signalprotocol.SignalProtocolMain;
import com.amnesica.kryptey.inputmethod.signalprotocol.chat.Contact;
import com.amnesica.kryptey.inputmethod.signalprotocol.encoding.EnvelopeCodec;
import com.amnesica.kryptey.inputmethod.signalprotocol.helper.StorageHelper;
import com.amnesica.kryptey.inputmethod.signalprotocol.storage.TestStores;
import com.amnesica.kryptey.inputmethod.signalprotocol.util.ProtocolAddresses;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.signal.libsignal.protocol.SignalProtocolAddress;

import java.util.ArrayList;

/**
 * The inviter's own arm pins a key, and it must say so when that key is already somewhere else.
 *
 * <p>{@code addContact}'s ciphertext arm - a {@code PreKeySignalMessage} with no bundle beside it -
 * pins by trust-on-first-use, and its own comment explains why that is not obvious: {@code decrypt}
 * takes its PREKEY branch on the ciphertext TYPE alone, and {@code isTrustedIdentity} returns true
 * whenever nothing is pinned. This is the INVITER'S side of every conversation this app sets up, so
 * whoever sent the invite meets this arm once, at the moment their peer's key is pinned.
 *
 * <p>It calls {@code warnIfThisKeyIsPinnedElsewhere}, and a review found that call driven by
 * nothing: deleting it left the whole suite green. It is one of five arrival sites of the one
 * warning this branch records as re-derived nowhere - so on this arm, a warning that is not raised
 * is not merely delayed, it never appears at all.
 *
 * <p><b>What the relay spends to reach it: one relabelled envelope.</b> The sender name and device
 * id are unsigned plaintext outside the bundle signature, so a relay can take a genuine
 * bundle-less message and present it under a fresh address. The key inside is the peer's real one,
 * which is exactly what makes this the fail-OPEN case the raiser's own comment describes: the pin
 * is live, the safety number of the new row matches the peer's, and comparing numbers by voice
 * SUCCEEDS. Only this sentence tells the user the two rows carry one key.
 *
 * <p><b>The measured fallback is worse than silence, which is why this case asserts the wording and
 * not merely that something was said.</b> With the call deleted the banner reads: <em>"Contact
 * Bobby J ... created. This key reached you through the messenger and the app cannot tell whose it
 * is - compare the security number by voice before sending anything private."</em> That is the
 * generic new-key caution, and on this particular row it points the user at the one check that
 * cannot help: the number matches, because it is the peer's real key. So the user does as they are
 * told, the comparison succeeds, and they conclude the row is genuine. A warning that survives the
 * mutation is not the same as the right warning surviving it.
 */
@RunWith(RobolectricTestRunner.class)
public class AciphertextAddThatPinsAknownKeyIsWarnedAboutTest {

  private E2EEStripView strip;
  private Account victim;
  private Account peer;
  private SignalProtocolAddress peerAddress;
  private String genuineBundle;

  @Before
  public void setUp() throws Exception {
    final Application app = RuntimeEnvironment.getApplication();

    SignalProtocolMain.resetForTest();
    SignalProtocolMain.testIsRunning = true;

    SignalProtocolMain.initialize(null);
    victim = SignalProtocolMain.getInstance().getAccount();
    SignalProtocolMain.initialize(null);
    peer = SignalProtocolMain.getInstance().getAccount();
    peerAddress = ProtocolAddresses.of(peer.getSignalProtocolAddress().getName(),
        peer.getDeviceId());

    SignalProtocolMain.getInstance().setAccount(peer);
    peer.setMessageLogLoader(ArrayList::new);
    genuineBundle = SignalProtocolMain.exportOwnKeyBundle();
    SignalProtocolMain.getInstance().setAccount(victim);
    victim.setMessageLogLoader(ArrayList::new);

    strip = new E2EEStripView(
        new ContextThemeWrapper(app, R.style.KeyboardTheme_LXX_Pure_Day), null);
    strip.setListener(new E2EEStripView.Listener() {
      @Override public void onTextInput(final String rawText) { }
      @Override public void onSensitiveContentVisibilityChanged(final boolean sensitive) { }
    }, strip);
    SignalProtocolMain.setStorageStateForTest(StorageHelper.StorageState.READABLE);
    TestStores.writesLand();
  }

  @After
  public void tearDown() {
    SignalProtocolMain.resetForTest();
    SignalProtocolMain.testIsRunning = false;
  }

  private void typeTheName(final String first, final String last) {
    ((EditText) strip.findViewById(R.id.e2ee_add_contact_first_name_input_field)).setText(first);
    ((EditText) strip.findViewById(R.id.e2ee_add_contact_last_name_input_field)).setText(last);
  }

  private String banner() {
    return String.valueOf(
        ((android.widget.TextView) strip.findViewById(R.id.e2ee_info_text)).getText());
  }

  /**
   * A genuine bundle-less PreKey message from the peer, presented under {@code underName}.
   *
   * <p>Relabelling is the whole attack and it costs nothing: the envelope's sender name and device
   * id sit outside the bundle signature, so the same genuine ciphertext can be presented under any
   * address. Passing the peer's real name reproduces the honest case.
   */
  private MessageEnvelope ciphertextFrom(final String underName) throws Exception {
    final SignalProtocolAddress victimAddress = ProtocolAddresses.of(
        victim.getSignalProtocolAddress().getName(), victim.getDeviceId());
    final String victimBundle = SignalProtocolMain.exportOwnKeyBundle();

    SignalProtocolMain.getInstance().setAccount(peer);
    assertTrue("precondition: the peer must be able to open a session with us",
        SignalProtocolMain.processPreKeyResponseMessage(
            EnvelopeCodec.fromWire(victimBundle), victimAddress));
    final MessageEnvelope sent = SignalProtocolMain.encryptMessage("hello", victimAddress);
    assertNotNull(sent);
    SignalProtocolMain.getInstance().setAccount(victim);

    return EnvelopeCodec.fromWire(EnvelopeCodec.toWire(new MessageEnvelope(
        sent.getCiphertextMessage(), sent.getCiphertextType(),
        underName, peerAddress.getDeviceId())));
  }

  /**
   * The premise, and it is what makes the case below about the warning rather than about a refusal.
   *
   * <p>A bundle-less message added under the peer's own name must be accepted and must NOT carry
   * the same-key sentence: there is only one row, so the condition is genuinely false. Without this
   * the main case would pass on a build that raises the sentence on every ciphertext add.
   */
  @Test
  public void thepremiseThatAnOrdinaryCiphertextAddIsNotWarnedAbout() throws Exception {
    typeTheName("Bob", "Jones");
    strip.addContactForTest(ciphertextFrom(peerAddress.getName()));

    assertTrue("precondition: the add must have created a row", victim.getContactList().size() >= 1);
    assertTrue("a first, ordinary ciphertext add has nothing pinned elsewhere and must not be "
        + "warned about: " + banner(), !banner().contains("same key already saved"));
  }

  @Test
  public void aciphertextAddThatPinsAkeyAlreadySavedElsewhereIsWarnedAbout() throws Exception {
    // The user adds the peer the ordinary way first: key K pinned at the peer's real address.
    typeTheName("Bob", "Jones");
    strip.addContactForTest(EnvelopeCodec.fromWire(genuineBundle));
    assertTrue("precondition: the peer must be pinned", victim.getContactList().size() >= 1);

    // The relay presents the peer's own genuine message under an address of its choosing. The key
    // inside is still K, so this pins K a second time.
    typeTheName("Bobby", "J");
    strip.addContactForTest(ciphertextFrom("relay-minted-address"));

    final ArrayList<Contact> contacts = victim.getContactList();
    assertTrue("precondition: the relabelled message must have created a SECOND row - if it did "
            + "not, nothing pinned twice and this case would be measuring an absence that is true "
            + "for the wrong reason", contacts.size() >= 2);

    assertTrue("pinning a key that is already saved at another address must be said out loud on "
            + "this arm. It is the inviter's own path, it pins by trust-on-first-use, and nothing "
            + "re-derives this warning - so unsaid here means never said. Comparing security "
            + "numbers cannot tell the two rows apart, because both show the peer's real number. "
            + "Banner was: " + banner(),
        banner().contains("same key already saved"));
    assertTrue("...and it must say the comparison will not separate them: " + banner(),
        banner().contains("will NOT tell them apart"));
  }
}
