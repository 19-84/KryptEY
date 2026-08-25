package com.amnesica.kryptey.inputmethod.latin.e2ee;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import com.amnesica.kryptey.inputmethod.signalprotocol.helper.StorageHelper;

import org.junit.Test;
import org.robolectric.RuntimeEnvironment;

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

  /**
   * The help must tell the user HOW to compare a safety number, not just that they should.
   *
   * <p>Every banner in this app says "by voice". The help screen - the one place a user goes to
   * learn the procedure - said only "compare the number with your chat partner\u0027s number", and a
   * user who follows that by pasting the number into the same chat has performed no check at all: a
   * messenger able to substitute keys is able to rewrite the numbers each side sees. The instruction
   * and the reason are both asserted, because an instruction without its reason is the first thing
   * to be dropped as clutter.
   */
  @Test
  public void thehelpTellsTheUserToCompareOutsideTheMessenger() {
    final String help = helpText();

    assertTrue("the help must name the channel: comparing by voice is what makes it a check",
        help.contains("by voice"));
    assertTrue("and must say not to send the number through the messenger being used",
        help.contains("not by sending it through the messenger"));
    assertTrue("and must give the reason, or the instruction reads as fussiness",
        help.contains("change the numbers you send each other"));
  }

  /**
   * And it must not claim the app can tell how an invite travelled.
   *
   * <p>The help now explains that handing an invite over out of band keeps the messenger from seeing
   * the first key. That is true and worth telling people. What must never appear beside it is any
   * suggestion that KryptEY knows which way was used - it cannot: the exported bundle is
   * byte-identical to the one the invite flow sends. This project already removed provenance as a
   * source of trust once, and the wording is where it would come back.
   */
  @Test
  public void thehelpDoesNotClaimToKnowHowAnInviteTravelled() {
    final String help = helpText();

    assertTrue("the out-of-band option must be explained", help.contains("in person"));
    assertTrue("and disclaimed in the same breath",
        help.contains("cannot tell which way you used"));
  }

  /** The help text as it ships, read from the resource rather than through Android. */
  private static String helpText() {
    for (final String candidate
        : new String[] {"src/main/res/values/strings.xml", "app/src/main/res/values/strings.xml"}) {
      final java.nio.file.Path path = java.nio.file.Paths.get(candidate);
      if (!java.nio.file.Files.isDirectory(path.getParent())) continue;
      try {
        return new String(java.nio.file.Files.readAllBytes(path),
            java.nio.charset.StandardCharsets.UTF_8);
      } catch (java.io.IOException e) {
        throw new IllegalStateException("could not read " + path, e);
      }
    }
    throw new IllegalStateException("could not locate strings.xml");
  }
}
