package com.amnesica.kryptey.inputmethod.signalprotocol;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.amnesica.kryptey.inputmethod.signalprotocol.encoding.EnvelopeCodec;
import com.amnesica.kryptey.inputmethod.signalprotocol.stores.KyberPreKeyStoreImpl;
import com.amnesica.kryptey.inputmethod.signalprotocol.stores.PreKeyMetadataStoreImpl;
import com.amnesica.kryptey.inputmethod.signalprotocol.stores.SignalProtocolStoreImpl;
import com.amnesica.kryptey.inputmethod.signalprotocol.util.KeyUtil;
import com.amnesica.kryptey.inputmethod.signalprotocol.util.ProtocolAddresses;

import org.junit.Before;
import org.junit.Test;
import org.signal.libsignal.protocol.InvalidKeyIdException;
import org.signal.libsignal.protocol.ReusedBaseKeyException;
import org.signal.libsignal.protocol.SignalProtocolAddress;
import org.signal.libsignal.protocol.ecc.ECKeyPair;
import org.signal.libsignal.protocol.state.KyberPreKeyRecord;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.Set;

/**
 * The replay guard is a map that only ever grew.
 *
 * <p>{@code KyberPreKeyStoreImpl.usedBaseKeys} records every base key ever seen against a Kyber
 * pre-key id, and {@code seen.add(encoded)} returning false is the whole of the replay refusal.
 * Nothing pruned the set belonging to the ACTIVE pre-key: {@code removeOldKyberPreKeys} only
 * reaches ids strictly below it, so the live set grows for the whole rotation window, is
 * {@code @JsonProperty} and so is re-serialised and re-encrypted into the Keystore box on every
 * save. Measured on the real path - export a bundle, establish a session, decrypt the first message
 * - 200 handshakes left 200 entries under one id, 47 bytes each in the persisted store.
 *
 * <p>What it is NOT: measured separately, a captured bundle replayed 300 times produced ONE entry,
 * because a consumed one-time pre-key is regenerated with fresh material and the 299 replays fail
 * their MAC - and a message that fails to decrypt never reaches {@code markKyberPreKeyUsed}. So a
 * peer cannot mint entries from one invite; each costs them a handshake the user's app accepted.
 * The growth is real and unbounded, and it is paced by the user, not by the wire.
 *
 * <p>The interesting half is the bound. Evicting the oldest entry is the obvious eviction policy
 * and it is the wrong one: a forgotten base key is a base key that can be replayed, which is the
 * one thing this map exists to deny. Retiring the RECORD is strictly stronger - the pre-key then
 * accepts nothing at all, so a forgotten key cannot be replayed against it - and the bundle path
 * already mints a fresh Kyber pre-key when the active one is missing, so it heals itself.
 */
public class KyberReplaySetStaysBoundedTest {

  /** Comfortably past the limit, and nothing like a number real correspondence produces. */
  private static final int SESSIONS = 300;

  private Account alice;
  private Account bob;
  private SignalProtocolAddress aliceAddress;
  private SignalProtocolAddress bobAddress;

  @Before
  public void setUp() {
    SignalProtocolMain.testIsRunning = true;
    SignalProtocolMain.initialize(null);
    alice = SignalProtocolMain.getInstance().getAccount();
    SignalProtocolMain.initialize(null);
    bob = SignalProtocolMain.getInstance().getAccount();
    aliceAddress =
        ProtocolAddresses.of(alice.getSignalProtocolAddress().getName(), alice.getDeviceId());
    bobAddress = ProtocolAddresses.of(bob.getSignalProtocolAddress().getName(), bob.getDeviceId());
  }

  private void activate(final Account account) {
    SignalProtocolMain.getInstance().setAccount(account);
  }

  /** The bound, read where it is declared, so the test cannot drift away from it. */
  private static int limit() {
    try {
      final Field f = KyberPreKeyStoreImpl.class.getDeclaredField("USED_BASE_KEY_LIMIT");
      f.setAccessible(true);
      return f.getInt(null);
    } catch (final Exception e) {
      throw new AssertionError(e);
    }
  }

  /** Every base key Bob's store is currently remembering, across all pre-key ids. */
  @SuppressWarnings("unchecked")
  private static int rememberedBaseKeys(final Account account) {
    try {
      final Object store = account.getSignalProtocolStore().getKyberPreKeyStore();
      final Field f = store.getClass().getDeclaredField("usedBaseKeys");
      f.setAccessible(true);
      int total = 0;
      for (final Set<String> s : ((Map<Integer, Set<String>>) f.get(store)).values()) {
        total += s.size();
      }
      return total;
    } catch (final Exception e) {
      throw new AssertionError(e);
    }
  }

