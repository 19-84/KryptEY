package com.amnesica.kryptey.inputmethod;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.amnesica.kryptey.inputmethod.signalprotocol.util.ProtocolAddresses;

import org.junit.Test;
import org.signal.libsignal.protocol.SignalProtocolAddress;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * There are two renderings of an address, and a comparison must never cross them.
 *
 * <p>{@code String.valueOf(address)} renders {@code name.deviceId}. {@code ProtocolAddresses.key}
 * renders the name, a non-printing separator, and the device id. A comparison with one on each side
 * is <b>constant-false, permanently, and invisible to the whole suite</b> - because a test written
 * beside such a predicate naturally builds its fixture with the rendering the predicate uses, so it
 * passes while production never enters the branch.
 *
 * <p>That is not hypothetical. A warning was added, shipped and withdrawn without ever firing: its
 * predicate built a set with {@code String.valueOf} and compared it against retired entries the app
 * writes with {@code ProtocolAddresses.key}. Three mutants against the surrounding code all left its
 * test green, because the branch was dead.
 *
 * <p>An audit found no second instance. It also found that <b>nothing keeps them apart</b>: the
 * separation is a file boundary. The dotted form is the idiom in dozens of places in {@code latin/};
 * the canonical form is what the retired-name list and the chat log are keyed by. The defect was
 * written by someone in the second realm reaching for the first realm's idiom. This pins the
 * boundary, so the next person meets a red test rather than a warning that silently never appears.
 *
 * <p><b>The boundary is not simply "the package".</b> {@code IdentityKeyStoreImpl} persists
 * {@code pendingIdentities}, {@code outOfBandAddresses} and {@code rejectedAddresses} keyed by its
 * own hand-rolled DOTTED rendering ({@code addressKey}), which is why it is exempted below. An
 * earlier version of this javadoc said the canonical form is what {@code signalprotocol/} persists,
 * and that was false about the three most security-critical persisted records in the layer - the
 * ones the paragraph below forbids re-keying. A maintainer reads a sentence like that to decide
 * which rendering to use, so it is worth stating precisely: the rule is per-record, and the
 * exemption exists because no string from those three maps ever leaves that class
 *
 * <p><b>Do not answer a failure here by harmonising the renderings.</b> {@code pendingIdentities},
 * {@code outOfBandAddresses} and {@code rejectedAddresses} are persisted as strings, so re-keying
 * them without a migration answers {@code wasKeyRejected} false at every address the user has ever
 * rejected - silently retracting every rejection on disk. The chat log and the retired-name list
 * have the same property in reverse. The boundary is the invariant; the renderings are not
 * interchangeable.
 */
public class AnaddressIsRenderedOneWayPerRealmTest {

  /** {@code ProtocolAddresses.key(} or {@code chatLogKey(}, however the line happens to wrap. */
  private static final java.util.regex.Pattern WRAPPED_CANONICAL =
      java.util.regex.Pattern.compile("ProtocolAddresses\\s*\\.\\s*key\\s*\\(|chatLogKey\\s*\\(");

  /**
   * {@code String.valueOf} applied to something that is an address.
   *
   * <p>Matched by what is being rendered rather than by a type name on the same line: an address
   * reaches this call as {@code getSignalProtocolAddress()}, or as a local or field whose name says
   * so. Anything else - {@code String.valueOf(deviceId)}, {@code String.valueOf(identifier)} - is
   * ordinary and must not be flagged, which is why this is not simply every {@code String.valueOf}.
   */
  private static final java.util.regex.Pattern VIEWS_RENDERING =
      java.util.regex.Pattern.compile(
          "String\\s*\\.\\s*valueOf\\s*\\(\\s*[\\w.]*"
              + "(getSignalProtocolAddress\\s*\\(\\s*\\)|[Aa]ddress\\b|\\baddr\\b)[^)]*\\)");

  private static Path mainSources() {
    for (final String candidate : new String[] {"src/main/java", "app/src/main/java"}) {
      final Path path = Paths.get(candidate);
      if (Files.isDirectory(path)) return path;
    }
    throw new IllegalStateException("could not locate the main source tree");
  }

  private static String withoutCommentsAndStrings(final String source) {
    return source.replaceAll("(?s)/\\*.*?\\*/", " ")
        .replaceAll("(?m)//[^\n]*", " ")
        .replaceAll("\"(?:[^\"\\\\]|\\\\.)*\"", "\"\"");
  }

  private static List<String> sourcesUnder(final String relative) throws IOException {
    final List<String> found = new ArrayList<>();
    try (Stream<Path> files = Files.walk(mainSources().resolve(relative))) {
      for (final Path file : (Iterable<Path>) files
          .filter(p -> p.toString().endsWith(".java"))::iterator) {
        found.add(mainSources().relativize(file) + " "
            + withoutCommentsAndStrings(
                new String(Files.readAllBytes(file), StandardCharsets.UTF_8)));
      }
    }
    return found;
  }

