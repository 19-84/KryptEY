package com.amnesica.kryptey.inputmethod;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.view.ContextThemeWrapper;
import android.widget.EditText;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.amnesica.kryptey.inputmethod.latin.e2ee.E2EEStripView;
import com.amnesica.kryptey.inputmethod.signalprotocol.Account;
import com.amnesica.kryptey.inputmethod.signalprotocol.SignalProtocolMain;
import com.amnesica.kryptey.inputmethod.signalprotocol.chat.Contact;
import com.amnesica.kryptey.inputmethod.signalprotocol.encoding.EnvelopeCodec;
import com.amnesica.kryptey.inputmethod.signalprotocol.helper.StorageHelper;
import com.amnesica.kryptey.inputmethod.signalprotocol.util.ProtocolAddresses;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.signal.libsignal.protocol.SignalProtocolAddress;

import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The strip's own UI, driven on a real device: type a message, press Encrypt, read it back.
 *
 * <p>Everything that exercises the strip today runs under Robolectric, which is the right place for
 * its logic and cannot speak for its behaviour on a phone: the views are shadows, the layout is
 * never measured, and libsignal is the desktop build. The instrumentation suite, meanwhile, covers
 * the Keystore, the IME binding and the protocol — but has never touched the strip.
 *
 * <p>So this is the first test that puts the two halves together: a real inflated strip, real
 * Android views, the real Android libsignal, and the button a user actually presses. What it
 * asserts is the app's whole purpose in one line — what leaves the keyboard is not the message, and
 * the intended recipient gets the message back.
 *
 * <p>It also pins the routing, which is the half a round of review found missing: the strip
 * redirects typing into its own compose box, so the send path must aim the connection back at the
 * host before committing. Get that wrong and the ciphertext returns to the compose box and is
 * erased - the message is never sent, and every content assertion still passes.
 *
 * <p><b>Deliberately NOT a substitute for driving the keyboard through a real messenger.</b> The
 * message is placed with {@code setText} rather than typed, so the strip's own text-ENTRY path is
 * not exercised here - only its send path. Nothing here touches the IME's key surface or moves text
 * through another app. Saying so is more useful than implying this closes it.
 */
@RunWith(AndroidJUnit4.class)
public class StripRoundTripOnDeviceTest {

  private static final String MESSAGE = "the meeting moved to nine";
  private static final String REPLY = "understood, see you at nine";

  private Context context;
  private E2EEStripView strip;
  private Account alice;
  private Account bob;
  private SignalProtocolAddress bobAddress;
  private final AtomicReference<String> committed = new AtomicReference<>();

  /** The host application's field — what the messenger would end up holding. */
  private android.view.inputmethod.BaseInputConnection host;

  /** The connection the strip writes through, and the thing that decides WHERE a commit lands. */
  private com.amnesica.kryptey.inputmethod.latin.RichInputConnection richIC;

  private static SignalProtocolAddress addressOf(final Account account) {
    return ProtocolAddresses.of(account.getSignalProtocolAddress().getName(),
        account.getDeviceId());
  }

