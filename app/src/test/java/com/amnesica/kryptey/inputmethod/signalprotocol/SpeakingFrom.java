package com.amnesica.kryptey.inputmethod.signalprotocol;

import com.amnesica.kryptey.inputmethod.signalprotocol.util.ProtocolAddresses;

import java.util.ArrayList;

/**
 * The same person - same identity key, same stores - speaking from an address they chose.
 *
 * <p>Until libsignal 0.91 a test could forge a sender by encrypting normally and then rewriting the
 * envelope's name and device id, because neither was authenticated. Now both parties' addresses are
 * bound into the MAC of every PreKey message - a session's opening messages, until the peer first
 * replies - whenever the names parse as Signal service IDs, which this app's do. So a relabelled
 * PreKey message is refused before the app ever sees it, and a relay, which cannot recompute the
 * MAC, loses most of that attack. Not all of it: the MAC binds the UUID's bytes and this app compares
 * name strings, so the sender's own UUID in upper case still passes (see
 * AciphertextAddThatPinsAknownKeyIsWarnedAboutTest, which drives exactly that). Ordinary messages on
 * an acknowledged session carry no addresses, so the binding says nothing about them.
 *
 * <p>It does not close it for the sender. Whoever runs the sending client chooses what it calls
 * itself, and the MAC then binds the address they chose. Nothing in the protocol ties an address to
 * an identity key - that is what the app's pinning, warnings and per-address records are for - so
 * the tests of those defences model this attacker. Rewriting the envelope after the fact would now
 * only test that libsignal refuses it.
 */
public final class SpeakingFrom {

  private SpeakingFrom() {
  }

  /** {@code who}, presenting itself as {@code (name, deviceId)}. Shares {@code who}'s stores. */
  public static Account address(final Account who, final String name, final int deviceId) {
    final Account at = new Account(name, deviceId,
        who.getSignalProtocolStore().getIdentityKeyPair(), who.getMetadataStore(),
        who.getSignalProtocolStore(), ProtocolAddresses.of(name, deviceId));
    at.setMessageLogLoader(ArrayList::new);
    return at;
  }
}
