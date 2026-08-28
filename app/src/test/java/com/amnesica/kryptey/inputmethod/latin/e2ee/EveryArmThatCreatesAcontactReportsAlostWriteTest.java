package com.amnesica.kryptey.inputmethod.latin.e2ee;

import com.amnesica.kryptey.inputmethod.signalprotocol.storage.TestStores;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.view.ContextThemeWrapper;
import android.widget.EditText;

import com.amnesica.kryptey.inputmethod.R;
import com.amnesica.kryptey.inputmethod.signalprotocol.Account;
import com.amnesica.kryptey.inputmethod.signalprotocol.MessageEnvelope;
import com.amnesica.kryptey.inputmethod.signalprotocol.SignalProtocolMain;
import com.amnesica.kryptey.inputmethod.signalprotocol.encoding.EnvelopeCodec;
import com.amnesica.kryptey.inputmethod.signalprotocol.helper.StorageHelper;
import com.amnesica.kryptey.inputmethod.signalprotocol.prekey.PreKeyResponse;
import com.amnesica.kryptey.inputmethod.signalprotocol.prekey.PreKeyResponseItem;
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
import java.util.List;

/**
 * A contact that exists only in memory must be reported however it was created.
 *
 * <p>{@code createAndAddContactToContacts} records whether the account write landed, and there is
 * exactly one place it is called from the add screen — but THREE ways out of {@code addContact}
 * after it. The notice was read inside one of them, the arm where a bundle both arrived and
 * established a session, so the other two created a contact that will not survive the next raise
 * and said nothing about it.
 *
 * <p>The silent one that matters is the ciphertext-only envelope. That arm's own comment records
 * that it pins a key by trust-on-first-use; the user reads a decrypted message from a brand-new
 * contact, is sent off to compare a security number, and the row is gone at the next raise — and
 * the log write can succeed while the account write fails, so the message notice does not cover it
 * either. Nothing on screen is false there; nothing is said at all, which for a disappearing
 * contact is the same thing.
 *
 * <p>This drives all three arms with a storage layer whose account write fails, and demands the
 * notice from each. It is written as a cross-product rather than three cases so that a fourth exit
 * added later is a hole someone has to notice rather than one this file silently ignores — the
 * completeness check at the bottom counts the arms.
 */
