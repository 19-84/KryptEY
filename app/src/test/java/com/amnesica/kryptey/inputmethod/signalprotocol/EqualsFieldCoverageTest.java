package com.amnesica.kryptey.inputmethod.signalprotocol;

import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import com.amnesica.kryptey.inputmethod.signalprotocol.chat.Contact;
import com.amnesica.kryptey.inputmethod.signalprotocol.chat.StorageMessage;

import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Makes "every field participates in equals" a claim that stays true as fields are added.
 *
 * <p>{@code EqualsCompletenessTest} and its siblings hand-enumerate the fields they vary. Every
 * field is covered today, so those tests are correct - and they stop being correct the moment
 * someone adds a field and does not notice there is a list to extend. The claim is load-bearing:
 * the wire round-trip and substitution tests are explicitly "only as strong as the equals methods",
 * and the failure they exist to catch is a substituted key comparing equal.
 *
 * <p>This varies each declared field REFLECTIVELY and requires the objects to stop being equal. The
 * first version scanned the class file for the field's name instead, which cannot fail: a field's
 * name is in the constant pool because it is declared there. Adding a field deliberately absent
 * from {@code equals} left it green - the exact shape of vacuous test this suite has spent a long
 * time removing, written while removing them.
 */
public class EqualsFieldCoverageTest {

  /**
   * Fields deliberately excluded, with the reason, rather than silently skipped.
   *
   * <p>Each entry says why. An exclusion without a reason is how a field quietly stops being
   * compared, which is the failure this test exists to catch.
   */
  private static final List<String> DERIVED = Arrays.asList(
      // Derived from signalProtocolAddressName and deviceId, and resynced from them.
      "signalProtocolAddress",
      // Migration bookkeeping, not identity. It records that the legacy migration has already asked
      // who this entry belongs to; two messages that differ only in whether that question has been
      // put are the same message. Including it would also be a hazard: removeAllUnencryptedMessages
      // matches with equals, so an entry the migration touched would stop matching the copy taken
      // before it, and a deletion rollback compares those copies.
      "legacyKeyResolved",
      // Deliberately not part of contact identity. removeContact matches with equals, so a contact
      // whose badge changed between the lookup and the delete must still be removable; and a
      // contact is WHO someone is, not whether the user has compared their number yet. Verified
      // state is covered by the trust tests, which is where it belongs.
      "verified",
      // MessageEnvelope.equals DOES compare this - the exclusion is a limit of this test, which
      // cannot synthesise a differing PreKeyResponse. BundleEqualsCompletenessTest varies its
      // fields one at a time, including the substituted-key case that matters.
      "preKeyResponse");

  /** A value of the right type that differs from whatever is there. */
  private static Object differentValue(final Class<?> type, final Object current) {
    if (type == String.class) return "different-" + current;
    if (type == int.class || type == Integer.class) {
      return current == null ? 1 : ((Integer) current) + 1;
    }
    if (type == long.class || type == Long.class) {
      return current == null ? 1L : ((Long) current) + 1L;
    }
    if (type == boolean.class || type == Boolean.class) {
      return !Boolean.TRUE.equals(current);
    }
    if (type == Instant.class) {
      return current == null ? Instant.ofEpochMilli(1) : ((Instant) current).plusMillis(1);
    }
    if (type == byte[].class) return new byte[] {9, 9, 9};
    return null;   // unsupported type: reported, not skipped silently
  }

  private static void assertEveryFieldIsCompared(final Object a, final Object b) throws Exception {
    assertTrue("the two fixtures must start equal, or this proves nothing", a.equals(b));

    final List<String> notCompared = new ArrayList<>();
    final List<String> unsupported = new ArrayList<>();

    for (final Field field : a.getClass().getDeclaredFields()) {
      if (Modifier.isStatic(field.getModifiers()) || field.isSynthetic()) continue;
      if (DERIVED.contains(field.getName())) continue;

      field.setAccessible(true);
      final Object original = field.get(b);
      final Object altered = differentValue(field.getType(), original);
      if (altered == null) {
        unsupported.add(field.getName() + " (" + field.getType().getSimpleName() + ")");
        continue;
      }

      field.set(b, altered);
      if (a.equals(b)) notCompared.add(field.getName());
      field.set(b, original);
    }

    assertTrue(a.getClass().getSimpleName() + ".equals ignores " + notCompared
            + " - two objects differing only in that field compare equal, and the wire round-trip "
            + "tests are only as strong as this method", notCompared.isEmpty());
    assertTrue("this test cannot vary " + unsupported + " - extend differentValue rather than "
            + "leaving a field silently unchecked", unsupported.isEmpty());
  }

  @Test
  public void everyContactFieldParticipatesInEquals() throws Exception {
    assertEveryFieldIsCompared(
        new Contact("Bob", "Jones", "peer-uuid", 7, false),
        new Contact("Bob", "Jones", "peer-uuid", 7, false));
  }

  @Test
  public void everyStorageMessageFieldParticipatesInEquals() throws Exception {
    final Instant when = Instant.ofEpochMilli(1_700_000_000_000L);
    assertEveryFieldIsCompared(
        new StorageMessage("peer", "me", "peer", when, "hello"),
        new StorageMessage("peer", "me", "peer", when, "hello"));
  }

  @Test
  public void everyMessageEnvelopeFieldParticipatesInEquals() throws Exception {
    assertEveryFieldIsCompared(
        new MessageEnvelope(new byte[] {1, 2, 3}, 3, "peer-uuid", 7),
        new MessageEnvelope(new byte[] {1, 2, 3}, 3, "peer-uuid", 7));
  }
}