  @Before
  public void setUp() throws Exception {
    context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    SignalProtocolMain.resetForTest();
    SignalProtocolMain.testIsRunning = true;

    // Bob first, so his bundle exists for Alice to accept.
    SignalProtocolMain.initialize(null);
    bob = SignalProtocolMain.getInstance().getAccount();
    bobAddress = addressOf(bob);
    final String bobBundle = SignalProtocolMain.exportOwnKeyBundle();

    SignalProtocolMain.initialize(null);
    alice = SignalProtocolMain.getInstance().getAccount();
    assertTrue("Alice must be able to accept Bob's invite on this device",
        SignalProtocolMain.processPreKeyResponseMessage(
            EnvelopeCodec.fromWire(bobBundle), bobAddress));

    final ArrayList<Contact> contacts = new ArrayList<>();
    contacts.add(new Contact("Bob", "Jones", bobAddress.getName(), bobAddress.getDeviceId(),
        false));
    alice.setContactList(contacts);

    InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
      strip = new E2EEStripView(
          new ContextThemeWrapper(context, R.style.KeyboardTheme_LXX_Pure_Day), null);

      // The strip writes through a RichInputConnection, and sendEncryptedMessageToApplication
      // dereferences it. Without one the Encrypt button NPEs - which is what the first run of this
      // test found, and is a fair thing for a test of the send path to have to provide.
      host = new android.view.inputmethod.BaseInputConnection(new android.view.View(context), true);
      richIC = new com.amnesica.kryptey.inputmethod.latin.RichInputConnection(
          new android.inputmethodservice.InputMethodService() {
            @Override
            public android.view.inputmethod.InputConnection getCurrentInputConnection() {
              return host;
            }
          });
      strip.setRichInputConnection(richIC);

      strip.setListener(new E2EEStripView.Listener() {
        @Override
        public void onTextInput(final String rawText) {
          committed.set(rawText);   // the CONTENT handed over
          // And where it goes. In production the listener is LatinIME, which wraps rawText in an
          // Event and hands it to InputLogic, which calls mConnection.commitText(text, 1) - so this
          // one line is the rest of the real path, and it is what makes the routing assertion below
          // possible. Without it the test could only ever check what was handed over, never where
          // it landed, and deleting the strip's own setShouldUseOtherIC(false) would leave the test
          // green while the ciphertext went back into the compose box and was erased.
          richIC.commitText(rawText, 1);
        }

        @Override
        public void onSensitiveContentVisibilityChanged(final boolean sensitive) { }
      }, strip);
    });
    SignalProtocolMain.setStorageStateForTest(StorageHelper.StorageState.READABLE);
  }

  @After
  public void tearDown() {
    // clear() is what releases the process-wide clipboard listener the constructor registered.
    // Without it a discarded strip stays attached to the ClipboardManager for the rest of the
    // instrumentation run, holding Alice's account and her plaintext log alive - which is the exact
    // retention clear()'s own javadoc was written to fix, left behind by the one test whose subject
    // is that object's lifecycle.
    if (strip != null) {
      InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> strip.clear());
    }
    SignalProtocolMain.resetForTest();
    SignalProtocolMain.testIsRunning = false;
  }

  /** Type, press Encrypt, and check what left the keyboard — then that Bob can read it. */
  @Test
  public void amessageTypedIntoTheStripLeavesAsCiphertextAndComesBack() throws Exception {
    // Nothing below asserts inside runOnMainSync. Instrumentation's SyncRunnable has no try/finally
    // around the runnable, so a throw on the main thread never sets mComplete: the AssertionError
    // reaches the Looper as an uncaught exception and the default handler kills the instrumentation
    // process. A red test would then present as a crashed run with every later class lost - worst
    // exactly when the assertion is doing its job, e.g. if the layout stopped inflating.
    final AtomicReference<EditText> composeRef = new AtomicReference<>();
    InstrumentationRegistry.getInstrumentation().runOnMainSync(
        () -> composeRef.set(strip.findViewById(R.id.e2ee_input_field)));
    final EditText compose = composeRef.get();
    assertNotNull("the compose field must inflate on a real device", compose);

    InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
      strip.selectContact(alice.getContactList().get(0));
      compose.setText(MESSAGE);
      // Put the connection into the state production is in at this moment, which the first version
      // of this test did not. The user can only have got text into the compose box THROUGH the
      // redirect, so by the time Encrypt is pressed the connection is pointed at the strip's own
      // box - and the send path's setShouldUseOtherIC(false) is what aims it back at the host.
      // Setting the text directly skips the focus listener that normally raises this (an unattached
      // view cannot take focus at all), so the flag was down, the send path's reset was a no-op,
      // and the whole run exercised the OPPOSITE routing state from the one users are in.
      richIC.setOtherIC(compose);
      richIC.setShouldUseOtherIC(true);
      strip.findViewById(R.id.e2ee_button_encrypt).performClick();
    });
    InstrumentationRegistry.getInstrumentation().waitForIdleSync();

    final String wire = committed.get();
    assertNotNull("pressing Encrypt must hand something to the messenger", wire);
    assertFalse("what leaves the keyboard must not be the message itself", wire.contains(MESSAGE));

    // WHERE it went, not just what it was. The redirect was up when Encrypt was pressed, so this
    // passes only if the send path aimed the connection back at the host before committing. If it
    // did not, the ciphertext lands in the strip's own compose box, clearUserInputString() erases
    // it moments later, and the user's message is silently never sent - a failure the content
    // assertions above cannot see, because the content handed over is identical either way.
    final AtomicReference<String> hostHolder = new AtomicReference<>();
    InstrumentationRegistry.getInstrumentation().runOnMainSync(
        () -> hostHolder.set(String.valueOf(host.getEditable())));
    assertEquals("the ciphertext must reach the HOST application's field. The strip redirects "
            + "typing into its own compose box, and the send path has to aim the connection back "
            + "at the host before it commits; if that reset is lost the message goes nowhere and "
            + "nothing else in this test notices.", wire, hostHolder.get());

    // And the intended recipient gets it back.
    SignalProtocolMain.getInstance().setAccount(bob);
    assertEquals("Bob must be able to read what Alice's strip produced, on this device",
        MESSAGE, SignalProtocolMain.decryptMessage(
            EnvelopeCodec.fromWire(wire.trim()), addressOf(alice)));
  }

  /**
   * And the other direction, which had no device coverage at all: paste, press Decrypt, read it.
   *
   * <p>The test above drives the send path. Receiving is the half a user does more often, and every
   * test of it ran under Robolectric — shadow clipboard, shadow views, desktop libsignal. Here the
   * real {@link android.content.ClipboardManager} carries the wire text, the real button is pressed,
   * and the plaintext has to appear in the real compose box.
   *
   * <p>The message is Bob's, encrypted with the Android build of libsignal, so this also exercises
   * the receiving half of the session established in {@code setUp} rather than a fixture string.
   */
  @Test
  public void amessageFromBobIsDecryptedIntoTheComposeBoxOnDevice() throws Exception {
    // setUp only builds Alice's side of the session: she accepted Bob's invite, so she can send to
    // him, but he has nothing for her yet. Bob accepts hers first, which is what a real second
    // party does before replying.
    final String aliceBundle = SignalProtocolMain.exportOwnKeyBundle();

    // Bob writes to Alice. Done as Bob, then the account is switched back, which is the same shape
    // the send test uses for its round trip.
    SignalProtocolMain.getInstance().setAccount(bob);
    assertTrue("Bob must be able to accept Alice's invite on this device",
        SignalProtocolMain.processPreKeyResponseMessage(
            EnvelopeCodec.fromWire(aliceBundle), addressOf(alice)));
    final ArrayList<Contact> bobsContacts = new ArrayList<>();
    bobsContacts.add(new Contact("Alice", "Adams", addressOf(alice).getName(),
        addressOf(alice).getDeviceId(), false));
    bob.setContactList(bobsContacts);
    final com.amnesica.kryptey.inputmethod.signalprotocol.MessageEnvelope fromBob =
        SignalProtocolMain.encryptMessage(REPLY, addressOf(alice));
    assertNotNull("Bob must be able to encrypt to Alice on this device", fromBob);
    SignalProtocolMain.getInstance().setAccount(alice);

    final String wire = com.amnesica.kryptey.inputmethod.signalprotocol.encoding.RawEncoder
        .encode(EnvelopeCodec.toWire(fromBob));

    final AtomicReference<EditText> composeRef = new AtomicReference<>();
    InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
      // The real system clipboard, not a shadow. This is the object the messenger hands over.
      final android.content.ClipboardManager clipboard =
          (android.content.ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
      clipboard.setPrimaryClip(android.content.ClipData.newPlainText("", wire));
      strip.selectContact(alice.getContactList().get(0));
      strip.findViewById(R.id.e2ee_button_decrypt).performClick();
      composeRef.set(strip.findViewById(R.id.e2ee_input_field));
    });
    InstrumentationRegistry.getInstrumentation().waitForIdleSync();

    final EditText compose = composeRef.get();
    assertNotNull("the compose field must inflate on a real device", compose);
    final AtomicReference<String> shown = new AtomicReference<>();
    InstrumentationRegistry.getInstrumentation().runOnMainSync(
        () -> shown.set(compose.getText().toString()));

    assertEquals("pressing Decrypt on a real device must put Bob's plaintext into the compose box. "
        + "Everything that tested this ran on shadow views with the desktop build of libsignal.",
        REPLY, shown.get());
  }
}
