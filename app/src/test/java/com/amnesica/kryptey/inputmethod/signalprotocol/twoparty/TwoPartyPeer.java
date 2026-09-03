package com.amnesica.kryptey.inputmethod.signalprotocol.twoparty;

import com.amnesica.kryptey.inputmethod.signalprotocol.MessageEnvelope;
import com.amnesica.kryptey.inputmethod.signalprotocol.SignalProtocolMain;
import com.amnesica.kryptey.inputmethod.signalprotocol.chat.Contact;
import com.amnesica.kryptey.inputmethod.signalprotocol.encoding.EnvelopeCodec;
import com.amnesica.kryptey.inputmethod.signalprotocol.util.ProtocolAddresses;

import org.signal.libsignal.protocol.SignalProtocolAddress;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * One person, in their own operating-system process.
 *
 * <p>Every end-to-end test in this project before this one put both parties in a single JVM and
 * swapped which {@code Account} was active, and each of them says so in its own javadoc. That is a
 * reasonable simulation of the protocol and a poor one of two people: the two identities are
 * objects on one heap, reachable from one another, sharing one {@code SignalProtocolMain}
 * singleton, one set of static test seams, one libsignal load and one process-wide random. A
 * defect where the recipient's key is read out of the sender's own store - the exact shape of the
 * worst bug this protocol can have - cannot be detected by a test built that way, because the read
 * succeeds.
 *
 * <p>So this is the other half: one identity per process. The only thing that crosses between them
 * is the text on stdin and stdout, which is the same discipline a real user is under when they
 * paste an invite into a messenger. Nothing here can reach the peer's memory, because there is no
 * peer in this memory.
 *
 * <p>The protocol is one command per line, fields separated by single spaces, every payload
 * base64 so that no message content, name or key material can contain a separator. Replies are
 * {@code OK} or {@code OK <payload>} or {@code ERR <text>}. It is deliberately dumb: the test
 * process holds all the reasoning, and this holds none, so a bug here cannot make a conversation
 * appear to work.
 *
 * <p>Not a production class and not reachable from one - it lives in the test source set, which
 * {@code TestSeamsStayOutOfProductionTest} is the standing guard for.
 */
public final class TwoPartyPeer {

  /**
   * Minted once per JVM, at class initialisation.
   *
   * <p>This is how the test proves its peers really are separate processes. {@code ProcessHandle}
   * and {@code Process.pid()} are not on the Android bootclasspath this source set compiles
   * against, so the pid is not available to ask for. A static token is better anyway: asked twice
   * it returns the same value, so a peer that answered with a fresh random each time would fail the
   * premise rather than satisfy it, and the test JVM can compare its OWN copy of this field against
   * both answers - if either matches, the "peer" is this process and the whole test is theatre.
   */
  public static final String INSTANCE = java.util.UUID.randomUUID().toString();

