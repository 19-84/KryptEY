package com.amnesica.kryptey.inputmethod.signalprotocol;

import com.amnesica.kryptey.inputmethod.signalprotocol.stores.PreKeyMetadataStoreImpl;
import com.amnesica.kryptey.inputmethod.signalprotocol.stores.SignalProtocolStoreImpl;

import org.signal.libsignal.protocol.SignalProtocolAddress;

import java.util.ArrayList;

public enum ProtocolIdentifier {
  UNIQUE_USER_ID(String.class),
  METADATA_STORE(PreKeyMetadataStoreImpl.class),
  PROTOCOL_STORE(SignalProtocolStoreImpl.class),
  PROTOCOL_ADDRESS(SignalProtocolAddress.class),
  DEVICE_ID(Integer.class),
  UNENCRYPTED_MESSAGES(ArrayList.class),
  CONTACTS(ArrayList.class),
  /** Per-install secret keying the contact display tags. See {@code Account.displayTagSecret}. */
  DISPLAY_TAG_SECRET(String.class),
  /**
   * Names of deleted contacts, so the duplicate warning survives a deletion.
   * See {@code Account.retiredDisplayNames}.
   */
  RETIRED_DISPLAY_NAMES(java.util.LinkedList.class),
  /**
   * Marks that the one-time key migration has run. See {@code StorageHelper.migrateLegacyKeys}.
   *
   * <p>An efficiency guard rather than a safety one, and specifically NOT the thing that makes
   * running twice safe - it is written in the account batch while the log is committed first, so a
   * re-keyed log without this marker is a state that ordering produces. What makes a second pass
   * harmless is that each log entry records whether the question has been put to it. This saves
   * rescanning the whole chat log on every {@code setInputView}.
   */
  KEY_SCHEMA_MIGRATED(String.class);

  public final Class className;

  ProtocolIdentifier(Class className) {
    this.className = className;
  }
}
