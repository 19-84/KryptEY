package com.amnesica.kryptey.inputmethod.signalprotocol.storage;

/**
 * Raised when encrypted storage cannot be read or written.
 *
 * <p>Deliberately checked. The pre-existing failure mode in this codebase was that a storage error
 * became {@code null}, and the {@code null} became an NPE thrown out of
 * {@code LatinIME.setInputView()} — i.e. the keyboard crashed every time it was raised. Callers are
 * forced to make a decision instead.
 */
public class StorageCryptoException extends Exception {

  public StorageCryptoException(final String message) {
    super(message);
  }

  public StorageCryptoException(final String message, final Throwable cause) {
    super(message, cause);
  }
}
