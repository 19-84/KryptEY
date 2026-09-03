package com.amnesica.kryptey.inputmethod.signalprotocol.twoparty;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Two people, in two operating-system processes, exchanging keys and then messages.
 *
 * <p>The suite already has {@code EndToEndConversationTest} and, on a device,
 * {@code ProtocolRoundTripOnRealHardwareTest}. Both are honest in their own javadoc about what they
 * are: {@code SignalProtocolMain} is a singleton with one active account, so "Alice" and "Bob" are
 * two {@code Account} objects on one heap and the conversation is produced by swapping which one is
 * active between statements.
 *
 * <p>That proves the protocol composes. It cannot prove the thing a user actually depends on,
 * because of what it shares: one heap, so either identity's private key is reachable from the
 * other; one singleton and one set of static test seams; one libsignal load; one process-wide
 * source of randomness. The failure this cannot see is the one that matters most - a key, a session
 * or a plaintext being read from the sender's own store rather than from what arrived over the
 * wire. In one process that read succeeds and the test goes green.
 *
 * <p>So here each identity gets its own JVM, and the only thing that crosses between them is text
 * on a pipe. If any step of establishing a session needs something other than what a user could
 * paste into a messenger, there is nowhere for it to come from and this fails.
 *
 * <p>What is deliberately NOT claimed: this is not two devices. Both processes run on one machine,
 * against the desktop libsignal, with no Android Keystore and no storage - a null Context, so
 * neither party persists anything. The keystore half is covered on a real Android runtime by
 * {@code AndroidKeystoreCryptoBoxTest} and {@code TheMigrationSealIsRealOnDeviceTest}. What this
 * adds, and nothing else in the project has, is that the two parties cannot see each other.
 */
public class TwoUsersInSeparateProcessesTest {

  /** Long enough for a cold JVM plus a native library load on a loaded machine; short enough to fail. */
  private static final int REPLY_TIMEOUT_SECONDS = 120;

  private final List<Peer> started = new ArrayList<>();

  @After
  public void closeEveryone() {
    for (final Peer peer : started) peer.close();
  }