@RunWith(RobolectricTestRunner.class)
public class EveryArmThatCreatesAcontactReportsAlostWriteTest {

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
      // This fixture depends on writes landing; see TestStores.
    TestStores.writesLand();
}

  @After
  public void tearDown() {
    SignalProtocolMain.resetForTest();
    SignalProtocolMain.testIsRunning = false;
  }

  /**
   * A store that loads fine and cannot write.
   *
   * <p>The state the notice exists for, and the one that looks healthy: an unreadable store has no
   * account and therefore creates no contact, so it was already covered by having nothing to
   * report.
   */
  private void makeTheAccountWriteFail() {
    SignalProtocolMain.getInstance().setStorageHelperForTest(
        new StorageHelper(RuntimeEnvironment.getApplication(), (ctx, hasExistingData) -> null) {
          @Override
          public boolean storeAllInformationInSharedPreferences(final Account account) {
            return false;
          }
        });
  }

  private void typeTheName() {
    ((EditText) strip.findViewById(R.id.e2ee_add_contact_first_name_input_field)).setText("Bob");
    ((EditText) strip.findViewById(R.id.e2ee_add_contact_last_name_input_field)).setText("Jones");
  }

  /** The relay's edit: a bundle whose one-time pre-key is gone, which this app refuses. */
  private MessageEnvelope refusedInvite() throws Exception {
    final PreKeyResponse genuine = EnvelopeCodec.fromWire(genuineBundle).getPreKeyResponse();
    final PreKeyResponseItem device = genuine.getDevices().get(0);
    assertNotNull("precondition: a genuine invite carries a one-time pre-key", device.getPreKey());
    final List<PreKeyResponseItem> devices = new ArrayList<>();
    devices.add(new PreKeyResponseItem(device.getDeviceId(), device.getRegistrationId(),
        device.getSignedPreKey(), null, device.getKyberPreKey()));
    return EnvelopeCodec.fromWire(EnvelopeCodec.toWire(new MessageEnvelope(
        new PreKeyResponse(genuine.getIdentityKey(), devices),
        peerAddress.getName(), peerAddress.getDeviceId())));
  }

  /** A message with no bundle beside it — the arm that pins by trust-on-first-use. */
  private MessageEnvelope ciphertextOnly() throws Exception {
    final SignalProtocolAddress victimAddress = ProtocolAddresses.of(
        victim.getSignalProtocolAddress().getName(), victim.getDeviceId());
    final String victimBundle = SignalProtocolMain.exportOwnKeyBundle();

    SignalProtocolMain.getInstance().setAccount(peer);
    assertTrue("precondition: the peer must be able to open a session with us",
        SignalProtocolMain.processPreKeyResponseMessage(
            EnvelopeCodec.fromWire(victimBundle), victimAddress));
    final MessageEnvelope sent = SignalProtocolMain.encryptMessage("hello from a stranger",
        victimAddress);
    assertNotNull(sent);
    SignalProtocolMain.getInstance().setAccount(victim);

    // Stripped of any bundle, which is what an ordinary PreKey message from someone the user has
    // never added looks like on arrival.
    return EnvelopeCodec.fromWire(EnvelopeCodec.toWire(new MessageEnvelope(
        sent.getCiphertextMessage(), sent.getCiphertextType(),
        peerAddress.getName(), peerAddress.getDeviceId())));
  }

  private String banner() {
    return String.valueOf(
        ((android.widget.TextView) strip.findViewById(R.id.e2ee_info_text)).getText());
  }

  private String expectedNotice() throws Exception {
    final java.lang.reflect.Field f =
        E2EEStripView.class.getDeclaredField("INFO_CONTACT_NOT_SAVED");
    f.setAccessible(true);
    return (String) f.get(strip);
  }

  /**
   * Whether any toast raised during the add said the contact was not saved.
   *
   * <p>Every toast, not the latest one. The security warnings on these arms are posted AFTER this
   * notice on purpose - the last toast posted is the one left on screen, and a key warning must not
   * be buried under a storage one - so a check that read only the latest would report the opposite
   * of the truth on exactly the arms that matter.
   */
  private boolean theLostWriteWasReported() throws Exception {
    final String notice = expectedNotice();
    final String stem = notice.substring(0, notice.indexOf("%s"));
    for (final android.widget.Toast toast : org.robolectric.Shadows
        .shadowOf(RuntimeEnvironment.getApplication()).getShownToasts()) {
      // Through the shadow's own record rather than through Toast.getView(), which returns null for
      // an ordinary text toast on every API this app supports.
      final Object shadow = org.robolectric.shadow.api.Shadow.extract(toast);
      final java.lang.reflect.Field textField =
          shadow.getClass().getDeclaredField("text");
      textField.setAccessible(true);
      final Object text = textField.get(shadow);
      if (text != null && String.valueOf(text).startsWith(stem)) return true;
    }
    return false;
  }

  @Test
  public void abundleThatEstablishesAsessionReportsIt() throws Exception {
    makeTheAccountWriteFail();
    typeTheName();
    strip.addContactForTest(EnvelopeCodec.fromWire(genuineBundle));

    assertTrue("the arm this notice was written for must still report", theLostWriteWasReported());
  }

  @Test
  public void abundleThatWasRefusedReportsItToo() throws Exception {
    makeTheAccountWriteFail();
    typeTheName();
    strip.addContactForTest(refusedInvite());

    assertTrue("a refused invite still creates the contact row, and that row is still lost at the "
            + "next raise. The banner's ask-for-a-fresh-invite advice does not cover it: the user "
            + "asks for another invite, gets one, and the contact vanishes again.",
        theLostWriteWasReported());
  }

  @Test
  public void amessageWithNoBundleReportsItAsWell() throws Exception {
    final MessageEnvelope message = ciphertextOnly();
    makeTheAccountWriteFail();
    typeTheName();
    strip.addContactForTest(message);

    assertTrue("the silent arm. A contact is created, a key is pinned by trust-on-first-use, a "
            + "message is decrypted and shown, and the row does not survive the next raise - with "
            + "the log write succeeding, so the message notice does not cover it either. The user "
            + "is sent to compare a security number for a contact that is about to disappear.",
        theLostWriteWasReported());
  }

  /**
   * And a successful write says nothing, so the tests above are not passing on a notice that always
   * fires.
   */
  @Test
  public void awriteThatLandsSaysNothing() throws Exception {
    typeTheName();
    strip.addContactForTest(EnvelopeCodec.fromWire(genuineBundle));

    assertTrue("the notice must not fire when the contact was saved, or the three tests above "
        + "prove nothing", !theLostWriteWasReported());
  }

  /**
   * And the lost write reaches the surface that lasts, not only the one that fades.
   *
   * <p>The defect this closes: the toast said the contact was not saved while the caution stored
   * beside it said "Contact X created… compare the security number by voice before sending anything
   * private". The toast is about three and a half seconds; the caution survives every repaint, a
   * screen switch and a rebuild. So the FALSE statement was the persistent one, and it sent the user
   * off to compare a number for a contact that will not exist after the next raise — a raise whose
   * timing the messenger controls.
   */
  @Test
  public void thelostWriteIsOnTheBannerAndNotOnlyInAtoast() throws Exception {
    makeTheAccountWriteFail();
    typeTheName();
    strip.addContactForTest(EnvelopeCodec.fromWire(genuineBundle));

    final String banner = String.valueOf(
        ((android.widget.TextView) strip.findViewById(R.id.e2ee_info_text)).getText());

    assertTrue("the banner is what survives; it must carry the lost write rather than a success "
            + "claim about the same contact: " + banner,
        banner.contains("could not be saved"));
    assertTrue("and it must not still be telling the user to go and compare a security number for "
            + "a contact that is about to disappear: " + banner,
        !banner.contains("compare the security number"));
  }

  /** The healthy case keeps the caution it is supposed to keep. */
  @Test
  public void awriteThatLandsStillGetsTheCompareTheNumberCaution() throws Exception {
    typeTheName();
    strip.addContactForTest(EnvelopeCodec.fromWire(genuineBundle));

    final String banner = String.valueOf(
        ((android.widget.TextView) strip.findViewById(R.id.e2ee_info_text)).getText());
    assertTrue("replacing the caution on a FAILED write must not have removed it from the "
            + "succeeding one - that caution is the only notice that fires because nothing was "
            + "noticed: " + banner, banner.contains("compare the security number"));
  }

  /**
   * The buttons must agree with the sentence — and only the button the sentence is about.
   *
   * <p>Encrypt goes dark, because the banner says "do not send them anything until you have added
   * them again successfully" and {@code encryptAndSendInputFieldContent} has no storage guard of its
   * own: pressing it hands the messenger ciphertext for a contact and session that exist only in
   * memory.
   *
   * <p><b>Decrypt does not</b>, and that is the more important half. Disabling both was a trap with
   * no exit: adding the contact again means pasting their invite, and pasting needs Decrypt.
   * Deleting them first does not help — a deletion whose write also fails is not treated as done, so
   * the caution stays up while the row leaves the list, taking that contact's verify screen and
   * therefore the only unconditional clear with it. The banner then held a caution nothing could
   * clear, with both buttons dark <em>for every contact</em>, until the input-method process was
   * killed; a rotation did not help, because the caution is carried across a rebuild on purpose.
   */
  @Test
  public void alostWriteDisablesEncryptAndLeavesDecryptAlone() throws Exception {
    makeTheAccountWriteFail();
    typeTheName();
    strip.addContactForTest(EnvelopeCodec.fromWire(genuineBundle));

    assertTrue("precondition: the banner must be carrying the lost write. " + banner(),
        banner().contains("could not be saved"));
    assertTrue("Encrypt must be dark while the app is telling the user not to send anything",
        !strip.findViewById(R.id.e2ee_button_encrypt).isEnabled());
    assertTrue("Decrypt must stay live, or the instruction in the banner cannot be followed: "
            + "adding the contact again means pasting their invite, and there is no other route "
            + "back to the add screen",
        strip.findViewById(R.id.e2ee_button_decrypt).isEnabled());
  }

  /**
   * And the refusal is not read off the banner, so a warning sharing it cannot defeat it.
   *
   * <p>The banner is composed warning-first. While the button state was derived by matching the
   * start of that string, any standing warning pushed the notice into the middle and the match
   * missed — so Encrypt came back on precisely when a security warning was already on screen, which
   * is the state where sending matters most. Both are reachable together in one {@code addContact}.
   */
  @Test
  public void awarningSharingTheBannerDoesNotReviveEncrypt() throws Exception {
    makeTheAccountWriteFail();
    typeTheName();
    strip.setWarningMessageForTest("Careful: something about a key.");
    strip.addContactForTest(EnvelopeCodec.fromWire(genuineBundle));

    assertTrue("precondition: a warning must be sharing the banner, ahead of the notice: "
            + banner(), banner().startsWith("Careful:"));
    assertTrue("and the notice must still be there, further down: " + banner(),
        banner().contains("could not be saved"));
    assertTrue("Encrypt must still be dark. Deriving this from the start of the banner meant a "
            + "warning could turn sending back on.",
        !strip.findViewById(R.id.e2ee_button_encrypt).isEnabled());
  }

  /** Choosing somebody else must not inherit this contact's storage trouble. */
  @Test
  public void anotherContactIsNotPunishedForThisOne() throws Exception {
    makeTheAccountWriteFail();
    typeTheName();
    strip.addContactForTest(EnvelopeCodec.fromWire(genuineBundle));
    assertTrue("precondition", !strip.findViewById(R.id.e2ee_button_encrypt).isEnabled());

    strip.selectContact(new com.amnesica.kryptey.inputmethod.signalprotocol.chat.Contact(
        "Carol", "Smith", peerAddress.getName(), peerAddress.getDeviceId() + 1, false));

    assertTrue("a contact whose row is on disk must be usable; the refusal is about the contact "
            + "that failed to save, not about the app",
        strip.findViewById(R.id.e2ee_button_encrypt).isEnabled());
  }

  /**
   * A session whose write did not land is reported, even when the contact row's did.
   *
   * <p>Two separate writes, either of which can be the one that fails. The row landing while the
   * session and the freshly pinned identity key did not is the worse of the two and used to be
   * entirely silent: {@code buildSession} called
   * {@code storeAllAccountInformationInSharedPreferences} and discarded the result, then returned
   * true, so the user got "Session with X created", a prompt to compare a security number, and a
   * session existing in memory only. Reads keep succeeding from the in-memory store, so nothing
   * looks wrong until the next {@code reloadAccount}.
   *
   * <p>It was the last member of the write family whose result went nowhere — creation, deletion,
   * rejection, verification, the chat log and both message directions all thread theirs up.
   */
  @Test
  public void afailedSessionWriteIsReportedEvenWhenTheRowLanded() throws Exception {
    // The row write lands; the session write does not. One helper, flipped after the row is stored,
    // is how the two are separated in a single add.
    final java.util.concurrent.atomic.AtomicInteger writes =
        new java.util.concurrent.atomic.AtomicInteger();
    SignalProtocolMain.getInstance().setStorageHelperForTest(
        new StorageHelper(RuntimeEnvironment.getApplication(), (ctx, has) -> null) {
          @Override
          public boolean storeAllInformationInSharedPreferences(final Account account) {
            // The first write is the contact row; everything after it in this add is the session.
            return writes.getAndIncrement() == 0;
          }
        });

    typeTheName();
    strip.addContactForTest(EnvelopeCodec.fromWire(genuineBundle));

    assertTrue("precondition: the row's own write must have landed, or this is the other test",
        writes.get() > 1);
    final String banner = String.valueOf(
        ((android.widget.TextView) strip.findViewById(R.id.e2ee_info_text)).getText());
    assertTrue("a session that exists only in memory must be reported. The user is otherwise told "
            + "the session was created and sent off to compare a security number for a key that "
            + "will be gone at the next reload. Banner: " + banner,
        banner.contains("could not be saved"));
    assertTrue("and Encrypt must be dark, for the same reason it is when the row is the part that "
            + "was lost", !strip.findViewById(R.id.e2ee_button_encrypt).isEnabled());
  }

  /**
   * A refused invite whose write also failed keeps both sentences.
   *
   * <p>They are independent facts, and the storage caution used to destroy the other one: the
   * refused-invite line is a plain banner write stored nowhere, so the repaint that posts the
   * caution simply erased it. The user was left with a storage notice and nothing saying the invite
   * itself had failed — while the caution's own advice, "add them again successfully", is not
   * actionable with an invite that will never work.
   */
  @Test
  public void arefusedInviteWhoseWriteAlsoFailedSaysBoth() throws Exception {
    makeTheAccountWriteFail();
    typeTheName();
    strip.addContactForTest(refusedInvite());

    final String banner = String.valueOf(
        ((android.widget.TextView) strip.findViewById(R.id.e2ee_info_text)).getText());
    assertTrue("the lost write must be reported: " + banner, banner.contains("could not be saved"));
    assertTrue("and so must the refused invite, or the user is told to add a contact again using "
            + "an invite that cannot work: " + banner, banner.contains("fresh one"));
  }

  /**
   * Adding somebody else must not cancel the refusal about this contact.
   *
   * <p>There is one caution slot. While the refusal lived on the standing caution, a successful add
   * of any OTHER contact overwrote that caution and took the refusal with it — so the app stopped
   * refusing to send to Bob because the user had added Carol, which is not a fact about whether
   * Bob's row is on disk.
   *
   * <p>This is the mirror of the defect before it: the refusal first lived on "the chosen contact"
   * and was reset by every recipient change, which the messenger forces by hiding the keyboard.
   * Both were the same mistake — keeping the fact somewhere that something else owns.
   */
  @Test
  public void addingAdifferentContactDoesNotCancelTheRefusal() throws Exception {
    makeTheAccountWriteFail();
    typeTheName();
    strip.addContactForTest(EnvelopeCodec.fromWire(genuineBundle));
    final com.amnesica.kryptey.inputmethod.signalprotocol.chat.Contact bob =
        strip.chosenContactForTest();
    assertNotNull(bob);
    assertTrue("precondition: Encrypt must be refused for Bob",
        !strip.findViewById(R.id.e2ee_button_encrypt).isEnabled());

    // Storage recovers and any later account write lands. Verifying is used because it is
    // unambiguous: one call, one write, and no second contact to complicate the address space.
    // (The first version of this used a second "contact" that happened to sit at Bob's address, so
    // the add was refused and nothing was written - the test was measuring the wrong event.)
    TestStores.writesLand();
    final long before = SignalProtocolMain.accountWritesLanded();
    SignalProtocolMain.verifyContact(bob);
    assertTrue("precondition: a later write must actually have landed",
        SignalProtocolMain.accountWritesLanded() > before);

    // Back to Bob.
    strip.selectContact(bob);

    assertTrue("after a later write landed, Bob's row is on disk, so the refusal must be gone - "
            + "asked of the control rather than of the button, because a dark button is a hint and "
            + "the send guard is the thing that actually refuses",
        !strip.sendingIsRefusedForTest());
    assertTrue("and the sentence that justified the refusal must go with it - a refusal that "
            + "expires while the banner still says 'do not send them anything' is the app "
            + "offering exactly what it forbids, permanently: " + banner(),
        !banner().contains("could not be saved"));
  }

  /** But while no write has landed, another contact's caution cannot lift it. */
  @Test
  public void anunrelatedCautionDoesNotLiftTheRefusalWhileNothingHasBeenWritten() throws Exception {
    makeTheAccountWriteFail();
    typeTheName();
    strip.addContactForTest(EnvelopeCodec.fromWire(genuineBundle));
    final com.amnesica.kryptey.inputmethod.signalprotocol.chat.Contact bob =
        strip.chosenContactForTest();
    assertNotNull(bob);

    // A caution about somebody else, with the store still failing, so nothing has reached disk.
    strip.setCautionForTest("Contact Carol Smith created. Compare the security number by voice.",
        new com.amnesica.kryptey.inputmethod.signalprotocol.chat.Contact(
            "Carol", "Smith", peerAddress.getName(), peerAddress.getDeviceId() + 5, false));
    strip.selectContact(bob);

    assertTrue("the caution about Carol says nothing about whether Bob's row reached disk, and "
            + "overwriting the banner must not cancel a refusal it does not own",
        !strip.findViewById(R.id.e2ee_button_encrypt).isEnabled());
  }

  /**
   * The refusal survives being suppressed on screen.
   *
   * <p>{@code sessionCreationFailed} was recorded inside the guard that decides whether to PAINT the
   * refusal line — and that guard fires when a standing item already holds the banner, which is the
   * common case immediately after adding anyone. So the fact was lost exactly when the guard fired,
   * and the lost-write caution then composed itself without the refusal while overwriting the very
   * standing item the guard was protecting. The suppression protected nothing and cost the sentence:
   * the user was told to "add them again successfully" with an invite that will never work, and
   * never told it had been refused.
   */
  @Test
  public void arefusedInviteIsStillSaidWhenItsLineWasSuppressed() throws Exception {
    // A standing item already on the banner, which is what triggers the suppression.
    strip.setWarningMessageForTest("Careful: something about a key.");

    makeTheAccountWriteFail();
    typeTheName();
    strip.addContactForTest(refusedInvite());

    final String banner = String.valueOf(
        ((android.widget.TextView) strip.findViewById(R.id.e2ee_info_text)).getText());
    assertTrue("the lost write must be reported: " + banner, banner.contains("could not be saved"));
    assertTrue("and so must the refused invite, even though its own line was suppressed - the "
            + "caution that replaced the banner is now the only place it can be said: " + banner,
        banner.contains("fresh one"));
  }

  /**
   * The trust-on-first-use arm reports a lost write too.
   *
   * <p>A ciphertext-only envelope never reaches {@code buildSession}, so the write inside
   * {@code decrypt} is the <em>only</em> place the freshly pinned key and its session are stored.
   * That result was discarded, which left the inviter's side of every conversation this app sets up
   * silent about a lost write: the row lands, no notice fires, the user is told to compare a
   * security number, and the pin exists in memory only. The next reload unpins it, so the peer's
   * next message is trust-on-first-use again and unwarned — and the messenger chooses when that
   * reload happens by forcing a configuration change.
   *
   * <p>Distinct from the arm above it: there the bundle's own write is what fails. Here there is no
   * bundle at all, which is exactly why the single discarded line mattered.
   */
  @Test
  public void thetrustOnFirstUseArmReportsAlostWriteToo() throws Exception {
    final MessageEnvelope message = ciphertextOnly();
    // The ROW write lands and the decrypt's write does not, which is the only configuration that
    // isolates the discarded line. Failing every write would raise the caution from the row half
    // and the test would pass with the defect in place - which is exactly what the first version
    // of it did.
    final java.util.concurrent.atomic.AtomicInteger writes =
        new java.util.concurrent.atomic.AtomicInteger();
    SignalProtocolMain.getInstance().setStorageHelperForTest(
        new StorageHelper(RuntimeEnvironment.getApplication(), (ctx, has) -> null) {
          @Override
          public boolean storeAllInformationInSharedPreferences(final Account account) {
            return writes.getAndIncrement() == 0;
          }
        });
    typeTheName();
    strip.addContactForTest(message);

    assertTrue("precondition: the row's own write must have landed, or this is the other test",
        writes.get() > 1);

    final String banner = String.valueOf(
        ((android.widget.TextView) strip.findViewById(R.id.e2ee_info_text)).getText());
    assertTrue("the arm that pins by trust-on-first-use must report a lost write, not only the one "
            + "that builds a session from a bundle: " + banner,
        banner.contains("could not be saved"));
    assertTrue("and sending must be refused until it is saved", strip.sendingIsRefusedForTest());
  }
}
