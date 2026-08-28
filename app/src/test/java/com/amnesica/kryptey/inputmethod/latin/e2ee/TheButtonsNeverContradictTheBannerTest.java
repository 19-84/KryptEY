package com.amnesica.kryptey.inputmethod.latin.e2ee;

import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.view.ContextThemeWrapper;
import android.widget.EditText;
import android.widget.TextView;

import com.amnesica.kryptey.inputmethod.R;
import com.amnesica.kryptey.inputmethod.signalprotocol.Account;
import com.amnesica.kryptey.inputmethod.signalprotocol.SignalProtocolMain;
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
import java.util.List;

/**
 * Two invariants over the whole space of states the strip can be in, rather than one case at a time.
 *
 * <p>Four consecutive review rounds each found the previous round's fix in this one decision — which
 * button is live, and what the banner says while it is. Not the same defect each time: a gate
 * matched against the start of a string that a warning could push it off; a refusal that disabled
 * the very button needed to escape it; a claim gated on the wrong question. Case-by-case tests kept
 * passing because each was written for the case that had just been fixed.
 *
 * <p>So these are stated as properties and checked across the cross product of the states a hostile
 * messenger can arrange. Each combination is built by driving real flows — a store that writes or
 * does not, a real invite, the real password-field entry point — rather than by setting fields, so a
 * combination that passes is one the app can actually be in.
 *
 * <p><b>Invariant one: the app never offers what it forbids.</b> If Encrypt is live, nothing on the
 * lasting surface is telling the user not to send. The failure this catches is not cosmetic —
 * {@code encryptAndSendInputFieldContent} has no storage guard of its own, so an Encrypt that is
 * live under a "do not send them anything" banner hands the messenger ciphertext for a session that
 * exists only in memory.
 *
 * <p><b>Invariant two: there is always a way out.</b> Decrypt may be dark only for reasons that are
 * about the whole app — an unreadable store, a password field, no contact chosen — never because one
 * contact's row failed to save. Disabling it for a per-contact reason is what produced a state with
 * no exit: the notice tells the user to add the contact again, adding needs pasting, pasting needs
 * Decrypt, and deleting the contact first does not help because a deletion whose write also fails is
 * not treated as done. Both buttons stayed dark for every contact until the process was killed.
 *
 * <p>What this does not do is prove those are the only invariants worth having, or that every
 * reachable state appears below. It fixes the two that four rounds of case-by-case work kept
 * breaking.
 */
@RunWith(RobolectricTestRunner.class)
public class TheButtonsNeverContradictTheBannerTest {

  private E2EEStripView strip;
  private String peerBundle;
  private SignalProtocolAddress peerAddress;

  @Before
  public void setUp() throws Exception {
    SignalProtocolMain.resetForTest();
    SignalProtocolMain.testIsRunning = true;
  }

  @After
  public void tearDown() {
    SignalProtocolMain.resetForTest();
    SignalProtocolMain.testIsRunning = false;
  }

  /** A whole world, rebuilt per combination so no case inherits the previous one's state. */
  private void build(final boolean writeLands) throws Exception {
    final Application app = RuntimeEnvironment.getApplication();
    SignalProtocolMain.resetForTest();
    SignalProtocolMain.testIsRunning = true;

    SignalProtocolMain.initialize(null);
    final Account peer = SignalProtocolMain.getInstance().getAccount();
    peer.setMessageLogLoader(ArrayList::new);
    peerAddress = ProtocolAddresses.of(peer.getSignalProtocolAddress().getName(),
        peer.getDeviceId());
    peerBundle = SignalProtocolMain.exportOwnKeyBundle();

    SignalProtocolMain.initialize(null);
    SignalProtocolMain.getInstance().getAccount().setMessageLogLoader(ArrayList::new);

    strip = new E2EEStripView(
        new ContextThemeWrapper(app, R.style.KeyboardTheme_LXX_Pure_Day), null);
    strip.setListener(new E2EEStripView.Listener() {
      @Override public void onTextInput(final String rawText) { }
      @Override public void onSensitiveContentVisibilityChanged(final boolean sensitive) { }
    }, strip);
    SignalProtocolMain.setStorageStateForTest(StorageHelper.StorageState.READABLE);

    if (writeLands) {
      TestStores.writesLand();
    } else {
      SignalProtocolMain.getInstance().setStorageHelperForTest(
          new StorageHelper(app, (ctx, has) -> null) {
            @Override
            public boolean storeAllInformationInSharedPreferences(final Account account) {
              return false;
            }
          });
    }

    ((EditText) strip.findViewById(R.id.e2ee_add_contact_first_name_input_field)).setText("Bob");
    ((EditText) strip.findViewById(R.id.e2ee_add_contact_last_name_input_field)).setText("Jones");
    strip.addContactForTest(EnvelopeCodec.fromWire(peerBundle));
  }

  private String banner() {
    return String.valueOf(
        ((TextView) strip.findViewById(R.id.e2ee_info_text)).getText());
  }

  private boolean encryptLive() {
    return strip.findViewById(R.id.e2ee_button_encrypt).isEnabled();
  }

  private boolean decryptLive() {
    return strip.findViewById(R.id.e2ee_button_decrypt).isEnabled();
  }

  /** Every sentence the app uses to mean "do not send anything to this contact". */
  private boolean bannerForbidsSending() {
    final String text = banner();
    return text.contains("Do not send them anything")
        || text.contains(E2EEStripView.INFO_STORAGE_UNREADABLE);
  }

