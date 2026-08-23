package com.amnesica.kryptey.inputmethod.signalprotocol.storage;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** A real (if simple) {@link KeyValueStore}, standing in for SharedPreferences on the JVM. */
final class InMemoryKeyValueStore implements KeyValueStore {

  private final Map<String, String> values = new HashMap<>();

  /** Set to have the next write throw, to simulate a process death mid-migration. */
  int failAfterWrites = -1;
  private int writes;

  /**
   * Keys whose writes are silently discarded — the value never lands, and no exception is raised.
   * Models a SharedPreferences commit() that fails on a full disk: the in-memory map keeps the new
   * value so the running process looks healthy, while the old bytes survive on disk.
   */
  final Set<String> silentlyDropWrites = new HashSet<>();

  @Override
  public String get(final String key) {
    return values.get(key);
  }

  @Override
  public void put(final String key, final String value) {
    if (failAfterWrites >= 0 && writes >= failAfterWrites) {
      throw new SimulatedCrash();
    }
    writes++;
    if (silentlyDropWrites.contains(key)) return; // write reported as success, value not stored
    values.put(key, value);
  }

  @Override
  public void remove(final String key) {
    values.remove(key);
  }

  @Override
  public boolean contains(final String key) {
    return values.containsKey(key);
  }

  @Override
  public Set<String> keys() {
    return new HashSet<>(values.keySet());
  }

  /**
   * Zeroes the write counter so {@code failAfterWrites} can be expressed relative to the operation
   * under test rather than to test setup. Without this, fixture writes are counted too and a crash
   * intended for mid-migration fires on the very first migration write instead.
   */
  void resetWriteCount() {
    writes = 0;
  }

  /** Raw view, for asserting on what actually sits on "disk". */
  Map<String, String> raw() {
    return values;
  }

  static final class SimulatedCrash extends RuntimeException {}
}
