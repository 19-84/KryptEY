package com.amnesica.kryptey.inputmethod.latin.e2ee;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.view.ContextThemeWrapper;
import android.widget.TextView;

import com.amnesica.kryptey.inputmethod.R;
import com.amnesica.kryptey.inputmethod.signalprotocol.Account;
import com.amnesica.kryptey.inputmethod.signalprotocol.ProtocolIdentifier;
import com.amnesica.kryptey.inputmethod.signalprotocol.SignalProtocolMain;
import com.amnesica.kryptey.inputmethod.signalprotocol.chat.Contact;
import com.amnesica.kryptey.inputmethod.signalprotocol.encoding.EnvelopeCodec;
import com.amnesica.kryptey.inputmethod.signalprotocol.helper.StorageHelper;
import com.amnesica.kryptey.inputmethod.signalprotocol.storage.GcmCryptoBox;
import com.amnesica.kryptey.inputmethod.signalprotocol.util.ProtocolAddresses;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.signal.libsignal.protocol.SignalProtocolAddress;

import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;

/**
 * The store fault is a fact about the store. It was reconsidered only while a sentence about it
 * happened to be the text in the banner.
 *
 * <p>{@code LatinIME.onStartInputViewInternal} re-reads the store on every keyboard raise so that a
 * warning raised from a condition can come down when the condition ends — and, far more
 * importantly, so that the account carrying {@code contactsWereUnreadable} is replaced once the
 * device is unlocked. Until that happens {@code StorageHelper} refuses <em>every</em> write.
 *
 * <p>That re-read was gated on {@code hasStandingConditionWarning()}, which asks what the banner is
 * currently saying. The banner holds one warning and any other writer takes the slot: a relayed
 * message offering a different key, tapping a contact whose name is shared, a refused invite. After
 * that the gate answered no, the store was never re-read again, and the fault latched for the life
 * of the process — writes refused after the unlock, with the sentence explaining why taken away by
 * the party who benefits from taking it away.
 *
 * <p>This is the same defect as the one this branch already fixed in the lowering direction, in the
 * same file, one step further along: <b>a control on a slot the adversary can overwrite is not a
 * control.</b> The gate now asks the fact.
 *
 * <p>Fixing it opens a second question, and the third test below is the answer: the re-read now runs
 * while an event warning is standing, so the condition warning must NOT paint over it. Re-raising a
 * condition warning over a key-substitution warning would be the eviction this file records as
 * strictly worse than displacement — a messenger that can hold a store fault could then erase key
 * warnings at a moment of its choosing.
 */
@RunWith(RobolectricTestRunner.class)
public class AstorageFaultOutlivesTheSentenceThatDescribesItTest {

  /**
   * A phrase from {@code INFO_CONTACTS_UNREADABLE}, which is private. Matched by substring rather
   * than widened, because the constant's visibility is not what this file is about — and the phrase
   * chosen is the load-bearing one: it is the clause that stops the user reading an apparently
   * empty app as a fresh install and re-inviting everybody, discarding every pin they had checked.
   */
  private static final String THE_CONTACTS_SENTENCE = "do NOT re-invite anyone";

  private Context context;
  private SecretKey key;
  private E2EEStripView strip;
  private Account victim;
  private Contact bob;
  private SignalProtocolAddress bobAddress;
  private String attackerBundle;

  private static SecretKey newKey() {
    try {
      final KeyGenerator generator = KeyGenerator.getInstance("AES");
      generator.init(256);
      return generator.generateKey();
    } catch (NoSuchAlgorithmException e) {
      throw new AssertionError(e);
    }
  }

