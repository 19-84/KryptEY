package com.amnesica.kryptey.inputmethod.latin.e2ee;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.widget.TextView;

import com.amnesica.kryptey.inputmethod.R;
import com.amnesica.kryptey.inputmethod.signalprotocol.Account;
import com.amnesica.kryptey.inputmethod.signalprotocol.SignalProtocolMain;
import com.amnesica.kryptey.inputmethod.signalprotocol.encoding.ChunkedWire;
import com.amnesica.kryptey.inputmethod.signalprotocol.helper.StorageHelper;
import com.amnesica.kryptey.inputmethod.signalprotocol.storage.TestStores;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.shadows.ShadowToast;

import java.util.List;

/**
 * The receiving half: parts copied one at a time, each followed by a Decrypt press, become the
 * invite once the last one is in - and until then the strip says how many are still missing.
 *
 * <p>A real invite from a second account, split against a limit, pasted through the real clipboard
 * and the real Decrypt button. The observable outcome is the add-contact screen: it opens only for
 * a key bundle the codec accepted from someone the user has not met, which is exactly what a
 * correctly reassembled first invite is.
 */
@RunWith(RobolectricTestRunner.class)
public class ThePartsOfAsplitInviteAreCollectedByDecryptTest {

  private static final int LIMIT = 500;

  private E2EEStripView strip;
  private Account victim;
  private Account peer;
  private ClipboardManager clipboard;

  private void activate(final Account account) {
    SignalProtocolMain.getInstance().setAccount(account);
  }

  @Before
  public void setUp() throws Exception {
    SignalProtocolMain.resetForTest();
    SignalProtocolMain.testIsRunning = true;
    SignalProtocolMain.initialize(null);
    victim = SignalProtocolMain.getInstance().getAccount();
    SignalProtocolMain.initialize(null);
    peer = SignalProtocolMain.getInstance().getAccount();
    activate(victim);

    strip = new E2EEStripView(new ContextThemeWrapper(RuntimeEnvironment.getApplication(),
        R.style.KeyboardTheme_LXX_Pure_Day), null);
    strip.setListener(new E2EEStripView.Listener() {
      @Override public void onTextInput(final String rawText) { }
      @Override public void onSensitiveContentVisibilityChanged(final boolean sensitive) { }
    }, strip);
    SignalProtocolMain.setStorageStateForTest(StorageHelper.StorageState.READABLE);
    TestStores.writesLand();

    clipboard = (ClipboardManager) RuntimeEnvironment.getApplication()
        .getSystemService(Context.CLIPBOARD_SERVICE);
    ShadowToast.reset();
  }

  @After
  public void tearDown() {
    if (strip != null) strip.clear();
    SignalProtocolMain.resetForTest();
    SignalProtocolMain.testIsRunning = false;
  }

  /** A fresh invite from the peer, in parts. Each call mints a new one-time pre-key, so a new set. */
  private List<String> inviteFromThePeerInParts() throws Exception {
    activate(peer);
    final String invite = SignalProtocolMain.exportOwnKeyBundle();
    activate(victim);
    final List<String> parts = ChunkedWire.split(invite, LIMIT);
    assertTrue("precondition: the invite needs several parts at " + LIMIT, parts.size() >= 3);
    return parts;
  }

  private void copy(final String text) {
    clipboard.setPrimaryClip(ClipData.newPlainText("", text));
  }

  private void pressDecrypt() {
    strip.findViewById(R.id.e2ee_button_decrypt).performClick();
  }

  private int addContactScreen() {
    return strip.findViewById(R.id.e2ee_add_contact_wrapper).getVisibility();
  }

  /** Characters of a part outside the header/base64 alphabet, with their code points. */
  private static String offending(final String part) {
    final StringBuilder sb = new StringBuilder();
    for (int i = 0; i < part.length(); i++) {
      final char c = part.charAt(i);
      if (!(Character.isLetterOrDigit(c) && c < 128) && "+/=#".indexOf(c) < 0) {
        sb.append(String.format("[%d:U+%04X]", i, (int) c));
      }
    }
    return sb.length() == 0 ? "none" : sb.toString();
  }

