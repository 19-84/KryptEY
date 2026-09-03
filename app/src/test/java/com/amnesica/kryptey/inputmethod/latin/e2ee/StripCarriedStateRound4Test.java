package com.amnesica.kryptey.inputmethod.latin.e2ee;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ListView;
import android.widget.TextView;

import com.amnesica.kryptey.inputmethod.R;
import com.amnesica.kryptey.inputmethod.latin.LatinIME;
import com.amnesica.kryptey.inputmethod.latin.RichInputConnection;
import com.amnesica.kryptey.inputmethod.signalprotocol.Account;
import com.amnesica.kryptey.inputmethod.signalprotocol.SignalProtocolMain;
import com.amnesica.kryptey.inputmethod.signalprotocol.chat.Contact;
import com.amnesica.kryptey.inputmethod.signalprotocol.chat.StorageMessage;
import com.amnesica.kryptey.inputmethod.signalprotocol.encoding.EnvelopeCodec;
import com.amnesica.kryptey.inputmethod.signalprotocol.helper.StorageHelper;
import com.amnesica.kryptey.inputmethod.signalprotocol.storage.GcmCryptoBox;
import com.amnesica.kryptey.inputmethod.signalprotocol.util.ProtocolAddresses;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.shadows.ShadowLooper;
import org.signal.libsignal.protocol.SignalProtocolAddress;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.ArrayList;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;

/** Round-4 probes against surrenderState/adoptState. Harness copied from the round-3 test. */
@RunWith(RobolectricTestRunner.class)
public class StripCarriedStateRound4Test {

  private static final String SECRET = "the meeting is at nine";
  private static final String LOGGED = "the safe combination is 4417";

  private ServiceController<LatinIME> controller;
  private LatinIME ime;
  private RichInputConnection connection;
  private StorageHelper helper;

  private SignalProtocolAddress peerAddress;
  private String attackerBundle;

  private static Object get(final Object target, final Class<?> owner, final String name) {
    try {
      final Field f = owner.getDeclaredField(name);
      f.setAccessible(true);
      return f.get(target);
    } catch (final Exception e) {
      throw new AssertionError(e);
    }
  }

  private static StorageHelper jvmHelper(final Context context, final SecretKey key) {
    return new StorageHelper(context, (ctx, alreadyEncrypted) -> new GcmCryptoBox() {
      @Override
      protected SecretKey key() {
        return key;
      }
    });
  }

  private static SecretKey newKey() {
    try {
      final KeyGenerator generator = KeyGenerator.getInstance("AES");
      generator.init(256);
      return generator.generateKey();
    } catch (final java.security.NoSuchAlgorithmException e) {
      throw new AssertionError(e);
    }
  }

  @Before
  public void setUp() throws Exception {
    SignalProtocolMain.resetForTest();
    SignalProtocolMain.testIsRunning = true;

    final Context context = org.robolectric.RuntimeEnvironment.getApplication();
    final SharedPreferences preferences =
        context.getSharedPreferences("protocol", Context.MODE_PRIVATE);
    preferences.edit().clear().commit();

    SignalProtocolMain.initialize(null);
    final Account peer = SignalProtocolMain.getInstance().getAccount();
    peerAddress = ProtocolAddresses.of(peer.getSignalProtocolAddress().getName(),
        peer.getDeviceId());
    final String peerBundle = SignalProtocolMain.exportOwnKeyBundle();

    SignalProtocolMain.initialize(null);
    attackerBundle = SignalProtocolMain.exportOwnKeyBundle();

    SignalProtocolMain.initialize(null);
    final Account fresh = SignalProtocolMain.getInstance().getAccount();
    assertTrue(SignalProtocolMain.processPreKeyResponseMessage(
        EnvelopeCodec.fromWire(peerBundle), peerAddress));

    final Contact contact = new Contact("Bob", "Jones", peerAddress.getName(),
        peerAddress.getDeviceId(), false);
    final ArrayList<Contact> contacts = new ArrayList<>();
    contacts.add(contact);
    fresh.setContactList(contacts);

    // One line of decrypted conversation on disk, so the chat-log screen has something real in it.
    fresh.addUnencryptedMessage(contact, new StorageMessage(
        StorageMessage.chatLogKey(contact.getSignalProtocolAddressName(), contact.getDeviceId()),
        contact.getSignalProtocolAddressName(),
        fresh.getSignalProtocolAddress().getName(),
        Instant.ofEpochSecond(1_700_000_000L), LOGGED));

    helper = jvmHelper(context, newKey());
    helper.storeAllInformationInSharedPreferences(fresh);
    SignalProtocolMain.setStorageHelperFactoryForTest(ctx -> helper);
    SignalProtocolMain.getInstance().setStorageHelperForTest(helper);

    controller = Robolectric.buildService(LatinIME.class);
    ime = controller.create().get();

    final View inputView = ime.onCreateInputView();
    ime.setInputView(inputView);
    ShadowLooper.idleMainLooper();

    connection = (RichInputConnection) get(ime, LatinIME.class, "mRichInputConnection");
    assertNotNull(connection);
  }

