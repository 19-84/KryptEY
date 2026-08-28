package com.amnesica.kryptey.inputmethod.signalprotocol;

import com.amnesica.kryptey.inputmethod.signalprotocol.storage.TestStores;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.amnesica.kryptey.inputmethod.signalprotocol.chat.Contact;
import com.amnesica.kryptey.inputmethod.signalprotocol.encoding.EnvelopeCodec;
import com.amnesica.kryptey.inputmethod.signalprotocol.util.ProtocolAddresses;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.signal.libsignal.protocol.SignalProtocolAddress;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

/**
 * A contact's protocol address must not be written to logcat.
 *
 * <p>{@link NoSensitiveLoggingTest} says in its own javadoc that what was being logged, and what it
 * now stops, was "every contact's first name, last name, device id and protocol UUID". It stops the
 * names: it matches {@code getFirstName()}, {@code getLastName()} and {@code Contact.toString()} as
 * literal source text. It never matched the protocol address, and the address is logged
 * unconditionally - not behind {@code DebugFlags} - on the ordinary trust paths.
 *
 * <p>The address is the durable identifier for a correspondent: it is what keys are pinned against,
 * what the address tag beside each name is derived from, and it is stable for that contact's whole
 * life. An IME's logcat is readable over adb, is captured whole into bug reports, and on a rooted or
 * debuggable device is readable by other software. A line naming it says "this device talks to this
 * person", and on these particular paths it also says what the app thinks of their key.
 *
 * <p>Behavioural rather than a source grep, deliberately. The sibling test's pattern list is
 * defeated by any rearrangement that puts the value in a local first - which is already the shape of
 * some of this code - so this drives the real API and reads what the process actually printed.
 */
public class ContactIdentityLoggingTest {

  private SignalProtocolAddress peerAddress;
  private Contact contact;
  private String attackerBundle;
  private PrintStream realOut;
  private ByteArrayOutputStream captured;

  @Before
  public void setUp() throws Exception {
    SignalProtocolMain.testIsRunning = true;

    SignalProtocolMain.initialize(null);
    final Account peer = SignalProtocolMain.getInstance().getAccount();
    peerAddress = ProtocolAddresses.of(peer.getSignalProtocolAddress().getName(),
        peer.getDeviceId());
    final String genuineBundle = SignalProtocolMain.exportOwnKeyBundle();

    SignalProtocolMain.initialize(null);
    attackerBundle = SignalProtocolMain.exportOwnKeyBundle();

    SignalProtocolMain.initialize(null);
    final Account victim = SignalProtocolMain.getInstance().getAccount();
    assertTrue("precondition: the victim must pin the peer's genuine key",
        SignalProtocolMain.processPreKeyResponseMessage(
            EnvelopeCodec.fromWire(genuineBundle), peerAddress));

    contact = new Contact("Bob", "Jones", peerAddress.getName(), peerAddress.getDeviceId(), false);
    final ArrayList<Contact> contacts = new ArrayList<>();
    contacts.add(contact);
    victim.setContactList(contacts);
      // This fixture depends on writes landing; see TestStores.
    TestStores.writesLand();
}

  /**
   * Starts recording what the process writes out.
   *
   * <p>The fake {@code android.util.Log} on the unit-test classpath prints to stdout, so this is
   * what would have reached logcat on a device. Called from the test body rather than {@code
   * @Before} so that setup noise is never mistaken for the line under test.
   */
  private void startCapture() throws Exception {
    realOut = System.out;
    captured = new ByteArrayOutputStream();
    System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8.name()));
  }

  @After
  public void tearDown() {
    if (realOut != null) System.setOut(realOut);
  }

  private String logged() {
    System.out.flush();
    return new String(captured.toByteArray(), StandardCharsets.UTF_8);
  }

  /**
   * Telling the app a safety number did not match must not publish who it was about.
   *
   * <p>Emitted at WARN, so it survives any release-build filtering that drops DEBUG, and it pairs
   * the address with the fact that the user reported a substitution against it.
   */
  @Test
  public void rejectingAkeyDoesNotLogTheContactsAddress() throws Exception {
    startCapture();
    assertTrue("precondition: there must have been a pinned key to forget",
        SignalProtocolMain.rejectContactKey(contact));

    final String out = logged();
    assertTrue("precondition: the rejection must have logged something, or this test would pass"
        + " for the wrong reason:\n" + out, out.contains("Forgot the pinned key"));
    assertFalse("logcat must not name the contact whose key was rejected; it printed:\n" + out,
        out.contains(peerAddress.getName()));
  }

  /**
   * And the messenger must not be able to make the app write a contact's address out at will.
   *
   * <p>This one needs no user action at all. Dropping a forged bundle for an address the messenger
   * sees in every envelope reaches {@code clearVerificationFor}, which logs the address at WARN. So
   * the central adversary in this threat model can drive identifying lines into logcat for any
   * contact it chooses, on demand, by pasting - which is also the one payload it fully controls.
   */
  @Test
  public void asubstitutedKeyDoesNotLogTheContactsAddress() throws Exception {
    assertTrue("precondition: the contact must be verified for the clearing path to run",
        SignalProtocolMain.verifyContact(contact));

    startCapture();
    SignalProtocolMain.importOutOfBandKeyBundle(attackerBundle, peerAddress);

    final String out = logged();
    assertTrue("precondition: the substitution must have logged something, or this test would"
        + " pass for the wrong reason:\n" + out, out.contains("Clearing verification"));
    assertFalse("logcat must not name the contact a substitution was aimed at; it printed:\n" + out,
        out.contains(peerAddress.getName()));
  }
}
