package com.amnesica.kryptey.inputmethod.latin.e2ee;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.view.ContextThemeWrapper;
import android.widget.TextView;

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
 * The banner holds one warning, so warnings displace each other — and that is only survivable
 * because every one of them can be worked out again.
 *
 * <p>Three writers run on every selection, in reverse severity so the most serious wins the slot for
 * the contact being tapped. That orders them against each other and not across subjects: tapping a
 * contact whose name is shared displaces an identity-change warning about a <em>different</em>
 * contact. A review round raised that, and the obvious fix — make the least severe one yield — was
 * tried here and reverted, because the test written for the eviction attack caught it immediately:
 * yielding to any standing warning means an attacker raises one cheap warning about somebody else
 * and the duplicate-name warning never returns. Closing a displacement by reopening an eviction is
 * strictly worse.
 *
 * <p>So the property that makes displacement acceptable is the one worth pinning: <b>a displaced
 * warning comes back</b>. Each is re-derived from state rather than remembered, so looking at its own
 * subject re-raises it. This checks that for each of the three, by displacing it and then looking.
 */
@RunWith(RobolectricTestRunner.class)
public class AwarningDisplacedIsAwarningThatComesBackTest {

  private E2EEStripView strip;
  private Account victim;
  private Contact bob;
  private Contact impostor;
  private SignalProtocolAddress bobAddress;

  @Before
  public void setUp() throws Exception {
    final Application app = RuntimeEnvironment.getApplication();

    SignalProtocolMain.resetForTest();
    SignalProtocolMain.testIsRunning = true;

    SignalProtocolMain.initialize(null);
    final Account peer = SignalProtocolMain.getInstance().getAccount();
    bobAddress = ProtocolAddresses.of(peer.getSignalProtocolAddress().getName(), peer.getDeviceId());
    final String bobBundle = SignalProtocolMain.exportOwnKeyBundle();

    SignalProtocolMain.initialize(null);
    victim = SignalProtocolMain.getInstance().getAccount();
    victim.setMessageLogLoader(ArrayList::new);
    TestStores.writesLand();
    assertTrue(SignalProtocolMain.processPreKeyResponseMessage(
        EnvelopeCodec.fromWire(bobBundle), bobAddress));

    bob = new Contact("Bob", "Jones", bobAddress.getName(), peer.getDeviceId(), false);
    impostor = new Contact("Bob", "Jones", bobAddress.getName(), peer.getDeviceId() + 9, false);
    final ArrayList<Contact> contacts = new ArrayList<>();
    contacts.add(bob);
    contacts.add(impostor);
    victim.setContactList(contacts);

    strip = new E2EEStripView(
        new ContextThemeWrapper(app, R.style.KeyboardTheme_LXX_Pure_Day), null);
    strip.setListener(new E2EEStripView.Listener() {
      @Override public void onTextInput(final String rawText) { }
      @Override public void onSensitiveContentVisibilityChanged(final boolean sensitive) { }
    }, strip);
    SignalProtocolMain.setStorageStateForTest(StorageHelper.StorageState.READABLE);
  }

  @After
  public void tearDown() {
    SignalProtocolMain.resetForTest();
    SignalProtocolMain.testIsRunning = false;
  }

  private String banner() {
    return String.valueOf(((TextView) strip.findViewById(R.id.e2ee_info_text)).getText());
  }

  /** Displaced by a warning about somebody else, and back when its own row is looked at. */
  @Test
  public void thesharedNameWarningComesBackAfterBeingDisplaced() {
    strip.selectContact(impostor);
    assertTrue("precondition: the shared-name warning must be standing: " + banner(),
        banner().contains("a different one - not a replacement"));

    // Anything else takes the slot, which is what an attacker arranges.
    strip.setWarningMessageForTest("Careful: something else entirely.");
    assertTrue("precondition: the slot must have been taken", !banner()
        .contains("a different one - not a replacement"));

    strip.selectContact(impostor);

    assertTrue("looking at the row must work it out again. A warning that cannot be recomputed is "
            + "one an attacker evicts permanently with a single cheap warning: " + banner(),
        banner().contains("a different one - not a replacement"));
  }

