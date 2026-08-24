package com.amnesica.kryptey.inputmethod.signalprotocol;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;

import com.amnesica.kryptey.inputmethod.signalprotocol.chat.Contact;
import com.amnesica.kryptey.inputmethod.signalprotocol.exceptions.DuplicateContactException;
import com.amnesica.kryptey.inputmethod.signalprotocol.exceptions.InvalidContactException;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;

/**
 * What {@code addContact} refuses.
 *
 * <p>Its validation is a four-way disjunction — missing first name, empty first name, missing
 * address name, device id zero — and mutation testing found the whole condition weakenable with the
 * suite green, because every existing test supplies four good arguments. Each arm has to be
 * exercised on its own to make it load-bearing.
 *
 * <p>The arms are not decorative. A contact with no address name or a zero device id is one the
 * identity store can never match, so it would display in the list and be unreachable — the same
 * class of split-identity bug as a contact whose device id disagreed with its address.
 */
public class AddContactValidationTest {

  private Account account;

  @Before
  public void setUp() {
    SignalProtocolMain.testIsRunning = true;
    SignalProtocolMain.initialize(null);
    account = SignalProtocolMain.getInstance().getAccount();
    account.setContactList(new ArrayList<>());
  }

  @Test
  public void aWellFormedContactIsAccepted() throws Exception {
    final Contact contact = SignalProtocolMain.addContact("Alice", "Smith", "peer-uuid", 7);

    assertNotNull(contact);
    assertEquals("Alice", contact.getFirstName());
    assertEquals(1, account.getContactList().size());
  }

  @Test
  public void aMissingFirstNameIsRefused() {
    assertThrows(InvalidContactException.class,
        () -> SignalProtocolMain.addContact(null, "Smith", "peer-uuid", 7));
  }

  @Test
  public void anEmptyFirstNameIsRefused() {
    assertThrows("an empty name is as unusable as a missing one",
        InvalidContactException.class,
        () -> SignalProtocolMain.addContact("", "Smith", "peer-uuid", 7));
  }

  /** A contact with no address name can never be matched against a pin. */
  @Test
  public void aMissingAddressNameIsRefused() {
    assertThrows(InvalidContactException.class,
        () -> SignalProtocolMain.addContact("Alice", "Smith", null, 7));
  }

  /** Device id 0 is outside libsignal's range, so such a contact is unreachable. */
  @Test
  public void aZeroDeviceIdIsRefused() {
    assertThrows(InvalidContactException.class,
        () -> SignalProtocolMain.addContact("Alice", "Smith", "peer-uuid", 0));
  }

  /** A missing last name is fine - only the first name is mandatory. */
  @Test
  public void aMissingLastNameIsAccepted() throws Exception {
    assertNotNull(SignalProtocolMain.addContact("Alice", null, "peer-uuid", 7));
  }

  @Test
  public void addingTheSameContactTwiceIsRefused() throws Exception {
    SignalProtocolMain.addContact("Alice", "Smith", "peer-uuid", 7);

    assertThrows(DuplicateContactException.class,
        () -> SignalProtocolMain.addContact("Alice", "Smith", "peer-uuid", 7));
    assertEquals("the list must not have grown", 1, account.getContactList().size());
  }
}
