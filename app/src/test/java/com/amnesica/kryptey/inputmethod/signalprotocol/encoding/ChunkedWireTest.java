package com.amnesica.kryptey.inputmethod.signalprotocol.encoding;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * A wire string cut into messages that each fit a limit, and put back together from a paste.
 *
 * <p>The properties, in the order they cost something if wrong: every part fits the limit with its
 * header counted, because the limit is the whole reason for splitting; every part is non-empty, so
 * the count in the header is the count of messages sent; the parts of two different messages are
 * never joined; and what the user is told never contains what they pasted.
 */
public class ChunkedWireTest {

  private static final String ALPHABET =
      "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";

  /** Deterministic base64-alphabet text of the given length. */
  private static String wire(final int length, final long seed) {
    final Random random = new Random(seed);
    final StringBuilder sb = new StringBuilder(length);
    for (int i = 0; i < length; i++) sb.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
    return sb.toString();
  }

  private static String wire(final int length) {
    return wire(length, length);
  }

  private static int declaredTotal(final String part) {
    return Integer.parseInt(part.substring(part.indexOf('/') + 1, part.indexOf('#', 2)));
  }

  private static String bodyOf(final String part) {
    // #K<part>/<total>#<set>#<body>
    final int afterSet = part.indexOf('#', part.indexOf('#', 2) + 1);
    return part.substring(afterSet + 1);
  }

  @Test
  public void textThatFitsIsReturnedUntouched() throws IOException {
    final String text = wire(300);
    final List<String> parts = ChunkedWire.split(text, 300);
    assertEquals(1, parts.size());
    assertEquals("a message that fits must not be wrapped: the recipient of an unwrapped message "
        + "needs no new behaviour", text, parts.get(0));
    assertFalse(ChunkedWire.isChunk(text));
  }

  @Test
  public void everyPartFitsTheLimitHeadersIncluded() throws IOException {
    // The 2,572-character invite against the platform limits the class note names, then a sweep.
    final int[] limits = {280, 300, 500, 1000, 2000, 4096};
    for (final int limit : limits) {
      for (final String part : ChunkedWire.split(wire(2572), limit)) {
        assertTrue("at a limit of " + limit + " a part is " + part.length() + " characters",
            part.length() <= limit);
      }
    }
    // From 40 up: below that, 3,000 characters need more than 999 parts and the split refuses,
    // which aLimitTooSmallForAnySplitIsRefusedRatherThanLooped covers.
    for (int length = 1; length <= 3000; length += 37) {
      for (int limit = 40; limit <= 400; limit += 19) {
        for (final String part : ChunkedWire.split(wire(length), limit)) {
          assertTrue("length " + length + " at limit " + limit + ": a part is " + part.length(),
              part.length() <= limit);
        }
      }
    }
  }

  /**
   * The case sizing against the FIRST header gets wrong: part 10's header is one character wider
   * than part 1's, so a split that measures "#K1/10#" leaves a full-length part 10 one over.
   *
   * <p>"Full-length" is the point, and the first version of this test missed it: at 2,572
   * characters the tenth part is the short remainder, so the wider header fitted anyway and the
   * mutant survived. The lengths here straddle 2,660, which at a limit of 280 is exactly ten bodies
   * of 266 - the size the first-header split would choose, leaving part 10 at 281.
   */
  @Test
  public void theLastOfTenPartsFitsToo() throws IOException {
    boolean sawTenOrMore = false;
    for (int length = 2640; length <= 2680; length++) {
      final List<String> parts = ChunkedWire.split(wire(length), 280);
      sawTenOrMore |= parts.size() >= 10;
      for (int i = 0; i < parts.size(); i++) {
        assertTrue("length " + length + ": part " + (i + 1) + " of " + parts.size() + " is "
            + parts.get(i).length() + " characters against a limit of 280 - the part that goes "
            + "over is the one the platform refuses, after the others were sent",
            parts.get(i).length() <= 280);
      }
    }
    assertTrue("the sweep never reached ten parts, so it never tested the two-digit header",
        sawTenOrMore);
  }

  @Test
  public void everyPartIsNonEmptyAtEverySize() throws IOException {
    for (int length = 1; length <= 700; length++) {
      for (int limit = 14; limit <= 120; limit += 7) {
        final List<String> parts;
        try {
          parts = ChunkedWire.split(wire(length), limit);
        } catch (final IOException tooSmall) {
          continue; // refused outright is fine; a silent short count is what this is for
        }
        for (final String part : parts) {
          if (parts.size() == 1) continue;
          assertFalse("length " + length + " at limit " + limit + ": an empty part. The header "
              + "then promises a message that is never sent, and the recipient waits forever",
              bodyOf(part).isEmpty());
          assertEquals("the declared total must be the number of parts produced",
              parts.size(), declaredTotal(part));
        }
      }
    }
  }