  @Before
  public void setUp() throws Exception {
    final Application app = RuntimeEnvironment.getApplication();
    context = app;
    context.getSharedPreferences("protocol", Context.MODE_PRIVATE).edit().clear().commit();

    SignalProtocolMain.resetForTest();
    SignalProtocolMain.testIsRunning = true;
    key = newKey();

    // Bob and the attacker are minted with a null context, deliberately. Handed the real one they
    // would each LOAD the account the previous initialize stored, so all three identities would be
    // the same key - the attacker would offer Bob's own key, no substitution would be recorded, and
    // the fixture would look like it was working while measuring nothing.
    SignalProtocolMain.initialize(null);
    final Account peer = SignalProtocolMain.getInstance().getAccount();
    bobAddress = ProtocolAddresses.of(peer.getSignalProtocolAddress().getName(), peer.getDeviceId());
    final String bobBundle = SignalProtocolMain.exportOwnKeyBundle();

    SignalProtocolMain.initialize(null);
    attackerBundle = SignalProtocolMain.exportOwnKeyBundle();

    // Only the victim gets a store, because only the victim's store is the subject.
    SignalProtocolMain.setStorageHelperFactoryForTest(ctx -> helper());
    SignalProtocolMain.initialize(context);
    victim = SignalProtocolMain.getInstance().getAccount();
    victim.setMessageLogLoader(ArrayList::new);
    assertTrue(SignalProtocolMain.processPreKeyResponseMessage(
        EnvelopeCodec.fromWire(bobBundle), bobAddress));

    bob = new Contact("Bob", "Jones", bobAddress.getName(), bobAddress.getDeviceId(), false);
    final ArrayList<Contact> contacts = new ArrayList<>();
    contacts.add(bob);
    victim.setContactList(contacts);

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

  private StorageHelper helper() {
    return new StorageHelper(context, (ctx, hasExistingData) -> new GcmCryptoBox() {
      @Override
      protected SecretKey key() {
        return key;
      }
    });
  }

  private SharedPreferences store() {
    return context.getSharedPreferences("protocol", Context.MODE_PRIVATE);
  }

  private String storedContactList() {
    return store().getString(String.valueOf(ProtocolIdentifier.CONTACTS), null);
  }

  /** The CONTACTS value present and unopenable: everything else in the store still reads. */
  private void sealTheStoredContactList() {
    store().edit().putString(String.valueOf(ProtocolIdentifier.CONTACTS),
        "not a sealed contact list").commit();
  }

  private String banner() {
    return String.valueOf(((TextView) strip.findViewById(R.id.e2ee_info_text)).getText());
  }

  /**
   * The fault, as the app is in when the device is locked and the contact list cannot be opened.
   *
   * @return the sealed value, so a later test can put the readable one back.
   */
  private String theContactListCannotBeOpened() {
    helper().storeAllInformationInSharedPreferences(victim);
    final String readable = storedContactList();
    sealTheStoredContactList();
    victim.markContactsUnreadable();
    strip.refreshOpeningMessage();
    assertTrue("precondition: the contacts-unreadable warning must be standing: " + banner(),
        banner().contains(THE_CONTACTS_SENTENCE));
    assertTrue("precondition: every write must be refused while the fault stands",
        SignalProtocolMain.contactsAreUnreadable());
    return readable;
  }

  /** A relayed message offering a different key at Bob's address — the messenger's cheapest move. */
  private void themessengerTakesTheBanner() {
    SignalProtocolMain.importOutOfBandKeyBundle(attackerBundle, bobAddress);
    assertTrue("precondition: a substitution must be pending", strip.warnIfIdentityChanged(bob));
    assertFalse("precondition: the slot must have been taken: " + banner(),
        banner().contains(THE_CONTACTS_SENTENCE));
  }

  /**
   * Whether the raise re-reads the store. Asked here exactly as {@code LatinIME} asks it — one
   * method, so this cannot drift from what production does the way a mirrored expression would.
   * {@code AconditionWarningIsLoweredWithItsConditionTest} pins that the per-raise path calls it.
   */
  @Test
  public void thefaultIsReconsideredAfterSomethingElseTakesTheBanner() {
    theContactListCannotBeOpened();
    assertTrue("precondition: while its own sentence is up, the raise reconsiders the store",
        strip.theStoreMustBeRereadOnThisRaise());

    themessengerTakesTheBanner();

    assertTrue("the store fault is a fact about the store, not about the banner. Gated on the "
            + "sentence, one relayed message ends the re-read for the life of the process: the "
            + "account keeping contactsWereUnreadable is never replaced, so every write stays "
            + "refused after the device is unlocked - contacts, pins, verifications, rejections "
            + "and the chat log all live in RAM until the process dies - and the one sentence "
            + "that explained it was taken away by the party who benefits",
        strip.theStoreMustBeRereadOnThisRaise());
  }

  /**
   * And the cost that gating on the sentence actually imposes, measured end to end.
   *
   * <p>The two lines below are what {@code LatinIME.onStartInputViewInternal} runs on a raise. They
   * are written out rather than called because a Robolectric {@code LatinIME} cannot be raised
   * here; what stops that from being a description of production rather than production is the
   * source scan in {@code AconditionWarningIsLoweredWithItsConditionTest}, which fails if the
   * per-raise path stops calling any of these three.
   */
  @Test
  public void thewritesComeBackAfterTheUnlockEvenIfTheWarningWasDisplaced() {
    final String readable = theContactListCannotBeOpened();
    themessengerTakesTheBanner();

    // The device is unlocked and the value opens again.
    store().edit().putString(String.valueOf(ProtocolIdentifier.CONTACTS), readable).commit();

    if (strip.theStoreMustBeRereadOnThisRaise()) {
      SignalProtocolMain.reloadAccountIfStorageRecovered(context);
      strip.refreshOpeningMessage();
    }

    assertFalse("the unlock must end the fault. Latched, the keyboard looks healthy and persists "
            + "nothing, and its own advice - 'this clears when the device can read its own storage "
            + "again' - has become false",
        SignalProtocolMain.contactsAreUnreadable());
  }

  /**
   * The other direction, which fixing the first opens — and the trade taken, so a later round does
   * not re-open it by reading only half of it.
   *
   * <p>With the gate asking the fact, this method now runs on every raise while a fault stands, so
   * it repaints the storage sentence over a key-substitution warning at moments the messenger picks
   * by presenting a field. A yield was written for that and reverted. The storage sentence has no
   * other way onto the screen and cannot be recovered by anything the user can do while the fault
   * stands — Verify and Reject clear a warning only once the response reaches disk, which is what
   * the fault refuses — and during a contacts fault the list <em>looks empty</em>, so this sentence
   * is the only thing between the user and re-inviting everybody, discarding every pin they have
   * checked.
   *
   * <p>The warning it displaces is recomputable, which is what makes the trade survivable and is
   * the second half of this test.
   */
  @Test
  public void thestorageSentenceWinsTheSlotAndTheKeyWarningIsRecomputable() {
    theContactListCannotBeOpened();
    themessengerTakesTheBanner();

    strip.refreshOpeningMessage();

    assertTrue("during a contacts fault the list looks empty, and this sentence is the only thing "
            + "stopping the user re-inviting everybody - which discards every pin they have "
            + "checked. It has no other way onto the screen: " + banner(),
        banner().contains(THE_CONTACTS_SENTENCE));

    // And the displaced warning comes back by an action its own text asks for.
    strip.selectContact(bob);

    assertFalse("an eviction the subject can re-derive is a displacement. If tapping the contact "
            + "did not bring the substitution warning back, the ordering above would be handing "
            + "the messenger a way to erase key warnings for good: " + banner(),
        banner().contains(THE_CONTACTS_SENTENCE));
  }
}
