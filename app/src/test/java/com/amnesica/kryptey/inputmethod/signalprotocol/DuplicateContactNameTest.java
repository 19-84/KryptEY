package com.amnesica.kryptey.inputmethod.signalprotocol;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.amnesica.kryptey.inputmethod.signalprotocol.chat.Contact;
import com.amnesica.kryptey.inputmethod.signalprotocol.encoding.EnvelopeCodec;
import com.amnesica.kryptey.inputmethod.signalprotocol.util.ProtocolAddresses;

import org.junit.Before;
import org.junit.Test;
import org.signal.libsignal.protocol.SignalProtocolAddress;

import java.util.ArrayList;

/**
 * The cheapest way past the entire trust model: don't fight the pin, go around it.
 *
 * <p>Every control built so far defends <em>one address</em>. Substituting a key for a pinned
 * contact is refused, recorded, and warned about. But a messenger does not have to do that. It can
 * fabricate a reinstall story — "phone died, here's my new invite" — and attach a bundle at a
 * <b>different</b> address it controls. Adding that is a clean first sighting: no pin to conflict
 * with, no identity change, no warning anywhere. And because the contact list rendered names only,
 * the result was two rows both reading "Alice", indistinguishable to the user.
 *
 * <p>What makes the story credible is that it is <em>true behaviour</em> for a genuine reinstall —
 * a fresh install really does mint a new address (see {@link AddressingPremiseTest}), so a real
 * reinstalled peer really does arrive as a second contact. The app therefore cannot refuse this.
 * It can only make it visible.
 */
public class DuplicateContactNameTest {

  private Account victim;
  private Account realAlice;
  private Account attacker;

  @Before
  public void setUp() throws Exception {
    SignalProtocolMain.testIsRunning = true;
    SignalProtocolMain.initialize(null);
    victim = SignalProtocolMain.getInstance().getAccount();
    SignalProtocolMain.initialize(null);
    realAlice = SignalProtocolMain.getInstance().getAccount();
    SignalProtocolMain.initialize(null);
    attacker = SignalProtocolMain.getInstance().getAccount();
    SignalProtocolMain.getInstance().setAccount(victim);
  }

  private static SignalProtocolAddress addressOf(final Account a) {
    return ProtocolAddresses.of(a.getSignalProtocolAddress().getName(), a.getDeviceId());
  }

  private Contact addAs(final String first, final String last, final Account peer) {
    final Contact c = new Contact(first, last, peer.getSignalProtocolAddress().getName(),
        peer.getDeviceId(), false);
    final ArrayList<Contact> list = victim.getContactList() == null
        ? new ArrayList<>() : new ArrayList<>(victim.getContactList());
    list.add(c);
    victim.setContactList(list);
    return c;
  }

  @Test
  public void aSecondContactWithTheSameNameIsFlagged() {
    addAs("Alice", "Smith", realAlice);

    assertTrue("adding a second Alice at another address must be noticed",
        SignalProtocolMain.hasContactWithSameDisplayName("Alice", "Smith", addressOf(attacker)));
  }

  /** The genuine contact must not flag itself, or every re-add would cry wolf. */
  @Test
  public void aContactDoesNotFlagItself() {
    addAs("Alice", "Smith", realAlice);

    assertFalse("the existing Alice must not count as her own duplicate",
        SignalProtocolMain.hasContactWithSameDisplayName("Alice", "Smith", addressOf(realAlice)));
  }

  @Test
  public void differentNamesAreNotFlagged() {
    addAs("Alice", "Smith", realAlice);

    assertFalse(SignalProtocolMain.hasContactWithSameDisplayName("Bob", "Smith",
        addressOf(attacker)));
    assertFalse(SignalProtocolMain.hasContactWithSameDisplayName("Alice", "Jones",
        addressOf(attacker)));
  }

  @Test
  public void anEmptyContactListFlagsNothing() {
    victim.setContactList(new ArrayList<>());
    assertFalse(SignalProtocolMain.hasContactWithSameDisplayName("Alice", "Smith",
        addressOf(attacker)));
  }

  // ------------------------------------------------------------- address tags

