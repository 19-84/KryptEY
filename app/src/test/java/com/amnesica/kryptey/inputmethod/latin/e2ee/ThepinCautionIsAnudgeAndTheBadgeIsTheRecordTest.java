package com.amnesica.kryptey.inputmethod.latin.e2ee;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ListView;
import android.widget.TextView;

import com.amnesica.kryptey.inputmethod.R;
import com.amnesica.kryptey.inputmethod.signalprotocol.Account;
import com.amnesica.kryptey.inputmethod.signalprotocol.SignalProtocolMain;
import com.amnesica.kryptey.inputmethod.signalprotocol.chat.Contact;
import com.amnesica.kryptey.inputmethod.signalprotocol.encoding.EnvelopeCodec;
import com.amnesica.kryptey.inputmethod.signalprotocol.helper.StorageHelper;
import com.amnesica.kryptey.inputmethod.signalprotocol.storage.TestStores;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.util.ArrayList;

/**
 * The pin caution is lost when a second contact is added, and the thing it points at is not.
 *
 * <p>A review round raised the first half: {@code setCautionBesideAnyWarning} replaces
 * {@code mStandingCaution} unconditionally, including when the new caution is about a different
 * contact, and {@code cautionThatAkeyWasPinned} returns immediately once a key is pinned — so it is
 * never re-posted. Two consecutive adds silently lose the first contact's "compare the security
 * number by voice" sentence. That is true, and it is measured below.
 *
 * <p>What makes it a nudge rather than an erasure is the second half, which is also measured: the
 * contact list draws a per-contact verified/unverified control from
 * {@code SignalProtocolMain.isContactKeyTrustworthy}, one per row. It cannot be overwritten by
 * another contact's anything, it survives every repaint and rebuild because it is derived from the
 * account, and the unverified control opens that contact's verify screen directly. The durable
 * answer to "whose number have I not compared" is per-contact and always there; the caution is the
 * prompt to go and look at it now.
 *
 * <p>So this is not fixed by re-deriving the caution on selection. That would put "this key reached
 * you through the messenger" beside every unverified contact on every selection for the life of the
 * install — the cries-wolf failure this project argues against everywhere else — to restore a
 * sentence whose durable content is already on the row.
 */
@RunWith(RobolectricTestRunner.class)
public class ThepinCautionIsAnudgeAndTheBadgeIsTheRecordTest {

  private E2EEStripView strip;
  private Account victim;
  private String firstBundle;
  private String secondBundle;

  @Before
  public void setUp() throws Exception {
    final Application app = RuntimeEnvironment.getApplication();
    SignalProtocolMain.resetForTest();
    SignalProtocolMain.testIsRunning = true;

    SignalProtocolMain.initialize(null);
    firstBundle = SignalProtocolMain.exportOwnKeyBundle();
    SignalProtocolMain.initialize(null);
    secondBundle = SignalProtocolMain.exportOwnKeyBundle();

    SignalProtocolMain.initialize(null);
    victim = SignalProtocolMain.getInstance().getAccount();
    victim.setMessageLogLoader(ArrayList::new);
    TestStores.writesLand();

    strip = new E2EEStripView(
        new ContextThemeWrapper(app, R.style.KeyboardTheme_LXX_Pure_Day), null);
    strip.setListener(new E2EEStripView.Listener() {
      @Override public void onTextInput(final String rawText) { }
      @Override public void onSensitiveContentVisibilityChanged(final boolean sensitive) { }
    }, strip);
    SignalProtocolMain.setStorageStateForTest(StorageHelper.StorageState.READABLE);
  }

  @After
  public void tearDown() {
    SignalProtocolMain.resetForTest();
    SignalProtocolMain.testIsRunning = false;
  }

  private void add(final String first, final String last, final String bundle) throws Exception {
    ((EditText) strip.findViewById(R.id.e2ee_add_contact_first_name_input_field)).setText(first);
    ((EditText) strip.findViewById(R.id.e2ee_add_contact_last_name_input_field)).setText(last);
    strip.addContactForTest(EnvelopeCodec.fromWire(bundle));
  }

  private String banner() {
    return String.valueOf(((TextView) strip.findViewById(R.id.e2ee_info_text)).getText());
  }