  /**
   * The two renderings really do differ, so everything below is about something.
   *
   * <p>Asserted by shape rather than by writing the separator out: it is a non-printing character,
   * and a literal one in this file would be invisible to anyone reading the assertion - which is the
   * property that let the original defect survive review in the first place.
   */
  @Test
  public void thetworenderingsAreNotTheSameString() {
    final SignalProtocolAddress address = ProtocolAddresses.of("peer-uuid", 7);
    final String dotted = String.valueOf(address);
    final String canonical = ProtocolAddresses.key(address);

    assertEquals("String.valueOf must render the dotted form", "peer-uuid.7", dotted);
    assertTrue("the canonical rendering must carry the name", canonical.startsWith("peer-uuid"));
    assertTrue("...and end with the device id", canonical.endsWith("7"));
    assertEquals("...joined by exactly one separator character", "peer-uuid7".length() + 1,
        canonical.length());
    assertTrue("the separator must be a control character, which is the property the whole scheme "
            + "rests on: BinaryEnvelope refuses any sender name outside printable ASCII, so a "
            + "rendered key and a bare name live in provably disjoint spaces. Asserting merely "
            + "'not a dot' admits any printable separator - measured: setting it to '-' leaves this "
            + "case green while the disjointness it certifies is gone",
        canonical.charAt("peer-uuid".length()) < 0x20);
    assertTrue("and the two must differ - a comparison crossing them is constant-false, which is "
            + "the whole subject of this file", !dotted.equals(canonical));
  }

  /** The view renders addresses one way, and must not reach for the store's records. */
  @Test
  public void theviewDoesNotUseTheCanonicalRendering() throws IOException {
    final List<String> offences = new ArrayList<>();
    for (final String entry : sourcesUnder("com/amnesica/kryptey/inputmethod/latin")) {
      final String name = entry.substring(0, entry.indexOf(' '));
      final String body = entry.substring(entry.indexOf(' ') + 1);
      // Whitespace-tolerant, because the project wraps at 100 columns and the receiver routinely
      // ends up on its own line: `ProtocolAddresses\n    .key(addr)`. A substring match sees only
      // the unwrapped form - and the commit that added this guard wrote the wrapped form in its own
      // new fixture, so the guard shipped demonstrating its own evasion.
      if (WRAPPED_CANONICAL.matcher(body).find()) {
        offences.add(name);
      }
    }
    assertEquals("the view compares addresses as String.valueOf renders them, and every record it "
            + "keys that way is in memory. A canonical rendering appearing here means a comparison "
            + "is about to cross the two, which cannot match and cannot be seen: " + offences,
        0, offences.size());
  }

  /** ...and the protocol layer must not reach for the view's. */
  @Test
  public void theprotocolLayerDoesNotRenderAddressesTheViewsWay() throws IOException {
    final List<String> offences = new ArrayList<>();
    for (final String entry : sourcesUnder("com/amnesica/kryptey/inputmethod/signalprotocol")) {
      final String name = entry.substring(0, entry.indexOf(' '));
      final String body = entry.substring(entry.indexOf(' ') + 1);
      // The store's own hand-rolled dotted key is deliberate and self-contained: every accessor
      // returns a boolean or an IdentityKey, so no string from those maps leaves the class and none
      // can be compared against a canonical one. Exempted by that method rather than by filename -
      // a whole-file skip would also hide any FUTURE String.valueOf of an address written there,
      // which is the one place in this layer where the dotted form is load-bearing and therefore
      // the easiest place to add a second one without noticing.
      if (name.endsWith("IdentityKeyStoreImpl.java")
          && body.contains("getName() + \".\" + ")) {
        continue;
      }
      // Not line-by-line, and not requiring the type name beside the call. The historical defect
      // matched a line-based scan only by coincidence of formatting - it happened to be a one-line
      // for loop - and in practice the address is a local or a field declared earlier, so the line
      // holding String.valueOf(x) usually carries no type name at all.
      final java.util.regex.Matcher rendered = VIEWS_RENDERING.matcher(body);
      while (rendered.find()) {
        offences.add(name + "  " + rendered.group().replaceAll("\\s+", " ").trim());
      }
    }
    assertEquals("the protocol layer persists addresses as ProtocolAddresses.key renders them. A "
            + "String.valueOf of an address here is how the withdrawn warning was written: it "
            + "compiles, it never matches what the store wrote, and no test beside it can see it: "
            + offences,
        0, offences.size());
  }
}
