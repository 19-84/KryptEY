package com.amnesica.kryptey.inputmethod.signalprotocol.chat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.time.Instant;

/**
 * Completeness of the two chat-model {@code equals} methods.
 *
 * <p>Neither is compared in production, but both are compared by tests - a contact list or a message
 * log that survives a storage round trip is asserted with {@code assertEquals}. A field the
 * comparison ignores is a field the round trip could silently drop with every test still green.
 * That gap was already found in {@code MessageEnvelope} and in the key bundle; these are the last
 * two, and a mutation sweep confirmed each conjunct was individually unexercised.
 */
public class ChatModelEqualsTest {

  private static final Instant WHEN = Instant.ofEpochSecond(1_700_000_000L);

  private static Contact contact() {
    return new Contact("Alice", "Smith", "peer-uuid", 7, false);
  }

  private static StorageMessage message() {
    return new StorageMessage("contact-uuid", "sender-uuid", "recipient-uuid", WHEN, "hello");
  }

  // ------------------------------------------------------------------ Contact

  @Test
  public void twoIdenticalContactsAreEqual() {
    assertEquals(contact(), contact());
    assertEquals(contact().hashCode(), contact().hashCode());
  }

  @Test
  public void aDifferentFirstNameIsNotEqual() {
    assertNotEquals(contact(), new Contact("Alicia", "Smith", "peer-uuid", 7, false));
  }

  @Test
  public void aDifferentLastNameIsNotEqual() {
    assertNotEquals(contact(), new Contact("Alice", "Smyth", "peer-uuid", 7, false));
  }

  /**
   * Note two conjuncts here are <b>equivalent mutants</b> and are not chased.
   *
   * <p>{@code Contact.equals} compares the address three ways - the name, the device id, and the
   * assembled {@code SignalProtocolAddress} - and the assembled one already contains the other two.
   * Dropping either scalar is therefore masked by it, and no test can distinguish the two forms.
   *
   * <p>That is only safe because the three are consistent by construction: the constructor builds
   * the address from the scalars and stores the folded device id back, and the setters keep all
   * three in step. If that invariant broke, these conjuncts would stop being redundant and start
   * disagreeing - which is why {@code ContactAddressConsistencyTest} exists and why this comment
   * points at it rather than at a mutation score.
   */
  @Test
  public void aDifferentAddressNameIsNotEqual() {
    assertNotEquals("two contacts at different addresses are different people",
        contact(), new Contact("Alice", "Smith", "other-uuid", 7, false));
  }

  @Test
  public void aDifferentDeviceIdIsNotEqual() {
    assertNotEquals(contact(), new Contact("Alice", "Smith", "peer-uuid", 9, false));
  }

  /**
   * The verified flag is deliberately NOT part of equality, and that is a fix rather than an
   * oversight.
   *
   * <p>It used to be included. Because the contact list is updated by finding an equal row and
   * replacing it, a caller that flipped {@code verified} on a copy and then asked the list to store
   * it could no longer find the row to replace - so verifying a contact silently did nothing. Trust
   * state is mutable; identity is not.
   */
  @Test
  public void theVerifiedFlagIsNotPartOfIdentity() {
    final Contact unverified = new Contact("Alice", "Smith", "peer-uuid", 7, false);
    final Contact verified = new Contact("Alice", "Smith", "peer-uuid", 7, true);

    assertEquals("verifying a contact must not make it a different contact", unverified, verified);
    assertEquals("and the hash must agree, or map lookups break",
        unverified.hashCode(), verified.hashCode());
  }

  // ----------------------------------------------------------- StorageMessage

  @Test
  public void twoIdenticalMessagesAreEqual() {
    assertEquals(message(), message());
    assertEquals(message().hashCode(), message().hashCode());
  }

  @Test
  public void everyMessageFieldParticipatesInEquality() {
    assertNotEquals("the contact it belongs to must count", message(),
        new StorageMessage("other-uuid", "sender-uuid", "recipient-uuid", WHEN, "hello"));
    assertNotEquals("the sender must count - it decides which side of the log it renders on",
        message(),
        new StorageMessage("contact-uuid", "other-uuid", "recipient-uuid", WHEN, "hello"));
    assertNotEquals("the recipient must count", message(),
        new StorageMessage("contact-uuid", "sender-uuid", "other-uuid", WHEN, "hello"));
    assertNotEquals("the timestamp must count", message(),
        new StorageMessage("contact-uuid", "sender-uuid", "recipient-uuid",
            WHEN.plusSeconds(1), "hello"));
    assertNotEquals("and the message body itself must count", message(),
        new StorageMessage("contact-uuid", "sender-uuid", "recipient-uuid", WHEN, "goodbye"));
  }

  @Test
  public void neitherIsEqualToNullOrAnotherType() {
    assertNotEquals(contact(), null);
    assertNotEquals(contact(), "not a contact");
    assertNotEquals(message(), null);
    assertNotEquals(message(), contact());
  }

  /** A contact built from a legacy device id still equals one built from the folded value. */
  @Test
  public void foldedAndRawDeviceIdsProduceTheSameContact() {
    final Contact fromLegacy = new Contact("Alice", "Smith", "peer-uuid", 7296, false);
    final Contact fromFolded = new Contact("Alice", "Smith", "peer-uuid",
        fromLegacy.getDeviceId(), false);

    assertTrue("the constructor folds, so both must land on one identity",
        fromLegacy.equals(fromFolded));
  }
}
