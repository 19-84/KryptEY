package com.amnesica.kryptey.inputmethod.latin;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.widget.EditText;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.lang.reflect.Field;

/**
 * {@code forgetCachedText} must forget every buffer in this class, not two of the three.
 *
 * <p>{@code RichInputConnection.forgetCachedText()} was added so that a decrypted message would not
 * stay cached behind whatever app the user opened next, and its javadoc says so. It clears {@code
 * mCommittedTextBeforeComposingText} and {@code mComposingText}. It does not clear {@code
 * mTempObjectForCommitText}, which sits on the same object, is {@code final} so it lives exactly as
 * long as the service, and holds a verbatim copy of whatever was passed to the last {@code
 * commitText}.
 *
 * <p>Ordinary typing puts one character there at a time, which is why this was easy to miss. Two
 * paths put a whole message there instead, and both are the ones that matter:
 *
 * <ul>
 *   <li>{@code InputLogic.onTextInput}, which is how the strip hands text to the host app;
 *   <li>{@code InputLogic}'s recapitalisation commit, {@code commitText(mRecapitalizeStatus
 *       .getRecapitalizedString(), 0)} - the entire selection, up to 100KB.
 * </ul>
 *
 * <p>The second is the same scenario {@code PlaintextBufferClearingTest} already covers: the user
 * selects a decrypted message in the compose box and hits shift. That fix cleared {@code
 * RecapitalizeStatus}; the copy the recapitalise handed to the input connection on its way out was
 * left behind, in a buffer the same method already knows to clean.
 */
@RunWith(RobolectricTestRunner.class)
public class CommitBufferClearingTest {

  private static final String PLAINTEXT = "meet me at the safe house at nine";

  private RichInputConnection connection;

  private static Object field(final Object target, final String name) throws Exception {
    final Field f = target.getClass().getDeclaredField(name);
    f.setAccessible(true);
    return f.get(target);
  }

  @Before
  public void setUp() {
    RichInputMethodManager.init(RuntimeEnvironment.getApplication());
    // No IME service in a unit test, so drive the strip's own connection - which is the one that
    // carries the decrypted message anyway.
    connection = new RichInputConnection(null);
    connection.setShouldUseOtherIC(true);
    connection.setOtherIC(new EditText(RuntimeEnvironment.getApplication()));
  }

  /**
   * A whole committed message must not outlive the dismissal in the commit scratch buffer.
   *
   * <p>The passing half of this assertion pair is deliberate: it shows the two buffers are filled by
   * the same call and that only one of them is emptied.
   */
  @Test
  public void committingAmessageLeavesNothingBehindAfterForgetting() throws Exception {
    connection.commitText(PLAINTEXT, 1);

    assertEquals("precondition: the commit buffer must hold the text",
        PLAINTEXT, field(connection, "mTempObjectForCommitText").toString());

    connection.forgetCachedText();

    assertEquals("the already-guarded buffer must be empty",
        0, ((CharSequence) field(connection, "mCommittedTextBeforeComposingText")).length());
    assertEquals("a committed message must not outlive the keyboard in the commit buffer",
        0, ((CharSequence) field(connection, "mTempObjectForCommitText")).length());
  }

  /** Forgetting twice, and with nothing committed, must be safe - it runs on every dismissal. */
  @Test
  public void forgettingWithNothingCommittedIsSafe() throws Exception {
    connection.forgetCachedText();
    connection.forgetCachedText();
    assertTrue(((CharSequence) field(connection, "mTempObjectForCommitText")).length() == 0);
  }
}