  /** And the storage warning, which is about the whole app rather than a contact. */
  @Test
  public void thestorageWarningComesBackOnTheNextRaise() {
    SignalProtocolMain.setStorageStateForTest(StorageHelper.StorageState.UNREADABLE);
    strip.refreshOpeningMessage();
    assertTrue("precondition: the storage warning must be standing: " + banner(),
        banner().contains(E2EEStripView.INFO_STORAGE_UNREADABLE));

    strip.setWarningMessageForTest("Careful: something else entirely.");
    assertTrue("precondition: the slot must have been taken",
        !banner().contains(E2EEStripView.INFO_STORAGE_UNREADABLE));

    // Not "what LatinIME does on every setInputView", which is what this comment used to say and
    // what it was not. LatinIME asks theStoreMustBeRereadOnThisRaise() first, and after the
    // displacement above the old gate answered no - so production never reached this call and the
    // control was hollow, in exactly the way this file already records for the LOWERING direction.
    // The gate is asked here for that reason.
    assertTrue("the raise must still reconsider the store after the slot was taken; gated on the "
            + "sentence it did not, and the fault latched for the life of the process",
        strip.theStoreMustBeRereadOnThisRaise());
    strip.refreshOpeningMessage();

    assertTrue("the storage warning is the one thing between the user and re-inviting everybody "
            + "into an apparently empty install; it must be re-derived rather than remembered: "
            + banner(), banner().contains(E2EEStripView.INFO_STORAGE_UNREADABLE));
  }

  /**
   * And a storage warning is lowered when it stops being true.
   *
   * <p>These two are the only warnings raised from a <em>condition</em> rather than from an event,
   * and nothing lowered them: when the condition went away the method's remaining branch treated a
   * standing warning as "leave the banner alone", so the sentence stayed. Every clause of it was
   * then false — including the one describing its own exit, "this clears when the device can read
   * its own storage again" — and it held the warning flag, so every informational line was
   * suppressed for the life of the process. The only way out was pressing Verify or Reject on some
   * contact: a security gesture performed for a cosmetic reason.
   */
  @Test
  public void thestorageWarningIsLoweredOnceStorageRecovers() {
    SignalProtocolMain.setStorageStateForTest(StorageHelper.StorageState.UNREADABLE);
    strip.refreshOpeningMessage();
    assertTrue("precondition: the storage warning must be standing: " + banner(),
        banner().contains(E2EEStripView.INFO_STORAGE_UNREADABLE));

    // Storage recovers, and the keyboard is raised again - which is a real sequence now rather
    // than a described one. LatinIME.onStartInputViewInternal asks hasStandingConditionWarning on
    // every raise and, when one is standing, re-reads the store and calls this. Before that caller
    // existed this test drove a path production could not reach: refreshOpeningMessage ran only on
    // setInputView, always against a freshly inflated strip.
    SignalProtocolMain.setStorageStateForTest(StorageHelper.StorageState.READABLE);
    strip.refreshOpeningMessage();

    assertTrue("the warning must come down with its condition. Left standing it asserts something "
            + "false about the store AND suppresses every other notice for the life of the "
            + "process: " + banner(),
        !banner().contains(E2EEStripView.INFO_STORAGE_UNREADABLE));
    assertTrue("and the flag must be down too, or nothing can write the banner again",
        !strip.warningIsStandingForTest());
  }

