package com.amnesica.kryptey.inputmethod.signalprotocol;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.amnesica.kryptey.inputmethod.signalprotocol.chat.Contact;
import com.amnesica.kryptey.inputmethod.signalprotocol.encoding.EnvelopeCodec;
import com.amnesica.kryptey.inputmethod.signalprotocol.util.Base64;
import com.amnesica.kryptey.inputmethod.signalprotocol.util.JsonUtil;
import com.amnesica.kryptey.inputmethod.signalprotocol.util.ProtocolAddresses;

import org.junit.Test;
import org.signal.libsignal.protocol.SignalProtocolAddress;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Not a test of behaviour: writes the stores that {@code StoresFromLibsignal0865SurviveTest} opens.
 *
 * <p>The question that test answers is whether a user who upgrades keeps their conversations. That
 * can only be asked of state written by the version being upgraded FROM, so this is run once,
 * deliberately, with libsignal 0.86.5 pinned, and its output is committed. Regenerating it under the
 * current libsignal would make that test prove only that libsignal can read its own output.
 *
 * <p>What it records, all through the app's own entry points and its own JSON persistence:
 * <ul>
 *   <li>Alice and Bob with an established conversation, several rounds each way;</li>
 *   <li>one message from Alice that Bob decrypted out of order, so Bob's session holds a skipped
 *       message key for the earlier one, which is kept here undelivered;</li>
 *   <li>one message in flight each way, encrypted and never decrypted;</li>
 *   <li>Carol, whose invite Alice accepted and answered with a first message Carol never read - a
 *       session still pending when the upgrade lands;</li>
 *   <li>Dave, whose invite nobody has accepted yet.</li>
 * </ul>
 *
 * <p>To regenerate (only when the FROM version itself changes):
 * <pre>
 *   ./tools/build-in-docker :app:testDebugUnitTest --tests '*UpgradeFixtureGenerator*' \
 *       -Dkryptey.writeUpgradeFixtures=true
 * </pre>
 * with the libsignal version named in {@link #DIR} pinned in app/build.gradle.
 */
public class UpgradeFixtureGenerator {

  static final String DIR = "src/test/resources/fixtures/libsignal-0.86.5";

  private static SignalProtocolAddress addressOf(final Account a) {
    return ProtocolAddresses.of(a.getSignalProtocolAddress().getName(), a.getDeviceId());
  }

  private static void activate(final Account a) {
    SignalProtocolMain.getInstance().setAccount(a);
  }

  private static String send(final Account from, final Account to, final String text)
      throws Exception {
    activate(from);
    final MessageEnvelope envelope = SignalProtocolMain.encryptMessage(text, addressOf(to));
    assertNotNull("nothing was encrypted for " + text, envelope);
    return EnvelopeCodec.toWire(envelope);
  }

  private static String receive(final Account at, final Account from, final String wire)
      throws Exception {
    activate(at);
    return SignalProtocolMain.decryptMessage(EnvelopeCodec.fromWire(wire), addressOf(from));
  }

  private static void accept(final Account at, final Account inviter, final String inviteWire)
      throws Exception {
    activate(at);
    assertTrue("the invite did not build a session",
        SignalProtocolMain.processPreKeyResponseMessage(
            EnvelopeCodec.fromWire(inviteWire), addressOf(inviter)));
  }

  private static String invite(final Account from) throws Exception {
    activate(from);
    return SignalProtocolMain.exportOwnKeyBundle();
  }

  private static String safetyNumber(final Account at, final Account peer) {
    activate(at);
    return SignalProtocolMain.getFingerprint(new Contact("Peer", "Fixture",
        peer.getSignalProtocolAddress().getName(), peer.getDeviceId(), false))
        .getDisplayableFingerprint().getDisplayText();
  }

  private static Account fresh() {
    SignalProtocolMain.initialize(null);
    return SignalProtocolMain.getInstance().getAccount();
  }

  @Test
  public void writeUpgradeFixture() throws Exception {
    org.junit.Assume.assumeTrue("set -Dkryptey.writeUpgradeFixtures=true to regenerate",
        Boolean.getBoolean("kryptey.writeUpgradeFixtures"));

    SignalProtocolMain.testIsRunning = true;
    final Account alice = fresh();
    final Account bob = fresh();
    final Account carol = fresh();
    final Account dave = fresh();

    final Map<String, String> wire = new LinkedHashMap<>();
    final Map<String, String> expect = new LinkedHashMap<>();

    // Alice accepts Bob's invite; the conversation runs several rounds each way.
    accept(alice, bob, invite(bob));
    for (int round = 1; round <= 3; round++) {
      final String a2b = "alice to bob, round " + round;
      assertEquals(a2b, receive(bob, alice, send(alice, bob, a2b)));
      final String b2a = "bob to alice, round " + round;
      assertEquals(b2a, receive(alice, bob, send(bob, alice, b2a)));
    }

    // Out of order: Bob reads the second before the first, so the first's key is held as skipped.
    final String skippedWire = send(alice, bob, "sent first, read after the upgrade");
    assertEquals("sent second, read before the upgrade",
        receive(bob, alice, send(alice, bob, "sent second, read before the upgrade")));
    wire.put("alice-to-bob-skipped", skippedWire);
    expect.put("alice-to-bob-skipped", "sent first, read after the upgrade");

    // In flight each way at the moment of the upgrade.
    wire.put("alice-to-bob-inflight", send(alice, bob, "in flight from alice"));
    expect.put("alice-to-bob-inflight", "in flight from alice");
    wire.put("bob-to-alice-inflight", send(bob, alice, "in flight from bob"));
    expect.put("bob-to-alice-inflight", "in flight from bob");

    // Carol: invite accepted and answered, but Carol never read the answer.
    accept(alice, carol, invite(carol));
    wire.put("alice-to-carol-first", send(alice, carol, "first words to carol"));
    expect.put("alice-to-carol-first", "first words to carol");

    // Dave: an invite nobody has accepted yet.
    wire.put("dave-invite", invite(dave));

    expect.put("safety-alice-bob", safetyNumber(alice, bob));
    expect.put("safety-bob-alice", safetyNumber(bob, alice));
    assertEquals("both sides must read the same number",
        expect.get("safety-alice-bob"), expect.get("safety-bob-alice"));

    final File dir = new File(DIR);
    if (!dir.isDirectory() && !dir.mkdirs()) {
      throw new IOException("could not create " + dir.getAbsolutePath());
    }
    final Map<String, Account> people = new LinkedHashMap<>();
    people.put("alice", alice);
    people.put("bob", bob);
    people.put("carol", carol);
    people.put("dave", dave);
    for (final Map.Entry<String, Account> e : people.entrySet()) {
      final Account a = e.getValue();
      write(dir, e.getKey() + ".protocol-store.json", JsonUtil.toJson(a.getSignalProtocolStore()));
      write(dir, e.getKey() + ".metadata-store.json", JsonUtil.toJson(a.getMetadataStore()));
      write(dir, e.getKey() + ".protocol-address.json",
          JsonUtil.toJson(a.getSignalProtocolAddress()));
      write(dir, e.getKey() + ".name.txt", a.getName());
      write(dir, e.getKey() + ".device-id.txt", String.valueOf(a.getDeviceId()));
      write(dir, e.getKey() + ".identity-key-pair.b64", Base64.encodeBytesWithoutPadding(
          a.getSignalProtocolStore().getIdentityKeyPair().serialize()));
    }
    write(dir, "wire.json", JsonUtil.toJson(wire));
    write(dir, "expect.json", JsonUtil.toJson(expect));
    write(dir, "WRITTEN-BY.txt", "libsignal-android 0.86.5, by UpgradeFixtureGenerator\n");
  }

  private static void write(final File dir, final String name, final String content)
      throws IOException {
    try (FileWriter w = new FileWriter(new File(dir, name))) {
      w.write(content);
    }
  }
}
