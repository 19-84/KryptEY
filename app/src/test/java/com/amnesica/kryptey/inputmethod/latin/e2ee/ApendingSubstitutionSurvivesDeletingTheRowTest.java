package com.amnesica.kryptey.inputmethod.latin.e2ee;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.view.ContextThemeWrapper;
import android.widget.EditText;

import com.amnesica.kryptey.inputmethod.R;
import com.amnesica.kryptey.inputmethod.signalprotocol.Account;
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
 * Deleting a contact does not answer a substitution, and re-adding them must say so.
 *
 * <p>{@code addContact}'s accepted-bundle arm calls {@code warnIfIdentityChanged}. A review found
 * that call is pinned by exactly one test - {@code NoWriteResultIsDiscardedTest}'s exemption-list
 * audit - which names the site rather than asserting anything about behaviour. Delete the call and
 * that audit fails because its exemption no longer names a real site; nothing fails because a user
 * stopped being warned. That is bookkeeping, not coverage.
 *
 * <p>The premise is reachable and needs no forgery beyond the first one. A messenger offers a
 * different key at an address the user has pinned; it is refused and recorded as pending. The user
 * deletes the contact row - which is what the app's own duplicate-name advice tells them to do -
 * and {@code removeContactFromContactListAndProtocol} deliberately clears neither the pin nor
 * {@code pendingIdentities}, so the record outlives the row. The peer then sends an ordinary,
 * genuine re-invite, the user accepts it, and it lands on this arm.
 *
 * <p><b>What is actually lost, measured rather than assumed.</b> The first version of this test
 * asserted the user would be left with "Contact created. You can send messages now." That is wrong,
 * and running the mutant said so: the banner in the broken world is <em>"Contact Bob Jones ...
 * created. This key reached you through the messenger and the app cannot tell whose it is - compare
 * the security number by voice before sending anything private."</em> The generic pin caution still
 * fires and still sends the user to the safety number.
 *
 * <p>So the loss is narrower than "no warning", and stating it narrowly is the point: what goes is
 * the specific sentence saying somebody <em>offered a different key here and it was refused</em>.
 * The user is told to check a new key, not that an impersonation attempt is on record and
 * unanswered at that address - the difference between "this is routine" and "this already happened
 * once". That is why this is MEDIUM rather than HIGH, and the severity is a consequence of the
 * measurement rather than of how the code reads.
 *
 * <p>The arm's own comment already argues the call is safe here - the raiser is a function of
 * {@code hasUnacceptedIdentityChange} alone and says nothing about the envelope just processed - so
 * what was missing was never the reasoning, only a test that fails when the line goes.
 */
@RunWith(RobolectricTestRunner.class)
public class ApendingSubstitutionSurvivesDeletingTheRowTest {

  private E2EEStripView strip;
  private Account victim;
  private SignalProtocolAddress peerAddress;
  private String genuineBundle;
  private String impostorBundle;

  @Before
  public void setUp() throws Exception {
    final Application app = RuntimeEnvironment.getApplication();

    SignalProtocolMain.resetForTest();
    SignalProtocolMain.testIsRunning = true;

    SignalProtocolMain.initialize(null);
    victim = SignalProtocolMain.getInstance().getAccount();

    SignalProtocolMain.initialize(null);
    final Account peer = SignalProtocolMain.getInstance().getAccount();
    peerAddress = ProtocolAddresses.of(peer.getSignalProtocolAddress().getName(),
        peer.getDeviceId());
    SignalProtocolMain.getInstance().setAccount(peer);
    peer.setMessageLogLoader(ArrayList::new);
    genuineBundle = SignalProtocolMain.exportOwnKeyBundle();

    // A third party's own identity, which the messenger will offer at the peer's address.
    SignalProtocolMain.initialize(null);
    final Account impostor = SignalProtocolMain.getInstance().getAccount();
    SignalProtocolMain.getInstance().setAccount(impostor);
    impostor.setMessageLogLoader(ArrayList::new);
    impostorBundle = SignalProtocolMain.exportOwnKeyBundle();

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

  private void typeTheName() {
    ((EditText) strip.findViewById(R.id.e2ee_add_contact_first_name_input_field)).setText("Bob");
    ((EditText) strip.findViewById(R.id.e2ee_add_contact_last_name_input_field)).setText("Jones");
  }

  private String banner() {
    return String.valueOf(
        ((android.widget.TextView) strip.findViewById(R.id.e2ee_info_text)).getText());
  }

  /** Adds the peer from their genuine invite, the ordinary way. */
  private Contact addThePeer() throws Exception {
    typeTheName();
    strip.addContactForTest(EnvelopeCodec.fromWire(genuineBundle));
    final ArrayList<Contact> contacts = victim.getContactList();
    assertTrue("precondition: the add must have created a row", contacts.size() >= 1);
    return contacts.get(contacts.size() - 1);
  }

  /**
   * The whole sequence, and the assertion is on the banner the user is left looking at.
   *
   * <p>Asserting the sentence rather than "no crash" or "some warning": the failure this guards is
   * a specific reassuring string appearing where a warning belongs, and only the text distinguishes
   * the two worlds.
   */
  @Test
  public void recreatingAcontactMustStillNameTheSubstitutionOnRecord() throws Exception {
    final Contact bob = addThePeer();

    // The messenger offers a different key at Bob's address. Refused; recorded as pending.
    SignalProtocolMain.processPreKeyResponseMessage(
        EnvelopeCodec.fromWire(impostorBundle), peerAddress);
    assertTrue("precondition: the substitution must be on record",
        SignalProtocolMain.hasUnacceptedIdentityChange(peerAddress));

    // The user deletes the row - which the app's own duplicate-name advice tells them to do.
    // The return value is asserted rather than dropped: a deletion whose write fails is rolled back
    // wholesale, so every assertion after it would be reading the state from before it, and this
    // whole case rests on the row actually being gone.
    assertTrue("precondition: the deletion must have reached disk",
        SignalProtocolMain.removeContactFromContactListAndProtocol(bob));
    assertTrue("precondition: deleting the row must NOT answer the substitution - this is the "
            + "property the whole case rests on, and if it ever changes this test should be "
            + "revisited rather than repaired",
        SignalProtocolMain.hasUnacceptedIdentityChange(peerAddress));

    // Bob sends an ordinary re-invite and the user accepts it.
    typeTheName();
    strip.addContactForTest(EnvelopeCodec.fromWire(genuineBundle));

    assertTrue("re-adding a contact at an address carrying an unanswered substitution must still "
            + "name it. Without this line the generic new-key caution still fires - so the user "
            + "is not left unwarned - but they are told to check a new key rather than that "
            + "somebody already offered a different one here and was refused. Banner was: "
            + banner(),
        banner().contains("offered a different key"));
    assertTrue("...and it must point at the voice comparison: " + banner(),
        banner().contains("compare the number"));
  }

  /**
   * The control: with no substitution on record, the ordinary reassurance is what appears.
   *
   * <p>Without this, the case above would pass on a build that shows the identity-change sentence
   * unconditionally - which would be a worse defect than the one it guards, since a warning that is
   * always on is a warning nobody reads.
   */
  @Test
  public void anordinaryAddWithNothingOnRecordIsNotWarnedAbout() throws Exception {
    typeTheName();
    strip.addContactForTest(EnvelopeCodec.fromWire(genuineBundle));

    assertNotNull(banner());
    assertTrue("an add with no substitution on record must not carry the identity-change sentence: "
        + banner(), !banner().contains("offered a different key"));
  }
}