  private static String enc(final String s) {
    return Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8));
  }

  private static String dec(final String s) {
    return new String(Base64.getDecoder().decode(s), StandardCharsets.UTF_8);
  }

  private static SignalProtocolAddress addressOf(final String b64name, final String deviceId) {
    return ProtocolAddresses.of(dec(b64name), Integer.parseInt(deviceId));
  }

  /**
   * A contact record for an address this peer has pinned.
   *
   * <p>The display name is arbitrary and deliberately so: the safety number must be a function of
   * the two identity keys and the address, never of what either side chose to call the other. If
   * that stopped being true, the two peers below would disagree while both being honest, which is
   * the failure that teaches a user to ignore mismatches.
   */
  private static Contact contactAt(final SignalProtocolAddress address) {
    return new Contact("Peer", "OverTheWire", address.getName(), address.getDeviceId(), false);
  }

  public static void main(final String[] args) throws Exception {
    // stdout carries the protocol, so anything else must not go there. libsignal, Jackson and the
    // app's own logging all write to stdout given the chance, and one stray line would desynchronise
    // the conversation in a way that reads as a protocol failure.
    final PrintStream wire = new PrintStream(new java.io.FileOutputStream(java.io.FileDescriptor.out),
        true, "UTF-8");
    System.setOut(System.err);

    SignalProtocolMain.testIsRunning = true;
    // No Context: this process has no Android around it. The Keystore-backed storage is covered by
    // the device tests; what is under examination here is whether two separated identities can
    // reach each other using nothing but the text between them.
    //
    // The return value is deliberately not the check. initialize() reports false whenever nothing
    // was PERSISTED, and with a null context nothing can be - "No protocol resources were stored
    // (mStorageHelper is null)". The identity is generated regardless, which is what the in-process
    // end-to-end tests have always relied on. What must be true here is that an account exists, so
    // that is what is asserted; treating false as fatal made every peer refuse to start while the
    // identity behind it was perfectly good.
    SignalProtocolMain.initialize(null);
    if (SignalProtocolMain.getInstance().getAccount() == null) {
      wire.println("ERR no identity was generated");
      return;
    }

    final BufferedReader in =
        new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
    String line;
    while ((line = in.readLine()) != null) {
      final String[] f = line.trim().split(" ");
      try {
        switch (f[0]) {
          case "WHOAMI": {
            final var account = SignalProtocolMain.getInstance().getAccount();
            wire.println("OK " + enc(account.getSignalProtocolAddress().getName())
                + " " + account.getDeviceId());
            break;
          }
          case "INSTANCE":
            wire.println("OK " + INSTANCE);
            break;
          case "INVITE":
            wire.println("OK " + enc(SignalProtocolMain.exportOwnKeyBundle()));
            break;
          case "ACCEPT": {
            final MessageEnvelope bundle = EnvelopeCodec.fromWire(dec(f[3]));
            final boolean built =
                SignalProtocolMain.processPreKeyResponseMessage(bundle, addressOf(f[1], f[2]));
            wire.println(built ? "OK" : "ERR the bundle did not build a session");
            break;
          }
          case "HASSESSION":
            wire.println("OK " + SignalProtocolMain.hasSessionWith(addressOf(f[1], f[2])));
            break;
          case "SEND": {
            final MessageEnvelope out =
                SignalProtocolMain.encryptMessage(dec(f[3]), addressOf(f[1], f[2]));
            if (out == null) {
              wire.println("ERR nothing to send to that address");
              break;
            }
            // The ciphertext bytes go back too, separately from the wire text. The test needs to
            // search the BYTES for the plaintext: the wire alphabet is base64, so searching the
            // wire text for a string containing a space is an assertion that could never fail -
            // which is a mistake this suite has already made once and written down.
            wire.println("OK " + enc(EnvelopeCodec.toWire(out)) + " "
                + Base64.getEncoder().encodeToString(out.getCiphertextMessage()));
            break;
          }
          case "RECV": {
            final MessageEnvelope inbound = EnvelopeCodec.fromWire(dec(f[3]));
            wire.println("OK " + enc(SignalProtocolMain.decryptMessage(inbound, addressOf(f[1], f[2]))));
            break;
          }
          case "SAFETY": {
            final var fingerprint = SignalProtocolMain.getFingerprint(contactAt(addressOf(f[1], f[2])));
            if (fingerprint == null) {
              wire.println("ERR no fingerprint for that address");
              break;
            }
            wire.println("OK " + enc(fingerprint.getDisplayableFingerprint().getDisplayText()));
            break;
          }
          case "BYE":
            wire.println("OK");
            return;
          default:
            wire.println("ERR unknown command " + f[0]);
        }
      } catch (final Throwable t) {
        // Reported rather than thrown. A peer that dies leaves the test blocked on a read with no
        // explanation, and "the conversation stopped" is a much worse diagnostic than the exception.
        wire.println("ERR " + t.getClass().getName() + ": "
            + String.valueOf(t.getMessage()).replace('\n', ' '));
      }
    }
  }
}
