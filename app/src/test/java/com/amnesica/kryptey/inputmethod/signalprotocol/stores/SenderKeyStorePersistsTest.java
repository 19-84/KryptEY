package com.amnesica.kryptey.inputmethod.signalprotocol.stores;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.amnesica.kryptey.inputmethod.signalprotocol.util.JsonUtil;

import org.junit.Test;
import org.signal.libsignal.protocol.SignalProtocolAddress;
import org.signal.libsignal.protocol.groups.GroupSessionBuilder;
import org.signal.libsignal.protocol.groups.state.SenderKeyRecord;

import java.util.UUID;

/**
 * A sender-key store with something in it can actually be written and read back.
 *
 * <p>It could not be. {@code SenderKeyRecord} is a handle onto native memory — no no-arg
 * constructor, no getters, no Jackson properties — so a map holding them serialized to an
 * {@code InvalidDefinitionException}. {@code JsonUtil.toJson} catches that and returns
 * {@code null}; {@code EncryptedKeyValueStore} refuses to seal a null; {@code StorageHelper} logs
 * it and carries on. The first sender key ever stored would therefore have stopped the
 * <em>whole</em> protocol store being written — sessions, pre-keys, identity — silently, with one
 * log line, on every save from then on.
 *
 * <p>Nothing noticed because the group-session API that populates this store is never called by
 * this app. That is what makes it a landmine rather than dead code: it arms itself the day group
 * messaging lands, and the symptom then has nothing to do with group messaging.
 *
 * <p>The store is populated through libsignal's real {@code GroupSessionBuilder} rather than a
 * hand-made record, because the point is what happens when the API is used for real.
 */
public class SenderKeyStorePersistsTest {

  private static final SignalProtocolAddress SENDER = new SignalProtocolAddress("peer", 3);

  @Test
  public void anonEmptySenderKeyStoreSurvivesAroundTrip() throws Exception {
    final SenderKeyStoreImpl store = new SenderKeyStoreImpl();
    final UUID distributionId = UUID.randomUUID();

    // The real group API, which is what would put something in here.
    new GroupSessionBuilder(store).create(SENDER, distributionId);
    final SenderKeyRecord stored = store.loadSenderKey(SENDER, distributionId);
    assertNotNull("precondition: the group API must have stored a record", stored);

    final String json = JsonUtil.toJson(store);
    assertNotNull("a non-empty sender-key store must serialize. Returning null here does not fail "
        + "loudly - it stops the entire protocol store being written, with one log line.", json);
    assertTrue("and it must actually contain the record", json.length() > 2);

    final SenderKeyStoreImpl restored = JsonUtil.fromJson(json, SenderKeyStoreImpl.class);
    assertNotNull("the store must read back", restored);
    final SenderKeyRecord back = restored.loadSenderKey(SENDER, distributionId);
    assertNotNull("the record must survive the round trip", back);
    assertArrayEquals("and be the same record", stored.serialize(), back.serialize());
  }
}