  @Test
  public void thePartsJoinBackIntoTheOriginal() throws IOException {
    final String text = wire(2572);
    final List<String> parts = ChunkedWire.split(text, 500);
    final ChunkedWire.Assembler assembler = new ChunkedWire.Assembler();
    for (int i = 0; i < parts.size() - 1; i++) {
      assertNull("not complete yet", assembler.accept(parts.get(i)));
    }
    assertEquals(text, assembler.accept(parts.get(parts.size() - 1)));
  }

  /** What a user actually pastes: a run of chat messages, newline-separated, possibly wrapped. */
  @Test
  public void severalPartsInOnePasteSeparatedByNewlinesAreAllTaken() throws IOException {
    final String text = wire(1500);
    final List<String> parts = ChunkedWire.split(text, 400);
    final StringBuilder paste = new StringBuilder();
    for (final String part : parts) {
      // Wrapped every 60 characters, as a messenger renders a long string.
      for (int i = 0; i < part.length(); i += 60) {
        paste.append(part, i, Math.min(part.length(), i + 60)).append('\n');
      }
      paste.append("\n\n");
    }
    assertEquals(text, new ChunkedWire.Assembler().accept(paste.toString()));
  }

  /**
   * The flake, made deterministic.
   *
   * <p>A part's body is base64 and may begin with {@code K}; the character before it is the
   * header's own closing {@code #}. Splitting a multi-part paste on the two-character marker
   * therefore cut such a part in two, and both halves were refused as damaged - about one split
   * message in eleven, on parts the app had just produced itself, telling the user to ask for
   * another invite that would fail at the same rate.
   *
   * <p>Every body here begins with {@code K}, so what was one run in eleven is every run. The
   * single-paste case is the one that broke; the one-at-a-time case is asserted beside it because
   * the same boundary logic runs on each piece.
   */
  @Test
  public void abodyBeginningWithKisNotAsecondPart() throws IOException {
    final String allKs = "K".repeat(900);
    final List<String> parts = ChunkedWire.split(allKs, 300);
    assertTrue("precondition: several parts", parts.size() >= 3);
    for (final String part : parts) {
      assertEquals("precondition: every body must start with K for this to test anything",
          'K', bodyOf(part).charAt(0));
    }

    assertEquals("pasted together", allKs,
        new ChunkedWire.Assembler().accept(String.join("\n", parts)));

    final ChunkedWire.Assembler oneAtAtime = new ChunkedWire.Assembler();
    String whole = null;
    for (final String part : parts) whole = oneAtAtime.accept(part);
    assertEquals("pasted one at a time", allKs, whole);
  }

  /** And the real thing: a wire whose parts happen to break that way, joined from one paste. */
  @Test
  public void aRealSplitSurvivesWhateverItsBodiesBeginWith() throws IOException {
    for (int length = 900; length <= 1100; length++) {
      final String text = wire(length, length);
      final List<String> parts = ChunkedWire.split(text, 300);
      assertEquals("length " + length + " does not survive being pasted as one block",
          text, new ChunkedWire.Assembler().accept(String.join("\n", parts)));
    }
  }

  @Test
  public void partsArriveInAnyOrder() throws IOException {
    final String text = wire(1800);
    final List<String> parts = new ArrayList<>(ChunkedWire.split(text, 300));
    Collections.shuffle(parts, new Random(7));
    final ChunkedWire.Assembler assembler = new ChunkedWire.Assembler();
    String whole = null;
    for (final String part : parts) whole = assembler.accept(part);
    assertEquals(text, whole);
  }

  @Test
  public void theSamePartTwiceIsHarmless() throws IOException {
    final List<String> parts = ChunkedWire.split(wire(900), 400);
    final ChunkedWire.Assembler assembler = new ChunkedWire.Assembler();
    assembler.accept(parts.get(0));
    assembler.accept(parts.get(0));
    assertEquals(1, assembler.collected());
  }

  @Test
  public void partsOfTwoDifferentMessagesAreNotSpliced() throws IOException {
    final List<String> first = ChunkedWire.split(wire(900, 1), 400);
    final List<String> second = ChunkedWire.split(wire(900, 2), 400);
    final ChunkedWire.Assembler assembler = new ChunkedWire.Assembler();
    assembler.accept(first.get(0));
    try {
      assembler.accept(second.get(1));
      fail("a part of a different message was accepted into the set");
    } catch (final ChunkedWire.PartRefusedException expected) {
      assertTrue(expected.getMessage(), expected.getMessage().contains("different message"));
    }
    assertEquals("the refusal must not cost the parts already collected", 1, assembler.collected());
  }

