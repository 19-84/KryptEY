package com.amnesica.kryptey.inputmethod.latin.e2ee;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.view.ContextThemeWrapper;
import android.widget.TextView;

import com.amnesica.kryptey.inputmethod.R;
import com.amnesica.kryptey.inputmethod.signalprotocol.Account;
import com.amnesica.kryptey.inputmethod.signalprotocol.SignalProtocolMain;
import com.amnesica.kryptey.inputmethod.signalprotocol.chat.Contact;
import com.amnesica.kryptey.inputmethod.signalprotocol.encoding.EnvelopeCodec;
import com.amnesica.kryptey.inputmethod.signalprotocol.util.ProtocolAddresses;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.signal.libsignal.protocol.SignalProtocolAddress;

import java.util.ArrayList;

/**
 * FLAG_SECURE and the one screen that names a contact without being one of the four.
 *
 * <p>{@code isShowingSensitiveContent()} is the whole of the window's FLAG_SECURE decision, and it
 * is computed from view properties: which wrapper is VISIBLE, and how many characters are in the
 * compose box. Its own javadoc gives the reason the contact list is in the list - "the contact list
 * holds who they talk to and the tags that distinguish them" - and {@code forgetChosenRecipient}
 * spends a paragraph on the same fact about the MAIN view's banner, calling "Chosen contact: Bob
 * #a1b2" surviving an app switch a disclosure worth costing the user a tap to prevent.
 *
 * <p>The predicate does not know that. On the main view it asks only whether the compose box has
 * characters in it, so with the box empty - which is its state for the whole interval between
 * choosing a recipient and typing - the window carries no FLAG_SECURE while the banner names the
 * recipient and prints the address tag that distinguishes them from a second contact of the same
 * name. The identity-change warning is the same shape and lasts longer: it names the contact twice,
 * it is the app's only standing surface, and nothing clears it but a deliberate user action - so it
 * sits on an unprotected window for as long as the user leaves it there.
 *
 * <p>The fix is not to read the banner - that is the substrate this round exists to get decisions
 * off. It is that {@code chosenContact} is a fact the model already owns, and every banner on the
 * main view that names a contact is written on a path where it is set.
 */
@RunWith(RobolectricTestRunner.class)
public class SecureWindowCoversTheChosenContactTest {

  private E2EEStripView strip;
  private Account victim;
  private Boolean lastSensitive;

  @Before
  public void setUp() throws Exception {
    SignalProtocolMain.resetForTest();
    SignalProtocolMain.testIsRunning = true;

    SignalProtocolMain.initialize(null);
    final Account peer = SignalProtocolMain.getInstance().getAccount();
    final SignalProtocolAddress peerAddress = ProtocolAddresses.of(
        peer.getSignalProtocolAddress().getName(), peer.getDeviceId());
    final String peerBundle = SignalProtocolMain.exportOwnKeyBundle();

    SignalProtocolMain.initialize(null);
    victim = SignalProtocolMain.getInstance().getAccount();
    assertTrue(SignalProtocolMain.processPreKeyResponseMessage(
        EnvelopeCodec.fromWire(peerBundle), peerAddress));

    final ArrayList<Contact> contacts = new ArrayList<>();
    contacts.add(new Contact("Bob", "Jones", peerAddress.getName(), peerAddress.getDeviceId(),
        false));
    victim.setContactList(contacts);

    strip = new E2EEStripView(new ContextThemeWrapper(RuntimeEnvironment.getApplication(),
        R.style.KeyboardTheme_LXX_Pure_Day), null);
    strip.setListener(new E2EEStripView.Listener() {
      @Override public void onTextInput(final String rawText) { }
      @Override public void onSensitiveContentVisibilityChanged(final boolean sensitive) {
        lastSensitive = sensitive;
      }
    }, strip);
  }

  @After
  public void tearDown() {
    SignalProtocolMain.resetForTest();
    SignalProtocolMain.testIsRunning = false;
  }

  private Contact bob() {
    return victim.getContactList().get(0);
  }

  private String banner() {
    final TextView view = strip.findViewById(R.id.e2ee_info_text);
    assertNotNull(view);
    return view.getText().toString();
  }

  /** The premise: choosing a contact really does print their name and tag on the main view. */
  @Test
  public void thepremise() {
    strip.selectContact(bob());

    assertTrue("precondition: the banner must name the chosen contact. Shown: " + banner(),
        banner().contains("Bob"));
    assertTrue("precondition: and carry the tag that tells two Bobs apart. Shown: " + banner(),
        banner().contains("#"));
    assertTrue("precondition: the compose box is empty, which is its state until the user types",
        ((android.widget.EditText) strip.findViewById(R.id.e2ee_input_field))
            .getText().length() == 0);
  }

  /**
   * Choosing a recipient must put the window behind FLAG_SECURE.
   *
   * <p>Driven through {@code selectContact}, which is what a contact row's click listener calls.
   * The listener is the IME's route to the window flag, so what it is told is the decision.
   */
  @Test
  public void thewindowMustBeSecureWhileTheBannerNamesTheRecipient() {
    strip.selectContact(bob());

    assertNotNull("the listener must have been told something at all", lastSensitive);
    assertTrue("the window is screenshot- and recording-visible while the strip prints who the "
            + "user talks to and the tag that identifies them - the same fact the contact list is "
            + "covered for. Banner: " + banner(),
        lastSensitive);
  }

}
