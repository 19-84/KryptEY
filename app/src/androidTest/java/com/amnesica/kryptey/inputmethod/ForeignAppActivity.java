package com.amnesica.kryptey.inputmethod;

import android.app.Activity;
import android.os.Bundle;
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

  /** How long this activity may live. It must not outlast the test that started it. */
  public static final String EXTRA_FINISH_AFTER_MS = "finishAfterMs";

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
          Log.i(TAG, BOUND_MARKER + " active=" + imm.isActive(field));
        }
        handler.postDelayed(this, 250L);
      }
    });

    // Never outlive the test that started it. An activity from another package left resumed on top
    // of the stack, holding FLAG_KEEP_SCREEN_ON and the input connection, is ambient contamination
    // for every test that runs afterwards - and it cost this suite a failure the first time round.
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
        Log.i(TAG, MARKER + " length=" + text.length()
            + " containsSecret=" + (secret != null && text.contains(secret)));
      }
    });
  }
}
