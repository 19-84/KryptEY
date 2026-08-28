package com.amnesica.kryptey.inputmethod.latin.e2ee;

import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.view.ContextThemeWrapper;
import android.widget.TextView;

import com.amnesica.kryptey.inputmethod.R;
import com.amnesica.kryptey.inputmethod.signalprotocol.SignalProtocolMain;
import com.amnesica.kryptey.inputmethod.signalprotocol.helper.StorageHelper;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

@RunWith(RobolectricTestRunner.class)
public class ZzTempRebuildProbeTest {

  private Application app;

  @Before
  public void setUp() {
    app = RuntimeEnvironment.getApplication();
    SignalProtocolMain.resetForTest();
    SignalProtocolMain.testIsRunning = true;
    SignalProtocolMain.initialize(null);
    SignalProtocolMain.setStorageStateForTest(StorageHelper.StorageState.READABLE);
  }

  @After
  public void tearDown() {
    SignalProtocolMain.resetForTest();
    SignalProtocolMain.testIsRunning = false;
  }

  private E2EEStripView newStrip() {
    final E2EEStripView s = new E2EEStripView(
        new ContextThemeWrapper(app, R.style.KeyboardTheme_LXX_Pure_Day), null);
    s.setListener(new E2EEStripView.Listener() {
      @Override public void onTextInput(final String rawText) { }
      @Override public void onSensitiveContentVisibilityChanged(final boolean sensitive) { }
    }, s);
    return s;
  }

  private String banner(final E2EEStripView s) {
    return String.valueOf(((TextView) s.findViewById(R.id.e2ee_info_text)).getText());
  }

  /** Exactly what LatinIME.setInputView does on a rebuild. */
  @Test
  public void thestaleStorageWarningSurvivesArebuildAfterRecovery() {
    SignalProtocolMain.setStorageStateForTest(StorageHelper.StorageState.UNREADABLE);
    final E2EEStripView first = newStrip();
    first.refreshOpeningMessage();
    assertTrue("precondition: " + banner(first),
        banner(first).contains(E2EEStripView.INFO_STORAGE_UNREADABLE));

    // Storage recovers.
    SignalProtocolMain.setStorageStateForTest(StorageHelper.StorageState.READABLE);

    // The rebuild, in LatinIME's order.
    final E2EEStripView.CarriedState carried = first.surrenderState();
    final E2EEStripView second = newStrip();
    second.refreshOpeningMessage();
    second.adoptState(carried);

    System.out.println("PROBE-BANNER-AFTER-REBUILD >>> " + banner(second));
    System.out.println("PROBE-WARNING-FLAG >>> " + second.warningIsStandingForTest());
    System.out.println("PROBE-DISABLES-BUTTONS >>> "
        + E2EEStripView.disablesActionButtons(banner(second)));
    assertTrue("the stale storage warning must be gone after a rebuild once storage recovered: "
        + banner(second), !banner(second).contains(E2EEStripView.INFO_STORAGE_UNREADABLE));
  }
}