  /**
   * The tag has to actually distinguish the two, or flagging them achieves nothing — the user still
   * cannot tell which row is which.
   */
  @Test
  public void theAddressTagDistinguishesTwoContactsWithOneName() {
    final Contact genuine = addAs("Alice", "Smith", realAlice);
    final Contact impostor = addAs("Alice", "Smith", attacker);

    assertEquals("Alice", genuine.getFirstName());
    assertEquals(genuine.getFirstName(), impostor.getFirstName());
    assertNotEquals("two contacts under one name must not share an address tag",
        genuine.getAddressTag(), impostor.getAddressTag());
  }

  /** Same address, same tag - it identifies the address, so it must be stable for one. */
  @Test
  public void theAddressTagIsStableForOneAddress() {
    final Contact first = new Contact("Alice", "Smith",
        realAlice.getSignalProtocolAddress().getName(), realAlice.getDeviceId(), false);
    final Contact again = new Contact("Alice", "Smith",
        realAlice.getSignalProtocolAddress().getName(), realAlice.getDeviceId(), true);

    assertEquals(first.getAddressTag(), again.getAddressTag());
  }

  /**
   * The tag must survive rejecting and re-pinning a key. It identifies the address, not the
   * identity — if it moved when a key was legitimately replaced it would read as a change when
   * nothing about who the contact is had moved.
   */
  @Test
  public void theAddressTagSurvivesRejectingAndRePinningTheKey() throws Exception {
    final Contact alice = addAs("Alice", "Smith", realAlice);
    final SignalProtocolAddress address = addressOf(realAlice);

    SignalProtocolMain.getInstance().setAccount(attacker);
    final String forged = SignalProtocolMain.exportOwnKeyBundle();
    SignalProtocolMain.getInstance().setAccount(victim);
    SignalProtocolMain.processPreKeyResponseMessage(EnvelopeCodec.fromWire(forged), address);

    final String before = alice.getAddressTag();
    SignalProtocolMain.rejectContactKey(alice);

    SignalProtocolMain.getInstance().setAccount(realAlice);
    final String genuine = SignalProtocolMain.exportOwnKeyBundle();
    SignalProtocolMain.getInstance().setAccount(victim);
    assertTrue(SignalProtocolMain.processPreKeyResponseMessage(
        EnvelopeCodec.fromWire(genuine), address));

    assertEquals("the tag names the address, so re-keying must not move it",
        before, alice.getAddressTag());
  }

  /**
   * The address NAME must be part of the tag too. Device ids fold into [1,127], so two unrelated
   * peers colliding on one is routine - and the impersonation case is precisely a second contact at
   * an address the attacker chose, which they can pick to match. A tag built from the device id
   * alone would render the two rows identical again.
   *
   * <p>The earlier distinguishing test could not catch this: its two accounts had different random
   * device ids, so the tags differed on that alone and a name-blind tag still passed.
   */
  @Test
  public void theTagSeparatesTwoAddressesThatShareADeviceId() {
    final Contact genuine = new Contact("Alice", "Smith",
        realAlice.getSignalProtocolAddress().getName(), 42, false);
    final Contact impostor = new Contact("Alice", "Smith",
        attacker.getSignalProtocolAddress().getName(), 42, false);

    assertEquals("the device ids collide, as they routinely can",
        genuine.getDeviceId(), impostor.getDeviceId());
    assertNotEquals("a colliding device id must not make two peers look like one contact",
        genuine.getAddressTag(), impostor.getAddressTag());
  }

  /** The device id is part of the address, so it has to be part of the tag. */
  @Test
  public void theTagSeparatesTwoDevicesOfOneName() {
    final String name = realAlice.getSignalProtocolAddress().getName();
    final Contact deviceOne = new Contact("Alice", "Smith", name, 11, false);
    final Contact deviceTwo = new Contact("Alice", "Smith", name, 22, false);

    assertNotEquals("same name, different device must be visibly different",
        deviceOne.getAddressTag(), deviceTwo.getAddressTag());
  }

  // ------------------------------------------------- confusable / invisible names

