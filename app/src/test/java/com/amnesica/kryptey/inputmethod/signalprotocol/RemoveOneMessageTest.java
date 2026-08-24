package com.amnesica.kryptey.inputmethod.signalprotocol;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.amnesica.kryptey.inputmethod.signalprotocol.chat.StorageMessage;

import org.junit.Before;
import org.junit.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code Account.removeUnencryptedMessage} matched directly, not through the send path.
 *
 * <p>Driving it through {@code encryptMessage} can only ever produce one shape: the refused message
 * is the last entry, so a rollback that ignored both the recipient and the timestamp and simply
 * dropped the last entry would still pass. Measured, that mutation survives the whole suite through
 * that route - and it is an equivalent mutant only for as long as nothing can be appended between
 * the record and the rollback, which is a property of today's single-threaded click handler rather
 * than of this method.
 *
 * <p>So the matching is tested here against lists the send path cannot currently build. That is the
 * point: this method's contract is "remove the entry to THIS recipient at THIS timestamp", and a
 * test that cannot distinguish it from "remove the last entry" is not testing the contract.
 */
public class RemoveOneMessageTest {

  private Account account;
  private static final String ME = "my-uuid";
  private static final String BOB = "bob-uuid";
  private static final String CAROL = "carol-uuid";

  private static StorageMessage sent(final String to, final long at, final String body) {
    return new StorageMessage(to, ME, to, Instant.ofEpochMilli(at), body);
  }

  private static StorageMessage received(final String from, final long at, final String body) {
    return new StorageMessage(from, from, ME, Instant.ofEpochMilli(at), body);
  }

  @Before
  public void setUp() {
    SignalProtocolMain.testIsRunning = true;
    SignalProtocolMain.initialize(null);
    account = SignalProtocolMain.getInstance().getAccount();

    final ArrayList<StorageMessage> messages = new ArrayList<>();
    messages.add(sent(BOB, 1000, "first to bob"));
    messages.add(received(BOB, 2000, "reply from bob"));
    messages.add(sent(CAROL, 3000, "to carol"));
    messages.add(sent(BOB, 4000, "the refused one"));
    messages.add(received(BOB, 5000, "arrived after"));
    account.setUnencryptedMessages(messages);
  }

  private List<String> bodies() {
    final List<String> out = new ArrayList<>();
    for (final StorageMessage message : account.getUnencryptedMessages()) {
      out.add(message.getUnencryptedMessage());
    }
    return out;
  }

  @Test
  public void itRemovesExactlyTheMatchingEntry() {
    assertTrue(account.removeUnencryptedMessage(BOB, Instant.ofEpochMilli(4000)));

    assertEquals("exactly one entry must go", 4, account.getUnencryptedMessages().size());
    assertFalse("the matching entry must be gone", bodies().contains("the refused one"));
    assertTrue("an earlier message to the same recipient must survive",
        bodies().contains("first to bob"));
    assertTrue("a LATER message must survive - dropping the last entry is not the contract",
        bodies().contains("arrived after"));
    assertTrue("and another recipient's message must survive", bodies().contains("to carol"));
  }

  /** A received message is not a candidate: its recipient is this account, not the peer. */
  @Test
  public void itNeverRemovesAmessageTheUserReceived() {
    assertFalse("a received message must not match on the peer's uuid",
        account.removeUnencryptedMessage(BOB, Instant.ofEpochMilli(5000)));
    assertEquals(5, account.getUnencryptedMessages().size());
    assertTrue(bodies().contains("arrived after"));
  }

  @Test
  public void itRefusesTheWrongRecipient() {
    assertFalse("the timestamp alone must not be enough",
        account.removeUnencryptedMessage(CAROL, Instant.ofEpochMilli(4000)));
    assertEquals(5, account.getUnencryptedMessages().size());
  }

  @Test
  public void itRefusesTheWrongTimestamp() {
    assertFalse("the recipient alone must not be enough",
        account.removeUnencryptedMessage(BOB, Instant.ofEpochMilli(9999)));
    assertEquals(5, account.getUnencryptedMessages().size());
  }

  /**
   * Two sends to one recipient in the same millisecond: the MOST RECENT must go.
   *
   * <p>This is the only case where the scan direction is observable, and it is exactly the case the
   * rollback exists for - the entry just appended is the one that was refused. Driving it through
   * the send path cannot test this: the refused message is always last there, so a forward scan
   * looks identical unless two sends collide in the same millisecond, which makes the mutation
   * survive or die by timing rather than by coverage. Measured, it did both across two runs.
   *
   * <p>{@code Instant} has nanosecond resolution but the envelope timestamp is milliseconds, so two
   * sends in one millisecond are not exotic - they are what a double tap produces.
   */
  @Test
  public void itRemovesTheMostRecentOfTwoEntriesAtTheSameInstant() {
    account.getUnencryptedMessages().add(sent(BOB, 4000, "the one just refused"));
    final int before = account.getUnencryptedMessages().size();

    assertTrue(account.removeUnencryptedMessage(BOB, Instant.ofEpochMilli(4000)));

    assertEquals("exactly one entry may be removed", before - 1,
        account.getUnencryptedMessages().size());
    assertFalse("the entry just appended is the refused one and must be the one removed",
        bodies().contains("the one just refused"));
    assertTrue("the earlier send at the same instant was really delivered and must survive",
        bodies().contains("the refused one"));
  }

  @Test
  public void nullArgumentsAreRefusedRatherThanThrowing() {
    assertFalse(account.removeUnencryptedMessage(null, Instant.ofEpochMilli(4000)));
    assertFalse(account.removeUnencryptedMessage(BOB, null));
    assertEquals(5, account.getUnencryptedMessages().size());
  }
}
