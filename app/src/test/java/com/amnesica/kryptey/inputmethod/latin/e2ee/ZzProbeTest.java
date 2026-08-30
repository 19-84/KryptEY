package com.amnesica.kryptey.inputmethod.latin.e2ee;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.view.ContextThemeWrapper;
import android.widget.EditText;
import android.widget.TextView;

import com.amnesica.kryptey.inputmethod.R;
import com.amnesica.kryptey.inputmethod.signalprotocol.Account;
import com.amnesica.kryptey.inputmethod.signalprotocol.MessageEnvelope;
import com.amnesica.kryptey.inputmethod.signalprotocol.SignalProtocolMain;
import com.amnesica.kryptey.inputmethod.signalprotocol.chat.Contact;
import com.amnesica.kryptey.inputmethod.signalprotocol.encoding.EnvelopeCodec;
import com.amnesica.kryptey.inputmethod.signalprotocol.helper.StorageHelper;
import com.amnesica.kryptey.inputmethod.signalprotocol.util.ProtocolAddresses;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.shadows.ShadowToast;
import org.signal.libsignal.protocol.SignalProtocolAddress;

import java.util.ArrayList;

/** TEMPORARY diagnostic probe. Delete before finishing. */
@RunWith(RobolectricTestRunner.class)
public class ZzProbeTest {

  private E2EEStripView strip;
  private Account victim;
  private SignalProtocolAddress peerAddress;
  private String attackerBundle;
  private String attackerBundleAgain;
  private String peerBundleAgain;

  @Before
  public void setUp() throws Exception {
    SignalProtocolMain.resetForTest();
    SignalProtocolMain.testIsRunning = true;

    SignalProtocolMain.initialize(null);
    final Account peer = SignalProtocolMain.getInstance().getAccount();
    peerAddress = ProtocolAddresses.of(peer.getSignalProtocolAddress().getName(),
        peer.getDeviceId());
    final String peerBundle = SignalProtocolMain.exportOwnKeyBundle();
    peerBundleAgain = SignalProtocolMain.exportOwnKeyBundle();

    SignalProtocolMain.initialize(null);
    attackerBundle = SignalProtocolMain.exportOwnKeyBundle();
    attackerBundleAgain = SignalProtocolMain.exportOwnKeyBundle();

    SignalProtocolMain.initialize(null);
    victim = SignalProtocolMain.getInstance().getAccount();
    assertTrue(SignalProtocolMain.processPreKeyResponseMessage(
        EnvelopeCodec.fromWire(peerBundle), peerAddress));

    final Contact genuine = new Contact("Bob", "Jones", peerAddress.getName(),
        peerAddress.getDeviceId(), false);
    final ArrayList<Contact> contacts = new ArrayList<>();
    contacts.add(genuine);
    victim.setContactList(contacts);

    strip = new E2EEStripView(new ContextThemeWrapper(RuntimeEnvironment.getApplication(),
        R.style.KeyboardTheme_LXX_Pure_Day), null);
    strip.setListener(new E2EEStripView.Listener() {
      @Override public void onTextInput(final String rawText) { }
      @Override public void onSensitiveContentVisibilityChanged(final boolean sensitive) { }
    }, strip);
    SignalProtocolMain.setStorageStateForTest(StorageHelper.StorageState.READABLE);
    com.amnesica.kryptey.inputmethod.signalprotocol.storage.TestStores.writesLand();
    ShadowToast.reset();
  }

  @After
  public void tearDown() {
    SignalProtocolMain.resetForTest();
    SignalProtocolMain.testIsRunning = false;
  }

  private String infoText() {
    final TextView view = strip.findViewById(R.id.e2ee_info_text);
    assertNotNull(view);
    return view.getText().toString();
  }

  private void acceptInviteFrom(final String bundle, final String first, final String last,
                                final SignalProtocolAddress address) throws Exception {
    final MessageEnvelope original = EnvelopeCodec.fromWire(bundle);
    final MessageEnvelope relabelled =
        com.amnesica.kryptey.inputmethod.signalprotocol.BundleSigning.asEditedInTransit(
            original, new MessageEnvelope(original.getPreKeyResponse(),
                address.getName(), address.getDeviceId()));
    ((EditText) strip.findViewById(R.id.e2ee_add_contact_first_name_input_field)).setText(first);
    ((EditText) strip.findViewById(R.id.e2ee_add_contact_last_name_input_field)).setText(last);
    strip.addContactForTest(relabelled);
  }

  private Contact rowAt(final SignalProtocolAddress address) {
    for (final Contact candidate : victim.getContactList()) {
      if (String.valueOf(candidate.getSignalProtocolAddress()).equals(String.valueOf(address))) {
        return candidate;
      }
    }
    throw new IllegalStateException("no contact row at " + address);
  }

  private static String show(final String s) {
    if (s == null) return "<null>";
    final StringBuilder out = new StringBuilder();
    for (final char c : s.toCharArray()) {
      if (c < 0x20 || c > 0x7e) out.append("\\u").append(String.format("%04x", (int) c));
      else out.append(c);
    }
    return out.toString();
  }

