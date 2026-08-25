package com.amnesica.kryptey.inputmethod.signalprotocol.helper;

import android.util.Log;

import com.amnesica.kryptey.inputmethod.signalprotocol.Account;

/**
 * Rewrites records keyed by a bare address name, once, at the first load after the upgrade.
 *
 * <p>Two records used to be filed under the address NAME alone: the chat log, and the address a
 * display name was retired from. A name is not an identity — it is public, it is in every envelope
 * the messenger relays, and the device id beside it is the sender's to choose — so both were
 * re-keyed onto the full address. The question that leaves is what to do with the records already
 * on disk, and the answer used to be a legacy arm on each reader: match a bare name too, but only
 * when exactly one contact bears it.
 *
 * <p>That answer was wrong in a way no ordering or comparison could fix. "Exactly one contact
 * bears this name" is a property of the contact list <em>at the moment it is asked</em>, and the
 * messenger moves the contact list. It introduces a second contact whose address name matches, so
 * the history is correctly withheld; then it replays a message until decryption fails, the user
 * follows the app's own advice to delete and re-invite, and the name is unambiguous again — with
 * the genuine contact gone and the attacker's row the one left to inherit the whole conversation,
 * and to delete it. Reading the gate before the prune instead only swaps which of two required
 * behaviours breaks: a delete genuinely cannot tell an impostor from the contact it is imitating,
 * because that is what ambiguous means.
 *
 * <p>So the question is asked once, here, at the only moment it has a sound answer: the first load
 * after the upgrade, when the contact list is still exactly what the pre-upgrade binary wrote and
 * before any new code has let the messenger touch it. Unambiguous entries are re-keyed. Ambiguous
 * ones are <b>deleted</b>, not left in place: unattributable plaintext that can later be handed to
 * whichever contact survives is worse than history that is gone, and orphaned plaintext with no
 * row to reach it from cannot be erased by the user either.
 *
 * <p>Gated on a sealed marker for the same reason the encrypted-store migration is: if a hostile
 * store could clear it, the migration would re-run against a contact list the messenger has since
 * edited, which is the whole problem again.
 */
public final class LegacyKeyMigration {

  private static final String TAG = "LegacyKeyMigration";

  private LegacyKeyMigration() {
  }

  public static void apply(final Account account) {
    if (account == null) return;
    final java.util.List<com.amnesica.kryptey.inputmethod.signalprotocol.chat.StorageMessage>
        messages = account.getUnencryptedMessages();
    if (messages != null) {
      final java.util.Iterator<
          com.amnesica.kryptey.inputmethod.signalprotocol.chat.StorageMessage> each =
          messages.iterator();
      while (each.hasNext()) {
        final com.amnesica.kryptey.inputmethod.signalprotocol.chat.StorageMessage message =
            each.next();
        final String key = message.getContactUUID();
        if (key == null) {
          each.remove();
          continue;
        }
        if (key.indexOf(com.amnesica.kryptey.inputmethod.signalprotocol.util.ProtocolAddresses
            .SEPARATOR) >= 0) {
          continue;   // already a rendered address
        }
        final com.amnesica.kryptey.inputmethod.signalprotocol.chat.Contact owner =
            account.soleContactNamed(key);
        if (owner == null) {
          Log.w(TAG, "Dropping a chat-log entry whose address name identifies no single contact");
          each.remove();
        } else {
          message.setContactUUID(com.amnesica.kryptey.inputmethod.signalprotocol.util
              .ProtocolAddresses.key(owner.getSignalProtocolAddress()));
        }
      }
    }

    final java.util.LinkedList<String[]> retired = account.getRetiredDisplayNames();
    if (retired != null) {
      for (final String[] entry : retired) {
        if (entry.length < 3 || entry[2] == null || entry[2].isEmpty()) continue;
        if (entry[2].indexOf(com.amnesica.kryptey.inputmethod.signalprotocol.util
            .ProtocolAddresses.SEPARATOR) >= 0) {
          continue;
        }
        // A contact row first, then the pin. A display name is retired when its contact is
        // DELETED, so the row is usually gone by definition - but deletion deliberately keeps the
        // pin, so the identity store still names the address. Without the second lookup every
        // pre-upgrade retirement would be unidentifiable and every legitimate re-add would warn.
        final com.amnesica.kryptey.inputmethod.signalprotocol.chat.Contact owner =
            account.soleContactNamed(entry[2]);
        org.signal.libsignal.protocol.SignalProtocolAddress address =
            owner == null ? null : owner.getSignalProtocolAddress();
        if (address == null && account.getSignalProtocolStore() != null) {
          address = account.getSignalProtocolStore().getIdentityKeyStore()
              .solePinnedAddressNamed(entry[2]);
        }
        // Still nothing means the entry can no longer say which address the name was retired FROM,
        // and an entry that cannot say that must not suppress the warning. Blanking leaves the name
        // recorded and the suppression off, which is the safe side: a false alarm on a legitimate
        // re-add, rather than silence on an impersonation.
        entry[2] = address == null ? ""
            : com.amnesica.kryptey.inputmethod.signalprotocol.util.ProtocolAddresses.key(address);
      }
    }

  }
}