  private static String enc(final String s) {
    return Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8));
  }

  private static String dec(final String s) {
    return new String(Base64.getDecoder().decode(s), StandardCharsets.UTF_8);
  }

  /** One person: a JVM holding exactly one identity, spoken to over a pipe. */
  private final class Peer {
    private final String who;
    private final Process process;
    private final PrintWriter toPeer;
    private final BlockingQueue<String> fromPeer = new ArrayBlockingQueue<>(64);
    private final java.util.ArrayDeque<String> diagnostics = new java.util.ArrayDeque<>();
    private final Thread pump;
    private String name;
    private String deviceId;

    Peer(final String who) throws IOException {
      this.who = who;
      final String java = System.getProperty("java.home") + File.separator + "bin"
          + File.separator + "java";
      final ProcessBuilder builder = new ProcessBuilder(
          java, "-cp", System.getProperty("java.class.path"), TwoPartyPeer.class.getName());
      process = builder.start();
      toPeer = new PrintWriter(new java.io.OutputStreamWriter(
          process.getOutputStream(), StandardCharsets.UTF_8), true);

      // Drained on its own thread. A pipe has a finite buffer, and a peer blocked writing into a
      // full one while this side blocks writing into ITS full one is a deadlock that looks exactly
      // like a hung protocol.
      pump = new Thread(() -> {
        try (BufferedReader r = new BufferedReader(
            new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
          String line;
          while ((line = r.readLine()) != null) fromPeer.put(line);
        } catch (final Exception ignored) {
          // The peer exited or the stream closed; ask() reports it as a timeout with context.
        }
      }, "peer-" + who);
      pump.setDaemon(true);
      pump.start();

      // The peer's stderr, echoed here with its name on it.
      //
      // INHERIT was tried first and is wrong under Gradle: the child's stderr goes to the test
      // worker's, which is not captured into the test report, so a peer that failed to start
      // reported "ERR could not initialise an identity" and the reason for it was nowhere. A child
      // process's diagnostics have to be carried back deliberately or they are lost.
      final Thread errors = new Thread(() -> {
        try (BufferedReader r = new BufferedReader(
            new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
          String line;
          while ((line = r.readLine()) != null) {
            synchronized (diagnostics) {
              diagnostics.addLast(line);
              // Bounded, because a peer logs every prekey it generates and the useful part of a
              // failure is always the end. Echoing all of it put hundreds of lines into the report
              // for every passing run.
              while (diagnostics.size() > 40) diagnostics.removeFirst();
            }
          }
        } catch (final Exception ignored) {
          // The peer exited; there is nothing further to relay.
        }
      }, "peer-err-" + who);
      errors.setDaemon(true);
      errors.start();
    }

    /** The tail of what the peer said on stderr, for a failure message that can be acted on. */
    private String lastWords() {
      synchronized (diagnostics) {
        if (diagnostics.isEmpty()) return "";
        return "\n  " + who + " last said:\n    " + String.join("\n    ", diagnostics);
      }
    }

    /** Sends one command and returns the peer's reply, failing rather than hanging. */
    String ask(final String command) {
      toPeer.println(command);
      final String reply;
      try {
        reply = fromPeer.poll(REPLY_TIMEOUT_SECONDS, TimeUnit.SECONDS);
      } catch (final InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new AssertionError(who + " was interrupted waiting for a reply to " + command, e);
      }
      if (reply == null) {
        fail(who + " did not answer '" + command.split(" ")[0] + "' within "
            + REPLY_TIMEOUT_SECONDS + "s. Alive=" + process.isAlive()
            + (process.isAlive() ? "" : ", exit=" + process.exitValue()) + lastWords());
      }
      return reply;
    }

    /** Sends one command, requires it to have succeeded, and returns the payload fields. */
    String[] must(final String command) {
      final String reply = ask(command);
      assertTrue(who + " refused '" + command.split(" ")[0] + "': " + reply + lastWords(),
          reply.equals("OK") || reply.startsWith("OK "));
      return reply.length() > 3 ? reply.substring(3).split(" ") : new String[0];
    }

    void introduce() {
      final String[] me = must("WHOAMI");
      name = me[0];
      deviceId = me[1];
    }

    void close() {
      try {
        if (process.isAlive()) {
          toPeer.println("BYE");
          process.waitFor(10, TimeUnit.SECONDS);
        }
      } catch (final InterruptedException e) {
        Thread.currentThread().interrupt();
      } finally {
        process.destroyForcibly();
      }
    }
  }

  private Peer peer(final String who) throws IOException {
    final Peer p = new Peer(who);
    started.add(p);
    p.introduce();
    return p;
  }

  private Peer alice;
  private Peer bob;

  @Before
  public void twoPeopleStartUp() throws IOException {
    alice = peer("alice");
    bob = peer("bob");
  }

  // ---------------------------------------------------------------------------------------------
  // The premises. Each one is a way this test could be worth nothing while passing.
  // ---------------------------------------------------------------------------------------------

  @Test
  public void theyReallyAreTwoProcesses() {
    final String aliceJvm = alice.must("INSTANCE")[0];
    final String bobJvm = bob.must("INSTANCE")[0];

    assertNotEquals("both identities are in one JVM, which is the thing this test exists not to do",
        aliceJvm, bobJvm);
    assertNotEquals("a peer answered with this process's own token, so it is not a peer",
        TwoPartyPeer.INSTANCE, aliceJvm);
    assertNotEquals("a peer answered with this process's own token, so it is not a peer",
        TwoPartyPeer.INSTANCE, bobJvm);

    // Asked twice. Without this the assertions above are also satisfied by a peer that returns a
    // fresh random every time, which would prove nothing about processes at all.
    assertEquals("the token is per-call rather than per-JVM, so it cannot distinguish processes",
        aliceJvm, alice.must("INSTANCE")[0]);
  }

  @Test
  public void theyReallyAreTwoIdentities() {
    assertNotEquals("two independently initialised identities collided, so every assertion about "
        + "one reaching the other is meaningless", alice.name, bob.name);
  }

  @Test
  public void neitherKnowsTheOtherBeforeAnythingIsExchanged() {
    assertEquals("bob has a session with alice before she has sent him anything",
        "false", bob.must("HASSESSION " + alice.name + " " + alice.deviceId)[0]);
    assertEquals("alice has a session with bob before anything was exchanged",
        "false", alice.must("HASSESSION " + bob.name + " " + bob.deviceId)[0]);
  }

  // ---------------------------------------------------------------------------------------------
  // The conversation.
  // ---------------------------------------------------------------------------------------------

  /** The whole point: an invite crosses as text, and a message comes back that only she can read. */
  @Test
  public void theyExchangeKeysAndThenMessages() {
    // Alice makes an invite. In the app this is what she copies out and pastes into a messenger.
    final String invite = alice.must("INVITE")[0];
    assertTrue("an invite must be text a user could paste",
        dec(invite).matches("[A-Za-z0-9+/=]+"));

    // Bob accepts it. This is the only thing that has ever crossed between the two processes.
    bob.must("ACCEPT " + alice.name + " " + alice.deviceId + " " + invite);
    assertEquals("bob accepted alice's invite and still has no session with her",
        "true", bob.must("HASSESSION " + alice.name + " " + alice.deviceId)[0]);

    // Bob replies.
    final String message = "the meeting moved to nine";
    final String[] sent = bob.must("SEND " + alice.name + " " + alice.deviceId + " " + enc(message));
    final String onTheWire = sent[0];
    final byte[] ciphertext = Base64.getDecoder().decode(sent[1]);

    // Searched in the ciphertext BYTES, not in the base64 wire text: the wire alphabet cannot
    // contain a space, so looking for this string there is an assertion that could never fail.
    final String asBytes = new String(ciphertext, StandardCharsets.ISO_8859_1);
    assertFalse("the message is visible in the ciphertext", asBytes.contains(message));
    assertFalse("nor a run of it", asBytes.contains("meeting moved"));

    // Alice reads it.
    final String read = dec(alice.must("RECV " + bob.name + " " + bob.deviceId + " " + onTheWire)[0]);
    assertEquals("alice did not recover what bob sent", message, read);
  }

  /** A reply proves the session works in the direction it was not established from. */
  @Test
  public void theConversationRunsBothWays() {
    bob.must("ACCEPT " + alice.name + " " + alice.deviceId + " " + alice.must("INVITE")[0]);

    final String fromBob = "understood";
    alice.must("RECV " + bob.name + " " + bob.deviceId + " "
        + bob.must("SEND " + alice.name + " " + alice.deviceId + " " + enc(fromBob))[0]);

    final String fromAlice = "bring the second key";
    final String back = dec(bob.must("RECV " + alice.name + " " + alice.deviceId + " "
        + alice.must("SEND " + bob.name + " " + bob.deviceId + " " + enc(fromAlice))[0])[0]);
    assertEquals("bob did not recover alice's reply", fromAlice, back);
  }

  /**
   * The safety number is the one claim the user is asked to check by hand, and it is worth nothing
   * unless two separated devices independently arrive at the same digits.
   */
  @Test
  public void bothSidesReadTheSameSafetyNumber() {
    // Each pins the other, which is what the app's add-contact flow does in both directions.
    bob.must("ACCEPT " + alice.name + " " + alice.deviceId + " " + alice.must("INVITE")[0]);
    alice.must("ACCEPT " + bob.name + " " + bob.deviceId + " " + bob.must("INVITE")[0]);

    final String asBobSeesIt = dec(bob.must("SAFETY " + alice.name + " " + alice.deviceId)[0]);
    final String asAliceSeesIt = dec(alice.must("SAFETY " + bob.name + " " + bob.deviceId)[0]);

    assertEquals("the safety number is not 60 digits, so it is not the thing users compare",
        60, asBobSeesIt.replaceAll("\\s", "").length());
    assertEquals("two honest devices read different safety numbers for the same pair, which is the "
        + "failure that teaches users to ignore mismatches", asAliceSeesIt, asBobSeesIt);
  }

  /**
   * The negative half. Without it, every assertion above is satisfied by a system that hands out
   * the plaintext to anyone.
   */
  @Test
  public void athirdPersonHoldingTheTranscriptLearnsNothing() throws IOException {
    final Peer mallory = peer("mallory");

    final String invite = alice.must("INVITE")[0];
    bob.must("ACCEPT " + alice.name + " " + alice.deviceId + " " + invite);
    final String onTheWire =
        bob.must("SEND " + alice.name + " " + alice.deviceId + " " + enc("nine o'clock"))[0];

    // Mallory has everything a messenger operator has: the invite and the ciphertext.
    mallory.must("ACCEPT " + alice.name + " " + alice.deviceId + " " + invite);
    final String reply = mallory.ask("RECV " + bob.name + " " + bob.deviceId + " " + onTheWire);
    assertTrue("a third party who only ever saw the wire recovered the message: " + reply,
        reply.startsWith("ERR"));
  }

  /** And a message altered in transit must not decrypt to anything at all. */
  @Test
  public void amessageAlteredOnTheWireIsRefused() {
    bob.must("ACCEPT " + alice.name + " " + alice.deviceId + " " + alice.must("INVITE")[0]);
    final String onTheWire =
        bob.must("SEND " + alice.name + " " + alice.deviceId + " " + enc("nine o'clock"))[0];

    // One character of the base64 body changed - the smallest edit a hostile relay can make.
    final String wire = dec(onTheWire);
    final int at = wire.length() / 2;
    final char was = wire.charAt(at);
    final String tampered =
        wire.substring(0, at) + (was == 'A' ? 'B' : 'A') + wire.substring(at + 1);
    assertNotEquals("the tamper did not change anything", wire, tampered);

    final String reply =
        alice.ask("RECV " + bob.name + " " + bob.deviceId + " " + enc(tampered));
    assertTrue("alice accepted a message that was altered in transit: " + reply,
        reply.startsWith("ERR"));
  }
}
