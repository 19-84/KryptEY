package com.amnesica.kryptey.inputmethod.signalprotocol.chat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.amnesica.kryptey.inputmethod.signalprotocol.util.JsonUtil;

import org.junit.Test;

/**
 * A contact carries its address three ways, and they must not be able to disagree on disk.
 *
 * <p>{@code signalProtocolAddressName}, {@code deviceId} and the assembled
 * {@code signalProtocolAddress} are all serialized, and different parts of the app key off
 * different ones — the contact list matches on the scalar id, the identity and session stores on
 * the assembled address. The constructor's own comment records what that cost once: a raw legacy
 * id kept beside a folded address, so every 0.1.5 peer had a contact whose two halves pointed at
 * different places.
 *
 * <p>The {@code @JsonCreator} rebuilds the address from the name and the folded id and ignores the
 * stored one — but the field is annotated {@code @JsonProperty} too, so the question is whether
 * Jackson assigns it afterwards and overwrites what the constructor computed. If it does, a stored
 * contact whose nested address disagrees comes back with two different answers, and an attacker who
 * can write the store picks which part of the app sees which.
 *
 * <p>Measured rather than argued, because the answer depends on Jackson's ordering rather than on
 * anything visible in this file.
 */
public class AcontactCannotCarryTwoAddressesTest {

  /** A stored contact whose nested address names a different device than its scalar id. */
  private static final String DISAGREEING =
      "{\"firstName\":\"Bob\",\"lastName\":\"Jones\","
          + "\"signalProtocolAddressName\":\"bob-uuid\","
          + "\"deviceId\":5,"
          + "\"verified\":false,"
          + "\"signalProtocolAddress\":{\"name\":\"bob-uuid\",\"deviceId\":99}}";

  /** And one whose nested address names a different PERSON. */
  private static final String DISAGREEING_NAME =
      "{\"firstName\":\"Bob\",\"lastName\":\"Jones\","
          + "\"signalProtocolAddressName\":\"bob-uuid\","
          + "\"deviceId\":5,"
          + "\"verified\":false,"
          + "\"signalProtocolAddress\":{\"name\":\"attacker-uuid\",\"deviceId\":5}}";

  @Test
  public void adisagreeingDeviceIdCannotSurviveAload() throws Exception {
    final Contact loaded = JsonUtil.fromJson(DISAGREEING, Contact.class);
    assertNotNull("the fixture must parse, or this measures nothing", loaded);

    assertEquals("the two halves must agree after a load. The contact list matches on this scalar "
            + "and the identity and session stores on the assembled address, so a contact that "
            + "carries both is one where those two look at different places",
        loaded.getDeviceId(), loaded.getSignalProtocolAddress().getDeviceId());

    // WHICH one wins, recorded because it is not what the creator's comment suggests and it took a
    // measurement to find out. The creator rebuilds the address from the name and the folded id,
    // and then Jackson assigns the stored nested address afterwards through
    // setSignalProtocolAddress - which syncs the name and the scalar id FROM it. So the stored
    // address wins and the other two follow it. That is why they agree, and the agreeing is the
    // property that matters: whichever writer runs last leaves all three saying the same thing.
    assertEquals("the stored nested address is the one that survives, and the scalar follows it",
        99, loaded.getDeviceId());
  }

  @Test
  public void adisagreeingAddressNameCannotSurviveAload() throws Exception {
    final Contact loaded = JsonUtil.fromJson(DISAGREEING_NAME, Contact.class);
    assertNotNull(loaded);

    assertEquals("the name the row displays and the name its session and identity are looked up "
            + "under must be the same one",
        loaded.getSignalProtocolAddressName(), loaded.getSignalProtocolAddress().getName());
  }

  /** And an ordinary round trip is unchanged, so the check above is not measuring a broken parse. */
  @Test
  public void anordinaryContactRoundTrips() throws Exception {
    final Contact original = new Contact("Bob", "Jones", "bob-uuid", 5, false);
    final Contact loaded = JsonUtil.fromJson(JsonUtil.toJson(original), Contact.class);

    assertNotNull(loaded);
    assertEquals("bob-uuid", loaded.getSignalProtocolAddressName());
    assertEquals(original.getDeviceId(), loaded.getDeviceId());
    assertEquals(String.valueOf(original.getSignalProtocolAddress()),
        String.valueOf(loaded.getSignalProtocolAddress()));
    assertTrue("a legacy id must have been folded into libsignal's range",
        loaded.getDeviceId() >= 1 && loaded.getDeviceId() <= 127);
  }

  /**
   * A stored nested address carrying a legacy device id, which is the upgrading case.
   *
   * <p>The creator folds an out-of-range id into libsignal's {@code [1,127]}, and its comment
   * records why: the contact list matches on the scalar and the stores on the address, and a raw
   * legacy value left the two pointing at different places for every 0.1.5 peer.
   * {@code setSignalProtocolAddress} — which Jackson calls last, and which therefore decides —
   * assigns whatever it is handed without folding.
   *
   * <p>So this asks what a store written by a 0.1.5 binary actually does on load. The answer is not
   * obvious from either method alone, and an upgrading user is the whole reason the folding exists.
   */
  @Test
  public void alegacyDeviceIdInAstoredNestedAddressIsHandled() {
    final String legacy =
        "{\"firstName\":\"Bob\",\"lastName\":\"Jones\","
            + "\"signalProtocolAddressName\":\"bob-uuid\","
            + "\"deviceId\":7296,"
            + "\"verified\":false,"
            + "\"signalProtocolAddress\":{\"name\":\"bob-uuid\",\"deviceId\":7296}}";

    Contact loaded = null;
    Throwable thrown = null;
    try {
      loaded = JsonUtil.fromJson(legacy, Contact.class);
    } catch (final Throwable t) {
      thrown = t;
    }

    // Whichever it is, it must not be "a contact whose two halves disagree", and it must not be an
    // unchecked throw escaping into a caller that catches only IOException.
    // Measured: it loads, and the id is folded. Not by setSignalProtocolAddress, which assigns
    // whatever it is handed - by the custom deserializer for SignalProtocolAddress, which sanitises
    // the device id before the setter ever sees it. So the folding the creator does for the scalar
    // pair has a counterpart on the nested value, and the two arms cannot disagree about range
    // either. That was worth measuring rather than reading: the setter and the creator each look
    // like the deciding writer and neither is.
    //
    // Being refused would also have been safe, so the assertions cover both. What must not happen
    // is the third outcome - loading with an out-of-range id, after which every session and
    // identity lookup for that contact throws IllegalArgumentException out of a raw constructor,
    // on the clipboard path, which catches three checked types and not that one.
    if (thrown != null) {
      assertTrue("but only as a checked failure the callers already handle. An unchecked one "
              + "escapes decryptMessageInClipboard, which catches three checked types: " + thrown,
          thrown instanceof java.io.IOException);
      return;
    }
    assertNotNull(loaded);
    assertEquals("if it loads, the scalar and the address must agree",
        loaded.getDeviceId(), loaded.getSignalProtocolAddress().getDeviceId());
    assertTrue("and the id must be one libsignal will accept, or every session and identity lookup "
            + "for this contact throws IllegalArgumentException from the raw constructor: "
            + loaded.getDeviceId(),
        loaded.getDeviceId() >= 1 && loaded.getDeviceId() <= 127);
  }
}