  @Test
  public void aPartCutShortIsDetectedWhenTheSetCompletes() throws IOException {
    final String text = wire(900);
    final List<String> parts = new ArrayList<>(ChunkedWire.split(text, 400));
    final String damaged = parts.get(1).substring(0, parts.get(1).length() - 1);
    parts.set(1, damaged);
    final ChunkedWire.Assembler assembler = new ChunkedWire.Assembler();
    for (int i = 0; i < parts.size() - 1; i++) assembler.accept(parts.get(i));
    try {
      assembler.accept(parts.get(parts.size() - 1));
      fail("a set with a truncated part was returned as whole");
    } catch (final ChunkedWire.PartRefusedException expected) {
      assertTrue(expected.getMessage(), expected.getMessage().contains("cut short"));
    }
    assertEquals("after that the set is worthless and must be dropped, or the user can never get "
        + "past it", 0, assembler.collected());
  }

  @Test
  public void malformedPartsAreRefusedNotCrashedOn() {
    final String[] bad = {
        "#K",
        "#K1/",
        "#K1/2#",
        "#K1/2#abcde#body",           // five hex, not six
        "#K1/2#abcdef#bo dy!",        // outside the alphabet
        "#K0/2#abcdef#body",          // numbered from one
        "#K3/2#abcdef#body",          // past the total
        "#K1/0#abcdef#body",          // no parts at all
        "#K1000/1000#abcdef#body",    // more digits than the format allows
        "#Kx/y#abcdef#body",
    };
    for (final String piece : bad) {
      try {
        new ChunkedWire.Assembler().accept(piece);
        fail("accepted: " + piece);
      } catch (final ChunkedWire.PartRefusedException expected) {
        // The user-facing kind.
      } catch (final IOException other) {
        fail("refused as something other than a part refusal: " + piece + " -> " + other);
      } catch (final RuntimeException crash) {
        fail("an unchecked exception on clipboard input kills the keyboard: " + piece + " -> "
            + crash);
      }
    }
  }

  @Test
  public void aPartDisagreeingAboutTheTotalIsRefused() throws IOException {
    final ChunkedWire.Assembler assembler = new ChunkedWire.Assembler();
    assembler.accept("#K1/3#abcdef#AAAA");
    try {
      assembler.accept("#K2/4#abcdef#BBBB");
      fail("accepted a part that says there are four when the first said three");
    } catch (final ChunkedWire.PartRefusedException expected) {
      assertTrue(expected.getMessage(), expected.getMessage().contains("4 parts"));
    }
  }

  @Test
  public void twoVersionsOfOnePartAreRefused() throws IOException {
    final ChunkedWire.Assembler assembler = new ChunkedWire.Assembler();
    assembler.accept("#K1/3#abcdef#AAAA");
    try {
      assembler.accept("#K1/3#abcdef#BBBB");
      fail("accepted a second, different part 1");
    } catch (final ChunkedWire.PartRefusedException expected) {
      assertTrue(expected.getMessage(), expected.getMessage().contains("part 1"));
    }
  }

  @Test
  public void ordinaryTextIsNotApart() {
    try {
      new ChunkedWire.Assembler().accept("hello there");
      fail("ordinary text was accepted as a part");
    } catch (final IOException expected) {
      assertTrue(expected instanceof ChunkedWire.PartRefusedException);
    }
    assertFalse(ChunkedWire.isChunk("hello"));
    assertFalse(ChunkedWire.isChunk(null));
    assertTrue(ChunkedWire.isChunk("  \n#K1/2#abcdef#AAAA"));
  }

  /**
   * The marker is two characters and the world is full of hashtags. The strip lights Decrypt on
   * whatever this says yes to, and a press then clears the clipboard - so "#Kubernetes" must not
   * be a part, while a header the messenger wrapped in the middle must still be one.
   */
  @Test
  public void ahashtagIsNotApartAndAwrappedHeaderIs() {
    assertFalse(ChunkedWire.isChunk("#Kubernetes rocks"));
    assertFalse(ChunkedWire.isChunk("#K1/2 is a fraction"));
    assertTrue(ChunkedWire.isChunk("#K1/\n2#abc\ndef#AAAA"));
    try {
      new ChunkedWire.Assembler().accept("#Kubernetes rocks");
      fail("a hashtag was taken for a part");
    } catch (final IOException expected) {
      assertTrue(expected.getMessage(), expected.getMessage().contains("not part of"));
    }
  }

  @Test
  public void aLimitTooSmallForAnySplitIsRefusedRatherThanLooped() {
    try {
      ChunkedWire.split(wire(10_000), 14);
      fail("split something no part count can carry");
    } catch (final IOException expected) {
      assertTrue(expected.getMessage(), expected.getMessage().contains("too small"));
    }
  }

