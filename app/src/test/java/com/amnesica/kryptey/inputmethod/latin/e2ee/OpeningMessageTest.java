package com.amnesica.kryptey.inputmethod.latin.e2ee;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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

  /**
   * Every state must produce a message, and the assertion says only that.
   *
   * <p>This was documented as "a new one must not silently fall through to the empty message",
   * which the assertion does not check - a fall-through returns a non-empty string and passes.
   * Checking it properly would mean enumerating what each state should say, which is what the tests
   * above already do for the three that exist. The claim is narrowed rather than the test widened.
   */
  @Test
  public void everyStorageStateProducesSomeMessage() {
    for (final StorageHelper.StorageState state : StorageHelper.StorageState.values()) {
      final String message = E2EEStripView.openingMessage(state);
      assertTrue("no message for " + state, message != null && !message.isEmpty());
    }
  }

  /**
   * The buttons are driven by the info TEXT, so a new message is also a decision about whether
   * encrypt and decrypt are usable - and the default for an unrecognised one is ENABLED.
   *
   * <p>Adding the unreadable-storage message re-enabled both buttons on an install whose account
   * cannot be decrypted, because it simply was not {@code INFO_NO_CONTACT_CHOSEN}. There is no
   * account to encrypt with in that state.
   */
  @Test
  public void themessagesThatMustLeaveTheButtonsDisabledAreBothRecognised() {
    assertTrue("the unreadable-storage message must be one the watcher disables on",
        E2EEStripView.disablesActionButtons(E2EEStripView.INFO_STORAGE_UNREADABLE));
    assertTrue("and so must the no-contact message",
        E2EEStripView.disablesActionButtons(E2EEStripView.INFO_NO_CONTACT_CHOSEN_TEXT));
    assertFalse("an ordinary informational message must leave them enabled",
        E2EEStripView.disablesActionButtons("Encrypted message detected"));
  }

  /**
   * Every message the clipboard listener can post must leave the buttons enabled - which is why
   * the listener has to stop before posting one at all when storage is unreadable.
   *
   * <p>The listener's three messages are ordinary informational text, so the watcher enables both
   * buttons for each of them. That is right in the normal case and wrong in the unreadable one,
   * where there is no account to encrypt with and the warning being overwritten is the part that
   * protects the user's pins. The predicate cannot express that - the fix is that the listener
   * returns early - so this pins the premise the fix rests on.
   */
  @Test
  public void theclipboardMessagesAllEnableTheButtons() {
    final String[] clipboardMessages = {
        "Keybundle detected: click on decrypt to save the content",
        "Encrypted message detected: click on decrypt to view message",
        "Encrypted update message detected: click on decrypt to view message",
    };

    for (final String message : clipboardMessages) {
      assertFalse("\"" + message + "\" enables both buttons, so posting it in the unreadable "
              + "state would re-enable them and wipe the warning - the listener must not reach it",
          E2EEStripView.disablesActionButtons(message));
    }
  }
}
