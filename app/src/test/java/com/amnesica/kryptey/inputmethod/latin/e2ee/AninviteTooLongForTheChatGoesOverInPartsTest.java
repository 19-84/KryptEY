package com.amnesica.kryptey.inputmethod.latin.e2ee;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.inputmethodservice.InputMethodService;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.InputConnection;
import android.widget.Button;

import com.amnesica.kryptey.inputmethod.R;
import com.amnesica.kryptey.inputmethod.compat.PreferenceManagerCompat;
import com.amnesica.kryptey.inputmethod.latin.RichInputConnection;
import com.amnesica.kryptey.inputmethod.latin.RichInputMethodManager;
import com.amnesica.kryptey.inputmethod.latin.settings.Settings;
import com.amnesica.kryptey.inputmethod.signalprotocol.SignalProtocolMain;
import com.amnesica.kryptey.inputmethod.signalprotocol.encoding.ChunkedWire;
import com.amnesica.kryptey.inputmethod.signalprotocol.encoding.EnvelopeCodec;
import com.amnesica.kryptey.inputmethod.signalprotocol.helper.StorageHelper;
import com.amnesica.kryptey.inputmethod.signalprotocol.storage.TestStores;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.shadows.ShadowToast;

import java.util.ArrayList;
import java.util.List;

/**
 * An invite is about 2,572 characters, and a chat app with a 2,000-character limit refuses it. With
 * a limit set, the strip places it in numbered parts instead - one per press, because the strip can
 * put text into the chat's field but cannot press the chat's send button.
 *
 * <p>Driven through the real buttons and observed at the host's field, the way
 * {@code TheInviteGoesToTheHostAppTest} does it, because the property is about what the chat app
 * receives and in what pieces.
 */
@RunWith(RobolectricTestRunner.class)
public class AninviteTooLongForTheChatGoesOverInPartsTest {

  private static final int LIMIT = 500;

  private E2EEStripView strip;
  private BaseInputConnection hostField;
  private RichInputConnection connection;

  private static void limit(final String value) {
    PreferenceManagerCompat.getDeviceSharedPreferences(RuntimeEnvironment.getApplication())
        .edit().putString(Settings.PREF_MESSAGE_LENGTH_LIMIT, value).commit();
  }

  private E2EEStripView newStrip() {
    final E2EEStripView built = new E2EEStripView(new ContextThemeWrapper(
        RuntimeEnvironment.getApplication(), R.style.KeyboardTheme_LXX_Pure_Day), null);
    built.setRichInputConnection(connection);
    built.setListener(new E2EEStripView.Listener() {
      @Override
      public void onTextInput(final String rawText) {
        connection.commitText(rawText, 1);
      }

      @Override public void onSensitiveContentVisibilityChanged(final boolean sensitive) { }
    }, built);
    return built;
  }

  @Before
  public void setUp() throws Exception {
    RichInputMethodManager.init(RuntimeEnvironment.getApplication());
    SignalProtocolMain.resetForTest();
    SignalProtocolMain.testIsRunning = true;
    SignalProtocolMain.initialize(null);
    SignalProtocolMain.setStorageStateForTest(StorageHelper.StorageState.READABLE);
    TestStores.writesLand();
    limit("0");

    hostField = new BaseInputConnection(new View(RuntimeEnvironment.getApplication()), true);
    connection = new RichInputConnection(new InputMethodService() {
      @Override
      public InputConnection getCurrentInputConnection() {
        return hostField;
      }
    });
    strip = newStrip();
    ShadowToast.reset();
  }

  @After
  public void tearDown() {
    limit("0");
    if (strip != null) strip.clear();
    SignalProtocolMain.resetForTest();
    SignalProtocolMain.testIsRunning = false;
  }

  private String takeFromHost() {
    final String text = hostField.getEditable().toString();
    hostField.getEditable().clear();
    return text;
  }

  private void pressInvite() {
    strip.findViewById(R.id.e2ee_contact_list_invite_new_contact_button).performClick();
  }

  private Button partButton() {
    return strip.findViewById(R.id.e2ee_button_next_part);
  }

  /** Presses the part button until it hides, collecting what each press placed. */
  private List<String> pressThroughTheRemainingParts() {
    final List<String> placed = new ArrayList<>();
    int guard = 0;
    while (partButton().getVisibility() == View.VISIBLE) {
      assertTrue("the part button never hid; " + guard + " presses", ++guard < 100);
      partButton().performClick();
      placed.add(takeFromHost());
    }
    return placed;
  }

  @Test
  public void withNoLimitTheInviteGoesOverWholeAsItAlwaysDid() throws Exception {
    pressInvite();
    final String delivered = takeFromHost().trim();
    assertFalse("nothing arrived", delivered.isEmpty());
    assertFalse("no limit is set, so nothing may be wrapped: a recipient on an old version needs "
        + "no new behaviour", ChunkedWire.isChunk(delivered));
    assertNotNull(EnvelopeCodec.fromWire(delivered).getPreKeyResponse());
    assertEquals("no parts pending, so no part button", View.GONE, partButton().getVisibility());
  }

