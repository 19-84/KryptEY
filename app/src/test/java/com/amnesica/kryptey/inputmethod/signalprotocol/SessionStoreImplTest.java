package com.amnesica.kryptey.inputmethod.signalprotocol;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.util.Log;

import com.amnesica.kryptey.inputmethod.signalprotocol.stores.SessionStoreImpl;

import org.junit.Test;
import org.signal.libsignal.protocol.NoSessionException;
import org.signal.libsignal.protocol.SignalProtocolAddress;
import org.signal.libsignal.protocol.state.SessionRecord;

import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.UUID;

public class SessionStoreImplTest {

  static final String TAG = SessionStoreImplTest.class.getSimpleName();

  @Test
  public void loadSessionTest() {
    Log.i(TAG, "------------ loadSessionTest: ------------");
    SessionStoreImpl sessionStore = new SessionStoreImpl();

    SessionRecord sessionRecord = new SessionRecord();
    SignalProtocolAddress signalProtocolAddress = new SignalProtocolAddress(UUID.randomUUID().toString(), com.amnesica.kryptey.inputmethod.signalprotocol.util.ProtocolAddresses.generateDeviceId());

    sessionStore.storeSession(signalProtocolAddress, sessionRecord);
    assertEquals(1, sessionStore.getSize());

    SessionRecord loadedSessionRecord = sessionStore.loadSession(signalProtocolAddress);
    assertEquals(sessionRecord.getSessionVersion(), loadedSessionRecord.getSessionVersion());
  }

  @Test
  public void loadExistingSessionsTest() throws NoSessionException {
    Log.i(TAG, "------------ loadExistingSessionsTest: ------------");
    SessionStoreImpl sessionStore = new SessionStoreImpl();

    SessionRecord sessionRecord = new SessionRecord();
    SignalProtocolAddress signalProtocolAddress = new SignalProtocolAddress(UUID.randomUUID().toString(), com.amnesica.kryptey.inputmethod.signalprotocol.util.ProtocolAddresses.generateDeviceId());
    SessionRecord sessionRecord2 = new SessionRecord();
    SignalProtocolAddress signalProtocolAddress2 = new SignalProtocolAddress(UUID.randomUUID().toString(), com.amnesica.kryptey.inputmethod.signalprotocol.util.ProtocolAddresses.generateDeviceId());

    sessionStore.storeSession(signalProtocolAddress, sessionRecord);
    sessionStore.storeSession(signalProtocolAddress2, sessionRecord2);
    assertEquals(2, sessionStore.getSize());

    List<SessionRecord> loadedSessionRecord = sessionStore.loadExistingSessions(Collections.singletonList(signalProtocolAddress));
    assertEquals(1, loadedSessionRecord.size());
  }

  @Test
  public void getSubDeviceSessionsTest() {
    // Device id is pinned below rather than generated: getSubDeviceSessions excludes id 1, and
    // ProtocolAddresses.generateDeviceId() draws from [1,127], so a random fixture failed roughly
    // one run in 127 - looking like a regression in an unrelated class.
    Log.i(TAG, "------------ getSubDeviceSessionsTest: ------------");
    SessionStoreImpl sessionStore = new SessionStoreImpl();

    SessionRecord sessionRecord = new SessionRecord();
    SignalProtocolAddress signalProtocolAddress = new SignalProtocolAddress(UUID.randomUUID().toString(), 42);
    SessionRecord sessionRecord2 = new SessionRecord();
    SignalProtocolAddress signalProtocolAddress2 = new SignalProtocolAddress(UUID.randomUUID().toString(), 42);

    sessionStore.storeSession(signalProtocolAddress, sessionRecord);
    sessionStore.storeSession(signalProtocolAddress2, sessionRecord2);
    assertEquals(2, sessionStore.getSize());

    List<Integer> deviceIds = sessionStore.getSubDeviceSessions(signalProtocolAddress.getName());
    assertEquals(1, deviceIds.size());

    // Sub-device grouping, and the device-id-1 filter, neither of which the fixture above reaches.
    //
    // The two addresses above have DIFFERENT random names, so getSubDeviceSessions matches on the
    // name alone and the filter never runs - deleting it left the whole suite green. And the
    // assertion that used to sit here compared the literal 42 against the literal 42 ten lines
    // above it.
    //
    // Device id 1 is the primary device: it is excluded because "sub-device" means the others.
    final String sharedName = UUID.randomUUID().toString();
    sessionStore.storeSession(new SignalProtocolAddress(sharedName, 1), new SessionRecord());
    sessionStore.storeSession(new SignalProtocolAddress(sharedName, 42), new SessionRecord());

    final List<Integer> subDevices = sessionStore.getSubDeviceSessions(sharedName);
    assertEquals("the primary device must not be listed as a sub-device: " + subDevices,
        List.of(42), subDevices);
  }

  @Test
  public void storeSessionTest() {
    Log.i(TAG, "------------ storeSessionTest: ------------");
    SessionStoreImpl sessionStore = new SessionStoreImpl();

    SessionRecord sessionRecord = new SessionRecord();
    SignalProtocolAddress signalProtocolAddress = new SignalProtocolAddress(UUID.randomUUID().toString(), com.amnesica.kryptey.inputmethod.signalprotocol.util.ProtocolAddresses.generateDeviceId());
    SessionRecord sessionRecord2 = new SessionRecord();
    SignalProtocolAddress signalProtocolAddress2 = new SignalProtocolAddress(UUID.randomUUID().toString(), com.amnesica.kryptey.inputmethod.signalprotocol.util.ProtocolAddresses.generateDeviceId());

    sessionStore.storeSession(signalProtocolAddress, sessionRecord);
    sessionStore.storeSession(signalProtocolAddress2, sessionRecord2);

    // store should delete old one and store new one instead (like map behaviour)
    SessionRecord sessionRecordUsed = sessionStore.loadSession(signalProtocolAddress);
    sessionStore.storeSession(signalProtocolAddress, sessionRecordUsed);

    assertEquals(2, sessionStore.getSize());
  }

