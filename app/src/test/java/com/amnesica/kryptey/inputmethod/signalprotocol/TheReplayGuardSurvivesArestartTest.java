package com.amnesica.kryptey.inputmethod.signalprotocol;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.amnesica.kryptey.inputmethod.signalprotocol.stores.KyberPreKeyStoreImpl;
import com.amnesica.kryptey.inputmethod.signalprotocol.util.JsonUtil;

import org.junit.Test;
import org.signal.libsignal.protocol.ecc.ECKeyPair;

/**
 * The Kyber replay set says it is persisted "so that a replay is still detected after the keyboard
 * process is restarted". Nothing checked that.
 *
 * <p>It is a {@code private final Map} annotated {@code @JsonProperty}, and a final field is
 * exactly the shape Jackson cannot always populate — so the claim was worth measuring rather than
 * reading. If it does not survive, the guard resets on every keyboard raise, which is not a
 * restart: {@code reloadAccount} runs on every {@code setInputView}, so a captured handshake could
 * be re-driven after any rotation, theme change, or putting the keyboard away and picking it up.
 *
 * <p>Asserted through the store's own refusal rather than by reading the map, because the refusal
 * is the behaviour and the map is how it happens to be implemented.
 */
public class TheReplayGuardSurvivesArestartTest {

  private static final int KYBER_ID = 7;

  @Test
  public void areplayIsStillRefusedAfterTheStoreIsReloaded() throws Exception {
    final KyberPreKeyStoreImpl before = new KyberPreKeyStoreImpl();
    final ECKeyPair baseKey = ECKeyPair.generate();

    // One incoming handshake: libsignal marks the base key used against this Kyber pre-key.
    before.markKyberPreKeyUsed(KYBER_ID, 1, baseKey.getPublicKey());

    // The same handshake again, in the same process, is refused. The control: without this the
    // test below could pass against a store that refuses nothing at all.
    try {
      before.markKyberPreKeyUsed(KYBER_ID, 1, baseKey.getPublicKey());
      fail("precondition: a replayed base key must be refused in the first place");
    } catch (final Exception expected) {
      assertTrue("the refusal must be the reused-base-key one: " + expected,
          expected.getClass().getSimpleName().contains("ReusedBaseKey"));
    }

    // The keyboard is put away and raised again: reloadAccount re-reads the whole protocol store
    // from JSON on every setInputView.
    final String json = JsonUtil.toJson(before);
    assertNotNull(json);
    final KyberPreKeyStoreImpl after = JsonUtil.fromJson(json, KyberPreKeyStoreImpl.class);
    assertNotNull("the store must round-trip at all", after);

    try {
      after.markKyberPreKeyUsed(KYBER_ID, 1, baseKey.getPublicKey());
      fail("the replay guard did not survive the reload. A captured first message can be re-driven "
          + "after any rotation, theme change, or putting the keyboard away - reloadAccount runs on "
          + "every setInputView, so 'after a restart' understates how often this happens. JSON was: "
          + json);
    } catch (final Exception expected) {
      assertTrue("and it must still be the reused-base-key refusal: " + expected,
          expected.getClass().getSimpleName().contains("ReusedBaseKey"));
    }
  }

  /** And a base key that was never seen is still accepted, so the guard is not refusing blindly. */
  @Test
  public void anunseenBaseKeyIsStillAcceptedAfterAreload() throws Exception {
    final KyberPreKeyStoreImpl before = new KyberPreKeyStoreImpl();
    before.markKyberPreKeyUsed(KYBER_ID, 1, ECKeyPair.generate().getPublicKey());

    final KyberPreKeyStoreImpl after =
        JsonUtil.fromJson(JsonUtil.toJson(before), KyberPreKeyStoreImpl.class);
    assertNotNull(after);

    // A different peer's first message, after the reload.
    after.markKyberPreKeyUsed(KYBER_ID, 1, ECKeyPair.generate().getPublicKey());
  }
}