  @After
  public void tearDown() {
    SignalProtocolMain.setStorageHelperFactoryForTest(null);
    SignalProtocolMain.resetForTest();
    SignalProtocolMain.testIsRunning = false;
  }

  private E2EEStripView liveStrip() {
    final E2EEStripView strip = (E2EEStripView) get(ime, LatinIME.class, "mE2EEStripView");
    assertNotNull("LatinIME must be holding a strip", strip);
    return strip;
  }

  private static EditText composeBoxOf(final E2EEStripView strip) {
    final EditText box = strip.findViewById(R.id.e2ee_input_field);
    assertNotNull(box);
    return box;
  }

  private static TextView bannerOf(final E2EEStripView strip) {
    final TextView banner = strip.findViewById(R.id.e2ee_info_text);
    assertNotNull(banner);
    return banner;
  }

  private static ListView chatLogOf(final E2EEStripView strip) {
    final ListView list = strip.findViewById(R.id.e2ee_messages_list);
    assertNotNull(list);
    return list;
  }

  private Contact bob() {
    return SignalProtocolMain.getInstance().getAccount().getContactList().get(0);
  }

  private void configurationChange() {
    final Configuration conf = new Configuration(ime.getResources().getConfiguration());
    conf.uiMode = (conf.uiMode & ~Configuration.UI_MODE_NIGHT_MASK)
        | Configuration.UI_MODE_NIGHT_YES;
    ime.onConfigurationChanged(conf);
    ShadowLooper.idleMainLooper();
  }

  /** What the long-lived connection would type into right now, or null. */
  private TextView redirectTarget() {
    final Object otherIC = get(connection, RichInputConnection.class, "mOtherIC");
    if (otherIC == null) return null;
    return (TextView) get(otherIC,
        com.amnesica.kryptey.inputmethod.latin.E2EEInputConnection.class, "mTextView");
  }

  // -------------------------------------------------------------------------------------------
  // Anti-vacuity
  // -------------------------------------------------------------------------------------------

  @Test
  public void aaTheChatLogFixtureReallyHasALine() throws Exception {
    assertEquals(1, SignalProtocolMain.getUnencryptedMessagesList(bob()).size());
    assertEquals(LOGGED,
        SignalProtocolMain.getUnencryptedMessagesList(bob()).get(0).getUnencryptedMessage());
  }

  @Test
  public void abTheRebuildReallyReplacesTheStrip() {
    final E2EEStripView before = liveStrip();
    configurationChange();
    assertNotSame(before, liveStrip());
  }

  // -------------------------------------------------------------------------------------------
  // P1 - after a rebuild with the redirect up, does typing reach the LIVE box?
  // -------------------------------------------------------------------------------------------

  @Test
  public void p1TypingAfterARebuildMustReachTheLiveComposeBox() {
    final EditText discarded = composeBoxOf(liveStrip());
    assertTrue(discarded.requestFocus());

    configurationChange();

    final EditText live = composeBoxOf(liveStrip());
    assertNotSame(discarded, live);

    connection.commitText(SECRET, 1);

    assertEquals("typing must land in the compose box that is on screen",
        SECRET, live.getText().toString());
  }

  // -------------------------------------------------------------------------------------------
  // P2 - the long-lived connection must not keep pointing at the discarded strip.
  // -------------------------------------------------------------------------------------------

  @Test
  public void p2TheConnectionMustNotKeepReferencingTheDiscardedComposeBox() {
    final EditText discarded = composeBoxOf(liveStrip());
    assertTrue(discarded.requestFocus());
    // The user taps back into the host field - the redirect stays UP by design, but suppose it is
    // lowered by a send. Model the ordinary "not composing at rebuild time" case.
    connection.setShouldUseOtherIC(false);
    assertEquals("precondition: the connection is aimed at the discarded box",
        discarded, redirectTarget());

    configurationChange();

    assertNotSame("precondition: rebuilt", discarded, composeBoxOf(liveStrip()));
    assertFalse("the service-lifetime connection must not hold the discarded strip alive",
        discarded == redirectTarget());
  }

  // -------------------------------------------------------------------------------------------
  // P3 - the decrypted conversation left on the discarded strip.
  // -------------------------------------------------------------------------------------------

  @Test
  public void p3TheDiscardedStripMustNotKeepTheDecryptedConversation() {
    final E2EEStripView first = liveStrip();
    first.selectContact(bob());
    final ImageButton chatLogs = first.findViewById(R.id.e2ee_button_chat_logs);
    assertNotNull(chatLogs);
    chatLogs.performClick();
    ShadowLooper.idleMainLooper();

    final ListView discardedLog = chatLogOf(first);
    assertNotNull("precondition: the chat log must be populated", discardedLog.getAdapter());
    assertEquals("precondition: with the real line", 1, discardedLog.getAdapter().getCount());

    configurationChange();

    // Everything the app has for forgetting decrypted plaintext.
    ime.onWindowHidden();
    ShadowLooper.idleMainLooper();

    assertNull("no decrypted conversation may outlive the keyboard being dismissed",
        discardedLog.getAdapter());
  }