  /**
   * Names that <em>render</em> identically must compare identically.
   *
   * <p>NFKC + trim + lowercase was not enough, and was documented as if it were. NFKC does not fold
   * scripts — Cyrillic А, Greek Α and Latin A are three characters that draw the same glyph and none
   * folds to another — and it does not strip format characters, so a zero-width space or a
   * right-to-left override survives it and renders as nothing.
   *
   * <p>The consequence was worse than a missed toast: the contact-row tag is gated on this same
   * comparison, so one invisible character suppressed the warning <em>and</em> removed the tag from
   * both rows. Two entries reading identically, with nothing anywhere to tell them apart.
   *
   * <p>The user types this text because they copy the name out of the invite — which the attacker
   * wrote.
   */
  @Test
  public void namesThatRenderIdenticallyAreTreatedAsDuplicates() {
    addAs("Alice", "Smith", realAlice);
    final SignalProtocolAddress elsewhere = ProtocolAddresses.of("attacker-uuid", 7);

    final String[] dodges = {
        "\u0410lice",       // Cyrillic А
        "Ali\u0441e",       // Cyrillic с
        "\u0391lice",       // Greek Α
        "Alice\u200B",      // zero-width space
        "Ali\u00ADce",      // soft hyphen
        "\u200EAlice",      // left-to-right mark
        "Alice\u2060",      // word joiner
        "\uFEFFAlice",      // BOM
        "  ALICE  ",         // whitespace + case, which the old folding did catch
        "Alice\u3164",       // HANGUL FILLER - category Lo, not Cf, so a category filter missed it
        "\u115FAlice",       // HANGUL CHOSEONG FILLER
        "Alice\u2800",       // BRAILLE PATTERN BLANK - category So
        "Alice\u2028",       // LINE SEPARATOR
        "AIice",             // capital I for lowercase l - pure ASCII, no Unicode needed at all
        "A1ice",             // digit one for l
        "Ali\u03F2e",        // Greek lunate sigma for c
        "A\u04CFice",        // Cyrillic palochka for l
        "\u0251lice",        // Latin alpha
        "\u13AAlice",        // Cherokee A
        "Alice\uFE0F",       // VARIATION SELECTOR-16 - category Mn, the cheapest dodge of all
        "Al\uFE00ice",       // VARIATION SELECTOR-1
        "Ali\u034Fce",       // COMBINING GRAPHEME JOINER
        "Alice\u180B",       // Mongolian free variation selector
        "Alice\u17B4",       // Khmer inherent vowel, invisible
        "Alice\u1680",       // OGHAM SPACE MARK
    };
    for (final String dodge : dodges) {
      assertTrue("\"" + dodge + "\" dodged the duplicate check",
          SignalProtocolMain.hasContactWithSameDisplayName(dodge, "Smith", elsewhere));
    }
  }

  /** The folding must not be so aggressive that genuinely different names collide. */
  @Test
  public void genuinelyDifferentNamesSurviveTheFolding() {
    addAs("Alice", "Smith", realAlice);
    final SignalProtocolAddress elsewhere = ProtocolAddresses.of("attacker-uuid", 7);

    for (final String[] other : new String[][] {
        {"Alicia", "Smith"}, {"Alice", "Smyth"}, {"Bob", "Smith"}, {"Al", "Smith"}}) {
      assertFalse(other[0] + " " + other[1] + " must not be treated as a duplicate of Alice Smith",
          SignalProtocolMain.hasContactWithSameDisplayName(other[0], other[1], elsewhere));
    }
  }

  // --------------------------------------------------------- one address, one identity

  /**
   * A second contact row at an address already in use is never legitimate, and needs no name
   * trickery to create.
   *
   * <p>An attacker already present as one contact sends a bundle from that same address with a
   * "this is Alice, I reinstalled" story. Same key, so no pin conflict and no identity change; a
   * different name, so the duplicate-name check does not fire; and the address matches the contact
   * being added, so the name loop skips the only row that would have matched. Nothing warned.
   *
   * <p>Unlike the name check this one is exact — it compares addresses, which a peer cannot dodge.
   */
  /**
   * Note the scope of what is tested here. This asserts the <em>detection</em>; the refusal itself
   * lives in {@code E2EEStripView.addContact}, which is an Android view and not reachable from a
   * JVM test, so nothing here proves the contact is not created. A mutation that turns the refusal
   * back into a warn-and-proceed survives this suite, and is recorded rather than papered over.
   */
  @Test
  public void asecondContactAtAnAddressAlreadyInUseIsFlagged() {
    addAs("Bob", "Jones", attacker);
    final SignalProtocolAddress attackerAddress = addressOf(attacker);

    final Contact clash =
        SignalProtocolMain.existingContactAtSameAddress(attackerAddress, "Alice", "Smith");

    assertNotNull("a second name at one address must be flagged", clash);
    assertEquals("Bob", clash.getFirstName());
  }