  /**
   * One incoming session establishment, the whole way round: Bob publishes an invite, Alice builds
   * a session from it, and Bob decrypts her first message.
   */
  private String oneIncomingSession(final int i) throws Exception {
    activate(bob);
    final String bundle = SignalProtocolMain.exportOwnKeyBundle();

    activate(alice);
    assertTrue("the fixture must be able to build a session, iteration " + i,
        SignalProtocolMain.processPreKeyResponseMessage(
            EnvelopeCodec.fromWire(bundle), bobAddress));
    final MessageEnvelope toBob = SignalProtocolMain.encryptMessage("hello " + i, bobAddress);
    assertNotNull("the fixture must be able to send, iteration " + i, toBob);

    activate(bob);
    return SignalProtocolMain.decryptMessage(
        EnvelopeCodec.fromWire(EnvelopeCodec.toWire(toBob)), aliceAddress);
  }

  /**
   * The premise: an incoming handshake really does mint a permanent entry, so there is something
   * here to bound. Without this the bound below would pass just as well if nothing were recorded -
   * which is the failure mode that matters, because nothing recorded means no replay guard.
   */
  @Test
  public void everyIncomingSessionRemembersItsBaseKey() throws Exception {
    for (int i = 0; i < 10; i++) oneIncomingSession(i);

    assertEquals("ten incoming sessions must leave ten remembered base keys - if this is lower "
        + "the store is not recording what it refuses replays with", 10, rememberedBaseKeys(bob));
  }

  /** The finding: the replay set must not grow one permanent entry per handshake. */
  @Test
  public void theReplaySetDoesNotGrowWithoutBound() throws Exception {
    for (int i = 0; i < SESSIONS; i++) oneIncomingSession(i);

    assertTrue("the replay set grows one persisted entry per incoming handshake and nothing "
            + "prunes the active pre-key's set: " + SESSIONS + " sessions left "
            + rememberedBaseKeys(bob) + " entries",
        rememberedBaseKeys(bob) < SESSIONS);
    assertTrue("and it must be held to the limit the store declares: " + rememberedBaseKeys(bob)
            + " entries against a bound of " + limit(),
        rememberedBaseKeys(bob) <= limit() + 1);
  }

  /** And the bound must not have cost the app the ability to receive. */
  @Test
  public void everyMessageStillArrives() throws Exception {
    for (int i = 0; i < SESSIONS; i++) {
      assertEquals("crossing the bound must not lose a message - iteration " + i,
          "hello " + i, oneIncomingSession(i));
    }
  }

  /**
   * The bound must not be bought by forgetting a base key.
   *
   * <p>This is the assertion the whole design turns on. After the limit is crossed the entries are
   * gone, so if the record were still usable those base keys could be replayed. It is not: the
   * record goes with them, and a pre-key that cannot be loaded refuses every handshake, replayed or
   * fresh. An eviction policy that dropped the oldest entry and kept the record would fail here.
   */
  @Test
  public void crossingTheLimitRetiresTheRecordRatherThanForgettingABaseKey() throws Exception {
    final SignalProtocolStoreImpl store = new SignalProtocolStoreImpl(
        KeyUtil.generateIdentityKeyPair(), KeyUtil.generateRegistrationId());
    final KyberPreKeyRecord record =
        KeyUtil.generateAndStoreKyberPreKey(store, new PreKeyMetadataStoreImpl());
    final int id = record.getId();

    final ECKeyPair first = ECKeyPair.generate();
    store.getKyberPreKeyStore().markKyberPreKeyUsed(id, 1, first.getPublicKey());
    assertThrows("precondition: within the limit a replayed base key is refused outright",
        ReusedBaseKeyException.class,
        () -> store.getKyberPreKeyStore().markKyberPreKeyUsed(id, 1, first.getPublicKey()));

    for (int i = 1; i <= limit(); i++) {
      store.getKyberPreKeyStore().markKyberPreKeyUsed(id, 1, ECKeyPair.generate().getPublicKey());
    }

    assertFalse("the pre-key whose replay set was dropped must be gone with it - keeping the "
            + "record while forgetting the keys is exactly the replay window this map exists to "
            + "close", store.containsKyberPreKey(id));
    assertThrows("and no handshake may be derived from it any more, so nothing that was forgotten "
            + "can be replayed against it", InvalidKeyIdException.class,
        () -> store.loadKyberPreKey(id));
  }
}