  /**
   * The per-raise path recognises the two warnings that have no other way down, and only those.
   *
   * <p>{@code LatinIME.onStartInputViewInternal} asks this on every keyboard raise and re-derives
   * only when the answer is yes — a refresh with no warning standing repaints the opening banner,
   * which would wipe whatever the strip is currently saying, and re-asking means re-reading the
   * store. So the answer being wrong in either direction matters: false while a condition warning
   * stands is the permanent banner again, and true for an event warning spends a store read on
   * every raise for the rest of the process.
   */
  @Test
  public void onlyTheconditionWarningsAreOfferedToThePerRaisePath() {
    SignalProtocolMain.setStorageStateForTest(StorageHelper.StorageState.UNREADABLE);
    strip.refreshOpeningMessage();
    assertTrue("a standing storage warning is one the keyboard raise must reconsider",
        strip.hasStandingConditionWarning());

    SignalProtocolMain.setStorageStateForTest(StorageHelper.StorageState.READABLE);
    strip.refreshOpeningMessage();
    assertTrue("and once it is down there is nothing left to reconsider",
        !strip.hasStandingConditionWarning());

    strip.setWarningMessageAboutForTest("Careful: someone offered a different key for Bob.", bob);
    assertTrue("an event warning is not a condition warning: a key substitution does not stop "
            + "having happened, and re-deriving it on every raise would cost a store read forever",
        !strip.hasStandingConditionWarning());

    // And the answer this test used to end on was read as desirable when it was the defect. With
    // no fault standing there is genuinely nothing to re-read, so the raise asks nothing - but the
    // reason is that the fact is false, not that an event warning is in the slot. The fault case is
    // measured in AstorageFaultOutlivesTheSentenceThatDescribesItTest, where the same event warning
    // is standing and the answer must be yes.
    assertTrue("with no store fault there is nothing for the raise to re-read, whatever is on the "
            + "banner - that is where the store read is saved",
        !strip.theStoreMustBeRereadOnThisRaise());
  }

  /**
   * Lowering the warning must not erase what was standing beside it.
   *
   * <p>The lowering path ends by painting the opening line, and it wrote that line RAW - the only
   * banner writer in the file that did not first ask whether a caution or a store notice held the
   * banner. That was invisible while the only callers were a freshly inflated strip and
   * {@code adoptState}, which restores the cautions afterwards and repaints; a keyboard raise
   * reaching it on a live strip is what made it matter, and a live strip in this state is exactly
   * one that holds cautions - the compare-the-number caution for a key just pinned, "do not send
   * them anything" for a row that never reached disk.
   *
   * <p>Their fields stay set when the paint is lost, so nothing would ever have written them again.
   * The fix for one erasure must not perform another.
   */
  @Test
  public void aloweringDoesNotEraseAcautionStandingBesideTheWarning() {
    SignalProtocolMain.setStorageStateForTest(StorageHelper.StorageState.UNREADABLE);
    strip.refreshOpeningMessage();
    strip.setCautionForTest("Compare the safety number with Bob before trusting this key.", bob);
    assertTrue("precondition: the caution must be on screen beside the warning: " + banner(),
        banner().contains("Compare the safety number"));

    SignalProtocolMain.setStorageStateForTest(StorageHelper.StorageState.READABLE);
    strip.refreshOpeningMessage();

    assertTrue("the storage warning came down and took the caution with it. The caution's field is "
            + "still set, so nothing will ever paint it again - the user is left with 'No contact "
            + "chosen' over a key nobody compared: " + banner(),
        banner().contains("Compare the safety number"));
  }

