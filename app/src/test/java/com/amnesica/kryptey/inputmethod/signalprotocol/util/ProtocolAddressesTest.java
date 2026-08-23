package com.amnesica.kryptey.inputmethod.signalprotocol.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.signal.libsignal.protocol.SignalProtocolAddress;

import java.util.HashSet;
import java.util.Set;

/**
 * libsignal 0.86 rejects device ids outside {@code [1, 127]}; 0.21 accepted anything. KryptEY 0.1.5
 * generated them with {@code new Random().nextInt(10000)}, so almost every existing install holds
 * an address the current library will not construct.
 *
 * <p>This class silently rewrites the address a user's sessions are keyed by, which makes it about
 * as safety-critical as code in this project gets. The properties below are the ones that decide
 * whether an upgrading user keeps their account or loses it.
 */
public class ProtocolAddressesTest {

  /** The boundary, established by probing libsignal directly rather than from documentation. */
  @Test
  public void libsignalAcceptsExactlyTheRangeWeClaim() {
    new SignalProtocolAddress("alice", ProtocolAddresses.MIN_DEVICE_ID);
    new SignalProtocolAddress("alice", ProtocolAddresses.MAX_DEVICE_ID);

    assertThrows("0 should be rejected by libsignal", IllegalArgumentException.class,
        () -> new SignalProtocolAddress("alice", ProtocolAddresses.MIN_DEVICE_ID - 1));
    assertThrows("128 should be rejected by libsignal", IllegalArgumentException.class,
        () -> new SignalProtocolAddress("alice", ProtocolAddresses.MAX_DEVICE_ID + 1));
  }

  @Test
  public void generatedDeviceIdsAreAlwaysAcceptable() {
    for (int i = 0; i < 10_000; i++) {
      final int id = ProtocolAddresses.generateDeviceId();
      assertTrue("generated an out-of-range device id: " + id,
          ProtocolAddresses.isValidDeviceId(id));
      // Must not merely be in range by our own definition - libsignal has to accept it.
      new SignalProtocolAddress("alice", id);
    }
  }

  @Test
  public void generationCoversTheRangeRatherThanCollapsingToOneValue() {
    final Set<Integer> seen = new HashSet<>();
    for (int i = 0; i < 10_000; i++) seen.add(ProtocolAddresses.generateDeviceId());
    // A constant or near-constant generator would put every device on one id.
    assertTrue("generator produced only " + seen.size() + " distinct ids", seen.size() > 100);
  }

  @Test
  public void validityBoundaries() {
    assertFalse(ProtocolAddresses.isValidDeviceId(0));
    assertTrue(ProtocolAddresses.isValidDeviceId(1));
    assertTrue(ProtocolAddresses.isValidDeviceId(127));
    assertFalse(ProtocolAddresses.isValidDeviceId(128));
    assertFalse(ProtocolAddresses.isValidDeviceId(-1));
    assertFalse(ProtocolAddresses.isValidDeviceId(9999));
  }

  /**
   * The single most important property. An already-valid id must pass through untouched — remapping
   * one would change the address of an account that was perfectly fine, destroying its sessions for
   * no reason.
   */
  @Test
  public void alreadyValidIdsAreNeverRewritten() {
    for (int id = ProtocolAddresses.MIN_DEVICE_ID; id <= ProtocolAddresses.MAX_DEVICE_ID; id++) {
      assertEquals("valid device id " + id + " was rewritten",
          id, ProtocolAddresses.sanitizeDeviceId(id));
    }
  }

  @Test
  public void everyLegacyIdMapsIntoTheValidRange() {
    // 0.1.5 generated with nextInt(10000); cover that whole space plus some pathological values.
    for (int id = 0; id < 10_000; id++) {
      final int mapped = ProtocolAddresses.sanitizeDeviceId(id);
      assertTrue("device id " + id + " mapped out of range: " + mapped,
          ProtocolAddresses.isValidDeviceId(mapped));
      new SignalProtocolAddress("alice", mapped);
    }
    for (final int id : new int[] {-1, Integer.MIN_VALUE, Integer.MAX_VALUE, 128, 255}) {
      final int mapped = ProtocolAddresses.sanitizeDeviceId(id);
      assertTrue("pathological id " + id + " mapped out of range: " + mapped,
          ProtocolAddresses.isValidDeviceId(mapped));
    }
  }

  /**
   * The remap has to be stable across runs and across call sites: the stored protocol address and
   * every {@code Contact} referring to it must land on the same value, or they stop matching.
   */
  @Test
  public void remappingIsDeterministic() {
    for (final int id : new int[] {0, 128, 7296, 9999, 4242}) {
      final int first = ProtocolAddresses.sanitizeDeviceId(id);
      for (int i = 0; i < 100; i++) {
        assertEquals("remap of " + id + " is not stable", first,
            ProtocolAddresses.sanitizeDeviceId(id));
      }
    }
  }

  @Test
  public void ofBuildsAnAddressForALegacyIdThatLibsignalWouldReject() {
    assertThrows(IllegalArgumentException.class,
        () -> new SignalProtocolAddress("2aecc0a1-67ef-42c5-b2db-60f0bfdf2f33", 7296));

    final SignalProtocolAddress address =
        ProtocolAddresses.of("2aecc0a1-67ef-42c5-b2db-60f0bfdf2f33", 7296);
    assertNotNull(address);
    assertEquals("2aecc0a1-67ef-42c5-b2db-60f0bfdf2f33", address.getName());
    assertTrue(ProtocolAddresses.isValidDeviceId(address.getDeviceId()));
  }

  @Test
  public void ofLeavesAValidAddressAlone() {
    final SignalProtocolAddress address = ProtocolAddresses.of("alice", 42);
    assertEquals("alice", address.getName());
    assertEquals(42, address.getDeviceId());
  }

  /**
   * Folding ~10000 values into 127 necessarily collides, so this documents the real consequence
   * rather than pretending it does not happen: collisions are only dangerous between two DIFFERENT
   * names, and the address name is unchanged, so distinct users never merge. Two of a single user's
   * own legacy devices could collide — KryptEY is single-device per install, so that is not
   * reachable today, but it is the assumption to re-check if multi-device is ever added.
   */
  @Test
  public void collisionsAreConfinedToASingleName() {
    final int a = ProtocolAddresses.sanitizeDeviceId(200);
    final int b = ProtocolAddresses.sanitizeDeviceId(200 + ProtocolAddresses.MAX_DEVICE_ID);
    assertEquals("expected these two legacy ids to collide", a, b);

    // Same device id, different names: still distinct addresses, so no user is ever merged.
    assertFalse(ProtocolAddresses.of("alice", 200).equals(ProtocolAddresses.of("bob", 200)));
  }
}
