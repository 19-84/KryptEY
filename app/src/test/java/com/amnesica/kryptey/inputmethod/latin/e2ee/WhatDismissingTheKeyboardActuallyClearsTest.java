package com.amnesica.kryptey.inputmethod.latin.e2ee;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.view.ContextThemeWrapper;

import com.amnesica.kryptey.inputmethod.R;
import com.amnesica.kryptey.inputmethod.signalprotocol.Account;
import com.amnesica.kryptey.inputmethod.signalprotocol.MessageEnvelope;
import com.amnesica.kryptey.inputmethod.signalprotocol.SignalProtocolMain;
import com.amnesica.kryptey.inputmethod.signalprotocol.chat.Contact;
import com.amnesica.kryptey.inputmethod.signalprotocol.encoding.EnvelopeCodec;
import com.amnesica.kryptey.inputmethod.signalprotocol.helper.StorageHelper;
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
 * What dismissing the keyboard clears, and what it does not.
 *
 * <p>{@code LatinIME.onWindowHidden} says "Decrypted plaintext must not outlive the keyboard being
 * dismissed", and everything it then calls clears a <em>view</em>: the compose box, the messages
 * adapter, the fingerprint digits, the screen. Nothing reaches the model. The decrypted chat log
 * lives on {@code Account}, which hangs off a static singleton, and the only assignment of
 * {@code mAccount = null} anywhere is inside {@code resetForTest}.
 *
 * <p>So the sentence is true of what is on screen and false of what is in memory, and the two are
 * easy to read as one claim. This pins the difference rather than leaving it to a comment.
 *
 * <p>Not a defect the messenger can reach — it cannot read this process's heap, so this is outside
 * the stated adversary model. It is a posture statement: after reading one message, the whole
 * decrypted history is resident until the process dies or the account is replaced, and the account
 * is replaced only on a theme or ui-mode change.
 */
@RunWith(RobolectricTestRunner.class)
public class WhatDismissingTheKeyboardActuallyClearsTest {

  private E2EEStripView strip;
  private Account victim;
  private SignalProtocolAddress peerAddress;
  private Contact bob;

  @Before
  public void setUp() throws Exception {
    final Application app = RuntimeEnvironment.getApplication();
    SignalProtocolMain.resetForTest();
    SignalProtocolMain.testIsRunning = true;

    SignalProtocolMain.initialize(null);
    final Account peer = SignalProtocolMain.getInstance().getAccount();
    peerAddress = ProtocolAddresses.of(peer.getSignalProtocolAddress().getName(),
        peer.getDeviceId());
    final String peerBundle = SignalProtocolMain.exportOwnKeyBundle();

    SignalProtocolMain.initialize(null);
    victim = SignalProtocolMain.getInstance().getAccount();
    victim.setMessageLogLoader(ArrayList::new);
    assertTrue(SignalProtocolMain.processPreKeyResponseMessage(
        EnvelopeCodec.fromWire(peerBundle), peerAddress));

    bob = new Contact("Bob", "Jones", peerAddress.getName(), peerAddress.getDeviceId(), false);
    final ArrayList<Contact> contacts = new ArrayList<>();
    contacts.add(bob);
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

  @Test
  public void thedecryptedLogSurvivesTheKeyboardBeingDismissed() throws Exception {
    final MessageEnvelope sent = SignalProtocolMain.encryptMessage("the meeting is at nine",
        peerAddress);
    assertNotNull("fixture: the send must be logged, or there is no plaintext to ask about", sent);
    assertTrue("precondition: one message in either direction forces the deferred log to load",
        victim.messageLogIsLoaded());
    assertFalse("precondition: and the log holds the plaintext",
        victim.getUnencryptedMessages().isEmpty());

    strip.onKeyboardHidden();

    // The finding, asserted as the behaviour it is rather than as a failure: the sentence at
    // LatinIME.onWindowHidden is about the screen, and the log is not on the screen.
    assertTrue("dismissing the keyboard clears the compose box, the messages adapter, the "
            + "fingerprint digits and the screen - all views. The decrypted chat log lives on the "
            + "Account behind a static singleton and nothing on this path touches it, so it is "
            + "still loaded",
        victim.messageLogIsLoaded());
    assertFalse("and still holds the plaintext",
        victim.getUnencryptedMessages().isEmpty());
  }

  /**
   * The anti-vacuity twin: something DOES put the log back out of memory, so the assertion above
   * is about this path rather than about the log being unclearable.
   */
  @Test
  public void areloadPutsTheLogBackOutOfMemory() throws Exception {
    assertNotNull(SignalProtocolMain.encryptMessage("the meeting is at nine", peerAddress));
    assertTrue("precondition: loaded", victim.messageLogIsLoaded());

    // What a theme or ui-mode change does: the account is replaced by the stored one, whose log is
    // deferred again.
    SignalProtocolMain.initialize(null);
    final Account replacement = SignalProtocolMain.getInstance().getAccount();
    replacement.setMessageLogLoader(ArrayList::new);

    assertFalse("replacing the account defers the log again - which is the only thing in this app "
            + "that takes the decrypted history back out of memory, and it happens on a theme or "
            + "ui-mode change rather than on anything the user does deliberately",
        replacement.messageLogIsLoaded());
  }
}
