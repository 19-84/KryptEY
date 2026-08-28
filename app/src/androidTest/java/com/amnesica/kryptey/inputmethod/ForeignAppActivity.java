package com.amnesica.kryptey.inputmethod;

import android.app.Activity;
import android.os.Bundle;
import android.content.BroadcastReceiver;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.inputmethod.InputMethodManager;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.LinearLayout;

/**
 * A text field belonging to a different application, for the keyboard to attach to.
 *
 * <p>Declared in the TEST APK ({@code com.amnesica.kryptey.test}), not in the app under test, and
 * that is the whole point: it runs in its own process under its own package, so an input connection
 * to it crosses the same boundary a real messenger's does. Every instrumentation test before this
 * one attached the keyboard to a field in the app it is part of.
 *
 * <p>Deliberately plain - one ordinary {@code EditText}, no inputType beyond the default - so a test
 * that needs a particular kind of field asks for it rather than inheriting one by accident.
 */
public class ForeignAppActivity extends Activity {

  /** The field the keyboard attaches to. Public so a test can address it without ids. */
  public EditText field;

  public static final String TAG = "ForeignAppActivity";

  /** The plaintext the test wants checked for, passed in so it never has to be logged. */
  public static final String EXTRA_SECRET = "secret";

  /**
   * A per-launch nonce, echoed in every marker.
   *
   * <p>Without it a test can pass on another test's activity. Both cross-app tests start one of
   * these, each lives for tens of seconds after its test ends, and each posts a binding marker every
   * 250ms - so clearing logcat at the start of a test is not enough: the PREVIOUS test's instance is
   * still running and still writing. The nonce makes a marker attributable to one launch.
   */
  public static final String EXTRA_NONCE = "nonce";

  /** What a test greps logcat for. */
  public static final String MARKER = "FOREIGN_FIELD";

  /**
   * The binding marker, reported by this process about its OWN field.
   *
   * <p>A test in the other process cannot ask this question soundly. {@code dumpsys input_method}
   * reports {@code mCurMethodId}, which is the SELECTED input method and is true from the moment
   * the harness runs {@code ime set} — this repo already records that "dumpsys will report a
   * selected method that has never been bound". And the package name appears in the dump's
   * start-input history, so it survives long after the IME has moved elsewhere: a test grepping for
   * both strings passes on residue from an earlier test. {@code isActive(field)} asked here is
   * about this field, now.
   */
  public static final String BOUND_MARKER = "FOREIGN_BOUND";

  /**
   * A backstop only. The test says when it is done; this is what catches a test that crashes first.
   *
   * <p>A timer alone was the wrong mechanism and cost two device runs. Too short and the subject
   * self-destructs mid-test - the send then commits into a dead connection and the failure reads as
   * "nothing arrived", pointing at the app. Too long and it outlives its test, sitting resumed on
   * top of the stack holding focus: three unrelated tests failed with "something else holds focus",
   * which is the ambient contamination this suite already had a name for.
   */
  public static final String EXTRA_FINISH_AFTER_MS = "finishAfterMs";

  /** Broadcast this to make the activity go away as soon as its test is finished with it. */
  public static final String ACTION_FINISH = "com.amnesica.kryptey.test.FINISH_FOREIGN";

  @Override
  protected void onCreate(final Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
        | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD);

    final LinearLayout root = new LinearLayout(this);
    root.setOrientation(LinearLayout.VERTICAL);
    field = new EditText(this);
    root.addView(field);
    setContentView(root);
    field.requestFocus();

    // Reports what arrives WITHOUT logging it.
    //
    // This process is not the test's, so the test cannot read the field by reference, and adding a
    // UI-automation dependency would mean new hashes in the dependency verification metadata. It
    // reports through logcat instead - but never the text. What lands here is whatever the keyboard
    // commits, and a test whose subject is "the plaintext must not cross this boundary" has no
    // business writing either half of that into a device-wide log.
    //
    // The plaintext to look for arrives as an extra, so the comparison happens here and only the
    // verdict leaves.
    final String secret = getIntent() == null ? null : getIntent().getStringExtra(EXTRA_SECRET);
    final String nonce = getIntent() == null ? "" : String.valueOf(getIntent().getStringExtra(EXTRA_NONCE));