  @Test
  public void containsSessionTest() {
    Log.i(TAG, "------------ containsSessionTest: ------------");
    SessionStoreImpl sessionStore = new SessionStoreImpl();

    SessionRecord sessionRecord = new SessionRecord();
    SignalProtocolAddress signalProtocolAddress = new SignalProtocolAddress(UUID.randomUUID().toString(), com.amnesica.kryptey.inputmethod.signalprotocol.util.ProtocolAddresses.generateDeviceId());
    SessionRecord sessionRecord2 = new SessionRecord();
    SignalProtocolAddress signalProtocolAddress2 = new SignalProtocolAddress(UUID.randomUUID().toString(), com.amnesica.kryptey.inputmethod.signalprotocol.util.ProtocolAddresses.generateDeviceId());

    sessionStore.storeSession(signalProtocolAddress, sessionRecord);
    sessionStore.storeSession(signalProtocolAddress2, sessionRecord2);

    boolean sessionOneLoaded = sessionStore.containsSession(signalProtocolAddress);
    boolean sessionTwoLoaded = sessionStore.containsSession(signalProtocolAddress2);
    assertTrue(sessionOneLoaded);
    assertTrue(sessionTwoLoaded);
  }

  @Test
  public void deleteSessionTest() {
    Log.i(TAG, "------------ deleteSessionTest: ------------");
    SessionStoreImpl sessionStore = new SessionStoreImpl();

    SessionRecord sessionRecord = new SessionRecord();
    SignalProtocolAddress signalProtocolAddress = new SignalProtocolAddress(UUID.randomUUID().toString(), com.amnesica.kryptey.inputmethod.signalprotocol.util.ProtocolAddresses.generateDeviceId());
    SessionRecord sessionRecord2 = new SessionRecord();
    SignalProtocolAddress signalProtocolAddress2 = new SignalProtocolAddress(UUID.randomUUID().toString(), com.amnesica.kryptey.inputmethod.signalprotocol.util.ProtocolAddresses.generateDeviceId());

    sessionStore.storeSession(signalProtocolAddress, sessionRecord);
    sessionStore.storeSession(signalProtocolAddress2, sessionRecord2);

    assertTrue(sessionStore.containsSession(signalProtocolAddress));
    sessionStore.deleteSession(signalProtocolAddress);
    assertFalse(sessionStore.containsSession(signalProtocolAddress));
  }

  @Test
  public void deleteAllSessionsTest() {
    Log.i(TAG, "------------ deleteAllSessionsTest: ------------");
    SessionStoreImpl sessionStore = new SessionStoreImpl();

    SessionRecord sessionRecord = new SessionRecord();
    SignalProtocolAddress signalProtocolAddress = new SignalProtocolAddress(UUID.randomUUID().toString(), com.amnesica.kryptey.inputmethod.signalprotocol.util.ProtocolAddresses.generateDeviceId());
    SessionRecord sessionRecord2 = new SessionRecord();
    SignalProtocolAddress signalProtocolAddress2 = new SignalProtocolAddress(UUID.randomUUID().toString(), com.amnesica.kryptey.inputmethod.signalprotocol.util.ProtocolAddresses.generateDeviceId());

    sessionStore.storeSession(signalProtocolAddress, sessionRecord);
    sessionStore.storeSession(signalProtocolAddress2, sessionRecord2);

    assertTrue(sessionStore.containsSession(signalProtocolAddress));
    assertTrue(sessionStore.containsSession(signalProtocolAddress2));
    sessionStore.deleteAllSessions(signalProtocolAddress.getName());
    assertFalse(sessionStore.containsSession(signalProtocolAddress));
    assertTrue(sessionStore.containsSession(signalProtocolAddress2));
  }

  /**
   * A missing address must fail the way the signature says, not with an unchecked throw.
   *
   * <p>{@code loadExistingSessions} called {@code .findFirst().get()} before its own null check, so
   * an address with no session raised {@code NoSuchElementException} from a method declaring
   * {@code NoSessionException}. Unreachable today - this is libsignal's multi-session entry point
   * and the app performs no group or multi-recipient send - but a store callback is a place where
   * an unchecked throw lands in a caller that catches only checked types, which is the crash class
   * this project names as its worst.
   */
  @Test
  public void loadingAsessionThatIsNotThereThrowsTheDeclaredType() {
    final SessionStoreImpl store = new SessionStoreImpl();
    final SignalProtocolAddress absent = new SignalProtocolAddress("nobody-here", 1);

    try {
      store.loadExistingSessions(java.util.Collections.singletonList(absent));
      org.junit.Assert.fail("a missing session must be refused, not returned");
    } catch (final NoSessionException expected) {
      assertTrue("the message should name the address it could not find: " + expected.getMessage(),
          expected.getMessage() != null && expected.getMessage().contains("nobody-here"));
    } catch (final RuntimeException unchecked) {
      org.junit.Assert.fail("an unchecked throw out of a store callback lands in callers that "
          + "catch only checked types: " + unchecked);
    }
  }
}
