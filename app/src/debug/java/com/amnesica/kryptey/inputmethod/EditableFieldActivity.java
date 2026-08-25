package com.amnesica.kryptey.inputmethod;

import android.app.Activity;
import android.os.Bundle;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.LinearLayout;

/**
 * An activity that exists only to give an input method something to attach to.
 *
 * <p>Deliberately the plainest editable field there is - no {@code inputType} beyond the default,
 * nothing about passwords, no autofill hints. A test that needs a *particular* kind of field should
 * configure this one rather than growing a second activity, because the thing under test is the
 * keyboard, and every property set here is a property the keyboard might be reacting to.
 */
public class EditableFieldActivity extends Activity {

  /** The field the keyboard attaches to. Held so a test can address it without findViewById ids. */
  public EditText field;

  @Override
  protected void onCreate(final Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);

    // The emulator boots with no lock screen, but a device running this suite might not, and an
    // activity behind a keyguard never gets focus - so the keyboard would never bind and the
    // failure would look like the thing under test rather than the harness.
    getWindow().addFlags(WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
        | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);

    final LinearLayout root = new LinearLayout(this);
    field = new EditText(this);
    // Named so an AssistStructure can identify it - autofill reports views by resource entry name,
    // and a view created in code has none.
    field.setId(R.id.autofill_probe_field);
    field.setFocusable(true);
    field.setFocusableInTouchMode(true);
    root.addView(field);
    setContentView(root);

    field.requestFocus();
  }
}