  /**
   * Lowering the warning must not tell a strip with a recipient that it has none.
   *
   * <p>The lowering ends by painting the opening line, and the opening line is "No contact chosen".
   * {@code disablesActionButtons} matches that sentence by prefix, so writing it on a strip that
   * has a recipient does not merely misdescribe the state - it darkens Encrypt and Decrypt on an
   * install whose storage has just recovered.
   *
   * <p>Reachable because a keyboard raise runs this now, and a raise arrives with the recipient
   * still set: the window is still up, so nothing has forgotten it. Three separate defects in this
   * file were this same sentence painted over a live state, and the enumeration written for the
   * previous round covered two of the three.
   */
  @Test
  public void aloweringDoesNotClaimThereIsNoRecipientWhenThereIsOne() {
    strip.selectContact(bob);
    SignalProtocolMain.setStorageStateForTest(StorageHelper.StorageState.UNREADABLE);
    strip.refreshOpeningMessage();
    assertTrue("precondition: the storage warning must be standing: " + banner(),
        banner().contains(E2EEStripView.INFO_STORAGE_UNREADABLE));

    SignalProtocolMain.setStorageStateForTest(StorageHelper.StorageState.READABLE);
    strip.refreshOpeningMessage();

    assertTrue("the strip has a recipient, so it must not say there is none - that sentence also "
            + "darkens both action buttons, on an install whose storage just recovered: " + banner(),
        banner().contains("Chosen contact"));
  }

  /**
   * And the contacts arm is offered to the per-raise path too, not only the storage one.
   *
   * <p>Written because dropping the second half of {@code hasStandingConditionWarning}'s test
   * survived the whole suite: every test here drove the storage arm. That mutant reinstates the
   * original defect on the arm with no other exit at all - the warning it leaves standing tells the
   * user to compare or reject a contact, and the contact list is what cannot be read.
   */
  @Test
  public void thecontactsArmIsOfferedToThePerRaisePathAswell() {
    victim.markContactsUnreadable();
    strip.refreshOpeningMessage();
    assertTrue("precondition: the contacts warning must be standing: " + banner(),
        banner().contains("This is not an empty app"));

    assertTrue("a keyboard raise must reconsider this arm as well; it is the one with no other way "
        + "down", strip.hasStandingConditionWarning());
  }

  /** But a warning about an EVENT is not lowered, because the event still happened. */
  @Test
  public void aneventWarningIsNotLoweredByArefresh() {
    strip.setWarningMessageAboutForTest("Careful: someone offered a different key for Bob.", bob);

    strip.refreshOpeningMessage();

    assertTrue("only the two condition warnings are lowered here. A key substitution does not stop "
            + "having happened because the store is readable: " + banner(),
        banner().contains("different key for Bob"));
  }

  /**
   * The rebuild, in the order LatinIME actually performs it.
   *
   * <p>The previous test for this called {@code refreshOpeningMessage} twice on one strip and
   * described that as "what LatinIME does on every setInputView". It is not. {@code setInputView} is
   * always given a freshly inflated view, so the lowering path — which only runs when a warning is
   * already standing — could never execute in production; and the real order is
   * {@code refreshOpeningMessage} <em>then</em> {@code adoptState}, so the stale warning was
   * re-posted immediately after the lowering was skipped.
   *
   * <p>This drives that order: raise the warning, let storage recover, surrender, build a NEW strip,
   * refresh, adopt. A condition warning must not survive it; an event warning must.
   */
  @Test
  public void aconditionWarningDoesNotSurviveArebuildOnceItsConditionIsGone() {
    SignalProtocolMain.setStorageStateForTest(StorageHelper.StorageState.UNREADABLE);
    strip.refreshOpeningMessage();
    assertTrue("precondition: the storage warning must be standing: " + banner(),
        banner().contains(E2EEStripView.INFO_STORAGE_UNREADABLE));

    SignalProtocolMain.setStorageStateForTest(StorageHelper.StorageState.READABLE);

    final E2EEStripView rebuilt = rebuildInTheOrderLatinImeUses();

    final String shown = String.valueOf(((TextView)
        rebuilt.findViewById(R.id.e2ee_info_text)).getText());
    assertTrue("a warning about the store must be re-derived across a rebuild, not replayed. "
            + "Replayed, it asserts something false, holds the warning flag so every other notice "
            + "is suppressed, and keeps Encrypt and Decrypt dark on an install whose storage has "
            + "recovered - with no user action that clears it: " + shown,
        !shown.contains(E2EEStripView.INFO_STORAGE_UNREADABLE));
    assertTrue("and the flag must be down, or the buttons stay dark",
        !rebuilt.warningIsStandingForTest());
  }

