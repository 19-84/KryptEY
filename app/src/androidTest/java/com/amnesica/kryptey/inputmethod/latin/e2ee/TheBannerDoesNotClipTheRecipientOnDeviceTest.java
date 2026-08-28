package com.amnesica.kryptey.inputmethod.latin.e2ee;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.widget.TextView;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.amnesica.kryptey.inputmethod.signalprotocol.SignalProtocolMain;
import com.amnesica.kryptey.inputmethod.R;
import com.amnesica.kryptey.inputmethod.signalprotocol.chat.Contact;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * The banner can hold three standing items at once, and the recipient line is composed last.
 *
 * <p>A review round raised this and could not settle it: {@code e2ee_info_text} is
 * {@code wrap_content} with no {@code maxLines} and no scrolling, inside a {@code wrap_content}
 * parent, while {@code warningWithRecipient} can compose a warning, a caution, a store notice and
 * then <em>"Sending to: X"</em>. Whether that clips is a question about measurement, so Robolectric
 * cannot answer it — its views are shadows and the layout is never measured. This runs on a device
 * and measures.
 *
 * <p>Why the last line specifically: {@code setChosenContact}'s own javadoc says an unnamed
 * recipient is "a mis-send rather than a disclosure", which is why that line exists at all. Compose
 * order makes it the first casualty of any bottom clipping — so the worst case for this app is
 * exactly the case where the text is longest.
 *
 * <p><b>What this asserts, and what it cannot.</b> The first version measured the banner with an
 * unbounded height, which {@code wrap_content} then satisfies by definition — it could not fail, so
 * it proved nothing. What is actually checkable without inventing a keyboard height is whether the
 * VIEW drops text: a {@code maxLines} or an {@code ellipsize} truncates regardless of the space
 * available, and adding either is the realistic regression here. So this asserts that every line the
 * text needs is laid out and that no line is ellipsised, and that the parent measures tall enough for
 * the child it contains.
 *
 * <p>It does not answer what happens inside a keyboard window of a particular height on a particular
 * device — that depends on a number this test would have to invent. The residue is recorded in
 * REVIVAL.md rather than papered over with a plausible-looking constant.
 */
@RunWith(AndroidJUnit4.class)
public class TheBannerDoesNotClipTheRecipientOnDeviceTest {

  private Context context;
  private E2EEStripView strip;

  @Before
  public void setUp() throws Exception {
    context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    SignalProtocolMain.resetForTest();
    SignalProtocolMain.testIsRunning = true;
    SignalProtocolMain.initialize(null);

    InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
      strip = new E2EEStripView(
          new ContextThemeWrapper(context, R.style.KeyboardTheme_LXX_Pure_Day), null);
      strip.setListener(new E2EEStripView.Listener() {
        @Override public void onTextInput(final String rawText) { }
        @Override public void onSensitiveContentVisibilityChanged(final boolean sensitive) { }
      }, strip);
    });
  }

  @After
  public void tearDown() {
    SignalProtocolMain.resetForTest();
    SignalProtocolMain.testIsRunning = false;
  }

  /** A phone-shaped measure/layout pass, which is the whole point of running this on a device. */
  private void layOutAtPhoneWidth() {
    InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
      final int width = View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY);
      final int height = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED);
      strip.measure(width, height);
      strip.layout(0, 0, strip.getMeasuredWidth(), strip.getMeasuredHeight());
    });
    InstrumentationRegistry.getInstrumentation().waitForIdleSync();
  }

  @Test
  public void thelongestBannerStillShowsEveryLineItComposed() throws Exception {
    final String longName = new String(new char[96]).replace('\0', 'W');
    final Contact contact = new Contact(longName, longName, "11111111-1111-1111-1111-111111111111",
        3, false);

    InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
      strip.selectContact(contact);
      // All three standing items, which is the composition the review round asked about.
      strip.setWarningMessageAboutForTest("Careful: someone offered a different key for this "
          + "contact, at this same address. This is a new key for that address and the app cannot "
          + "tell whose it is.", contact);
      strip.setCautionForTest("A key for this contact has been stored. This key reached you "
          + "through the messenger and the app cannot tell whose it is - compare the security "
          + "number by voice before sending anything private.", contact);
      strip.setStoreNoticeForTest("That contact was removed, but their saved messages could not be "
          + "deleted - the app could not write to its own storage. Those messages are still on "
          + "this device and no screen can reach them now.");
    });

    layOutAtPhoneWidth();

    final TextView banner = strip.findViewById(R.id.e2ee_info_text);
    assertNotNull(banner);
    final String text = String.valueOf(banner.getText());
    assertTrue("precondition: the banner must actually be carrying the long composition, or this "
        + "test is measuring a short string", text.length() > 400);
    assertTrue("precondition: the recipient line must be composed last, which is what makes it the "
        + "first casualty of clipping", text.trim().endsWith(longName + " " + longName)
        || text.contains("Sending to:"));

    // What a device can answer and a shadow cannot: the real measured layout.
    assertNotNull("the banner must have been laid out, or nothing below is measuring anything",
        banner.getLayout());
    final int lines = banner.getLineCount();
    assertTrue("a 400-character banner at this width must wrap to several lines; one line means "
        + "the measure did not happen: " + lines, lines > 3);

    // No line may be ellipsised. maxLines or an ellipsize would drop text whatever space exists,
    // and the text it drops is the end - which is the recipient line.
    final StringBuilder truncated = new StringBuilder();
    for (int i = 0; i < lines; i++) {
      final int ellipsised = banner.getLayout().getEllipsisCount(i);
      if (ellipsised > 0) {
        truncated.append("\n  line ").append(i).append(" drops ").append(ellipsised)
            .append(" characters");
      }
    }
    assertTrue("the banner is dropping characters. Compose order puts the recipient last, so what "
        + "is dropped first is the line that exists because an unnamed recipient is a mis-send:"
        + truncated, truncated.length() == 0);

    // And every character the banner was given is still in a laid-out line.
    assertTrue("the layout stopped short of the text it was given: laid out "
            + banner.getLayout().getLineEnd(lines - 1) + " of " + text.length() + " characters",
        banner.getLayout().getLineEnd(lines - 1) >= text.length());

    // The parent measured tall enough for it, which is the containment question this test can
    // answer without inventing a keyboard height.
    final View parent = (View) banner.getParent();
    assertTrue("the banner's parent measured shorter than the banner: parent="
            + parent.getMeasuredHeight() + " banner=" + banner.getMeasuredHeight(),
        parent.getMeasuredHeight() >= banner.getMeasuredHeight());
  }
}