  /** Re-adding the same contact under the same name is not a clash. */
  @Test
  public void reAddingTheSameContactAtItsOwnAddressIsNotFlagged() {
    addAs("Bob", "Jones", attacker);

    assertNull("the same person under the same name is a re-add, not a second identity",
        SignalProtocolMain.existingContactAtSameAddress(addressOf(attacker), "Bob", "Jones"));
  }

  /** And a genuinely new address is not a clash. */
  @Test
  public void aContactAtAFreshAddressIsNotFlagged() {
    addAs("Bob", "Jones", attacker);

    assertNull(SignalProtocolMain.existingContactAtSameAddress(
        addressOf(realAlice), "Alice", "Smith"));
    assertNull(SignalProtocolMain.existingContactAtSameAddress(null, "Alice", "Smith"));
  }

  // ------------------------------------------------------------------ tag width

  /**
   * The tag must not be <em>predictable</em>. Width was the wrong lever.
   *
   * <p>An earlier version of this test asserted 96 output bits, on the reasoning that 40 were
   * grindable. Both the fix and the test were wrong: the hashed input is an address the peer chooses
   * freely, and the adversary knows the address it wants to collide with, so it could compute the
   * target tag and grind towards it however wide the output was. Matching the leading group took
   * nine seconds on one JVM thread. Widening made it worse, because a longer string is one a user
   * reads less of.
   *
   * <p>What closes it is keying with a per-install secret: there is nothing to aim at, because the
   * attacker cannot compute the victim's tag. That in turn lets the tag be short enough to read
   * end to end, which is the property that actually protects anyone — security here is bounded by
   * the characters a person compares, not the characters emitted.
   */
  @Test
  public void theAddressTagIsKeyedToThisInstallAndShortEnoughToRead() {
    final Contact contact = new Contact("A", "B", "peer-uuid", 7, false);
    final String tag = contact.getAddressTag();

    assertTrue("the tag must be short enough to compare in full, got: " + tag,
        tag.length() <= 16);
    assertTrue("the tag must be hex with separators", tag.matches("#[0-9a-f]{4}-[0-9a-f]{6}"));

    // A different install must produce a different tag for the SAME address - that is what makes it
    // unpredictable to someone who knows the address.
    final String beforeReinstall = tag;
    SignalProtocolMain.initialize(null);
    final String afterReinstall = new Contact("A", "B", "peer-uuid", 7, false).getAddressTag();

    assertNotEquals("the tag must be keyed per install, not a plain hash of the address",
        beforeReinstall, afterReinstall);
  }

  /** Within one install it must be stable, or two rows cannot be compared against each other. */
  @Test
  public void theAddressTagIsStableWithinOneInstall() {
    final Contact first = new Contact("A", "B", "peer-uuid", 7, false);
    final Contact again = new Contact("Other", "Name", "peer-uuid", 7, true);

    assertEquals("the same address must tag the same way regardless of the name on the row",
        first.getAddressTag(), again.getAddressTag());
  }