  /** And an EVENT warning does survive the same rebuild, because the event still happened. */
  @Test
  public void aneventWarningSurvivesTheSameRebuild() {
    strip.setWarningMessageAboutForTest("Careful: someone offered a different key for Bob.", bob);

    final E2EEStripView rebuilt = rebuildInTheOrderLatinImeUses();

    final String shown = String.valueOf(((TextView)
        rebuilt.findViewById(R.id.e2ee_info_text)).getText());
    assertTrue("re-deriving the condition warnings must not have made every warning disposable: a "
            + "detected key substitution is not undone by a rotation, and a warning a rebuild can "
            + "forget is one the host app can force away: " + shown,
        shown.contains("different key for Bob"));
  }

  /** Surrender, inflate a fresh strip, refresh, adopt - the order in LatinIME.setInputView. */
  private E2EEStripView rebuildInTheOrderLatinImeUses() {
    final E2EEStripView.CarriedState carried = strip.surrenderState();
    final E2EEStripView rebuilt = new E2EEStripView(new ContextThemeWrapper(
        RuntimeEnvironment.getApplication(), R.style.KeyboardTheme_LXX_Pure_Day), null);
    rebuilt.setListener(new E2EEStripView.Listener() {
      @Override public void onTextInput(final String rawText) { }
      @Override public void onSensitiveContentVisibilityChanged(final boolean sensitive) { }
    }, rebuilt);
    rebuilt.refreshOpeningMessage();
    rebuilt.adoptState(carried);
    return rebuilt;
  }

  /**
   * A displacer that is itself re-derived is not a displacement — it is a permanent loss.
   *
   * <p>{@code selectContact} re-derives four warnings in reverse severity and the last writer wins,
   * which is safe while the displacer is a one-shot. It stopped being safe when the invite refusal
   * became recomputable: for any row whose name folds onto another AND which has a refusal record,
   * the duplicate-name warning was recomputed and immediately overwritten, on every selection, for
   * the life of the record. Recomputed and never rendered is not survivable.
   *
   * <p>It lands on the one control covering the case the pin cannot — two rows the user cannot tell
   * apart — and the attacker's cost is one stripped one-time pre-key on any message relayed from
   * that address, plus the user pressing Decrypt once.
   *
   * <p>The existing test above does not see it, and is not hollow: it displaces with a one-shot
   * writer, which is a different property. Production is the mutant for this one.
   */
  @Test
  public void arecomputedRefusalDoesNotSilenceTheSharedNameWarning() {
    strip.selectContact(impostor);
    assertTrue("precondition: the shared-name warning must be standing: " + banner(),
        banner().contains("a different one - not a replacement"));

    // One relayed message with the one-time pre-key stripped, and one Decrypt press.
    strip.rememberRefusedInviteForTest(impostor,
        "That invite from Bob Jones could not be used - it does not verify, which means it was "
            + "changed on the way here.");

    strip.selectContact(impostor);

    assertTrue("both are about this row and this tap. The refusal must not be the only thing said: "
            + banner(),
        banner().contains("a different one - not a replacement"));
    assertTrue("...and the refusal must still be said too, or fixing this has just moved the "
            + "silence onto the other one: " + banner(),
        banner().contains("changed on the way here"));
  }

  /** And composing is idempotent, or the banner grows a copy on every tap. */
  @Test
  public void thecompositionDoesNotGrowOnEverySelection() {
    strip.rememberRefusedInviteForTest(impostor, "That invite could not be used - it does not "
        + "verify, which means it was changed on the way here.");

    strip.selectContact(impostor);
    final String once = banner();
    strip.selectContact(impostor);
    strip.selectContact(impostor);

    assertEquals("re-deriving both on every selection must produce the same banner, not a longer "
            + "one: " + banner(), once, banner());
  }

