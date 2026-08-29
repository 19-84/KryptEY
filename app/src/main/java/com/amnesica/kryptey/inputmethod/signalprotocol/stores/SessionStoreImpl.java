package com.amnesica.kryptey.inputmethod.signalprotocol.stores;

import com.amnesica.kryptey.inputmethod.signalprotocol.Session;
import com.fasterxml.jackson.annotation.JsonProperty;

import org.signal.libsignal.protocol.IdentityKey;
import org.signal.libsignal.protocol.InvalidMessageException;
import org.signal.libsignal.protocol.NoSessionException;
import org.signal.libsignal.protocol.SignalProtocolAddress;
import org.signal.libsignal.protocol.state.SessionRecord;
import org.signal.libsignal.protocol.state.SessionStore;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

public class SessionStoreImpl implements SessionStore {

  @JsonProperty
  private List<Session> sessions = new ArrayList<>();

  public SessionStoreImpl() {
  }

  @Override
  public synchronized SessionRecord loadSession(SignalProtocolAddress remoteAddress) {
    try {
      if (containsSession(remoteAddress)) {
        return new SessionRecord(sessions.stream()
            .filter(s -> s.getSignalProtocolAddress().equals(remoteAddress))
            .findFirst()
            .get().getSerializedSessionRecord());
      } else {
        return new SessionRecord();
      }
    } catch (InvalidMessageException e) {
      throw new StoredRecordUnreadableException(
          "a stored session record could not be read back", e);
    }
  }

  @Override
  public synchronized List<SessionRecord> loadExistingSessions(List<SignalProtocolAddress> addresses) throws NoSessionException {
    List<SessionRecord> resultSessions = new LinkedList<>();
    for (SignalProtocolAddress remoteAddress : addresses) {
      // Asked before the get(), not after. This called .get() first, so a missing address raised
      // NoSuchElementException - unchecked - from a method whose signature declares
      // NoSessionException, and the null check below could only ever fire for a stored Session
      // holding a null record, which nothing produces. Unreachable today: this is libsignal's
      // multi-session entry point and the app performs no group or multi-recipient send. Fixed
      // because it costs two statements, and because an unchecked throw out of a store callback is
      // the crash class this project names as its worst.
      final java.util.Optional<Session> stored = sessions.stream()
          .filter(s -> s.getSignalProtocolAddress().equals(remoteAddress))
          .findFirst();
      if (!stored.isPresent()) {
        throw new NoSessionException("no session for " + remoteAddress);
      }
      final byte[] serialized = stored.get().getSerializedSessionRecord();
      if (serialized == null) {
        throw new NoSessionException("no session record for " + remoteAddress);
      }
      try {
        resultSessions.add(new SessionRecord(serialized));
      } catch (InvalidMessageException e) {
        throw new StoredRecordUnreadableException(
          "a stored session record could not be read back", e);
      }
    }
    return resultSessions;
  }

  @Override
  public synchronized List<Integer> getSubDeviceSessions(String name) {
    List<Integer> deviceIds = new LinkedList<>();

    for (Session session : sessions) {
      SignalProtocolAddress address = session.getSignalProtocolAddress();
      if (address.getName().equals(name) &&
          address.getDeviceId() != 1) {
        deviceIds.add(address.getDeviceId());
      }
    }

    return deviceIds;
  }

  @Override
  public synchronized void storeSession(SignalProtocolAddress address, SessionRecord record) {
    deleteSession(address); // before changed implementation from map to list, items got overriden!
    sessions.add(new Session(address, record.serialize()));
  }

  @Override
  public synchronized boolean containsSession(SignalProtocolAddress address) {
    for (Session session : sessions) {
      if (session.getSignalProtocolAddress().getName().equals(address.getName()) &&
          session.getSignalProtocolAddress().getDeviceId() == address.getDeviceId()) return true;
    }
    return false;
  }

  @Override
  public synchronized void deleteSession(SignalProtocolAddress address) {
    List<Session> alteredSessionList = new ArrayList<>(sessions);
    for (Session session : sessions) {
      if (session.getSignalProtocolAddress().getName().equals(address.getName()) &&
          session.getSignalProtocolAddress().getDeviceId() == address.getDeviceId()) {
        alteredSessionList.remove(session);
      }
    }
    sessions = alteredSessionList;
  }

  @Override
  public synchronized void deleteAllSessions(String name) {
    List<Session> alteredSessionList = new ArrayList<>(sessions);
    for (Session session : sessions) {
      if (session.getSignalProtocolAddress().getName().equals(name)) {
        alteredSessionList.remove(session);
      }
    }
    sessions = alteredSessionList;
  }

  public int getSize() {
    return sessions.size();
  }

  // getPublicKeyFromSession was here, and is deleted rather than left unused.
  //
  // It returned the session's own copy of the remote identity, which is the one thing
  // SignalProtocolMain.createFingerprint says must never be consulted: "The session is not
  // consulted at all, and that is the point ... Reading the pin first but keeping the session as a
  // fallback does not fail closed." It had no caller anywhere in the app or the tests, so what it
  // amounted to was a ready-made building block for the failure VerifyContactTest exists to
  // prevent - a safety number derived from a key the messenger supplied - sitting in the store with
  // no test able to notice if something started calling it.
}
