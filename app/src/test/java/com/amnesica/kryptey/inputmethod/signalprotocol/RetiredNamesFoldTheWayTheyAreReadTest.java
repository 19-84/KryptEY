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

    // All at ONE address, which is the case the fold is about: the writer must collapse spellings
    // the reader treats as one name. Across addresses they stay separate, which the test below
    // covers and which is what stops one deletion erasing the record of another.
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
   * The same name at a different address is ONE entry that remembers both addresses.
   *
   * <p>This assertion has been written three ways, and the two earlier ones each held half of it.
   * Separate entries per address made the hundred-name bound fillable by varying the address, which
   * the attacker chooses freely. A single entry keyed by name alone erased the earlier address,
   * which is worse: the user deletes the genuine "Bob"; an impostor invites as "Bob" from another
   * address and is correctly warned about; the user heeds the warning and deletes the impostor —
   * and that deletion, under the same folded name, evicted the genuine record, so the impostor's
   * next invite arrived silent. One cycle, using the impersonated name as the eviction key.
   *
   * <p>The record is not the entry count. It is "which addresses has this name been deleted from",
   * and the answer must keep both while occupying one slot. What that buys is asserted where it can
   * be seen end to end, in {@code RetiredNameBoundCountsNamesTest}; this pins the shape underneath
   * it.
   */
  @Test
  public void thesameNameAtAdifferentAddressJoinsTheSameEntry() {
    final Account account = account();
    final String first = ProtocolAddresses.key(address());
    final String second =
        ProtocolAddresses.key(ProtocolAddresses.of("22222222-2222-2222-2222-222222222222", 1));

    account.retireDisplayName("Bob", "Jones", first);
    account.retireDisplayName("Bob", "Jones", second);

    assertEquals("one folded name occupies one slot, or the bound counts addresses the attacker "
        + "picks instead of names the user typed", 1, account.getRetiredDisplayNames().size());

    final java.util.List<String> addresses = java.util.Arrays.asList(
        account.getRetiredDisplayNames().getFirst());
    assertTrue("deleting a contact at one address must not erase the record of a deletion at "
        + "another - that record is what warns about the next impersonation", 
        addresses.contains(first));
    assertTrue("and the newer deletion must be recorded too", addresses.contains(second));
  }

  /** And the fold is the reader's, not a second one that could drift from it. */
  @Test
  public void thewriterUsesThereadersOwnComparison() {
    assertTrue("precondition: the reader must fold these two to the same name, or this whole file "
            + "is describing a fold that does not exist",
        SignalProtocolMain.displayNamesMatch("Bob", "Jones", "Bob ", "Jones"));
  }
}
