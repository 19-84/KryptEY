package com.amnesica.kryptey.inputmethod.signalprotocol;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.amnesica.kryptey.inputmethod.signalprotocol.chat.Contact;
import com.amnesica.kryptey.inputmethod.signalprotocol.exceptions.UnknownContactException;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;

/**
 * How the contact list finds the row it is about to replace.
 *
 * <p>{@code updateContactInContactList} is what persists a verification, so picking the wrong row
 * means marking the wrong contact verified — and, because it <em>replaces</em>, silently destroying
 * the row it overwrote. Two contacts can legitimately share a display name, and two can share an
 * address name while differing by device id, which is exactly what a legacy peer's folded id
 * produces.
 *
 * <p>The match is on address name <b>and</b> device id. Weakening that conjunction survived the
 * whole suite: no test had two contacts distinguishable only by device id, so the second condition
 * never had to carry anything. The method's own comment describes the bug that reappears without
 * it — matching by name alone took the wrong row, so verifying one contact overwrote the other.
 */
public class AccountContactListTest {

  private Account account;

  @Before
  public void setUp() {
    SignalProtocolMain.testIsRunning = true;
    SignalProtocolMain.initialize(null);
    account = SignalProtocolMain.getInstance().getAccount();
    account.setContactList(new ArrayList<>());
  }

  private Contact add(final String first, final String name, final int deviceId) {
    final Contact c = new Contact(first, "Peer", name, deviceId, false);
    final ArrayList<Contact> list = new ArrayList<>(account.getContactList());
    list.add(c);
    account.setContactList(list);
    return c;
  }

  /** The case the conjunction exists for: one address name, two devices. */
  @Test
  public void updatingOneDeviceDoesNotOverwriteTheOther() throws Exception {
    add("Alice", "peer-uuid", 11);
    add("Alice", "peer-uuid", 22);

    final Contact verifiedDevice = new Contact("Alice", "Peer", "peer-uuid", 22, true);
    account.updateContactInContactList(verifiedDevice);

    assertEquals("both rows must survive the update", 2, account.getContactList().size());
    assertFalse("device 11 must not have been marked verified",
        account.getContactList().get(0).isVerified());
    assertEquals(11, account.getContactList().get(0).getDeviceId());
    assertTrue("device 22 is the one that was verified",
        account.getContactList().get(1).isVerified());
  }

  /** And the mirror: updating the first device must not touch the second. */
  @Test
  public void updatingTheOtherDeviceIsAlsoScoped() throws Exception {
    add("Alice", "peer-uuid", 11);
    add("Alice", "peer-uuid", 22);

    account.updateContactInContactList(new Contact("Alice", "Peer", "peer-uuid", 11, true));

    assertTrue(account.getContactList().get(0).isVerified());
    assertFalse("the second device must be untouched",
        account.getContactList().get(1).isVerified());
  }

  /** Two different peers sharing a device id must also stay separate. */
  @Test
  public void updatingOnePeerDoesNotTouchAnotherWithTheSameDeviceId() throws Exception {
    add("Alice", "alice-uuid", 7);
    add("Bob", "bob-uuid", 7);

    account.updateContactInContactList(new Contact("Bob", "Peer", "bob-uuid", 7, true));

    assertFalse("Alice must be untouched", account.getContactList().get(0).isVerified());
    assertTrue(account.getContactList().get(1).isVerified());
  }

  @Test
  public void updatingAContactThatIsNotThereIsRejected() {
    add("Alice", "peer-uuid", 11);

    assertThrows(UnknownContactException.class, () -> account.updateContactInContactList(
        new Contact("Ghost", "Peer", "nobody-uuid", 3, true)));
  }

  /** The update must replace in place, not append - otherwise the list grows on every verify. */
  @Test
  public void updatingReplacesRatherThanAppending() throws Exception {
    add("Alice", "peer-uuid", 11);

    account.updateContactInContactList(new Contact("Alice", "Peer", "peer-uuid", 11, true));
    account.updateContactInContactList(new Contact("Alice", "Peer", "peer-uuid", 11, false));

    assertEquals("repeated updates must not grow the list", 1, account.getContactList().size());
    assertFalse(account.getContactList().get(0).isVerified());
  }
}
