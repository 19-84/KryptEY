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
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.shadows.ShadowLooper;
import org.signal.libsignal.protocol.SignalProtocolAddress;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.time.Instant;
import java.util.ArrayList;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;

/** Round-5 probes against the "last rites" patch. Harness copied from the round-4 test. */
@RunWith(RobolectricTestRunner.class)
public class StripCarriedStateRound5Test {

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

  private static ListView contactListOf(final E2EEStripView strip) {
    final ListView list = strip.findViewById(R.id.e2ee_contact_list);
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

  private void copyToClipboard(final String text) {
    final android.content.ClipboardManager clipboard =
        (android.content.ClipboardManager) org.robolectric.RuntimeEnvironment.getApplication()
            .getSystemService(Context.CLIPBOARD_SERVICE);
    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("t", text));
    ShadowLooper.idleMainLooper();
  }

  private static void collect() {
    for (int i = 0; i < 6; i++) {
      System.gc();
      System.runFinalization();
      try {
        Thread.sleep(15);
      } catch (final InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
  }

  // ---------------------------------------------------------------------------------------------
  // R0 - is the discarded strip actually reachable any more? This is the question that decides
  // whether leftover CONTENT on it matters at all. The weak reference is taken without keeping a
  // strong one, so if nothing outside the old view tree still points at it, it must be collected.
  // ---------------------------------------------------------------------------------------------

  /** Anti-vacuity for R0: the LIVE strip is reachable, so a surviving reference is detectable. */
  @Test
  public void r0aTheProbeCanSeeALiveStrip() {
    final WeakReference<E2EEStripView> ref = new WeakReference<>(liveStrip());
    collect();
    assertNotNull("the live strip is held by LatinIME and must not be collected", ref.get());
  }

  @Test
  public void r0TheDiscardedStripMustBecomeUnreachable() {
    final WeakReference<E2EEStripView> ref = new WeakReference<>(liveStrip());

    configurationChange();
    assertNotSame("precondition: rebuilt", ref.get(), liveStrip());

    collect();
    assertNull("a strip the IME threw away must not be reachable from anything that outlives it; "
        + "whatever holds it also holds every decrypted thing left on it", ref.get());
  }

  // ---------------------------------------------------------------------------------------------
  // R1 - the destroy path. surrenderState runs from LatinIME.setInputView only; onDestroy throws
  // the strip away through E2EEStripView.clear(), which performs no last rites at all.
  // ---------------------------------------------------------------------------------------------

  @Test
  public void r1TheStripDiscardedByOnDestroyMustNotStillRunOnClipboardChanges() {
    final E2EEStripView discarded = liveStrip();
    final String bannerBefore = bannerOf(discarded).getText().toString();

    ime.onDestroy();
    ShadowLooper.idleMainLooper();

    copyToClipboard(attackerBundle);

    assertEquals("a strip the IME destroyed must not still be executing on clipboard events",
        bannerBefore, bannerOf(discarded).getText().toString());
  }

  @Test
  public void r1bTheStripDiscardedByOnDestroyMustNotKeepTheDecryptedConversation() {
    final E2EEStripView first = liveStrip();
    first.selectContact(bob());
    final ImageButton chatLogs = first.findViewById(R.id.e2ee_button_chat_logs);
    assertNotNull(chatLogs);
    chatLogs.performClick();
    ShadowLooper.idleMainLooper();

    final ListView discardedLog = first.findViewById(R.id.e2ee_messages_list);
    assertNotNull("precondition: the chat log must be populated", discardedLog.getAdapter());
    assertEquals("precondition: with the real line", 1, discardedLog.getAdapter().getCount());

    ime.onDestroy();
    ShadowLooper.idleMainLooper();

    assertNull("no decrypted conversation may outlive the IME being destroyed",
        discardedLog.getAdapter());
  }

  /**
   * REJECTED - VACUOUS. It fails on HEAD and it also fails with the destroy path fixed, so it is
   * not driven by any guard: this harness holds {@code ime} as a field, {@code LatinIME.onDestroy}
   * never nulls {@code mE2EEStripView}, and so the strip stays reachable through the test itself.
   * Kept only as the record of why the reachability claim for the destroy path rests on r1 and on
   * round 4's clipboard argument instead.
   */
  @Ignore("rejected: vacuous - the harness itself retains the strip through LatinIME")
  @Test
  public void r1cTheStripDiscardedByOnDestroyMustBecomeUnreachable() {
    final WeakReference<E2EEStripView> ref = new WeakReference<>(liveStrip());

    ime.onDestroy();
    ShadowLooper.idleMainLooper();

    collect();
    assertNull("a strip the IME destroyed must not be reachable from anything that outlives it",
        ref.get());
  }

  // ---------------------------------------------------------------------------------------------
  // R2 - the contact list. clearDecryptedContent drops the chat-log adapter but only LEAVES the
  // contact-list screen, and on a discarded view leaving a screen is not a clearing operation.
  // ---------------------------------------------------------------------------------------------

  /**
   * REJECTED. It does fail on HEAD - surrenderState never drops the contact adapter, despite its
   * own javadoc claiming "the contact list still held who the user talks to" among the things it
   * fixes. But r0 passes, so after a rebuild nothing reachable points at that strip: this is
   * content on an object the collector takes, which is true of every freed object in the process.
   * Not a defect. The javadoc is inaccurate; that is all.
   */
  @Ignore("rejected: r0 shows the discarded strip is unreachable, so this residue is ordinary garbage")
  @Test
  public void r2TheDiscardedStripMustNotKeepWhoTheUserTalksTo() {
    final E2EEStripView first = liveStrip();
    final ListView discardedContacts = contactListOf(first);
    assertNotNull("precondition: the contact list is populated", discardedContacts.getAdapter());
    assertEquals("precondition: with the real contact", 1,
        discardedContacts.getAdapter().getCount());

    configurationChange();
    ime.onWindowHidden();
    ShadowLooper.idleMainLooper();

    assertNull("the user's whole set of correspondents must not be left on a strip no clearing "
        + "path can reach", discardedContacts.getAdapter());
  }

  // ---------------------------------------------------------------------------------------------
  // R3 - the banner. forgetChosenRecipient exists because "Chosen contact: Bob #a1b2" surviving
  // the keyboard being dismissed names who the user talks to; it runs on the LIVE strip only.
  // ---------------------------------------------------------------------------------------------

  /**
   * REJECTED, for the same reason as r2. The LIVE strip is handled - forgetChosenRecipient blanks
   * the banner when the keyboard is dismissed - and the discarded one is unreachable by r0.
   */
  @Ignore("rejected: r0 shows the discarded strip is unreachable, so this residue is ordinary garbage")
  @Test
  public void r3TheDiscardedStripMustNotKeepNamingTheChosenContact() {
    final E2EEStripView first = liveStrip();
    first.selectContact(bob());
    ShadowLooper.idleMainLooper();
    final TextView discardedBanner = bannerOf(first);
    assertTrue("precondition: the banner names the contact - was: "
            + discardedBanner.getText(),
        discardedBanner.getText().toString().contains("Bob"));

    configurationChange();
    ime.onWindowHidden();
    ShadowLooper.idleMainLooper();

    assertFalse("a discarded strip must not go on naming the user's correspondent - was: "
            + discardedBanner.getText(),
        discardedBanner.getText().toString().contains("Bob"));
  }

  // ---------------------------------------------------------------------------------------------
  // R5 - challenging the classification of mCodes. EveryStripFieldIsClassifiedTest argues it is
  // safe to drop because surrenderState blanks it through clearFingerprintViews, "load-bearing
  // rather than tidy". This drives that claim rather than reading it.
  // ---------------------------------------------------------------------------------------------

  @Test
  public void r5TheLastRitesMustBlankTheSafetyNumberDigits() {
    final E2EEStripView first = liveStrip();
    first.showVerifyContactForTest(bob());
    ShadowLooper.idleMainLooper();

    final TextView firstDigit = first.findViewById(R.id.code_first);
    assertNotNull(firstDigit);
    assertFalse("precondition: the verify screen really shows a safety number - was: '"
            + firstDigit.getText() + "'",
        firstDigit.getText().toString().isEmpty());

    configurationChange();
    assertNotSame("precondition: rebuilt", first, liveStrip());

    assertEquals("the discarded strip must not keep the safety number the user was shown",
        "", firstDigit.getText().toString());
  }

  // ---------------------------------------------------------------------------------------------
  // R4 - the carried encoding is applied AFTER adoptState re-raises the typing redirect, and
  // raising it recomputes which encoding button is shown from encodingMethod - which is still the
  // new view's default at that moment.
  // ---------------------------------------------------------------------------------------------

  /** Anti-vacuity for R4: on a strip that is not rebuilt, the shown button follows the choice. */
  @Test
  public void r4aTheShownEncodingButtonFollowsTheChoiceWithoutARebuild() {
    final E2EEStripView live = liveStrip();
    final View raw = live.findViewById(R.id.e2ee_button_select_encoding_raw);
    final View fairy = live.findViewById(R.id.e2ee_button_select_encoding_fairytale);
    assertNotNull(raw);
    assertNotNull(fairy);

    assertTrue(composeBoxOf(live).requestFocus());
    assertEquals("precondition: RAW is the default and its button is the one shown",
        View.VISIBLE, raw.getVisibility());

    raw.performClick();   // the visible button names the current mode; clicking it switches

    assertEquals(View.VISIBLE, fairy.getVisibility());
    assertEquals(View.GONE, raw.getVisibility());
  }

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
  public void r4TheStripMustNotShowRawWhileItIsCarryingFairyTale() {
    final E2EEStripView first = liveStrip();
    final View firstRaw = first.findViewById(R.id.e2ee_button_select_encoding_raw);
    assertNotNull(firstRaw);

    assertTrue(composeBoxOf(first).requestFocus());
    firstRaw.performClick();                       // encodingMethod = FAIRYTALE
    connection.commitText(SECRET, 1);              // a draft, so the redirect is up at rebuild time

    configurationChange();

    final E2EEStripView live = liveStrip();
    assertNotSame("precondition: rebuilt", first, live);
    assertEquals("precondition: the draft was carried", SECRET,
        composeBoxOf(live).getText().toString());

    final View raw = live.findViewById(R.id.e2ee_button_select_encoding_raw);
    final View fairy = live.findViewById(R.id.e2ee_button_select_encoding_fairytale);
    assertNotNull(raw);
    assertNotNull(fairy);

    assertEquals("the strip must not show the RAW indicator while it will encode as FairyTale",
        View.GONE, raw.getVisibility());
    assertEquals("the carried encoding is what the strip must say it is using",
        View.VISIBLE, fairy.getVisibility());
  }
}
