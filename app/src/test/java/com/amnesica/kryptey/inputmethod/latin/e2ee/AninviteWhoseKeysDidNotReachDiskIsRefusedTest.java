package com.amnesica.kryptey.inputmethod.latin.e2ee;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.view.ContextThemeWrapper;
import android.widget.TextView;

import com.amnesica.kryptey.inputmethod.R;
import com.amnesica.kryptey.inputmethod.signalprotocol.Account;
import com.amnesica.kryptey.inputmethod.signalprotocol.SignalProtocolMain;
import com.amnesica.kryptey.inputmethod.signalprotocol.helper.StorageHelper;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.util.ArrayList;

/**
 * An invite whose private halves did not reach disk must not leave the device.
 *
 * <p>Building an invite allocates a one-time pre-key and a signed pre-key, and the halves that make
 * replies readable are the PRIVATE ones, which live only in this app's storage. If that write fails,
 * the invite is still a perfectly well-formed thing to paste — and it is useless: the peer encrypts
 * to keys this device cannot decrypt with, so every reply to it is unreadable, permanently, and
 * nothing later repairs it.
 *
 * <p>So the app refuses to hand it over, and the refusal is a {@code return} before the invite
 * reaches the host application. That refusal had no test. Deleting the {@code return} ships an invite
 * whose replies can never be read, the user pastes it into their messenger, and the whole suite stays
 * green — which is the shape this branch keeps finding: a control whose only evidence is that
 * somebody remembered to write it.
 *
 * <p>The default test store fails its writes, which is what makes this reachable without a stub:
 * {@code getPreKeyBundle} records whether its write landed, and here it did not.
 */
@RunWith(RobolectricTestRunner.class)
public class AninviteWhoseKeysDidNotReachDiskIsRefusedTest {

  private E2EEStripView strip;
  private Account account;
  private final ArrayList<String> handedToTheApp = new ArrayList<>();

  @Before
  public void setUp() {
    SignalProtocolMain.resetForTest();
    SignalProtocolMain.testIsRunning = true;
    SignalProtocolMain.initialize(null);
    account = SignalProtocolMain.getInstance().getAccount();

    strip = new E2EEStripView(new ContextThemeWrapper(RuntimeEnvironment.getApplication(),
        R.style.KeyboardTheme_LXX_Pure_Day), null);
    strip.setListener(new E2EEStripView.Listener() {
      @Override public void onTextInput(final String rawText) {
        handedToTheApp.add(rawText);
      }

      @Override public void onSensitiveContentVisibilityChanged(final boolean sensitive) { }
    }, strip);
    SignalProtocolMain.setStorageStateForTest(StorageHelper.StorageState.READABLE);
  }

  @After
  public void tearDown() {
    if (strip != null) strip.clear();
    SignalProtocolMain.resetForTest();
    SignalProtocolMain.testIsRunning = false;
  }

  private String banner() {
    final TextView view = strip.findViewById(R.id.e2ee_info_text);
    return view == null ? "" : view.getText().toString();
  }

  @Test
  public void aninviteWhosePrivateHalvesWereNotStoredIsNotGivenToTheMessenger() {
    assertTrue("precondition: this fixture's writes must fail, or the invite reaches disk and there "
            + "is no refusal to measure",
        !SignalProtocolMain.getInstance().getAccount().getSignalProtocolStore().equals(null)
            && !new StorageHelper(RuntimeEnvironment.getApplication())
                .storeAllInformationInSharedPreferences(account));

    strip.findViewById(R.id.e2ee_contact_list_invite_new_contact_button).performClick();

    assertEquals("the invite must not be handed to the host application when the private halves of "
            + "its keys did not reach disk - it would be pasted into a messenger, the peer would "
            + "encrypt to keys this device cannot decrypt with, and every reply would be unreadable "
            + "with nothing that repairs it later. Handed over: " + handedToTheApp,
        0, handedToTheApp.size());
    assertTrue("...and the user must be told why, on the durable surface rather than only a toast, "
            + "because the thing they have to not do - send it - outlives the toast. Banner: "
            + banner(),
        banner().contains("could not write to its own storage")
            || banner().contains("cannot keep the private half"));
  }
}
