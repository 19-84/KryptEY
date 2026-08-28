package com.amnesica.kryptey.inputmethod.signalprotocol;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Arrays;
import java.util.Objects;

public class PreKeyWithStatus {

  final byte[] serializedPreKeyRecord;
  boolean isUsed;

  /**
   * When this record was consumed, relative to the others - not a clock.
   *
   * <p>Retention keeps the most recently used records, and "recent" used to mean "high id". That
   * proxy is broken by the allocator, which hands out the LOWEST free id: a peer's first message
   * removes their id and the app regenerates it in place, so the next invite gets that low id back
   * and is then the first thing pruned. Measured: with fifty used records and id 3 recycled, the
   * invite handed id 3 was destroyed by the very next invite, while fifty older keys were kept. The
   * peer holding it could never be decrypted.
   *
   * <p>Zero on a record stored by an older build, which sorts it oldest - correct, since it was
   * consumed before this field existed.
   */
  long usedAt;

  @JsonCreator
  public PreKeyWithStatus(@JsonProperty("serializedPreKeyRecord") byte[] serializedPreKeyRecord,
                          @JsonProperty("isUsed") boolean isUsed,
                          @JsonProperty("usedAt") long usedAt) {
    this.serializedPreKeyRecord = serializedPreKeyRecord;
    this.isUsed = isUsed;
    this.usedAt = usedAt;
  }

  public PreKeyWithStatus(final byte[] serializedPreKeyRecord, final boolean isUsed) {
    this(serializedPreKeyRecord, isUsed, 0L);
  }

  public long getUsedAt() {
    return usedAt;
  }

  public byte[] getSerializedPreKeyRecord() {
    return serializedPreKeyRecord;
  }

  public boolean isUsed() {
    return isUsed;
  }

  public void setUsed(boolean used) {
    isUsed = used;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    PreKeyWithStatus that = (PreKeyWithStatus) o;
    return isUsed == that.isUsed && Arrays.equals(serializedPreKeyRecord, that.serializedPreKeyRecord);
  }

  @Override
  public int hashCode() {
    int result = Objects.hash(isUsed);
    result = 31 * result + Arrays.hashCode(serializedPreKeyRecord);
    return result;
  }

  @Override
  public String toString() {
    return "PreKeyWithStatus{" +
        "serializedPreKeyRecord=" + Arrays.toString(serializedPreKeyRecord) +
        ", isUsed=" + isUsed +
        '}';
  }
}
