package com.amnesica.kryptey.inputmethod.latin.e2ee;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;

import com.amnesica.kryptey.inputmethod.signalprotocol.Account;
import com.amnesica.kryptey.inputmethod.signalprotocol.MessageEnvelope;
import com.amnesica.kryptey.inputmethod.signalprotocol.SignalProtocolMain;
import com.amnesica.kryptey.inputmethod.signalprotocol.chat.Contact;
import com.amnesica.kryptey.inputmethod.signalprotocol.encoding.EnvelopeCodec;
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
 * A refused key substitution must be reported as one, not as a failed invite.
 *
 * <p>{@code E2EEStrip.createSessionWithContact} has three arms. Two of them are a plain success and
 * a plain failure; the middle one asks {@code hasUnacceptedIdentityChange} and, when it is true,
 * says "Someone offered a different key for X ... compare the number with them by voice before
 * sending anything". The arm exists because the generic arm below it says "Could not set up a
 * session from that invite. <b>Ask your contact to send a fresh one.</b>" - which, after a refused
 * substitution at a pinned address, tells the user to invite the attacker to try again. The code's
 * own comment says the generic advice "would talk a user straight past a possible
 * man-in-the-middle".
 *
 * <p>The predicate is thoroughly tested: {@code IdentityChangeTest} has eleven assertions on
 * {@code hasUnacceptedIdentityChange}. Nothing tested that this arm consults it. Measured at
 * 32df444: deleting the whole {@code else if} arm, so a refused substitution falls through to the
 * generic "ask for a fresh invite" advice, left the suite at 854 tests and 0 failures.
 *
 * <p>This test drives the real path end to end - a second key bundle for an address that already
 * carries a pin - and asserts the message the user is actually shown.
 */
@RunWith(RobolectricTestRunner.class)
public class ImpersonationWarningWiringTest {

  private E2EEStrip strip;
  private Contact bob;
  private SignalProtocolAddress bobAddress;
  private MessageEnvelope attackerBundle;

  @Before
  public void setUp() throws Exception {
    SignalProtocolMain.resetForTest();
    SignalProtocolMain.testIsRunning = true;

    final Context context = RuntimeEnvironment.getApplication();
    final SharedPreferences preferences =
        context.getSharedPreferences("protocol", Context.MODE_PRIVATE);
    preferences.edit().clear().commit();

    // Bob, and the bundle the user pins.
    SignalProtocolMain.initialize(null);
    final Account bobAccount = SignalProtocolMain.getInstance().getAccount();
    bobAddress = ProtocolAddresses.of(bobAccount.getSignalProtocolAddress().getName(),
        bobAccount.getDeviceId());
    final String bobBundle = SignalProtocolMain.exportOwnKeyBundle();

    // Somebody else's bundle, which the messenger will present for Bob's address.
    SignalProtocolMain.initialize(null);
    final String forged = SignalProtocolMain.exportOwnKeyBundle();

    // The user.
    SignalProtocolMain.initialize(null);
    final Account user = SignalProtocolMain.getInstance().getAccount();
    assertTrue("precondition: the user pins Bob's real key",
        SignalProtocolMain.processPreKeyResponseMessage(EnvelopeCodec.fromWire(bobBundle),
            bobAddress));

    bob = new Contact("Bob", "Jones", bobAddress.getName(), bobAddress.getDeviceId(), false);
    final ArrayList<Contact> contacts = new ArrayList<>();
    contacts.add(bob);
    user.setContactList(contacts);

    attackerBundle = EnvelopeCodec.fromWire(forged);
    strip = new E2EEStrip(context);
    ShadowToast.reset();
  }

  @After
  public void tearDown() {
    SignalProtocolMain.resetForTest();
    SignalProtocolMain.testIsRunning = false;
  }

  /**
   * The substitution is refused - and the user is told that is what happened.
   *
   * <p>Kills the mutant that removes the {@code hasUnacceptedIdentityChange} arm.
   */
  @Test
  public void arefusedKeySubstitutionMustNotBeReportedAsAfailedInvite() {
    final boolean created = strip.createSessionWithContact(bob, attackerBundle, bobAddress);

    assertFalse("precondition: a second key for a pinned address must be refused", created);
    assertTrue("precondition: the refusal must have been recorded as a pending identity change",
        SignalProtocolMain.hasUnacceptedIdentityChange(bobAddress));

    final String shown = ShadowToast.getTextOfLatestToast();
    assertNotNull("the user must be told something", shown);
    assertTrue("a refused key substitution must be named as one - was: " + shown,
        shown.contains("offered a different key"));
    assertFalse("and must not be reported as an invite that merely failed, which tells the user "
            + "to ask for another one - and the sender of the substituted key is who would send "
            + "it: " + shown,
        shown.contains("Ask your contact to send a fresh one"));
  }

  /**
   * The control: an ordinary failure with no pending change still gets the ordinary advice, so the
   * test above pins the arm rather than the message.
   */
  @Test
  public void anordinaryFailureStillGetsTheOrdinaryAdvice() {
    final SignalProtocolAddress stranger = ProtocolAddresses.of("no-such-address", 1);
    final Contact unknown = new Contact("Nobody", "", stranger.getName(), stranger.getDeviceId(),
        false);

    final boolean created = strip.createSessionWithContact(unknown, null, stranger);

    assertFalse("precondition: nothing can be built from a null envelope", created);
    assertFalse("no key has been offered for this address, so nothing was substituted",
        SignalProtocolMain.hasUnacceptedIdentityChange(stranger));
    final String shown = ShadowToast.getTextOfLatestToast();
    assertNotNull(shown);
    assertTrue("an ordinary failure keeps the ordinary advice - was: " + shown,
        shown.contains("Could not set up a session"));
  }
}
