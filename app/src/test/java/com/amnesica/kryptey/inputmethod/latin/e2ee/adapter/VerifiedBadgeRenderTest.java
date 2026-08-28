package com.amnesica.kryptey.inputmethod.latin.e2ee.adapter;

import com.amnesica.kryptey.inputmethod.signalprotocol.storage.TestStores;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.view.ContextThemeWrapper;
import android.view.View;
import android.widget.ImageButton;
import android.widget.TextView;

import com.amnesica.kryptey.inputmethod.R;
import com.amnesica.kryptey.inputmethod.signalprotocol.Account;
import com.amnesica.kryptey.inputmethod.signalprotocol.SignalProtocolMain;
import com.amnesica.kryptey.inputmethod.signalprotocol.chat.Contact;
import com.amnesica.kryptey.inputmethod.signalprotocol.encoding.EnvelopeCodec;
import com.amnesica.kryptey.inputmethod.signalprotocol.util.ProtocolAddresses;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.signal.libsignal.protocol.SignalProtocolAddress;

import java.util.ArrayList;

/**
 * The place where the trust model becomes a green tick.
 *
 * <p>Everything Phase 4 does — refusing a displaced key, recording the change, keeping the pin
 * through a deletion, dropping the badge when a different key is offered — reaches the user as one
 * of two icons on a contact row. {@code isContactKeyTrustworthy} is tested hard as a function. The
 * line that turns its answer into an icon was tested by nothing: <b>inverting that condition, so
 * every untrusted contact renders as verified and every verified one as unverified, passed the
 * entire suite. So did replacing it with {@code true}.</b> Both measured, before this file existed.
 *
 * <p>Same shape as the gap that let seven mutations survive in {@code E2EEStripView}: the decision
 * is covered, the rendering of the decision is not. It matters more here, because a user who has
 * been told to compare safety numbers is being asked to act on this icon and nothing else.
 */
@RunWith(RobolectricTestRunner.class)
public class VerifiedBadgeRenderTest {

  private Account me;
  private SignalProtocolAddress peerAddress;
  private String attackerBundle;

  @Before
  public void setUp() throws Exception {
    SignalProtocolMain.testIsRunning = true;

    SignalProtocolMain.initialize(null);
    final Account attacker = SignalProtocolMain.getInstance().getAccount();
    attackerBundle = SignalProtocolMain.exportOwnKeyBundle();

    SignalProtocolMain.initialize(null);
    me = SignalProtocolMain.getInstance().getAccount();
    SignalProtocolMain.initialize(null);
    final Account peer = SignalProtocolMain.getInstance().getAccount();

    peerAddress = ProtocolAddresses.of(peer.getSignalProtocolAddress().getName(),
        peer.getDeviceId());

    SignalProtocolMain.getInstance().setAccount(peer);
    final String peerBundle = SignalProtocolMain.exportOwnKeyBundle();
    SignalProtocolMain.getInstance().setAccount(me);
    assertTrue(SignalProtocolMain.processPreKeyResponseMessage(
        EnvelopeCodec.fromWire(peerBundle), peerAddress));
      // This fixture depends on writes landing; see TestStores.
    TestStores.writesLand();
}

  /** A contact in the account's live list, which is what the adapter always renders from. */
  private Contact storedContact() {
    final Contact contact = new Contact("Bob", "Jones", peerAddress.getName(),
        peerAddress.getDeviceId(), false);
    final ArrayList<Contact> contacts = new ArrayList<>();
    contacts.add(contact);
    me.setContactList(contacts);
    return contact;
  }

  private Contact live() {
    return me.getContactList().get(0);
  }

  /** Renders one row through the real adapter. */
  private View renderRow(final Contact contact) {
    final ArrayList<Object> items = new ArrayList<>();
    items.add(contact);
    final ListAdapterContacts adapter = new ListAdapterContacts(
        new ContextThemeWrapper(RuntimeEnvironment.getApplication(),
            R.style.KeyboardTheme_LXX_Pure_Day),
        R.layout.e2ee_contact_list_element_view, items);

    final View row = adapter.getView(0, null, null);
    assertNotNull("the adapter must render a row", row);
    return row;
  }

  private ImageButton verifiedBadge(final View row) {
    final ImageButton badge = row.findViewById(R.id.e2ee_verify_contact_verified_button);
    assertNotNull(badge);
    return badge;
  }

  private ImageButton unverifiedBadge(final View row) {
    final ImageButton badge = row.findViewById(R.id.e2ee_verify_contact_unverified_button);
    assertNotNull(badge);
    return badge;
  }

  private int verifiedVisibility(final View row) {
    return verifiedBadge(row).getVisibility();
  }

  private int unverifiedVisibility(final View row) {
    return unverifiedBadge(row).getVisibility();
  }

