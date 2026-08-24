package com.amnesica.kryptey.inputmethod.latin.e2ee;

import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Build;
import android.util.Log;
import android.widget.Toast;

import com.amnesica.kryptey.inputmethod.latin.e2ee.util.HTMLHelper;
import com.amnesica.kryptey.inputmethod.signalprotocol.MessageEnvelope;
import com.amnesica.kryptey.inputmethod.signalprotocol.MessageType;
import com.amnesica.kryptey.inputmethod.signalprotocol.SignalProtocolMain;
import com.amnesica.kryptey.inputmethod.signalprotocol.chat.Contact;
import com.amnesica.kryptey.inputmethod.signalprotocol.chat.StorageMessage;
import com.amnesica.kryptey.inputmethod.signalprotocol.encoding.EncodeHelper;
import com.amnesica.kryptey.inputmethod.signalprotocol.encoding.Encoder;
import com.amnesica.kryptey.inputmethod.signalprotocol.encoding.EnvelopeCodec;
import com.amnesica.kryptey.inputmethod.signalprotocol.encoding.FairyTaleEncoder;
import com.amnesica.kryptey.inputmethod.signalprotocol.encoding.RawEncoder;
import com.amnesica.kryptey.inputmethod.signalprotocol.exceptions.DuplicateContactException;
import com.amnesica.kryptey.inputmethod.signalprotocol.exceptions.InvalidContactException;
import com.amnesica.kryptey.inputmethod.signalprotocol.exceptions.TooManyCharsException;
import com.amnesica.kryptey.inputmethod.signalprotocol.exceptions.UnknownContactException;
import com.amnesica.kryptey.inputmethod.signalprotocol.exceptions.UnknownMessageException;