  private String describeClipboard() {
    if (!clipboard.hasPrimaryClip()) return "no clip";
    final ClipData clip = clipboard.getPrimaryClip();
    if (clip == null || clip.getItemCount() == 0) return "empty clip";
    final CharSequence text = clip.getItemAt(0).getText();
    return text == null ? "null text" : text.length() + " chars, starts "
        + text.subSequence(0, Math.min(16, text.length()));
  }

  private String banner() {
    return ((TextView) strip.findViewById(R.id.e2ee_info_text)).getText().toString();
  }

  @Test
  public void apartOnTheClipboardLightsDecrypt() throws Exception {
    final List<String> parts = inviteFromThePeerInParts();
    assertFalse("precondition: with nothing of ours on the clipboard Decrypt is dark",
        strip.findViewById(R.id.e2ee_button_decrypt).isEnabled());
    copy(parts.get(0));
    assertTrue("a part is not decodable on its own, so the listener must recognise it by its "
        + "marker - otherwise Decrypt stays dark on the one thing the user needs it for",
        strip.findViewById(R.id.e2ee_button_decrypt).isEnabled());
    assertTrue("and the banner says what to do: " + banner(), banner().contains("split message"));
  }

  @Test
  public void thePartsPastedOneAtAtimeBecomeTheInvite() throws Exception {
    final List<String> parts = inviteFromThePeerInParts();
    for (int i = 0; i < parts.size() - 1; i++) {
      copy(parts.get(i));
      pressDecrypt();
      assertEquals("not yet: " + (i + 1) + " of " + parts.size() + " pasted",
          View.GONE, addContactScreen());
      final String said = ShadowToast.getTextOfLatestToast();
      assertNotNull(said);
      assertTrue("the user is told how far along they are: " + said + " | part " + (i + 1)
              + " is " + parts.get(i).length() + " chars, offending: " + offending(parts.get(i))
              + " | clipboard held: " + describeClipboard(),
          said.startsWith((i + 1) + " of " + parts.size() + " parts collected"));
      assertEquals("the banner says the same, so the count survives the toast", said, banner());
      assertFalse("and the part is consumed: the clipboard is cleared, as after any decrypt",
          clipboard.hasPrimaryClip() && clipboard.getPrimaryClip().getItemCount() > 0
              && clipboard.getPrimaryClip().getItemAt(0).getText() != null
              && clipboard.getPrimaryClip().getItemAt(0).getText().length() > 0);
    }
    copy(parts.get(parts.size() - 1));
    ShadowToast.reset();
    pressDecrypt();
    assertEquals("with the last part in, the whole takes the ordinary path: a key bundle from "
        + "someone new opens the add-contact screen | last toast: "
        + ShadowToast.getTextOfLatestToast() + " | banner: " + banner() + " | parts rejoin: "
        + rejoin(parts), View.VISIBLE, addContactScreen());
  }

  /** Whether the parts, joined by a fresh assembler in this test, are the invite they came from. */
  private static String rejoin(final List<String> parts) {
    try {
      final ChunkedWire.Assembler assembler = new ChunkedWire.Assembler();
      String whole = null;
      for (final String part : parts) whole = assembler.accept(part);
      return whole == null ? "incomplete" : "ok, " + whole.length() + " chars";
    } catch (final java.io.IOException e) {
      return "refused: " + e.getMessage();
    }
  }

  @Test
  public void partsInAnyOrderAndSeveralInOnePasteAreAllTaken() throws Exception {
    final List<String> parts = inviteFromThePeerInParts();
    copy(parts.get(parts.size() - 1));
    pressDecrypt();
    // The rest as one paste, newline-separated, the way a run of selected chat messages arrives.
    final StringBuilder rest = new StringBuilder();
    for (int i = 0; i < parts.size() - 1; i++) rest.append(parts.get(i)).append('\n');
    copy(rest.toString());
    pressDecrypt();
    assertEquals(View.VISIBLE, addContactScreen());
  }

