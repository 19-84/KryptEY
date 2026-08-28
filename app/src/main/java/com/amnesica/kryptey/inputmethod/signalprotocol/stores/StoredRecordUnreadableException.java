package com.amnesica.kryptey.inputmethod.signalprotocol.stores;

/**
 * A record in one of this app's own protocol stores could not be parsed back.
 *
 * <p>The stores previously answered this with {@code throw new AssertionError(e)} — libsignal's own
 * idiom, and it encodes an assumption this project has already disproved elsewhere: that bytes the
 * app wrote itself cannot come back corrupt. They can. The store is sealed per value and a single
 * flipped byte makes GCM refuse; that is the same reachable state the chat log's own unreadable
 * handling exists for, and it is reached without any key material or attacker cooperation.
 *
 * <p>What made the idiom dangerous here is not the assumption but the type. {@code AssertionError}
 * is an {@code Error}, and every guard on the paths that reach these stores catches
 * {@code RuntimeException} or {@code Exception} — this file's neighbours say so in as many words
 * about {@code OutOfMemoryError}: "an Error that neither this method's catch (RuntimeException) nor
 * the clipboard listener's catch (Exception) stops". These stores are called by libsignal from
 * inside {@code decrypt}, which runs from a click listener, so a corrupt record killed the input
 * method in whatever app the user was typing in — and the condition is persistent, so it was
 * crash-on-tap until reinstall.
 *
 * <p>Unchecked, because the call sites are libsignal callbacks whose signatures this app does not
 * control. Being a {@code RuntimeException} is the whole point: the existing guards catch it, and a
 * corrupt record becomes a decryption failure the user is told about rather than a keyboard that
 * disappears.
 */
public class StoredRecordUnreadableException extends RuntimeException {

  public StoredRecordUnreadableException(final String message, final Throwable cause) {
    super(message, cause);
  }
}
