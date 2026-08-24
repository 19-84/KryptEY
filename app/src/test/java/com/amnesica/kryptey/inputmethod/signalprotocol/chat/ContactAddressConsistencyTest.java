package com.amnesica.kryptey.inputmethod.signalprotocol.chat;

import static org.junit.Assert.assertEquals;

import com.amnesica.kryptey.inputmethod.signalprotocol.util.JsonUtil;
import com.amnesica.kryptey.inputmethod.signalprotocol.util.ProtocolAddresses;

import org.junit.Test;
import org.signal.libsignal.protocol.SignalProtocolAddress;

/**
 * A contact must never hold two disagreeing views of one address.
 *
 * <p>{@link Contact} carries the address three ways — the name, the device id, and the assembled
 * {@link SignalProtocolAddress} — and different parts of the app key off different ones: the
 * contact list matches on the first two, the identity and session stores on the third. When those
 * disagree, a contact can be found in one and be a stranger in the other.
 *
 * <p>This is not hypothetical. It shipped once: a raw peer-supplied device id was stored on the
 * contact while the address held the value folded into libsignal's range, so for any legacy peer the
 * two differed and the pin could not be found for a contact the list displayed happily.
 *
 * <p>No production code calls the setters today — they exist for Jackson — so these tests are here
 * to stop the next caller reintroducing the desync silently.
 */
public class ContactAddressConsistencyTest {

  private static void assertConsistent(final Contact c) {
    assertEquals("the address name and the assembled address disagree",
        c.getSignalProtocolAddressName(), c.getSignalProtocolAddress().getName());
    assertEquals("the device id and the assembled address disagree",
        c.getDeviceId(), c.getSignalProtocolAddress().getDeviceId());
  }

  @Test
  public void aFreshlyConstructedContactIsConsistent() {
    assertConsistent(new Contact("A", "B", "peer-uuid", 7, false));
  }

  /** Including for a legacy device id, which the address folds into range. */
  @Test
  public void constructionFoldsALegacyDeviceIdConsistently() {
    final Contact c = new Contact("A", "B", "peer-uuid", 7296, false);
    assertConsistent(c);
    assertEquals("the folded id must be the one stored, not the raw one",
        ProtocolAddresses.of("peer-uuid", 7296).getDeviceId(), c.getDeviceId());
  }

  @Test
  public void settingTheDeviceIdKeepsTheAddressInStep() {
    final Contact c = new Contact("A", "B", "peer-uuid", 7, false);
    c.setDeviceId(9);
    assertConsistent(c);
    assertEquals(9, c.getSignalProtocolAddress().getDeviceId());
  }

  @Test
  public void settingALegacyDeviceIdFoldsBothViewsTogether() {
    final Contact c = new Contact("A", "B", "peer-uuid", 7, false);
    c.setDeviceId(7296);
    assertConsistent(c);
  }

  @Test
  public void settingTheNameKeepsTheAddressInStep() {
    final Contact c = new Contact("A", "B", "peer-uuid", 7, false);
    c.setSignalProtocolAddressName("other-uuid");
    assertConsistent(c);
    assertEquals("other-uuid", c.getSignalProtocolAddress().getName());
  }

  @Test
  public void settingTheAddressKeepsTheScalarsInStep() {
    final Contact c = new Contact("A", "B", "peer-uuid", 7, false);
    c.setSignalProtocolAddress(new SignalProtocolAddress("third-uuid", 11));
    assertConsistent(c);
    assertEquals("third-uuid", c.getSignalProtocolAddressName());
    assertEquals(11, c.getDeviceId());
  }

  /** And the invariant must survive the round trip through storage. */
  @Test
  public void consistencySurvivesSerialization() throws Exception {
    final Contact original = new Contact("A", "B", "peer-uuid", 7296, false);
    final Contact reloaded = JsonUtil.fromJson(JsonUtil.toJson(original), Contact.class);
    assertConsistent(reloaded);
    assertEquals(original.getSignalProtocolAddress(), reloaded.getSignalProtocolAddress());
  }
}
