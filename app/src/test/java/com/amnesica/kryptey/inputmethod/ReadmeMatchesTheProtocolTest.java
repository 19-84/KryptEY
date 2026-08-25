package com.amnesica.kryptey.inputmethod;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * The README describes the protocol the app implements, and it described the wrong one.
 *
 * <p>It said key agreement is X3DH. Phase 2 of this revival replaced that with PQXDH — X25519 plus a
 * Kyber-1024 encapsulation — and said so nowhere a user reads. The error ran in the conservative
 * direction, understating what the app does rather than overstating it, which is why it survived: a
 * claim that undersells is one nobody is motivated to check.
 *
 * <p>It is the same class the help text was audited for, on the document a prospective user meets
 * first. And it is the claim this project already knows is invisible without an assertion:
 * {@code PqxdhSessionTest} exists precisely because a v3 session "works and carries messages exactly
 * the same way", so nothing distinguishes the two except the negotiated version number. A README
 * naming the wrong one cannot be caught by reading either.
 *
 * <p>So this ties the sentence to the number the suite pins. If the app is ever moved off PQXDH the
 * README stops being able to claim it, and if the README is edited back to X3DH it fails against the
 * session version the protocol tests assert.
 */
public class ReadmeMatchesTheProtocolTest {

  /** The libsignal session version for PQXDH. Asserted independently by PqxdhSessionTest. */
  private static final int PQXDH_SESSION_VERSION = 4;

  private static String readme() throws IOException {
    for (final String candidate : new String[] {"README.md", "../README.md"}) {
      final Path path = Paths.get(candidate);
      if (Files.exists(path)) {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
      }
    }
    throw new IllegalStateException("could not locate README.md from "
        + Paths.get("").toAbsolutePath());
  }

  @Test
  public void thereadmeNamesTheProtocolTheAppActuallyNegotiates() throws IOException {
    final String text = readme();

    assertTrue("this test reads the README and found almost nothing; it has stopped testing "
        + "anything", text.length() > 1000);
    assertTrue("the README must name PQXDH as the key agreement - the app negotiates session "
            + "version " + PQXDH_SESSION_VERSION + ", which is PQXDH, and a reader deciding whether "
            + "to trust this app with a recorded-now-decrypted-later threat needs to know that",
        text.contains("PQXDH"));
  }

  /**
   * And it must not still present X3DH as what the app does.
   *
   * <p>Naming X3DH historically is correct and useful — the README now explains that earlier
   * versions used it and why the difference is invisible. What must not survive is the original
   * sentence presenting it as current.
   */
  @Test
  public void thereadmeDoesNotPresentX3dhAsCurrent() throws IOException {
    final String text = readme();

    assertFalse("the README still describes X3DH as the key agreement protocol in use. It is what "
            + "earlier versions did; saying so in the past tense is right, presenting it as current "
            + "is not",
        text.contains("the X3DH Key Agreement Protocol from the applied")
            || text.contains("both the X3DH Key Agreement Protocol"));
  }

  /**
   * And it must say that keys at rest are encrypted, because the baseline's were not.
   *
   * <p>The most consequential change this revival made to a user's exposure, and the Security
   * section did not mention storage at all — so a reader comparing this app against the version it
   * replaces had no way to learn that cleartext key material on disk was the thing that changed.
   */
  @Test
  public void thereadmeSaysKeysAtRestAreEncrypted() throws IOException {
    final String text = readme();
    final int security = text.indexOf("## Security");
    assertTrue("the README must have a Security section", security > 0);
    final int end = text.indexOf("\n## ", security + 1);
    final String section = end > security ? text.substring(security, end) : text.substring(security);

    assertTrue("the Security section must say that keys at rest are encrypted - earlier versions "
            + "stored the identity key, sessions and pre-keys in cleartext, and that is the change "
            + "a reader most needs to see: " + section,
        section.contains("Keystore") && section.contains("AES-256-GCM"));
  }

  /** The session version named here must be the one the protocol tests assert. */
  @Test
  public void theversionThisTestNamesIsTheOneTheSuiteAsserts() throws IOException {
    final Path test = Paths.get("src/test/java/com/amnesica/kryptey/inputmethod/signalprotocol/"
        + "PqxdhSessionTest.java");
    assertTrue("expected to find " + test, Files.exists(test));
    final String source = new String(Files.readAllBytes(test), StandardCharsets.UTF_8);

    assertTrue("PqxdhSessionTest must define the PQXDH version this test cross-checks against, or "
            + "the two can drift apart silently",
        source.contains("PQXDH = " + PQXDH_SESSION_VERSION));
    assertEquals("and it must be 4", 4, PQXDH_SESSION_VERSION);
  }
}