import org.signal.libsignal.protocol.DuplicateMessageException;
import org.signal.libsignal.protocol.InvalidKeyException;
import org.signal.libsignal.protocol.InvalidKeyIdException;
import org.signal.libsignal.protocol.InvalidMessageException;
import org.signal.libsignal.protocol.InvalidVersionException;
import org.signal.libsignal.protocol.LegacyMessageException;
import org.signal.libsignal.protocol.NoSessionException;
import org.signal.libsignal.protocol.SignalProtocolAddress;
import org.signal.libsignal.protocol.UntrustedIdentityException;
import org.signal.libsignal.protocol.fingerprint.Fingerprint;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class E2EEStrip {
  private static final String TAG = E2EEStrip.class.getSimpleName();

  private final Context mContext;

  private final String INFO_CONTACT_ALREADY_EXISTS = "Contact already exists and was not saved";
  private final String INFO_CONTACT_INVALID = "Contact is invalid and was not saved";
  private final String INFO_SESSION_CREATION_FAILED = "Could not set up a session from that invite. Ask your contact to send a fresh one.";
  /**
   * Deliberately does NOT tell the user to just delete and re-add. That is the right remedy for a
   * reinstall - which cannot happen at a pinned address - and exactly the wrong one for an
   * impersonation attempt, which is the only thing it can be
   * this is - only the user can, by comparing the safety number out of band.
   */
  // Does not name a reinstall as the cause: a reinstall mints a fresh address and cannot collide
  // with an existing pin, so a changed key at a pinned address is never one. See the matching
  // comment on INFO_IDENTITY_CHANGED_EXISTING in E2EEStripView.
  private final String INFO_IDENTITY_CHANGED = "Someone offered a different key for %s. It was refused and is not in use. Open them in your contact list and compare the number with them by voice before sending anything.";

  // Constants, not per-instance state, and visible so a test can relate them to
  // MAX_DECODABLE_CHARS. The send side counts UTF-8 bytes of plaintext wire text; the receive side
  // counts characters of encoded text. Nothing else connects the two, and the FairyTale encoder
  // expands by about 1.57x, so they can drift apart silently - see SendAndReceiveLimitsTest.
  public static final int CHAR_THRESHOLD_RAW = 500;
  public static final int CHAR_THRESHOLD_FAIRYTALE = 500;

  /**
   * Separate, much larger limit for a key bundle.
   *
   * <p>A PQXDH bundle is irreducibly large: a Kyber-1024 public key is 1568 bytes, and the whole
   * base64 envelope measures 3352 characters (see {@code PreKeyBundleSizeTest}, which measures it
   * rather than estimating). High-entropy key material does not compress, so this cannot be tuned
   * down — it is the cost of the post-quantum handshake. Under the old 500-character limit no user
   * could send an invite at all.
   *
   * <p>4096 is chosen to clear the measured size with headroom while still fitting the per-message
   * limit of the messengers this keyboard is used with (Telegram 4096, WhatsApp and Signal far
   * higher). It does NOT fit SMS — but neither did the previous 500-character limit, so that is not
   * a regression.
   *
   * <p>Normal messages keep the smaller limit: they are user-typed and have no reason to be large.
   */
  public static final int CHAR_THRESHOLD_PRE_KEY_RESPONSE = 4096;

  public E2EEStrip(Context context) {
    mContext = context;
  }

  CharSequence encryptMessage(final String unencryptedMessage, final SignalProtocolAddress signalProtocolAddress, Encoder encoder) throws IOException {
    checkMessageLengthForEncodingMethod(unencryptedMessage, encoder, false);
    final MessageEnvelope messageEnvelope = SignalProtocolMain.encryptMessage(unencryptedMessage, signalProtocolAddress);
    if (messageEnvelope == null) return null;
    return encode(EnvelopeCodec.toWire(messageEnvelope), encoder);
  }

  CharSequence decryptMessage(final MessageEnvelope messageEnvelope, final Contact sender) {
    CharSequence decryptedMessage = null;
    try {
      // An attached bundle is processed by SignalProtocolMain.decrypt, which performs exactly this
      // check itself. Doing it here as well drove SessionBuilder.process twice for every message
      // carrying a bundle, and each run archives the previous session state - so a message with an
      // attached bundle burned two of libsignal's limited archived-state slots instead of one.
      decryptedMessage = SignalProtocolMain.decryptMessage(messageEnvelope, sender.getSignalProtocolAddress());
    } catch (InvalidMessageException | NoSessionException | InvalidContactException |
             UnknownMessageException |
             UntrustedIdentityException | DuplicateMessageException | InvalidVersionException |
             InvalidKeyIdException |
             LegacyMessageException | InvalidKeyException e) {
      Log.e(TAG, "Error: Decrypting message failed");
      e.printStackTrace();
    }
    return decryptedMessage;
  }

  public String encode(final String message, final Encoder encoder) throws IOException {
    String encodedMessage = null;
    if (encoder.equals(Encoder.FAIRYTALE))
      encodedMessage = FairyTaleEncoder.encode(message, mContext);
    if (encoder.equals(Encoder.RAW)) encodedMessage = RawEncoder.encode(message);
    return encodedMessage;
  }


  CharSequence getEncryptedMessageFromClipboard() {
    ClipboardManager clipboardManager =
        (ClipboardManager) mContext.getSystemService(Context.CLIPBOARD_SERVICE);

    if (clipboardManager != null) {
      try {
        // hint: listener for HTML text needed for using app with telegram
        if (clipboardManager.getPrimaryClipDescription().hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN) ||
            clipboardManager.getPrimaryClipDescription().hasMimeType(ClipDescription.MIMETYPE_TEXT_HTML)) {
          ClipData.Item item = clipboardManager.getPrimaryClip().getItemAt(0);
          return HTMLHelper.replaceHtmlCharacters(item.getText().toString());
        }
      } catch (Exception e) {
        e.printStackTrace();
        Log.e(TAG, "Error: Getting clipboard message!");
      }
    }
    return null;
  }

  void clearClipboard() {
    ClipboardManager clipboardManager =
        (ClipboardManager) mContext.getSystemService(Context.CLIPBOARD_SERVICE);

    if (clipboardManager != null) {
      try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
          clipboardManager.clearPrimaryClip();
          // debug Toast.makeText(mContext, "Clipboard deleted!", Toast.LENGTH_SHORT).show();
        } else {
          // support for older devices
          ClipData clipData = ClipData.newPlainText("", "");
          clipboardManager.setPrimaryClip(clipData);
          // debug Toast.makeText(mContext, "Clipboard deleted!", Toast.LENGTH_SHORT).show();
        }
      } catch (Exception e) {
        e.printStackTrace();
        Log.e(TAG, "Error: Clearing clipboard message!");
      }
    }
  }

  public ArrayList<Contact> getContacts() {
    return SignalProtocolMain.getContactList();
  }

  public Contact createAndAddContactToContacts(final CharSequence firstName, final CharSequence lastName, final String signalProtocolAddressName, final int deviceId) {
    Contact contact = null;
    try {
      contact = SignalProtocolMain.addContact(firstName, lastName, signalProtocolAddressName, deviceId);
    } catch (DuplicateContactException e) {
      Toast.makeText(mContext, INFO_CONTACT_ALREADY_EXISTS, Toast.LENGTH_SHORT).show();
      e.printStackTrace();
    } catch (InvalidContactException e) {
      Toast.makeText(mContext, INFO_CONTACT_INVALID, Toast.LENGTH_SHORT).show();
      e.printStackTrace();
    }
    return contact;
  }

  public boolean createSessionWithContact(Contact chosenContact, MessageEnvelope messageEnvelope, SignalProtocolAddress recipientProtocolAddress) {
    boolean successful = SignalProtocolMain.processPreKeyResponseMessage(messageEnvelope, recipientProtocolAddress);
    if (successful) {
      // Via displayLabelFor, like every other surface. Building the label by hand here is how this
      // one ended up without the bidi handling every other site had.
      Toast.makeText(mContext, "Session with " + SignalProtocolMain.displayLabelFor(chosenContact)
          + " created", Toast.LENGTH_SHORT).show();
    } else if (SignalProtocolMain.hasUnacceptedIdentityChange(recipientProtocolAddress)) {
      // Distinguish this from a generic failure. A changed safety number means the contact
      // being impersonated - a reinstall arrives at a fresh address and cannot land here - and
      // the generic "delete and re-invite"
      // advice would talk a user straight past a possible man-in-the-middle.
      Toast.makeText(mContext,
          String.format(INFO_IDENTITY_CHANGED, SignalProtocolMain.displayLabelFor(chosenContact)),
          Toast.LENGTH_LONG).show();
    } else {
      Toast.makeText(mContext, INFO_SESSION_CREATION_FAILED, Toast.LENGTH_SHORT).show();
    }
    return successful;
  }

  public String getPreKeyResponseMessage() throws IOException {
    final MessageEnvelope messageEnvelope = SignalProtocolMain.getPreKeyResponseMessage();
    if (messageEnvelope == null) return null;
    return EnvelopeCodec.toWire(messageEnvelope);
  }

  public Object getContactFromEnvelope(MessageEnvelope messageEnvelope) {
    return SignalProtocolMain.extractContactFromMessageEnvelope(messageEnvelope);
  }

  public MessageType getMessageType(MessageEnvelope messageEnvelope) {
    return SignalProtocolMain.getMessageType(messageEnvelope);
  }

  public void removeContact(Contact contact) {
    SignalProtocolMain.removeContactFromContactListAndProtocol(contact);
  }

  public List<StorageMessage> getUnencryptedMessages(Contact contact) throws UnknownContactException {
    return SignalProtocolMain.getUnencryptedMessagesList(contact);
  }

  public String getAccountName() {
    return SignalProtocolMain.getNameOfAccount();
  }

  public Fingerprint getFingerprint(Contact contact) {
    return SignalProtocolMain.getFingerprint(contact);
  }

  /** @see SignalProtocolMain#wasKeyRejected */
  public boolean wasKeyRejected(final org.signal.libsignal.protocol.SignalProtocolAddress address) {
    return SignalProtocolMain.wasKeyRejected(address);
  }



  /** @see SignalProtocolMain#existingContactAtSameAddress */
  public com.amnesica.kryptey.inputmethod.signalprotocol.chat.Contact existingContactAtSameAddress(
      final org.signal.libsignal.protocol.SignalProtocolAddress address, final String firstName,
      final String lastName) {
    return SignalProtocolMain.existingContactAtSameAddress(address, firstName, lastName);
  }

  /** @see SignalProtocolMain#hasContactWithSameDisplayName */
  public boolean hasContactWithSameDisplayName(final String firstName, final String lastName,
      final org.signal.libsignal.protocol.SignalProtocolAddress excluding) {
    return SignalProtocolMain.hasContactWithSameDisplayName(firstName, lastName, excluding);
  }

  /**
   * Forgets a contact's pinned key after the user compared safety numbers and they did not match.
   * See {@code SignalProtocolMain.rejectContactKey} for why this is safe here and would not be safe
   * on any failure path.
   */
  public boolean rejectContactKey(Contact contact) {
    return SignalProtocolMain.rejectContactKey(contact);
  }

  /** @return false only when no contact or account is loaded; never a security claim. */
  public boolean verifyContact(Contact contact) throws UnknownContactException {
    return SignalProtocolMain.verifyContact(contact);
  }

  /**
   * Maximum wire text we will even attempt to decode.
   *
   * <p>The send side caps a message at {@link #CHAR_THRESHOLD_RAW} and a key bundle at
   * {@link #CHAR_THRESHOLD_PRE_KEY_RESPONSE}, so anything materially larger did not come from a
   * peer. The cap matters because the FairyTale path feeds an unbounded {@code InflaterOutputStream}:
   * a ~390 KB clipboard payload inflates to 64 MB, on the IME main thread, inside a system clipboard
   * callback — and {@code OutOfMemoryError} is an {@code Error}, so nothing on that path catches it.
   */
  public static final int MAX_DECODABLE_CHARS = 8192;

  public String decodeMessage(String encodedMessage) throws IOException {
    if (encodedMessage == null) throw new IOException("nothing to decode");
    if (encodedMessage.length() > MAX_DECODABLE_CHARS) {
      throw new IOException("input too large to decode: " + encodedMessage.length()
          + " characters (limit " + MAX_DECODABLE_CHARS + ")");
    }
    try {
      if (EncodeHelper.encodedTextContainsInvisibleCharacters(encodedMessage)) {
        return FairyTaleEncoder.decode(encodedMessage);
      } else {
        return RawEncoder.decode(encodedMessage);
      }
    } catch (RuntimeException e) {
      // The decoders raise unchecked exceptions on input that merely looks encoded - ordinary
      // multi-line text is the common case. Convert at this boundary so it cannot reach
      // LatinIME.setInputView() and kill the process.
      throw new IOException("could not decode message", e);
    }
  }

  public void checkMessageLengthForEncodingMethod(String message, Encoder encodingMethod, boolean isPreKeyResponse) throws TooManyCharsException {
    if (message == null || encodingMethod == null) return;
    final int messageBytes = message.getBytes(StandardCharsets.UTF_8).length;
    if (isPreKeyResponse) {
      // Key bundles are sized by the protocol, not by the user, so they get their own limit.
      if (messageBytes > CHAR_THRESHOLD_PRE_KEY_RESPONSE) {
        throw new TooManyCharsException(String.format("Too many characters for invite or update message (%s characters, only %s characters allowed)", messageBytes, CHAR_THRESHOLD_PRE_KEY_RESPONSE));
      }
      return;
    }
    if (encodingMethod.equals(Encoder.RAW) && messageBytes > CHAR_THRESHOLD_RAW) {
      throw new TooManyCharsException(String.format("Too many characters for raw message (%s characters, only %s characters allowed)", messageBytes, CHAR_THRESHOLD_RAW));
    } else if (encodingMethod.equals(Encoder.FAIRYTALE) && messageBytes > CHAR_THRESHOLD_FAIRYTALE) {
      throw new TooManyCharsException(String.format("Too many characters for fairytale message (%s characters, only %s characters allowed)", messageBytes, CHAR_THRESHOLD_FAIRYTALE));
    }
  }
}
