package com.amnesica.kryptey.inputmethod.latin.e2ee.adapter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import android.content.Context;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.amnesica.kryptey.inputmethod.R;
import com.amnesica.kryptey.inputmethod.signalprotocol.chat.StorageMessage;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.time.Instant;
import java.util.ArrayList;

/**
 * Who said what, in the chat log.
 *
 * <p>{@code ListAdapterMessages} had no test at all, and a review found that making every row
 * render on the "own message" side survives the whole suite. That is not cosmetic. The two sides of
 * this list are the only thing telling a user which words are theirs and which arrived from
 * somebody else - there is no name on a row, only its side. A log showing an incoming message as
 * one the user wrote is a log they will read back and act on wrongly, and every message in it
 * arrived through a channel this threat model treats as hostile.
 */
@RunWith(RobolectricTestRunner.class)
public class ListAdapterMessagesTest {

  private static final String ME = "my-own-uuid";
  private static final String PEER = "peer-uuid";

  private Context context;

  @Before
  public void setUp() {
    context = RuntimeEnvironment.getApplication();
  }

  private static StorageMessage sent(final String body) {
    return new StorageMessage(PEER, ME, PEER, Instant.ofEpochMilli(1_700_000_000_000L), body);
  }

  private static StorageMessage received(final String body) {
    return new StorageMessage(PEER, PEER, ME, Instant.ofEpochMilli(1_700_000_001_000L), body);
  }

  private ListAdapterMessages adapterFor(final StorageMessage... messages) {
    final ArrayList<Object> list = new ArrayList<>();
    for (final StorageMessage message : messages) list.add(message);
    return new ListAdapterMessages(context, R.layout.e2ee_messages_element_view, list, ME);
  }

  private static TextView own(final View row) {
    return row.findViewById(R.id.e2ee_own_messages_text_view_element);
  }

  private static TextView others(final View row) {
    return row.findViewById(R.id.e2ee_others_messages_text_view_element);
  }

  @Test
  public void amessageTheUserSentRendersOnTheOwnSide() {
    final View row = adapterFor(sent("I will be there at nine"))
        .getView(0, null, new FrameLayout(context));
    assertNotNull("the row must inflate", row);

    assertEquals("a sent message must be on the own side", View.VISIBLE, own(row).getVisibility());
    assertEquals("I will be there at nine", own(row).getText().toString());
    assertEquals("and must not also appear as the other side's",
        View.GONE, others(row).getVisibility());
  }

  @Test
  public void amessageTheUserReceivedRendersOnTheOtherSide() {
    final View row = adapterFor(received("bring the documents"))
        .getView(0, null, new FrameLayout(context));

    assertEquals("a received message must be on the other side",
        View.VISIBLE, others(row).getVisibility());
    assertEquals("bring the documents", others(row).getText().toString());
    assertEquals("a received message shown as the user's own is a log they will act on wrongly",
        View.GONE, own(row).getVisibility());
  }

  /**
   * The two must differ in the SAME list, which is how a user actually reads it.
   *
   * <p>Asserting each case alone passes on an adapter that ignores the message and always picks one
   * side - the exact mutation that survived.
   */
  @Test
  public void sentAndReceivedMessagesRenderDifferently() {
    final ListAdapterMessages adapter = adapterFor(sent("mine"), received("theirs"));

    final View mine = adapter.getView(0, null, new FrameLayout(context));
    final View theirs = adapter.getView(1, null, new FrameLayout(context));

    assertEquals(View.VISIBLE, own(mine).getVisibility());
    assertEquals(View.GONE, own(theirs).getVisibility());
    assertEquals("the two sides must differ, or the list says nothing about who spoke",
        View.GONE, others(mine).getVisibility());
    assertEquals(View.VISIBLE, others(theirs).getVisibility());
  }

  /**
   * A recycled row must not carry the previous message's side.
   *
   * <p>{@code convertView} is reused by ListView, and each branch hides the other side explicitly
   * for exactly this reason. Without that, scrolling shows an incoming message on whichever side
   * the recycled row was left in.
   */
  @Test
  public void arecycledRowDoesNotKeepThePreviousMessagesSide() {
    final ListAdapterMessages adapter = adapterFor(sent("mine"), received("theirs"));

    final View recycled = adapter.getView(0, null, new FrameLayout(context));
    assertEquals(View.VISIBLE, own(recycled).getVisibility());

    final View reused = adapter.getView(1, recycled, new FrameLayout(context));

    assertEquals("a recycled row must not still show the previous message on the own side",
        View.GONE, own(reused).getVisibility());
    assertEquals(View.VISIBLE, others(reused).getVisibility());
    assertEquals("theirs", others(reused).getText().toString());
  }
}