    final Handler handler = new Handler(Looper.getMainLooper());
    final InputMethodManager imm =
        (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);

    // Ask for the keyboard, and keep asking. Binding is asynchronous and a single request can land
    // before the framework is ready for this window - the same retry the app's own binding test
    // documents as what makes it survive a cold device.
    handler.post(new Runnable() {
      private int attempts = 0;

      @Override
      public void run() {
        if (isFinishing() || attempts++ > 200) return;
        field.requestFocus();
        if (imm != null) {
          imm.showSoftInput(field, InputMethodManager.SHOW_FORCED);
          Log.i(TAG, BOUND_MARKER + " nonce=" + nonce + " active=" + imm.isActive(field));
        }
        handler.postDelayed(this, 250L);
      }
    });

    // Never outlive the test that started it. An activity from another package left resumed on top
    // of the stack, holding FLAG_KEEP_SCREEN_ON and the input connection, is ambient contamination
    // for every test that runs afterwards - and it cost this suite a failure the first time round.
    // The test's own signal, which is what normally ends this activity.
    final BroadcastReceiver finisher = new BroadcastReceiver() {
      @Override
      public void onReceive(final android.content.Context ignored, final Intent intent) {
        finish();
      }
    };
    // RECEIVER_EXPORTED, and it must be: the sender is the shell, a different uid. From Android 13
    // a registerReceiver for a non-system action without an export flag throws SecurityException -
    // which would land here in onCreate BEFORE the backstop timer is posted and before the text
    // watcher is attached, so the activity would die silently and both cross-app tests would fail
    // with "the keyboard never became active", pointing at the app. The suite pins API 28 today;
    // this is for the day that image moves.
    // The platform flag directly rather than ContextCompat: androidx.core is not on the
    // androidTest classpath, and adding it would mean new hashes in the dependency verification
    // metadata for a two-line guard.
    // Suppressed on the branch, not in the baseline. Lint asks for an export flag on the
    // unguarded call; below TIRAMISU there is no such flag to pass, and the platform does not
    // require one - the requirement and the constant arrived together. A baseline entry would hide
    // the next genuine instance of this check somewhere that does matter.
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
      registerReceiver(finisher, new IntentFilter(ACTION_FINISH), RECEIVER_EXPORTED);
    } else {
      registerLegacyFinisher(finisher);
    }

    final long finishAfter = getIntent() == null ? 0L
        : getIntent().getLongExtra(EXTRA_FINISH_AFTER_MS, 0L);
    if (finishAfter > 0) handler.postDelayed(this::finish, finishAfter);
    field.addTextChangedListener(new TextWatcher() {
      @Override
      public void beforeTextChanged(final CharSequence s, final int a, final int b, final int c) { }

      @Override
      public void onTextChanged(final CharSequence s, final int a, final int b, final int c) { }

      @Override
      public void afterTextChanged(final Editable edited) {
        final String text = edited == null ? "" : edited.toString();
        // haveSecret is reported separately, because "the secret was absent" and "I was never told
        // the secret" produce the same containsSecret=false and a test asserting on it alone would
        // pass on either. Any cause of a missing extra - task reuse, a caller that forgets it -
        // yields exactly the token the assertion wants.
        Log.i(TAG, MARKER + " nonce=" + nonce + " haveSecret=" + (secret != null)
            + " length=" + text.length()
            + " containsSecret=" + (secret != null && text.contains(secret)));
      }
    });
  }

  /** Pre-TIRAMISU registration, where no export flag exists; see the call site. */
  @android.annotation.SuppressLint("UnspecifiedRegisterReceiverFlag")
  private void registerLegacyFinisher(final BroadcastReceiver finisher) {
    registerReceiver(finisher, new IntentFilter(ACTION_FINISH));
  }
}
