package com.amnesica.kryptey.inputmethod.signalprotocol;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import com.amnesica.kryptey.inputmethod.signalprotocol.util.ProtocolAddresses;

import org.junit.Test;
import org.signal.libsignal.protocol.SignalProtocolAddress;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Pins the addressing premise the whole Phase 4 trust design rests on.
 *
 * <p>The design says: a pending identity change is <em>always</em> hostile — a substitution or a
 * store rollback — never a legitimate peer. That is why confirming the number dismisses the offered
 * key rather than adopting it, why there is deliberately no one-tap "accept this new key" button,
 * and why contact removal does not surrender the pin.
 *
 * <p>All of that follows from one property of addressing, and nothing else: <b>a fresh install
 * mints a brand-new random address</b>, so a peer who reinstalls comes back as a different address
 * and can never collide with an existing pin. If that ever stops being true — say the address
 * becomes derived from the identity key fingerprint, which is a reasonable thing for someone to
 * want, since it binds the address to the key — then a reinstalled peer WOULD collide, every
 * reinstall would look like an attack, and refusing to verify would brick legitimate contacts
 * permanently.
 *
 * <p>That change would be silent: no other test would fail. This one exists so that it is not.
 */
public class AddressingPremiseTest {

  /**
   * The premise itself. If this fails, re-read the trust model before changing anything — the
   * dismissal in {@code verifyContactInContactList} and the absence of an accept path both become
   * wrong, not just suboptimal - a peer who legitimately returned to the same address would have
   * their real new key thrown away every time the user pressed verify.
   */
  @Test
  public void everyFreshInstallMintsADistinctAddress() {
    SignalProtocolMain.testIsRunning = true;

    final Set<String> names = new HashSet<>();
    for (int install = 0; install < 50; install++) {
      SignalProtocolMain.initialize(null);
      final Account account = SignalProtocolMain.getInstance().getAccount();
      final String name = account.getSignalProtocolAddress().getName();

      assertTrue("install " + install + " reused an address name: " + name, names.add(name));
    }
  }

  /**
   * And the name is random, not derived from anything stable about the account. A derived name
   * would repeat across installs for the same user, which is exactly the collision the design
   * assumes cannot happen.
   */
  @Test
  public void theAddressNameIsARandomUuidNotDerivedFromTheIdentity() {
    SignalProtocolMain.testIsRunning = true;
    SignalProtocolMain.initialize(null);
    final Account account = SignalProtocolMain.getInstance().getAccount();
    final String name = account.getSignalProtocolAddress().getName();

    // Parses as a UUID and is not a function of the identity key.
    UUID.fromString(name);

    final String identityKey = com.amnesica.kryptey.inputmethod.signalprotocol.util.Base64
        .encodeBytesWithoutPadding(
            account.getSignalProtocolStore().getIdentityKeyPair().getPublicKey().serialize());
    assertFalse("the address name must not be derived from the identity key",
        identityKey.contains(name) || name.contains(identityKey.substring(0, 8)));
  }

  /**
   * Device-id folding compresses ~10000 legacy values into 127, so collisions there are certain.
   * They are harmless only because the <em>name</em> disambiguates — two different peers can share
   * a folded device id and still be distinct addresses. If names ever stop being unique per install
   * that safety margin disappears too.
   */
  @Test
  public void foldedDeviceIdsCollideButNamesKeepAddressesDistinct() {
    final int a = ProtocolAddresses.sanitizeDeviceId(200);
    final int b = ProtocolAddresses.sanitizeDeviceId(200 + ProtocolAddresses.MAX_DEVICE_ID);
    assertEquals("expected these legacy ids to fold together", a, b);

    final SignalProtocolAddress first = ProtocolAddresses.of("peer-one", 200);
    final SignalProtocolAddress second =
        ProtocolAddresses.of("peer-two", 200 + ProtocolAddresses.MAX_DEVICE_ID);

    assertEquals("the folding really did collide", first.getDeviceId(), second.getDeviceId());
    assertNotEquals("but distinct peers must remain distinct addresses", first, second);
  }

  /**
   * The same peer folding onto one address from two different legacy device ids IS possible, and
   * would present as an identity change. KryptEY is single-device per install so it is unreachable
   * today; this records the assumption so multi-device work has to confront it.
   */
  @Test
  public void oneNameWithTwoLegacyDeviceIdsCanFoldOntoOneAddress() {
    final SignalProtocolAddress first = ProtocolAddresses.of("same-peer", 200);
    final SignalProtocolAddress second =
        ProtocolAddresses.of("same-peer", 200 + ProtocolAddresses.MAX_DEVICE_ID);

    assertEquals("two devices of one peer can fold onto a single address - if KryptEY ever "
            + "becomes multi-device, this is a legitimate identity change and the trust model "
            + "must stop assuming every pending change is hostile",
        first, second);
  }
}