  private void dump(final String where, final SignalProtocolAddress at) {
    System.out.println("=== PROBE " + where + " ===");
    System.out.println("  address asked about        : " + show(String.valueOf(at)));
    System.out.println("  ProtocolAddresses.key      : " + show(ProtocolAddresses.key(at)));
    System.out.println("  live same name             : "
        + SignalProtocolMain.hasLiveContactWithSameDisplayName("Bob", "Jones", at));
    System.out.println("  hasRetiredDisplayName(excl): "
        + SignalProtocolMain.hasRetiredDisplayName("Bob", "Jones", at));
    System.out.println("  hasRetiredDisplayName(null): "
        + SignalProtocolMain.hasRetiredDisplayName("Bob", "Jones"));
    System.out.println("  hasContactWithSameName     : "
        + SignalProtocolMain.hasContactWithSameDisplayName("Bob", "Jones", at));
    System.out.println("  addressesAlreadyPinning    : ");
    for (final SignalProtocolAddress p : SignalProtocolMain.addressesAlreadyPinningTheSameKey(at)) {
      System.out.println("      -> String.valueOf=" + show(String.valueOf(p))
          + "   key=" + show(ProtocolAddresses.key(p)));
    }
    System.out.println("  adeletedContactOfThisName..: "
        + SignalProtocolMain.adeletedContactOfThisNamePinnedTheSameKey(at, "Bob", "Jones"));
    System.out.println("  retired entries            : ");
    for (final String[] e : victim.getRetiredDisplayNames()) {
      final StringBuilder b = new StringBuilder();
      for (final String x : e) b.append("[").append(show(x)).append("] ");
      System.out.println("      -> len=" + e.length + " " + b);
    }
    System.out.println("  live rows                  : ");
    for (final Contact c : victim.getContactList()) {
      System.out.println("      -> " + c.getFirstName() + " " + c.getLastName() + " @ "
          + show(String.valueOf(c.getSignalProtocolAddress())) + " verified=" + c.isVerified());
    }
    System.out.println("  banner                     : " + infoText());
  }

  /** The reporter's fixture: relay mints a second same-named row, user deletes it, selects D. */
  @Test
  public void probeProductionDeletionPath() throws Exception {
    final SignalProtocolAddress relayed = ProtocolAddresses.of("an-address-the-relay-picked", 1);
    acceptInviteFrom(peerBundleAgain, "Bob", "Jones", relayed);
    assertNotNull("relayed row must pin",
        victim.getSignalProtocolStore().getIdentityKeyStore().getIdentity(relayed));

    dump("after the relayed row is added", peerAddress);

    strip.removeContact(rowAt(relayed));

    dump("after removeContact(relayed), asking about D, UNVERIFIED", peerAddress);

    SignalProtocolMain.verifyContact(rowAt(peerAddress));
    strip.selectContact(rowAt(peerAddress));

    dump("after verifyContact(D) + selectContact(D)", peerAddress);
  }

  /** The same, without verifying: is the retired branch reached, and which wording? */
  @Test
  public void probeProductionDeletionPathUnverified() throws Exception {
    final SignalProtocolAddress relayed = ProtocolAddresses.of("an-address-the-relay-picked", 1);
    acceptInviteFrom(peerBundleAgain, "Bob", "Jones", relayed);

    strip.removeContact(rowAt(relayed));
    strip.selectContact(rowAt(peerAddress));

    dump("after removeContact(relayed) + selectContact(D), no verify", peerAddress);
  }

  /**
   * The existing same-key test's fixture, but retiring with the rendering production writes
   * (ProtocolAddresses.key) instead of String.valueOf.
   */
  @Test
  public void probeRetiredRenderingMismatch() throws Exception {
    final SignalProtocolAddress secondAddress = ProtocolAddresses.of("a-second-address", 1);

    victim.setContactList(new ArrayList<>());
    victim.retireDisplayName("Bob", "Jones", ProtocolAddresses.key(peerAddress));

    acceptInviteFrom(peerBundleAgain, "Bob", "Jones", secondAddress);
    strip.selectContact(rowAt(secondAddress));

    dump("retired written with ProtocolAddresses.key, asking about the NEW row", secondAddress);
  }

  /** Control: the same with String.valueOf, which is what the shipped test uses. */
  @Test
  public void probeRetiredRenderingStringValueOf() throws Exception {
    final SignalProtocolAddress secondAddress = ProtocolAddresses.of("a-second-address", 1);

    victim.setContactList(new ArrayList<>());
    victim.retireDisplayName("Bob", "Jones", String.valueOf(peerAddress));

    acceptInviteFrom(peerBundleAgain, "Bob", "Jones", secondAddress);
    strip.selectContact(rowAt(secondAddress));

    dump("retired written with String.valueOf, asking about the NEW row", secondAddress);
  }
}