  /**
   * The bypass that needed no Unicode at all: put the whole name in one field.
   *
   * <p>The comparison was field-pairwise while every render site concatenates, so
   * {@code ("Alice Smith","")} and {@code ("Alice","Smith")} read identically on screen and did not
   * match. Only the first name is mandatory, so this was also a false negative for honest users who
   * happened to fill the fields differently.
   */
  @Test
  public void aNameSplitAcrossFieldsDifferentlyIsStillADuplicate() {
    addAs("Alice", "Smith", realAlice);
    final SignalProtocolAddress elsewhere = ProtocolAddresses.of("attacker-uuid", 7);

    assertTrue("the whole name in the first field must still match",
        SignalProtocolMain.hasContactWithSameDisplayName("Alice Smith", "", elsewhere));
    assertTrue("...and in the last field",
        SignalProtocolMain.hasContactWithSameDisplayName("", "Alice Smith", elsewhere));
    // Note NOT asserted: splitting mid-word ("Ali" + "ce Smith") renders as "Ali ce Smith", which
    // genuinely does not look like "Alice Smith". The join is a faithful model of the rendering, so
    // it only collapses splits that a reader also cannot see.
    assertFalse("a split that changes what is rendered must not be folded together",
        SignalProtocolMain.hasContactWithSameDisplayName("Ali", "ce Smith", elsewhere));
  }

  /**
   * Over-folding is its own failure. Stripping every combining mark collapses Indic and South-East
   * Asian scripts, so unrelated names collide and the warning fires on non-events — which teaches
   * the user to dismiss it.
   */
  @Test
  public void unrelatedIndicNamesDoNotCollide() {
    addAs("\u0930\u0940\u0924\u093E", "", realAlice);  // रीता (Rita)
    final SignalProtocolAddress elsewhere = ProtocolAddresses.of("attacker-uuid", 7);

    assertFalse("रुत must not be treated as a duplicate of रीता",
        SignalProtocolMain.hasContactWithSameDisplayName("\u0930\u0941\u0924", "", elsewhere));
    assertFalse("कविता vs कवीता must not collide",
        SignalProtocolMain.hasContactWithSameDisplayName(
            "\u0915\u0935\u093F\u0924\u093E", "", elsewhere));
  }

  /** Case variants of one name must still match each other - the confusable map must not split them. */
  @Test
  public void ordinaryCaseVariantsStillMatch() {
    addAs("Alice", "Smith", realAlice);
    final SignalProtocolAddress elsewhere = ProtocolAddresses.of("attacker-uuid", 7);

    for (final String[] variant : new String[][] {
        {"ALICE", "SMITH"}, {"alice", "smith"}, {"AliCe", "SmItH"}}) {
      assertTrue(variant[0] + " " + variant[1] + " must match Alice Smith",
          SignalProtocolMain.hasContactWithSameDisplayName(variant[0], variant[1], elsewhere));
    }
  }

  /**
   * An enclosing mark draws a visible ring, so it must NOT be folded away.
   *
   * <p>These were stripped alongside the genuinely invisible characters, which contradicted the
   * filter's own name and quietly folded together two names a reader can plainly tell apart.
   */
  @Test
  public void visibleCombiningMarksAreNotFoldedAway() {
    addAs("Alice", "Smith", realAlice);
    final SignalProtocolAddress elsewhere = ProtocolAddresses.of("attacker-uuid", 7);

    assertFalse("an enclosing ring is visible, so it must not fold onto the bare name",
        SignalProtocolMain.hasContactWithSameDisplayName("A\u20DDlice", "Smith", elsewhere));
  }

  // ------------------------------------------------- names imitating a tag

  /**
   * A name must not be able to counterfeit the tag rendered beside it.
   *
   * <p>The tag shares a text view with the name, at the same size and style, separated by two
   * spaces — and the name is text the attacker writes into the invite for the user to copy. So
   * "add me as: Alice  #abcd-123456" produces a row whose first line reads exactly like a genuine
   * tagged one.
   *
   * <p>The check normalises before looking, because U+FF03 FULLWIDTH NUMBER SIGN and U+FE5F both
   * render as '#' and fold to one under NFKC — a raw {@code indexOf('#')} lets them through.
   */
  @Test
  public void namesThatImitateATagAreRefused() {
    for (final String bad : new String[] {
        "Alice #abcd-123456",
        "Alice \uFF03abcd-123456",   // fullwidth number sign
        "Alice \uFE5F abcd",        // small number sign
        "Alice \u266Fabcd",         // music sharp sign - does not fold, listed explicitly
        "#",
    }) {
      assertTrue("\"" + bad + "\" must be refused as a tag imitation",
          SignalProtocolMain.displayNameImitatesATag(bad));
    }
  }

