package com.amnesica.kryptey.inputmethod.latin.e2ee;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.view.ContextThemeWrapper;
import android.view.View;
import android.widget.ListView;

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
 * No E2EE screen may still be up when the keyboard next rises in another app.
 *
 * <p>The IME view is not recreated when the user switches apps. That is the crux of this whole
 * class of bug, and it is already understood here: {@code LatinIME.onWindowHidden} calls {@code
 * clearDecryptedContent()} precisely because "a decrypted message came back on screen the next time
 * the keyboard rose - in whatever app that was".
 *
 * <p>But that method leaves the screen only when the <em>messages list</em> is the one showing. The
 * strip has six screens and three of them render things the messenger must not be handed: the
 * contact list is the user's entire set of correspondents, name and address tag; the verify screen
 * names one contact and shows the safety number the user is being asked to trust; the add-contact
 * screen holds a name mid-typing. Open any of those, switch to the messenger, and the keyboard comes
 * back up in the messenger still showing it.
 *
 * <p>{@code FLAG_SECURE} does not help. It stops a screenshot; the exposure here is that the content
 * is simply still rendered, and the person the threat model is about may be standing next to the
 * user - or may be the app that now has the keyboard over it, prompting for a screenshot the user
 * takes themselves.
 */
@RunWith(RobolectricTestRunner.class)
public class ScreenLifetimeTest {

  private E2EEStripView strip;
  private Account victim;
  private SignalProtocolAddress peerAddress;

  @Before
  public void setUp() throws Exception {
    SignalProtocolMain.resetForTest();
    SignalProtocolMain.testIsRunning = true;

    SignalProtocolMain.initialize(null);
    final Account peer = SignalProtocolMain.getInstance().getAccount();
    peerAddress = ProtocolAddresses.of(peer.getSignalProtocolAddress().getName(),
        peer.getDeviceId());
    final String peerBundle = SignalProtocolMain.exportOwnKeyBundle();

    SignalProtocolMain.initialize(null);
    victim = SignalProtocolMain.getInstance().getAccount();
    assertTrue("precondition: the peer's key must be pinned so a number exists to show",
        SignalProtocolMain.processPreKeyResponseMessage(
            EnvelopeCodec.fromWire(peerBundle), peerAddress));

    final Contact contact = new Contact("Bob", "Jones", peerAddress.getName(),
        peerAddress.getDeviceId(), false);
    final ArrayList<Contact> contacts = new ArrayList<>();
    contacts.add(contact);
    victim.setContactList(contacts);

    strip = new E2EEStripView(new ContextThemeWrapper(RuntimeEnvironment.getApplication(),
        R.style.KeyboardTheme_LXX_Pure_Day), null);
  }

  private Contact bob() {
    return victim.getContactList().get(0);
  }

  private View wrapper(final int id) {
    final View view = strip.findViewById(id);
    assertNotNull(view);
    return view;
  }

  /** What {@code LatinIME.onWindowHidden} does when the user leaves for another app. */
  private void switchAway() {
    // The strip's whole dismissal path, not a copy of the two calls it happened to make when this
    // test was written - it had already gone stale once.
    strip.onKeyboardHidden();
  }

  /**
   * The contact list must not still be up in the next app.
   *
   * <p>Driven through the real recipient button, so the adapter is populated exactly as a user
   * would populate it rather than by poking visibilities.
   */
  @Test
  public void thecontactListDoesNotSurviveTheAppSwitch() {
    strip.findViewById(R.id.e2ee_button_select_recipient).performClick();

    final View list = wrapper(R.id.e2ee_contact_list_wrapper);
    assertEquals("precondition: the contact list must be on screen",
        View.VISIBLE, list.getVisibility());
    final ListView listView = strip.findViewById(R.id.e2ee_contact_list);
    assertNotNull("precondition: the list must be populated, or this proves nothing",
        listView.getAdapter());
    assertTrue("precondition: Bob must be listed", listView.getAdapter().getCount() > 0);

    switchAway();

    assertEquals("the user's contact list must not still be rendered in whatever app the keyboard"
        + " rises in next", View.GONE, list.getVisibility());
  }

  /**
   * Nor must the verify screen, which names one contact and shows their safety number.
   *
   * <p>Worse than the list in one respect: the number on screen is the thing the user is being
   * asked to trust, and it stays there under a banner naming the contact, in an app that had
   * nothing to do with it.
   */
  @Test
  public void theverifyScreenDoesNotSurviveTheAppSwitch() {
    strip.verifyContact(bob());

    final View verify = wrapper(R.id.e2ee_verify_contact_wrapper);
    assertEquals("precondition: the verify screen must be on screen",
        View.VISIBLE, verify.getVisibility());

    switchAway();

    assertEquals("a contact's name and safety number must not still be rendered in the next app",
        View.GONE, verify.getVisibility());
  }
}