  @Test
  public void apartOfAnotherInviteIsRefusedAndTheCollectionKept() throws Exception {
    final List<String> first = inviteFromThePeerInParts();
    final List<String> second = inviteFromThePeerInParts();
    copy(first.get(0));
    pressDecrypt();

    ShadowToast.reset();
    copy(second.get(1));
    pressDecrypt();
    final String said = ShadowToast.getTextOfLatestToast();
    assertNotNull(said);
    assertTrue("the case the set id exists for - re-inviting is the app's own advice, so a second "
        + "invite's parts land in the same chat: " + said, said.contains("different message"));
    assertEquals(View.GONE, addContactScreen());
    assertFalse("the listener wrote \"a part is on the clipboard\" when it was copied; after the "
        + "refusal the clipboard is empty and Decrypt dark, so that sentence must not stand: "
        + banner(), banner().contains("on the clipboard"));
    assertTrue("the progress line takes its place: " + banner(),
        banner().startsWith("1 of " + first.size() + " parts collected"));

    for (int i = 1; i < first.size(); i++) {
      copy(first.get(i));
      pressDecrypt();
    }
    assertEquals("the refusal must not have cost the part already collected",
        View.VISIBLE, addContactScreen());
  }

  @Test
  public void tappingTheBannerStartsOver() throws Exception {
    final List<String> first = inviteFromThePeerInParts();
    final List<String> second = inviteFromThePeerInParts();
    copy(first.get(0));
    pressDecrypt();

    ShadowToast.reset();
    strip.findViewById(R.id.e2ee_info_text).performClick();
    final String dropped = ShadowToast.getTextOfLatestToast();
    assertNotNull("dropping collected parts must be said", dropped);
    assertTrue(dropped, dropped.contains("Started over"));

    for (final String part : second) {
      copy(part);
      pressDecrypt();
    }
    assertEquals("after starting over, the other invite's parts are collected without complaint",
        View.VISIBLE, addContactScreen());
  }

  @Test
  public void thePartsCollectedSoFarSurviveArebuild() throws Exception {
    final List<String> parts = inviteFromThePeerInParts();
    copy(parts.get(0));
    pressDecrypt();

    final E2EEStripView.CarriedState carried = strip.surrenderState();
    strip = new E2EEStripView(new ContextThemeWrapper(RuntimeEnvironment.getApplication(),
        R.style.KeyboardTheme_LXX_Pure_Day), null);
    strip.setListener(new E2EEStripView.Listener() {
      @Override public void onTextInput(final String rawText) { }
      @Override public void onSensitiveContentVisibilityChanged(final boolean sensitive) { }
    }, strip);
    strip.adoptState(carried);

    for (int i = 1; i < parts.size(); i++) {
      copy(parts.get(i));
      pressDecrypt();
    }
    assertEquals("a rotation between parts is something the chat app can force, and the part "
        + "collected before it cost the user a copy and a press", View.VISIBLE, addContactScreen());
  }

  @Test
  public void aDamagedPartIsRefusedWithoutKillingTheKeyboard() throws Exception {
    final List<String> parts = inviteFromThePeerInParts();
    // A whole header on a body with a character outside the alphabet: what a messenger that
    // reformats text produces. A damaged HEADER is not a part at all and takes the ordinary path.
    copy(parts.get(0) + "!");
    pressDecrypt();
    final String said = ShadowToast.getTextOfLatestToast();
    assertNotNull(said);
    assertTrue(said, said.contains("damaged"));
    assertFalse("nothing pasted may be quoted back in a toast", said.contains(parts.get(0)));
    assertFalse("with nothing collected and the clipboard cleared, the banner must not still say "
        + "a part is on it: " + banner(), banner().contains("on the clipboard"));
  }

  /** Copying a hashtag in any app must not light Decrypt or write a sentence about parts. */
  @Test
  public void ahashtagOnTheClipboardIsNotApart() {
    final String before = banner();
    copy("#Kubernetes rocks");
    assertFalse("Decrypt lit on ordinary text, and a press would clear the user's clipboard",
        strip.findViewById(R.id.e2ee_button_decrypt).isEnabled());
    assertEquals("and the banner is untouched", before, banner());
  }
}
