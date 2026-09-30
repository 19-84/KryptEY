package com.amnesica.kryptey.inputmethod.signalprotocol;

import com.amnesica.kryptey.inputmethod.signalprotocol.util.ProtocolAddresses;

import java.util.ArrayList;

/**
 * The same person - same identity key, same stores - speaking from an address they chose.
 *
 * <p>Until libsignal 0.91 a test could forge a sender by encrypting normally and then rewriting the
 * envelope's name and device id, because neither was authenticated. Now a PreKey message - a
 * session's opening message, until the peer first replies - binds both parties' addresses into its
 * MAC, provided both names parse as Signal service IDs as the SENDER holds them. So a test that
 * relabels an honest sender's opening message now tests libsignal's refusal, not the app.
 *
 * <p>Binding is not a defence to lean on, in either direction. The sender chooses its own address
 * and the MAC binds whatever it chose. And a relay is barely slowed: it can relabel under a case
 * variant of the sender's UUID (the MAC binds the UUID's bytes, this app compares strings), or
 * rewrite the invite so the peer holds the victim under a name that does not parse, after which the
 * peer sends no addresses at all and any relabel is accepted. Both were measured on 0.103.0. What
 * protects the user is the app's pinning, warnings and per-address records, so the tests of those
 * defences model an attacker libsignal does not stop - this one.
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