  /**
   * The parts one device writes must be readable on any device, including its own.
   *
   * <p><b>Written for a hypothesis that was wrong, and kept because the property is worth having.</b>
   * A test of this feature flaked - it passed alone and failed in the full suite - and the guess was
   * {@code setIdOf}, which formatted the id with {@code String.format("%02x")} and no locale, in a
   * suite whose {@code AsafetyNumberReadsTheSameOnBothDevicesTest} sets a Devanagari default locale
   * in the same JVM. The control refutes it: removing the {@code Locale.ROOT} added to that method
   * leaves this test green, because {@code Formatter} substitutes a locale's own digits for decimal
   * conversions and not for {@code %x}. The flake is therefore still unexplained, and is recorded
   * as such rather than reported fixed.
   *
   * <p>What this does pin is the property itself, which no test held before: a message one device
   * splits is one that device can rejoin, in a locale whose numbering system is not Latin. The
   * {@code Locale.ROOT} in {@code setIdOf} stays as defence that costs nothing and matches
   * {@code E2EEStripView.formatCodeSegment}'s decision, not as a fix for anything observed.
   */
  @Test
  public void asetIdIsLatinDigitsUnderAnyLocale() throws IOException {
    final Locale original = Locale.getDefault();
    try {
      for (final String tag : new String[] {"hi-IN-u-nu-deva", "ar-EG-u-nu-arab", "en-US"}) {
        Locale.setDefault(Locale.forLanguageTag(tag));

        final String id = ChunkedWire.setIdOf("whatever this device is about to send");
        assertTrue("under " + tag + " the set id is \"" + id + "\", which the parser's own "
            + "[0-9a-f] class rejects - so the sender's parts are unreadable to the sender",
            id.matches("[0-9a-f]{6}"));

        final String text = wire(900);
        final List<String> split = ChunkedWire.split(text, 300);
        final ChunkedWire.Assembler assembler = new ChunkedWire.Assembler();
        String whole = null;
        for (final String part : split) whole = assembler.accept(part);
        assertEquals("under " + tag + ", a message this device split must be one it can rejoin",
            text, whole);
      }
    } finally {
      Locale.setDefault(original);
    }
  }

  @Test
  public void theCountsSayHowFarAlongTheSetIs() throws IOException {
    final ChunkedWire.Assembler assembler = new ChunkedWire.Assembler();
    assertEquals(0, assembler.expected());
    assembler.accept("#K2/4#abcdef#AAAA");
    assertEquals(1, assembler.collected());
    assertEquals(4, assembler.expected());
  }

  /**
   * The control for the toast guard's exception. Every refusal is shown outside the secure window,
   * so no refusal may quote the paste - not the body, not the set id, not the header.
   */
  @Test
  public void noRefusalMessageContainsWhatWasPasted() throws IOException {
    final String body = "QUOTEDBODYQUOTEDBODY";
    final String[][] scenarios = {
        {"#K1/3#abc123#" + body, "#K2/3#def456#" + body},       // different set
        {"#K1/3#abc123#" + body, "#K2/4#abc123#" + body},       // different total
        {"#K1/3#abc123#" + body, "#K1/3#abc123#" + body + "A"}, // two versions
        {"#K1/3#abc123#" + body, "#K5/3#abc123#" + body},       // out of range
        {"#K1/3#abc123#" + body, "#K2/3#abc123#" + body + "!"}, // damaged
        {"#K1/3#abc123#" + body, "plain " + body},              // not a part
    };
    int refusals = 0;
    for (final String[] scenario : scenarios) {
      final ChunkedWire.Assembler assembler = new ChunkedWire.Assembler();
      assembler.accept(scenario[0]);
      try {
        assembler.accept(scenario[1]);
        fail("expected a refusal for " + scenario[1]);
      } catch (final ChunkedWire.PartRefusedException refusal) {
        refusals++;
        final String said = refusal.getMessage();
        assertNotNull(said);
        assertFalse("the refusal quotes the body: " + said, said.contains(body));
        assertFalse("the refusal quotes a set id: " + said,
            said.contains("abc123") || said.contains("def456"));
        assertFalse("the refusal quotes the marker: " + said, said.contains("#K"));
      }
    }
    // And the completion check, whose refusal comes from a different place.
    final ChunkedWire.Assembler assembler = new ChunkedWire.Assembler();
    assembler.accept("#K1/2#abc123#" + body);
    try {
      assembler.accept("#K2/2#abc123#" + body);
      fail("a set whose id does not match its content was returned as whole");
    } catch (final ChunkedWire.PartRefusedException refusal) {
      refusals++;
      assertFalse(refusal.getMessage(), refusal.getMessage().contains(body));
    }
    assertEquals(scenarios.length + 1, refusals);
  }
}