  /** The finding, measured: the first contact's caution is replaced and never comes back. */
  @Test
  public void asecondAddReplacesTheFirstContactsPinCaution() throws Exception {
    add("Alice", "Adams", firstBundle);
    assertTrue("precondition: the pin caution must be up for Alice: " + banner(),
        banner().contains("Alice") && banner().contains("compare the security number"));

    add("Bob", "Brown", secondBundle);

    assertTrue("the caution is a single slot and the second add takes it", 
        banner().contains("Bob"));
    assertFalse("Alice's caution is gone, and cautionThatAkeyWasPinned returns early once a key is "
            + "pinned, so nothing re-posts it: " + banner(),
        banner().contains("Alice"));

    // And looking at Alice does not bring hers back, which is what "not recomputable" means here.
    final Contact alice = victim.getContactList().stream()
        .filter(c -> c.getFirstName().equals("Alice")).findFirst().orElse(null);
    assertNotNull(alice);
    strip.selectContact(alice);

    assertFalse("selecting a contact re-derives four warnings and the pin caution is not one of "
            + "them, so Alice's never returns: " + banner(),
        banner().contains("Contact Alice Adams"));
    // What is on screen instead, recorded because it is not obvious and it is deliberate: the
    // caution still names BOB while the recipient line names ALICE. warningWithRecipient composes
    // whatever standing items exist with the current recipient, and the caution carries its own
    // subject in its first three words precisely so the pair can be read apart. Not false, and
    // worth knowing: a caution and a recipient on one banner need not be about the same person.
    assertTrue("the standing caution is still Bob's: " + banner(),
        banner().contains("Contact Bob Brown"));
    assertTrue("...composed above a recipient line naming Alice: " + banner(),
        banner().contains("Sending to: Alice"));
  }

  /**
   * And the durable half, which is why the loss above is a nudge rather than an erasure.
   *
   * <p>One control per row, derived from the account rather than remembered, so no other contact's
   * notice can overwrite it and no repaint or rebuild can lose it.
   */
  @Test
  public void everyUnverifiedContactKeepsItsOwnIndicator() throws Exception {
    add("Alice", "Adams", firstBundle);
    add("Bob", "Brown", secondBundle);
    assertEquals("precondition: two contacts", 2, victim.getContactList().size());

    strip.findViewById(R.id.e2ee_button_select_recipient).performClick();
    final ListView list = strip.findViewById(R.id.e2ee_contact_list);
    assertNotNull("the contact list must be drawn", list.getAdapter());
    assertEquals("both rows", 2, list.getAdapter().getCount());

    for (int row = 0; row < list.getAdapter().getCount(); row++) {
      final View view = list.getAdapter().getView(row, null, list);
      final ImageButton verified =
          view.findViewById(R.id.e2ee_verify_contact_verified_button);
      final ImageButton unverified =
          view.findViewById(R.id.e2ee_verify_contact_unverified_button);
      final Contact contact = (Contact) list.getAdapter().getItem(row);

      assertFalse("precondition: neither key has been compared yet",
          SignalProtocolMain.isContactKeyTrustworthy(contact));
      assertEquals("the unverified control must be showing for " + contact.getFirstName()
              + " - this is the durable answer to whose number has not been compared, one per row, "
              + "and it is what the lost caution was pointing at",
          View.VISIBLE, unverified.getVisibility());
      assertEquals("and the verified one must not be", View.INVISIBLE, verified.getVisibility());
    }
  }

  /** And comparing one number changes only that row, so the indicator is really per-contact. */
  @Test
  public void verifyingOneContactLeavesTheOtherMarkedUnverified() throws Exception {
    add("Alice", "Adams", firstBundle);
    add("Bob", "Brown", secondBundle);

    final Contact alice = victim.getContactList().stream()
        .filter(c -> c.getFirstName().equals("Alice")).findFirst().orElse(null);
    assertNotNull(alice);
    strip.showVerifyContactForTest(alice);
    strip.findViewById(R.id.e2ee_verify_contact_verify_button).performClick();

    assertTrue("the contact whose number was compared must be marked so",
        SignalProtocolMain.isContactKeyTrustworthy(victim.getContactList().stream()
            .filter(c -> c.getFirstName().equals("Alice")).findFirst().orElse(null)));
    assertFalse("and the other must not be - a badge that spread would be far worse than a caution "
            + "that is lost",
        SignalProtocolMain.isContactKeyTrustworthy(victim.getContactList().stream()
            .filter(c -> c.getFirstName().equals("Bob")).findFirst().orElse(null)));
  }
}
