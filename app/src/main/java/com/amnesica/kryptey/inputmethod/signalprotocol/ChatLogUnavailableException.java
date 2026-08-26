package com.amnesica.kryptey.inputmethod.signalprotocol;

/**
 * The stored chat log exists but could not be read, so it is refused rather than shown as empty.
 *
 * <p>Its own type rather than a bare {@code IllegalStateException} so the three places that must
 * survive it can say so precisely, and so a reader grepping for them finds them. Unchecked because
 * it can surface from any read of the log and a checked type would put a throws clause on most of
 * {@code Account}; the compiler is therefore not the thing that guarantees the catches, and
 * {@code ChatLogFailureDoesNotCrashTheKeyboardTest} is.
 *
 * <p><b>Why refusing rather than returning empty.</b> An account that believes the user has no
 * history gets that belief written to disk by the next ordinary save. A read failure must never
 * become a delete — the log is plaintext the user cannot recover from anywhere else.
 *
 * <p><b>Why this must not reach a click listener.</b> The keyboard is an input method: an uncaught
 * exception kills the IME process in whatever app the user happens to be typing in, and the
 * condition that causes this one is persistent, so it would be crash-on-tap until reinstall. The
 * codebase already holds this line elsewhere — {@code getClassFromSharedPreferences} catches
 * {@code RuntimeException} for exactly this reason. Callers degrade: show no history rather than
 * no keyboard.
 */
public class ChatLogUnavailableException extends RuntimeException {

  public ChatLogUnavailableException(final String message) {
    super(message);
  }

  public ChatLogUnavailableException(final String message, final Throwable cause) {
    super(message, cause);
  }
}