  // -------------------------------------------------------------------------------------------
  // P4 - a carried warning painted over the warning the rebuild itself raised.
  // -------------------------------------------------------------------------------------------

  @Test
  public void p4ACarriedWarningMustNotEraseTheStorageWarningTheRebuildRaised() {
    final E2EEStripView first = liveStrip();
    SignalProtocolMain.importOutOfBandKeyBundle(attackerBundle, peerAddress);
    assertTrue("precondition: a substitution is pending", first.warnIfIdentityChanged(bob()));

    // The Keystore key is invalidated while the keyboard is up (a lock-screen change, a new
    // fingerprint). The process is not restarted; the next rebuild is the sunset flip.
    SignalProtocolMain.setStorageStateForTest(StorageHelper.StorageState.UNREADABLE);

    configurationChange();

    assertEquals("the rebuild's own storage warning must not be overwritten by the carried one",
        E2EEStripView.INFO_STORAGE_UNREADABLE, bannerOf(liveStrip()).getText().toString());
  }

  @Test
  public void p4bACarriedWarningMustNotReEnableTheActionsOnAnUnreadableStore() {
    final E2EEStripView first = liveStrip();
    SignalProtocolMain.importOutOfBandKeyBundle(attackerBundle, peerAddress);
    assertTrue(first.warnIfIdentityChanged(bob()));

    SignalProtocolMain.setStorageStateForTest(StorageHelper.StorageState.UNREADABLE);

    configurationChange();

    final View encrypt = liveStrip().findViewById(R.id.e2ee_button_encrypt);
    final View decrypt = liveStrip().findViewById(R.id.e2ee_button_decrypt);
    assertNotNull(encrypt);
    assertNotNull(decrypt);
    assertFalse("encrypt must stay disabled while the store cannot be read",
        encrypt.isEnabled());
    assertFalse("decrypt must stay disabled while the store cannot be read",
        decrypt.isEnabled());
  }

  // -------------------------------------------------------------------------------------------
  // P5 - the carried draft and the clear button.
  // -------------------------------------------------------------------------------------------

  // Across the API range this app supports, not just the one the target happens to name.
  //
  // This test failed at 28, 30, 34 and 35 and passed at 31 and 33, and nothing in this repo could
  // see it: the JVM suite runs at whatever targetSdk says - 33 for the whole revival - and the
  // device suite runs 28 but not these tests. The defect was in composeInsideTheKeyboard, which
  // raised the redirect and then left the affordances beside it to the focus listener, on a
  // requestFocus() its own javadoc says fails silently. See paintComposeAffordances.
  //
  // Pinned as a matrix rather than at one level, because a single level is what hid it.
  @Config(sdk = {26, 28, 30, 31, 33, 34, 35})
  @Test
  public void p5ACarriedDraftMustStillBeClearable() {
    final EditText box = composeBoxOf(liveStrip());
    assertTrue(box.requestFocus());
    connection.commitText(SECRET, 1);

    configurationChange();

    final E2EEStripView live = liveStrip();
    assertEquals("precondition: the draft was carried", SECRET,
        composeBoxOf(live).getText().toString());
    final View clear = live.findViewById(R.id.e2ee_button_clear_text);
    assertNotNull(clear);
    assertEquals("the clear button must be usable while a draft is on screen",
        View.VISIBLE, clear.getVisibility());
  }

  // -------------------------------------------------------------------------------------------
  // P6 - the discarded strip is not garbage: it is still registered on the clipboard.
  // -------------------------------------------------------------------------------------------

  private void copyToClipboard(final String text) {
    final android.content.ClipboardManager clipboard =
        (android.content.ClipboardManager) org.robolectric.RuntimeEnvironment.getApplication()
            .getSystemService(Context.CLIPBOARD_SERVICE);
    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("t", text));
    ShadowLooper.idleMainLooper();
  }

  /** Anti-vacuity for P6: the clipboard listener runs at all, on the live strip. */
  @Test
  public void acTheClipboardListenerRunsOnTheLiveStrip() {
    final E2EEStripView live = liveStrip();
    assertEquals(E2EEStripView.INFO_NO_CONTACT_CHOSEN_TEXT,
        bannerOf(live).getText().toString());
    copyToClipboard(attackerBundle);
    assertNotSame("the clipboard listener must reach the live strip's banner",
        E2EEStripView.INFO_NO_CONTACT_CHOSEN_TEXT, bannerOf(live).getText().toString());
    assertFalse(E2EEStripView.INFO_NO_CONTACT_CHOSEN_TEXT
        .equals(bannerOf(live).getText().toString()));
  }

  @Test
  public void p6TheDiscardedStripMustNotStillBeRunningOnEveryClipboardChange() {
    final E2EEStripView discarded = liveStrip();
    final String bannerBefore = bannerOf(discarded).getText().toString();

    configurationChange();
    assertNotSame("precondition: rebuilt", discarded, liveStrip());

    copyToClipboard(attackerBundle);

    assertEquals("a strip the IME threw away must not still be executing on clipboard events",
        bannerBefore, bannerOf(discarded).getText().toString());
  }
}
