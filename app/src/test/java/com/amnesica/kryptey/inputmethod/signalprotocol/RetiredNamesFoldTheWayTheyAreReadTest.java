package com.amnesica.kryptey.inputmethod.signalprotocol;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.amnesica.kryptey.inputmethod.signalprotocol.util.ProtocolAddresses;

import org.junit.Test;
import org.signal.libsignal.protocol.SignalProtocolAddress;

/**
 * A bound on "distinct names" only means something if the writer and the reader agree what distinct
 * means.
 *
 * <p>The retired-name list is capped at a hundred entries and de-duplicated so the cap counts
 * distinct names rather than repeats. But the writer compared raw strings while the reader —
 * {@code hasRetiredDisplayName}, through {@code displayNamesMatch} — NFKC-folds, strips invisible
 * characters and maps confusables. So "Bob Jones", "Bob Jones " and a Cyrillic-B "Вob Jones"
 * occupied three slots and answered one query.
 *
 * <p>Why that is worth a test rather than a tidy-up: a messenger can drive deletions. Replaying a
 * message makes the decrypt fail, and this app's own advice for a failed decrypt is
 * delete-and-re-invite. Varying the name each cycle meant the dedup never collapsed, so a hundred
 * <em>variants</em> pressed a real entry out of the bound where a hundred repeats could not. The
 * entry pressed out is what stops a later contact reusing a deleted person's name at a new address
 * from being accepted in silence.
 */
public class RetiredNamesFoldTheWayTheyAreReadTest {

  private static SignalProtocolAddress address() {
    return ProtocolAddresses.of("11111111-1111-1111-1111-111111111111", 1);
  }

  private static Account account() {
    return new Account("me", 1, null, null, null, address());
  }

  /** The variants collapse to one slot, because the reader treats them as one name. */
  @Test
  public void variantsOfOneNameOccupyOneSlot() {
    final Account account = account();
    final String key = ProtocolAddresses.key(address());

    account.retireDisplayName("Bob", "Jones", key);
    account.retireDisplayName("Bob ", "Jones", key);
    account.retireDisplayName("Bob", " Jones ", key);

    assertEquals("the reader folds these to one name, so the writer must store them as one entry - "
            + "otherwise a bound on distinct names counts something the app does not distinguish, "
            + "and a hundred variants evict what a hundred repeats could not",
        1, account.getRetiredDisplayNames().size());
  }

  /** And genuinely different names still take their own slots. */
  @Test
  public void differentNamesStillTakeTheirOwnSlots() {
    final Account account = account();
    final String key = ProtocolAddresses.key(address());

    account.retireDisplayName("Bob", "Jones", key);
    account.retireDisplayName("Carol", "Smith", key);

    assertEquals("folding must not merge names that are actually different, or retiring one name "
        + "would silently retract another", 2, account.getRetiredDisplayNames().size());
  }

  /**
   * The same name at a different address is ONE entry, because the reader matches on the name.
   *
   * <p>This test asserted the opposite until a review round showed what that cost. Requiring the
   * address to match as well meant the hundred-entry bound counted (name, address) pairs — and the
   * address is derived from a peer-chosen value, so an attacker mints a fresh one per invite for
   * nothing. Varying it made every delete-and-re-add cycle a distinct entry, and a hundred cycles
   * evicted the genuine retired entry: the one that stops a later contact reusing a deleted
   * person's name at a new address from being accepted in silence. Folding the name closed that
   * attack through the name; this closes it through the field that was still exact.
   *
   * <p>What is given up is in the safe direction. The stored address suppresses the warning for a
   * re-add at that same address while its pin survives, so collapsing to the most recent address
   * means an older one is no longer suppressed — a warning the user did not need, rather than a
   * warning they did.
   */
  @Test
  public void thesameNameAtAdifferentAddressIsOneEntry() {
    final Account account = account();

    account.retireDisplayName("Bob", "Jones", ProtocolAddresses.key(address()));
    account.retireDisplayName("Bob", "Jones",
        ProtocolAddresses.key(ProtocolAddresses.of("22222222-2222-2222-2222-222222222222", 1)));

    assertEquals("the bound has to count what the reader distinguishes, which is names. Counting "
        + "pairs let an attacker who controls the address fill it with one name.",
        1, account.getRetiredDisplayNames().size());
  }

  /** And the fold is the reader's, not a second one that could drift from it. */
  @Test
  public void thewriterUsesThereadersOwnComparison() {
    assertTrue("precondition: the reader must fold these two to the same name, or this whole file "
            + "is describing a fold that does not exist",
        SignalProtocolMain.displayNamesMatch("Bob", "Jones", "Bob ", "Jones"));
  }
}