  @Test
  public void withAlimitTheInviteArrivesAsNumberedPartsEachWithinIt() throws Exception {
    limit(String.valueOf(LIMIT));
    pressInvite();

    final String first = takeFromHost();
    assertTrue("the first part must be a part, got: " + first.substring(0, Math.min(20, first.length())),
        first.startsWith(ChunkedWire.MARKER + "1/"));
    assertTrue("the first part is " + first.length() + " characters against a limit of " + LIMIT,
        first.length() <= LIMIT);
    assertEquals("with parts pending the part button must be on screen",
        View.VISIBLE, partButton().getVisibility());
    assertTrue("the button says which part it will place: " + partButton().getText(),
        partButton().getText().toString().contains("part 2 of"));

    final List<String> rest = pressThroughTheRemainingParts();
    assertTrue("more than one further part expected for a 2,500-character invite at " + LIMIT
        + ", got " + rest.size(), rest.size() >= 2);
    for (final String part : rest) {
      assertTrue("a later part is " + part.length() + " characters against " + LIMIT,
          part.length() <= LIMIT);
    }

    final ChunkedWire.Assembler assembler = new ChunkedWire.Assembler();
    String whole = assembler.accept(first);
    for (final String part : rest) whole = assembler.accept(part);
    assertNotNull("all the parts the button offered, joined, must be the whole invite - the "
        + "button hid with parts still missing otherwise", whole);
    assertNotNull("and the whole must be a key bundle the other side can accept",
        EnvelopeCodec.fromWire(whole).getPreKeyResponse());
  }

  @Test
  public void aSecondInviteIsRefusedWhileTheFirstIsStillGoingOut() throws Exception {
    limit(String.valueOf(LIMIT));
    pressInvite();
    final String first = takeFromHost();

    ShadowToast.reset();
    pressInvite();
    assertEquals("a second invite while the first is half sent would leave the peer holding parts "
        + "of two; nothing may reach the host", "", takeFromHost());
    final String said = ShadowToast.getTextOfLatestToast();
    assertNotNull("and the user must be told why the press did nothing", said);
    assertTrue(said, said.contains("still going out in parts"));

    // The exit the toast names: tapping the banner drops the rest - and says so, because the tap's
    // older meaning is "clear the recipient" and a sender using it for that would otherwise lose
    // the rest of a message the log records as sent, with nothing said.
    ShadowToast.reset();
    strip.findViewById(R.id.e2ee_info_text).performClick();
    assertEquals(View.GONE, partButton().getVisibility());
    final String dropped = ShadowToast.getTextOfLatestToast();
    assertNotNull("dropping the rest of a half-sent message must be said", dropped);
    assertTrue(dropped, dropped.contains("never placed"));
    pressInvite();
    final String fresh = takeFromHost();
    assertTrue("after giving up, a new invite starts again from part 1",
        fresh.startsWith(ChunkedWire.MARKER + "1/"));
    assertFalse("and it is a different invite from the abandoned one", fresh.equals(first));
  }

  @Test
  public void aPendingPartIsNotPlacedOverApasswordField() throws Exception {
    limit(String.valueOf(LIMIT));
    pressInvite();
    takeFromHost();
    assertEquals(View.VISIBLE, partButton().getVisibility());

    strip.setHostFieldIsPassword(true);
    assertFalse("a lit control under \"encryption and decryption are turned off here\" is the app "
        + "offering what it forbids - the invariant the other two buttons keep",
        partButton().isEnabled());
    partButton().performClick();
    assertEquals("a part of a key bundle committed into another app's password box is the "
        + "disclosure the guard exists for; the other three senders refuse here and so must this",
        "", takeFromHost());
    assertEquals("the part is kept for when the cursor moves somewhere sensible",
        View.VISIBLE, partButton().getVisibility());

    strip.setHostFieldIsPassword(false);
    assertTrue("and lit again once the cursor leaves the password box", partButton().isEnabled());
    partButton().performClick();
    assertTrue(takeFromHost().startsWith(ChunkedWire.MARKER + "2/"));
  }

  @Test
  public void theRemainingPartsSurviveArebuild() throws Exception {
    limit(String.valueOf(LIMIT));
    pressInvite();
    final String first = takeFromHost();
    final String label = partButton().getText().toString();

    final E2EEStripView.CarriedState carried = strip.surrenderState();
    strip = newStrip();
    strip.adoptState(carried);

    assertEquals("a rotation between parts is something the chat app can force; dropping the "
        + "rest would leave the peer two thirds of an invite and the user with only a re-invite",
        View.VISIBLE, partButton().getVisibility());
    assertEquals(label, partButton().getText().toString());

    final List<String> rest = pressThroughTheRemainingParts();
    final ChunkedWire.Assembler assembler = new ChunkedWire.Assembler();
    String whole = assembler.accept(first);
    for (final String part : rest) whole = assembler.accept(part);
    assertNotNull("the parts placed after the rebuild complete the invite placed before it", whole);
    assertNotNull(EnvelopeCodec.fromWire(whole).getPreKeyResponse());
  }
}