  /**
   * And the composition is scoped to one subject, or it merges two people into one sentence.
   *
   * <p>{@code warnIfNameIsShared} lowers its warning only for the contact it is about, so a
   * shared-name warning about one row stays standing while a different row is selected. Composing
   * without checking the subject would then paint "you already have a contact called Bob Jones, and
   * this is a different one" directly above a refusal about Carol, as one warning about one tap.
   * Two people merged into a single sentence on the surface whose whole job is to be believed is
   * worse than either warning being lost.
   */
  @Test
  public void thecompositionDoesNotMergeTwoContacts() {
    final Contact carol = new Contact("Carol", "Danvers", bobAddress.getName(),
        bobAddress.getDeviceId() + 17, false);
    final java.util.ArrayList<Contact> contacts =
        new java.util.ArrayList<>(victim.getContactList());
    contacts.add(carol);
    victim.setContactList(contacts);

    strip.selectContact(impostor);
    assertTrue("precondition: a shared-name warning about the impostor must be standing: "
            + banner(),
        banner().contains("a different one - not a replacement"));

    strip.rememberRefusedInviteForTest(carol, "That invite from Carol Danvers could not be used - "
        + "it does not verify, which means it was changed on the way here.");
    strip.selectContact(carol);

    assertTrue("Carol's refusal must be said: " + banner(),
        banner().contains("changed on the way here"));
    assertTrue("and the impostor's shared-name warning must not be composed into it - it is about "
            + "somebody else, and the lowering is address-scoped so it is still standing: "
            + banner(),
        !banner().contains("a different one - not a replacement"));
  }

  /**
   * The fold ending must take down the fold's sentence, not the one composed with it.
   *
   * <p>Composing put the shared-name text in front of the refusal, so the stored warning starts
   * with the shared-name warning's opening words — and {@code standingWarningIsAboutAsharedName}
   * tests exactly that prefix. So the lowering branch read a composed warning as its own and
   * cleared the whole thing, refusal included. A relayed message reaches it:
   * {@code setChosenContact} re-derives only the shared-name warning when the recipient changes.
   *
   * <p>That is the two-readers-disagree hazard the predicate's own javadoc names, arrived at by
   * composing. The fold ending means the fold is gone; it does not mean the invite was fine.
   */
  @Test
  public void endingTheFoldReducesTheComposedWarningRatherThanClearingIt() {
    strip.rememberRefusedInviteForTest(impostor, "That invite from Bob Jones could not be used - "
        + "it does not verify, which means it was changed on the way here.");
    strip.selectContact(impostor);
    assertTrue("precondition: both must be composed: " + banner(),
        banner().contains("a different one - not a replacement")
            && banner().contains("changed on the way here"));

    // The user resolves the fold by deleting the other row, so the names no longer collide.
    final java.util.ArrayList<Contact> remaining = new java.util.ArrayList<>();
    for (final Contact c : victim.getContactList()) {
      if (!String.valueOf(c.getSignalProtocolAddress())
          .equals(String.valueOf(bob.getSignalProtocolAddress()))) {
        remaining.add(c);
      }
    }
    victim.setContactList(remaining);

    // The recipient moves the way an arriving message moves it, not by a tap. That route re-derives
    // ONLY the shared-name warning - which is what makes this reachable: after a tap, the refusal
    // is re-raised by the writer that runs next, so the loss is invisible there.
    strip.moveRecipientTheWayAmessageDoesForTest(null);
    strip.moveRecipientTheWayAmessageDoesForTest(impostor);

    assertTrue("the fold is gone, so its sentence must be: " + banner(),
        !banner().contains("a different one - not a replacement"));
    assertTrue("but the invite really was changed in transit, and nothing about the fold ending "
            + "makes that untrue: " + banner(),
        banner().contains("changed on the way here"));
  }
}