  /** Ordinary names must not be caught by it. */
  @Test
  public void ordinaryNamesAreNotMistakenForTagImitations() {
    for (final String ok : new String[] {
        "Alice", "Alice Smith", "\u0928\u092E\u0938\u094D\u0924\u0947", "Nikos", "", null}) {
      assertFalse("\"" + ok + "\" must be accepted",
          SignalProtocolMain.displayNameImitatesATag(ok));
    }
  }

  // ------------------------------------------------------ label directionality

  /**
   * The tag must read the same way round whatever the name does to text direction.
   *
   * <p>Stripping bidi control characters stops a {@code U+202E} in the name mirroring the tag. It
   * does nothing for a name whose <em>first strong character</em> is right-to-left: a Hebrew or
   * Arabic name flips the paragraph under first-strong resolution, and a tag appended to it is
   * relocated with its {@code #} migrating across. That is not exotic input — it is every
   * Hebrew-named contact — and one leading RTL letter triggers it deliberately.
   *
   * <p>So the tag is wrapped in a first-strong isolate. These assert the isolate is present and
   * that the tag survives inside it.
   */
  @Test
  public void theTagIsIsolatedFromTheNamesDirectionality() {
    final Contact hebrew = addAs("\u05D0\u05DC\u05D9\u05E1", "\u05E9\u05DE\u05D9\u05EA",
        realAlice);
    final String label = SignalProtocolMain.displayLabelFor(hebrew);

    assertTrue("the tag must be wrapped in a first-strong isolate",
        label.contains("\u2068") && label.contains("\u2069"));
    final int open = label.indexOf('\u2068');
    final int close = label.indexOf('\u2069');
    assertTrue("the isolate must actually enclose the tag",
        label.substring(open, close).contains("#"));
  }

  /** A bidi override typed into the name must not survive into the label at all. */
  @Test
  public void bidiControlsInANameAreStripped() {
    final Contact contact = addAs("\u202EAlice", "Smith", realAlice);
    final String label = SignalProtocolMain.displayLabelFor(contact);

    assertFalse("an override in the name must not reach the label",
        label.contains("\u202E"));
    assertTrue("and the name itself must survive", label.contains("Alice"));
  }

  /** With no contacts loaded there is nothing to disambiguate, so no tag and no isolate. */
  @Test
  public void aLabelWithNoContactsCarriesNoTag() {
    victim.setContactList(new java.util.ArrayList<>());
    final String label = SignalProtocolMain.displayLabelFor(
        new Contact("Alice", "Smith", "peer-uuid", 7, false));

    assertFalse(label.contains("#"));
    assertFalse(label.contains("\u2068"));
  }

  /**
   * The duplicate-name check must be safe before an account is loaded.
   *
   * <p>It is called from the add-contact flow, which the user can reach as soon as the strip is
   * drawn — and {@code setInputView} can run before {@code reloadAccount} completes. With the guard
   * weakened, that window dereferences null on the IME main thread. Every other test here loads an
   * account first, so only the both-present arm ever ran.
   */
  @Test
  public void theDuplicateCheckIsSafeWithNoAccountLoaded() {
    SignalProtocolMain.getInstance().setAccount(null);

    assertFalse("no account means nothing to clash with, not a crash",
        SignalProtocolMain.hasContactWithSameDisplayName("Alice", "Smith",
            ProtocolAddresses.of("peer-uuid", 7)));
    assertNull(SignalProtocolMain.existingContactAtSameAddress(
        ProtocolAddresses.of("peer-uuid", 7), "Alice", "Smith"));
  }

  /** And with an account whose contact list has not been populated. */
  @Test
  public void theDuplicateCheckIsSafeWithNoContactList() {
    victim.setContactList(null);

    assertFalse(SignalProtocolMain.hasContactWithSameDisplayName("Alice", "Smith",
        ProtocolAddresses.of("peer-uuid", 7)));
    assertNull(SignalProtocolMain.existingContactAtSameAddress(
        ProtocolAddresses.of("peer-uuid", 7), "Alice", "Smith"));
  }
}
