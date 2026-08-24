package com.amnesica.kryptey.inputmethod.latin.e2ee;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import com.amnesica.kryptey.inputmethod.signalprotocol.helper.StorageHelper;

import org.junit.Test;

/**
 * What the strip says when it first opens, when the user's storage cannot be decrypted.
 *
 * <p>It used to say "No contact chosen" - byte-identical to a healthy install that simply has no
 * contacts yet. That is the worst available reading of the situation. The identity key, every
 * session and every verified contact are still on disk under a key that no longer exists, and the
 * obvious thing to do with an apparently empty app is to re-invite everyone. Re-inviting replaces
 * every pin the user had already compared, so a key loss silently becomes a fresh
 * trust-on-first-use window for every contact at once - and whoever caused the key loss knows
 * exactly when that window opens.
 *
 * <p>The decision is separated from the view so it can be tested without an inflated IME. That is a
 * real limit and worth stating: this covers WHICH message is chosen, not that the view displays it.
 * The wiring is a single call at the one place the opening message is set.
 */
public class OpeningMessageTest {

  @Test
  public void unreadableStorageDoesNotLookLikeAnEmptyApp() {
    assertEquals(E2EEStripView.INFO_STORAGE_UNREADABLE,
        E2EEStripView.openingMessage(StorageHelper.StorageState.UNREADABLE));

    assertNotEquals("an unreadable store must not read as a fresh install",
        E2EEStripView.openingMessage(StorageHelper.StorageState.NONE),
        E2EEStripView.openingMessage(StorageHelper.StorageState.UNREADABLE));
  }

  @Test
  public void afreshInstallAndAhealthyAccountBothReadNormally() {
    assertEquals(E2EEStripView.INFO_NO_CONTACT_CHOSEN_TEXT,
        E2EEStripView.openingMessage(StorageHelper.StorageState.NONE));
    assertEquals(E2EEStripView.INFO_NO_CONTACT_CHOSEN_TEXT,
        E2EEStripView.openingMessage(StorageHelper.StorageState.READABLE));
  }

  /**
   * The warning has to say the one thing that stops the damage. Telling the user something is wrong
   * without telling them not to re-invite leaves them doing exactly the harmful thing.
   */
  @Test
  public void thewarningTellsTheUserNotToReInvite() {
    final String warning = E2EEStripView.openingMessage(StorageHelper.StorageState.UNREADABLE);

    assertTrue("the warning must mention re-inviting, which is the harmful response: " + warning,
        warning.toLowerCase().contains("re-invit"));
    assertTrue("and must say the existing data is still there, or the user has no reason to pause",
        warning.toLowerCase().contains("still stored"));
  }

  /** Every state must be handled - a new one must not silently fall through to the empty message. */
  @Test
  public void everyStorageStateIsHandled() {
    for (final StorageHelper.StorageState state : StorageHelper.StorageState.values()) {
      final String message = E2EEStripView.openingMessage(state);
      assertTrue("no message for " + state, message != null && !message.isEmpty());
    }
  }
}
