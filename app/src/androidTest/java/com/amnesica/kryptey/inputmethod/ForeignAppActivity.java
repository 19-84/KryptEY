package com.amnesica.kryptey.inputmethod;

import android.app.Activity;
import android.os.Bundle;
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
  }
}
