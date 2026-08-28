package com.amnesica.kryptey.inputmethod.signalprotocol.stores;

import android.util.Log;

import com.amnesica.kryptey.inputmethod.signalprotocol.SenderKey;
import com.fasterxml.jackson.annotation.JsonProperty;

import org.signal.libsignal.protocol.InvalidMessageException;
import org.signal.libsignal.protocol.SignalProtocolAddress;
import org.signal.libsignal.protocol.groups.state.SenderKeyRecord;
import org.signal.libsignal.protocol.groups.state.SenderKeyStore;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class SenderKeyStoreImpl implements SenderKeyStore {
  static final String TAG = SenderKeyStoreImpl.class.getSimpleName();

  /**
   * Serialized records, not {@code SenderKeyRecord} objects, for the same reason
   * {@code SessionStoreImpl} keeps bytes.
   *
   * <p>{@code SenderKeyRecord} is a handle onto native memory: no no-arg constructor, no getters,
   * no Jackson properties at all. A map holding them serializes to an
   * {@code InvalidDefinitionException}, which {@code JsonUtil.toJson} catches and turns into
   * {@code null}, which {@code EncryptedKeyValueStore} then refuses to seal - and
   * {@code StorageHelper} logs that and carries on. So the first sender key ever stored would have
   * stopped the WHOLE protocol store being written: sessions, pre-keys, identity, silently, with
   * one log line. Reading it back was equally impossible.
   *
   * <p>Nothing has ever hit it because the group-session API that populates this store is never
   * called. That makes it a landmine rather than dead code, and the fix is cheap enough now that
   * waiting for group messaging to arrive is not worth it.
   */
  @JsonProperty
  private final Map<SenderKey, byte[]> store = new HashMap<>();

  public SenderKeyStoreImpl() {
  }

  @Override
  public void storeSenderKey(SignalProtocolAddress sender, UUID distributionId, SenderKeyRecord record) {
    Log.d(TAG, "Storing SenderKeyRecord with address: " + sender + " and distributionId: " + distributionId + " and record: " + record);
    store.put(new SenderKey(sender.getName(), sender.getDeviceId(), distributionId.toString()),
        record.serialize());
  }

  @Override
  public SenderKeyRecord loadSenderKey(SignalProtocolAddress sender, UUID distributionId) {
    Log.d(TAG, "Loading SenderKeyRecord with address: " + sender + " and distributionId: " + distributionId);

    try {
      final byte[] serialized = store.get(
          new SenderKey(sender.getName(), sender.getDeviceId(), distributionId.toString()));

      if (serialized == null) {
        return null;
      } else {
        return new SenderKeyRecord(serialized);
      }
    } catch (InvalidMessageException e) {
      throw new StoredRecordUnreadableException(
          "a stored senderkey record could not be read back", e);
    }
  }
}