  /**
   * A contact the user has compared renders as verified, and one they have not does not.
   *
   * <p>Both directions in one test on purpose: a mutation showing the verified icon unconditionally
   * is caught by the first half, one showing the unverified icon unconditionally by the second.
   * Either alone leaves half of an inversion alive.
   */
  @Test
  public void thebadgeRendersTheTrustDecisionInBothDirections() throws Exception {
    final Contact contact = storedContact();
    assertFalse("precondition: pinned but not compared is not trustworthy",
        SignalProtocolMain.isContactKeyTrustworthy(contact));

    View row = renderRow(contact);
    assertEquals("an unverified contact must show the unverified icon",
        View.VISIBLE, unverifiedVisibility(row));
    assertEquals("and must NOT show the verified one - this is the icon the user acts on",
        View.INVISIBLE, verifiedVisibility(row));

    SignalProtocolMain.verifyContact(contact);
    assertTrue("precondition: comparing must make it trustworthy",
        SignalProtocolMain.isContactKeyTrustworthy(live()));

    row = renderRow(live());
    assertEquals("a compared contact must show the verified icon",
        View.VISIBLE, verifiedVisibility(row));
    assertEquals("and not also the unverified one", View.INVISIBLE, unverifiedVisibility(row));
  }

  /**
   * A substitution must take the tick off the row, not merely out of the store.
   *
   * <p>"A verified badge is dropped as soon as a different key is offered" is one of Phase 4's
   * stated invariants. It was asserted against the function and never against the screen, which is
   * the only place the user can read it.
   */
  @Test
  public void asubstitutedKeyTakesTheTickOffTheRow() throws Exception {
    final Contact contact = storedContact();
    SignalProtocolMain.verifyContact(contact);
    assertEquals("precondition: the row must start out ticked",
        View.VISIBLE, verifiedVisibility(renderRow(live())));

    SignalProtocolMain.importOutOfBandKeyBundle(attackerBundle, peerAddress);

    final View row = renderRow(live());
    assertEquals("someone offering a different key must take the tick off the row",
        View.INVISIBLE, verifiedVisibility(row));
    assertEquals(View.VISIBLE, unverifiedVisibility(row));
  }

  /**
   * A standing rejection must take the tick off the row too.
   *
   * <p>The store makes a rejection outrank a verified badge. If the row went on rendering the tick,
   * that invariant would hold everywhere except the one surface it exists for.
   */
  @Test
  public void arejectedKeyTakesTheTickOffTheRow() throws Exception {
    final Contact contact = storedContact();
    SignalProtocolMain.verifyContact(contact);
    assertEquals(View.VISIBLE, verifiedVisibility(renderRow(live())));

    SignalProtocolMain.rejectContactKey(live());

    assertEquals("after the user says the number did not match, the row must not still be ticked",
        View.INVISIBLE, verifiedVisibility(renderRow(live())));
  }

  /**
   * The address tag renders from the first contact.
   *
   * <p>If it appeared only once two contacts clashed, the tag would arrive at the moment an impostor
   * does — and it would appear on the genuine long-standing row, inverting the cue.
   */
  @Test
  public void theaddressTagRendersFromTheFirstContact() {
    final Contact contact = storedContact();
    final TextView tag = renderRow(contact).findViewById(R.id.e2ee_contact_address_tag_element);

    assertNotNull("the tag must have its own view, or a long name squeezes it out", tag);
    assertFalse("precondition: the tag must not be empty, or this asserts nothing",
        contact.getAddressTag().isEmpty());
    assertEquals("the tag must render from the first contact, not the second",
        contact.getAddressTag(), tag.getText().toString());
  }

  /**
   * The badge that is showing is the one wired to the verify screen.
   *
   * <p>The two arms of the render differ by exactly one line each: which button gets the click
   * listener. Swapping only those two lines leaves the visible badge inert and binds the invisible
   * one instead, so a user who sees an unverified contact and taps it gets nothing - no way to
   * reach the safety number at all, which is the one action the whole trust model asks of them.
   * Every existing test passed with that mutant in place, because they all assert visibility.
   *
   * <p>Found by a reviewer who introduced the mutant, saw the suite stay green, and reported it as
   * a coverage gap rather than a defect. It was not a defect; it is one line away from being one.
   */
  @Test
  public void thevisibleBadgeIsTheOneWiredToTheVerifyScreen() throws Exception {
    final Contact contact = storedContact();

    // Untrusted: the unverified badge is showing, so that is the one that must respond.
    View row = renderRow(contact);
    assertTrue("the visible unverified badge must be clickable",
        clickListenerOf(unverifiedBadge(row)) != null);
    assertFalse("and the hidden verified badge must not be the one carrying the listener",
        clickListenerOf(verifiedBadge(row)) != null);

    // Trusted: the other way round.
    SignalProtocolMain.verifyContact(contact);
    row = renderRow(live());
    assertTrue("the visible verified badge must be clickable",
        clickListenerOf(verifiedBadge(row)) != null);
    assertFalse("and the hidden unverified badge must not be",
        clickListenerOf(unverifiedBadge(row)) != null);
  }

  /** {@code View.hasOnClickListener()} is not in the compile SDK; the shadow exposes it. */
  private static View.OnClickListener clickListenerOf(final View view) {
    return org.robolectric.Shadows.shadowOf(view).getOnClickListener();
  }
}
