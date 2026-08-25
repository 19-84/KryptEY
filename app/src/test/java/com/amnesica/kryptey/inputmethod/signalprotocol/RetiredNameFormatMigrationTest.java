package com.amnesica.kryptey.inputmethod.signalprotocol;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.amnesica.kryptey.inputmethod.signalprotocol.encoding.EnvelopeCodec;
import com.amnesica.kryptey.inputmethod.signalprotocol.util.ProtocolAddresses;

import org.junit.Before;
import org.junit.Test;
import org.signal.libsignal.protocol.SignalProtocolAddress;

import java.util.LinkedList;

/**
 * The retired-name record changed format, and nothing migrates the entries already on disk.
 *
 * <p>{@code retireDisplayName}'s third element used to be the address NAME
 * ({@code contactToRemove.getSignalProtocolAddressName()}); f3e7baf changed the writer to
 * {@code ProtocolAddresses.key(address)} - {@code "name.deviceId"} - and changed the reader to
 * compare against that. Both halves moved together, so a fresh retirement works. What did not move
 * is the entries a user already had: they hold a bare name where the reader now expects a rendered
 * address, so {@code excludedAddress.equals(retired[2])} can never be true for them.
 *
 * <p>The suppression is therefore permanently dead for every contact retired before the upgrade.
 * Its own javadoc says what that costs: a re-add at the SAME address is provably the same identity,
 * "warning there is a false alarm, and it is the commonest firing of this control, because the
 * app's own decryption-failure advice sends users round exactly that loop." The user is shown
 * INFO_RETIRED_CONTACT_NAME, which asserts something untrue of this state - "this new one has a
 * different address, so it is not the same person coming back" - about an address holding the same
 * pin it always held. Habituation is the documented failure mode of this whole control, and a
 * warning that states a checkable falsehood is the fastest way there.
 *
 * <p>Stated honestly: this is a LOW. The reader's pre-existing "entries written before the address
 * was recorded have length 2; treat those as matching nothing in particular" comment shows the
 * project has already accepted warning over an unrecognised entry once. What is new is that the
 * unrecognised entries are now three-element ones written by this same method a version ago, which
 * is a migration rather than a policy, and no test pins the decision either way.
 */
public class RetiredNameFormatMigrationTest {

  private Account victim;
  private SignalProtocolAddress peerAddress;

  @Before
  public void setUp() throws Exception {
    SignalProtocolMain.resetForTest();
    SignalProtocolMain.testIsRunning = true;

    SignalProtocolMain.initialize(null);
    victim = SignalProtocolMain.getInstance().getAccount();
    SignalProtocolMain.initialize(null);
    final Account peer = SignalProtocolMain.getInstance().getAccount();

    peerAddress = ProtocolAddresses.of(peer.getSignalProtocolAddress().getName(),
        peer.getDeviceId());

    SignalProtocolMain.getInstance().setAccount(peer);
    final String peerBundle = SignalProtocolMain.exportOwnKeyBundle();
    SignalProtocolMain.getInstance().setAccount(victim);
    assertTrue("fixture: the peer must pin",
        SignalProtocolMain.processPreKeyResponseMessage(EnvelopeCodec.fromWire(peerBundle),
            peerAddress));
  }

  /**
   * A pre-upgrade retirement at an address that still holds its pin must not warn on re-add.
   *
   * <p>This is the state the suppression exists for, described by its own javadoc, differing from
   * the passing case only in which format the entry on disk happens to be in.
   */
  @Test
  public void apreUpgradeRetirementAtTheSameAddressMustStillSuppressTheWarning() {
    // Exactly what the pre-f3e7baf removeContact wrote: the address NAME, not the rendered address.
    final LinkedList<String[]> retired = new LinkedList<>();
    retired.add(new String[] {"Bob", "Jones", peerAddress.getName()});
    victim.setRetiredDisplayNames(retired);
    // What the first load after the upgrade does with a pre-upgrade entry: re-key it. The reader
    // no longer accepts a bare name, so this is the only thing that makes the entry usable - and
    // the address is identified by the surviving PIN, since deleting the contact took its row.
    com.amnesica.kryptey.inputmethod.signalprotocol.helper.LegacyKeyMigration.apply(victim);

    assertNotNull("fixture: the pin must still be at this address - that is the whole premise",
        victim.getSignalProtocolStore().getIdentityKeyStore().getIdentity(peerAddress));

    assertFalse("a re-add at the address the contact was deleted from, whose pin is still there, "
            + "is warned about because the stored entry is in the previous format",
        SignalProtocolMain.hasRetiredDisplayName("Bob", "Jones", peerAddress));
  }

  /**
   * The same retirement in the CURRENT format is suppressed. Without this the test above could pass
   * for reasons that have nothing to do with the format - a missing pin, a name that does not
   * match - and would assert only that some call returns false.
   */
  @Test
  public void thesameRetirementInTheCurrentFormatIsSuppressed() {
    final LinkedList<String[]> retired = new LinkedList<>();
    retired.add(new String[] {"Bob", "Jones", ProtocolAddresses.key(peerAddress)});
    victim.setRetiredDisplayNames(retired);

    assertFalse("control: the current format must suppress, or the fixture proves nothing",
        SignalProtocolMain.hasRetiredDisplayName("Bob", "Jones", peerAddress));
  }

  /**
   * And a retirement at a DIFFERENT address still warns, in both formats. This is the direction the
   * control must never lose, and it is what makes the assertion above a false-alarm claim rather
   * than a request to weaken the warning.
   */
  @Test
  public void aretirementAtAnotherAddressStillWarns() {
    final LinkedList<String[]> retired = new LinkedList<>();
    retired.add(new String[] {"Bob", "Jones",
        ProtocolAddresses.key(ProtocolAddresses.of("someone-else", 4))});
    victim.setRetiredDisplayNames(retired);

    assertTrue("control: a different address must still warn",
        SignalProtocolMain.hasRetiredDisplayName("Bob", "Jones", peerAddress));
  }
}
