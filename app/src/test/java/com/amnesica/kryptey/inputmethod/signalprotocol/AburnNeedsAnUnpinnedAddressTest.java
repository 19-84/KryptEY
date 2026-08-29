package com.amnesica.kryptey.inputmethod.signalprotocol;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.amnesica.kryptey.inputmethod.signalprotocol.encoding.EnvelopeCodec;
import com.amnesica.kryptey.inputmethod.signalprotocol.util.ProtocolAddresses;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.signal.libsignal.protocol.SignalProtocolAddress;
import org.signal.libsignal.protocol.UntrustedIdentityException;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Map;
import java.util.Set;

/**
 * Whether a relay can burn Kyber base keys at an address whose key is already pinned.
 *
 * <p>The replay set is bounded by retiring the Kyber pre-key, so an attacker who can drive enough
 * distinct base keys through it retires the active record and kills every outstanding invite until
 * the next export heals it. That trade is measured and recorded — but it rests on each burn costing
 * the attacker a session establishment the app actually accepts, which at a pinned address it
 * should refuse.
 *
 * <p>Whether it does depends on the order libsignal calls the store back in: if
 * {@code markKyberPreKeyUsed} runs before {@code isTrustedIdentity}, a message that is about to be
 * refused has already spent a base key, and the burn becomes free at every pinned address. A review
 * round named this as the thing it could not read — the ordering lives in the Rust layer, not in
 * the Java surface — so it is measured here rather than argued.
 */
public class AburnNeedsAnUnpinnedAddressTest {

  private Account victim;
  private Account genuine;
  private Account impostor;
  private SignalProtocolAddress victimAddress;
  private SignalProtocolAddress genuineAddress;

  private static void activate(final Account account) {
    SignalProtocolMain.getInstance().setAccount(account);
  }

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

  @Before
  public void setUp() throws Exception {
    SignalProtocolMain.resetForTest();
    SignalProtocolMain.testIsRunning = true;

    SignalProtocolMain.initialize(null);
    victim = SignalProtocolMain.getInstance().getAccount();
    victim.setMessageLogLoader(ArrayList::new);
    victimAddress = ProtocolAddresses.of(victim.getSignalProtocolAddress().getName(),
        victim.getDeviceId());

    SignalProtocolMain.initialize(null);
    genuine = SignalProtocolMain.getInstance().getAccount();
    genuine.setMessageLogLoader(ArrayList::new);
    genuineAddress = ProtocolAddresses.of(genuine.getSignalProtocolAddress().getName(),
        genuine.getDeviceId());

    SignalProtocolMain.initialize(null);
    impostor = SignalProtocolMain.getInstance().getAccount();
    impostor.setMessageLogLoader(ArrayList::new);

    // The victim pins the genuine contact's key, the ordinary way.
    activate(genuine);
    final String genuineBundle = SignalProtocolMain.exportOwnKeyBundle();
    activate(victim);
    assertTrue("fixture: the victim must pin the genuine key",
        SignalProtocolMain.processPreKeyResponseMessage(
            EnvelopeCodec.fromWire(genuineBundle), genuineAddress));
  }

  @After
  public void tearDown() {
    SignalProtocolMain.resetForTest();
    SignalProtocolMain.testIsRunning = false;
  }

  /**
   * A first message from a different identity, labelled with a pinned address, must be refused —
   * and must not have spent a base key on the way to being refused.
   */
  @Test
  public void arefusedFirstMessageAtApinnedAddressDoesNotSpendAbaseKey() throws Exception {
    activate(victim);
    final String victimBundle = SignalProtocolMain.exportOwnKeyBundle();
    final int before = rememberedBaseKeys(victim);

    // The impostor builds a session to the victim and composes a first message, then relabels it
    // with the genuine contact's address - which is what a relay controls.
    activate(impostor);
    assertTrue(SignalProtocolMain.processPreKeyResponseMessage(
        EnvelopeCodec.fromWire(victimBundle), victimAddress));
    final MessageEnvelope forged = SignalProtocolMain.encryptMessage("not from them", victimAddress);
    assertNotNull(forged);
    final MessageEnvelope relabelled = new MessageEnvelope(forged.getCiphertextMessage(),
        forged.getCiphertextType(), genuineAddress.getName(), genuineAddress.getDeviceId());

    activate(victim);
    try {
      SignalProtocolMain.decryptMessage(relabelled, genuineAddress);
      fail("a first message carrying a different identity at a pinned address must be refused");
    } catch (final UntrustedIdentityException expected) {
      // The refusal itself is the contract; its message is empty here and that is libsignal's
      // business, not this app's. Asserting on it cost a run: the incidental assertion fired
      // before the one that matters and hid the answer.
    }

    assertEquals("the message was refused, and refusing it must not have cost a base key. If the "
            + "store is called back before the trust check, a relay burns the replay set for free "
            + "at every PINNED address - and the bound is enforced by retiring the Kyber pre-key, "
            + "so that kills every outstanding invite until the next export",
        before, rememberedBaseKeys(victim));
  }

  /** And an accepted first message does spend one, so the assertion above is not vacuous. */
  @Test
  public void anacceptedFirstMessageDoesSpendAbaseKey() throws Exception {
    activate(victim);
    final String victimBundle = SignalProtocolMain.exportOwnKeyBundle();
    final int before = rememberedBaseKeys(victim);

    activate(genuine);
    assertTrue(SignalProtocolMain.processPreKeyResponseMessage(
        EnvelopeCodec.fromWire(victimBundle), victimAddress));
    final MessageEnvelope hello = SignalProtocolMain.encryptMessage("hello", victimAddress);
    assertNotNull(hello);

    activate(victim);
    assertEquals("hello", SignalProtocolMain.decryptMessage(hello, genuineAddress));

    assertTrue("an accepted handshake must spend exactly the base key the replay guard remembers, "
            + "or the test above is measuring a store that never records anything",
        rememberedBaseKeys(victim) > before);
  }
}
