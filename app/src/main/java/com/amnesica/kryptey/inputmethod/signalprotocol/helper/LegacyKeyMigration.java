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
        // No "is this already re-keyed?" test.
        //
        // There was one - skip any key containing the separator - and it was a hole. The separator
        // cannot appear in a name the WIRE accepts, but a 0.1.5 store was never held to that check,
        // and the chat log was keyed by the peer-supplied address name. So the messenger picked the
        // address name "bobName<SEP>5", which is byte-for-byte the rendered key of Bob at device 5,
        // and its own pre-upgrade messages were waved through as "already rendered" straight into
        // Bob's conversation. EncryptedKeyValueStore's javadoc had already written the rule this
        // broke: deciding "already converted" from the shape of the bytes is a guess.
        //
        // The marker is what answers that question, and it is the only thing that can: it is a
        // fact about the store, written by this app, not a value the messenger supplied. When it is
        // absent every key in the log is pre-upgrade by definition, whatever it looks like.
        final com.amnesica.kryptey.inputmethod.signalprotocol.chat.Contact owner =
            account.soleContactNamed(key);
        if (owner == null) {
          // Kept, not deleted.
          //
          // Deleting was chosen when an unattributed entry could still be matched by a bare-name
          // reader and handed to whichever row survived. No reader does that any more - belongsTo
          // compares the full rendered address and nothing produces a bare name to match - so an
          // un-re-keyed entry is inert: invisible to every contact including the attacker's. That
          // turned deletion from a safety measure into a destruction primitive, one ordinary invite
          // sent before the upgrade being enough to have a genuine conversation classed ambiguous
          // and erased with no prompt and no way back.
          Log.w(TAG, "Leaving a chat-log entry whose address name identifies no single contact");
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
        // Blanked, always - no attempt to identify the address from the name.
        //
        // Looking it up by contact row or by surviving pin was reachable: the messenger chooses its
        // own address name, so it plants a row (or gets a pin, which deletion deliberately keeps)
        // bearing the victim's address name at another device id. Nothing warns about that - the
        // display names differ and no row exists at that exact address - and once the genuine
        // contact is deleted the attacker's is the only thing left bearing the name, so the
        // migration would write the ATTACKER's address into the victim's retirement and suppress
        // the duplicate warning for it permanently.
        //
        // Moving the question from read time to load time did not take the messenger out of it; it
        // froze its answer. A bare name simply does not identify an address, and no moment exists
        // at which it does. Blanking costs a false alarm on a legitimate re-add of a contact
        // retired before the upgrade; the alternative is silence on an impersonation, and this
        // codebase has settled that trade in the same direction every time it has come up.
        entry[2] = "";
      }
    }
  }
}