  /**
   * What a hostile app can do between the state being reached and the user acting.
   *
   * <p>The first version of this sweep enumerated states and stopped. It missed the defect that
   * matters most, because the messenger does not need to reach a bad state - it needs to move the
   * app out of a good one. Hiding the keyboard, relaying a message from anybody, and offering
   * clipboard content are all unilateral, and the first two move the recipient.
   */
  private enum Interference {
    NOTHING,
    /** Any app may hide the keyboard. This nulls the chosen recipient. */
    HIDE_AND_RAISE,
    /** So does tapping the banner, which is a natural gesture on a notice just read. */
    TAP_THE_BANNER,
    /** And ordinary clipboard traffic, which the messenger produces at will. */
    CLIPBOARD_TRAFFIC
  }

  /** The states this cross product walks, named so a failure says which one broke. */
  private static final class Case {
    final boolean writeLands;
    final boolean warningStanding;
    final boolean storageUnreadable;
    final boolean passwordField;
    final Interference interference;

    Case(final boolean w, final boolean warn, final boolean unreadable, final boolean password,
        final Interference interference) {
      this.writeLands = w;
      this.warningStanding = warn;
      this.storageUnreadable = unreadable;
      this.passwordField = password;
      this.interference = interference;
    }

    @Override
    public String toString() {
      return "[write " + (writeLands ? "landed" : "failed")
          + ", " + (warningStanding ? "warning standing" : "no warning")
          + ", store " + (storageUnreadable ? "unreadable" : "readable")
          + ", " + (passwordField ? "password field" : "ordinary field")
          + ", then " + interference + "]";
    }
  }

  private static List<Case> everyCombination() {
    final List<Case> cases = new ArrayList<>();
    for (final boolean write : new boolean[] {true, false}) {
      for (final boolean warn : new boolean[] {true, false}) {
        for (final boolean unreadable : new boolean[] {true, false}) {
          for (final boolean password : new boolean[] {true, false}) {
            for (final Interference i : Interference.values()) {
              cases.add(new Case(write, warn, unreadable, password, i));
            }
          }
        }
      }
    }
    return cases;
  }

  private void enter(final Case c) throws Exception {
    build(c.writeLands);
    if (c.warningStanding) {
      // Warning-first composition is the point: this is what pushed the lost-write notice off the
      // start of the banner and defeated a gate that matched there.
      strip.setWarningMessageAboutForTest("Careful: someone offered a different key for Bob.",
          strip.chosenContactForTest());
    }
    if (c.storageUnreadable) {
      SignalProtocolMain.setStorageStateForTest(StorageHelper.StorageState.UNREADABLE);
    }
    strip.setHostFieldIsPassword(c.passwordField);

    // Now let the adversary act, and put the user back where they were.
    final com.amnesica.kryptey.inputmethod.signalprotocol.chat.Contact chosen =
        strip.chosenContactForTest();
    switch (c.interference) {
      case HIDE_AND_RAISE:
        strip.onKeyboardHidden();
        if (chosen != null) strip.selectContact(chosen);
        break;
      case TAP_THE_BANNER:
        strip.resetChosenContactAndInfoTextForTest();
        if (chosen != null) strip.selectContact(chosen);
        break;
      case CLIPBOARD_TRAFFIC:
        strip.onClipboardHoldsDecryptableItemForTest();
        break;
      case NOTHING:
      default:
        break;
    }
  }

  @Test
  public void theappNeverOffersToSendWhileTellingTheUserNotTo() throws Exception {
    final List<String> broken = new ArrayList<>();
    for (final Case c : everyCombination()) {
      enter(c);
      if (encryptLive() && bannerForbidsSending()) {
        broken.add(c + " Encrypt live under: " + banner());
      }
    }

    assertTrue("Encrypt was live while the lasting surface forbade sending. "
        + "encryptAndSendInputFieldContent has no storage guard of its own, so this hands the "
        + "messenger ciphertext for a session that may exist only in memory - and the states below "
        + "are ones a hostile app can arrange:\n" + String.join("\n", broken), broken.isEmpty());
  }

  @Test
  public void thereIsAlwaysAwayBackIn() throws Exception {
    final List<String> trapped = new ArrayList<>();
    for (final Case c : everyCombination()) {
      enter(c);
      // Decrypt may be dark for reasons about the whole app. Never for one contact's failed write.
      final boolean aWholeAppReason = c.storageUnreadable || c.passwordField;
      if (!decryptLive() && !aWholeAppReason) {
        trapped.add(c + " Decrypt dark under: " + banner());
      }
    }

    assertTrue("Decrypt was dark for a reason that is about one contact rather than the whole app. "
        + "That is the shape with no exit: the notice says to add the contact again, adding means "
        + "pasting their invite, pasting needs Decrypt, and deleting them first does not help "
        + "because a deletion whose write also fails is not treated as done. Both buttons then stay "
        + "dark for every contact until the process is killed:\n" + String.join("\n", trapped),
        trapped.isEmpty());
  }

  /** The cross product really visits both answers, or the two tests above are vacuous. */
  @Test
  public void thecrossProductActuallyVariesTheButtons() throws Exception {
    boolean sawEncryptLive = false;
    boolean sawEncryptDark = false;
    for (final Case c : everyCombination()) {
      enter(c);
      sawEncryptLive |= encryptLive();
      sawEncryptDark |= !encryptLive();
    }
    assertTrue("the sweep must reach a state where Encrypt is live, or the first invariant is "
        + "satisfied by never offering anything", sawEncryptLive);
    assertTrue("and one where it is dark, or the first invariant is satisfied by never refusing",
        sawEncryptDark);
  }
}
