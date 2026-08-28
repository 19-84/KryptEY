package com.amnesica.kryptey.inputmethod.signalprotocol.storage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * The write-back inside {@code reloadAccount} must not count as a landed write.
 *
 * <p>The counter answers one question: "has anything reached disk since this contact's row failed to
 * get there?" A yes expires the send-refusal, because the account batch writes the whole contact
 * list. The write-back inside {@code reloadAccount} is the one write for which that reasoning does
 * not hold — it stores what it has <em>just read</em>, so it cannot contain the row an earlier
 * failure lost.
 *
 * <p>Counting it made every carried refusal dead on arrival. The order on a configuration change is:
 * reload from disk, write back, build the new strip, restore its state. So the counter moved before
 * the map arrived, and every restored entry satisfied "a later write landed" on its first read. The
 * refusal was destroyed by exactly the event it was carried across.
 *
 * <p><b>Why this is a source scan and not a behavioural test.</b> Reaching the write-back means
 * getting {@code reloadAccount} to load a real account and store it, which needs a working
 * {@code AndroidKeystore} — there is none under Robolectric, so {@code secureStore()} returns null,
 * every write fails, and a behavioural test would pass whether or not the exclusion existed. That is
 * a vacuous control, and this file exists instead of one: it asserts the shape that makes the
 * defect impossible, and says plainly that it is doing so.
 */
public class TheReloadWriteBackIsNotCountedTest {

  private static String source() throws IOException {
    Path here = Paths.get("").toAbsolutePath();
    while (here != null && !Files.exists(here.resolve("gradle/verification-metadata.xml"))) {
      here = here.getParent();
    }
    assertTrue("the repository root must be findable, or this test checks nothing", here != null);
    return new String(Files.readAllBytes(here.resolve(
        "app/src/main/java/com/amnesica/kryptey/inputmethod/signalprotocol/SignalProtocolMain.java")),
        StandardCharsets.UTF_8);
  }

  /** The body of {@code reloadAccount}, from its signature to the next method. */
  private static String reloadAccountBody() throws IOException {
    final String all = source();
    final int start = all.indexOf("public static void reloadAccount(");
    assertTrue("reloadAccount must exist, or this test is scanning for something that has been "
        + "renamed and is passing by absence", start > 0);
    int depth = 0;
    int i = all.indexOf('{', start);
    final int bodyStart = i;
    while (i < all.length()) {
      if (all.charAt(i) == '{') depth++;
      else if (all.charAt(i) == '}') {
        depth--;
        if (depth == 0) break;
      }
      i++;
    }
    return all.substring(bodyStart, i);
  }

  @Test
  public void reloadAccountRestoresTheCounterAroundItsWriteBack() throws IOException {
    final String body = reloadAccountBody();
    assertTrue("reloadAccount must put the counter back after writing. Without it the write-back "
            + "counts as 'something reached disk since your contact failed to save', which is false "
            + "- it wrote what it had just read - and it fires on every configuration change, "
            + "destroying every carried send-refusal at the moment the new strip adopts it.",
        body.contains("mAccountWritesLanded = before"));
    // The CALL, not the name: the method's own comment mentions
    // storeAllAccountInformationInSharedPreferences several lines above the write, and the first
    // version of this assertion matched that prose and reported the ordering backwards.
    assertTrue("and it must capture the count BEFORE the write, not after",
        body.indexOf("final long before")
            < body.indexOf("sInstance.storeAllAccountInformationInSharedPreferences()"));
  }

  /**
   * And the counter has exactly one increment, so the exclusion cannot be sidestepped.
   *
   * <p>If a second site started incrementing, the reload's careful restore would be undone by
   * whatever else it called, and this file would still be green.
   */
  @Test
  public void thecounterIsIncrementedInExactlyOnePlace() throws IOException {
    final String all = source();
    final int increments = all.split("mAccountWritesLanded\\+\\+", -1).length - 1;
    assertEquals("the counter must be incremented in exactly one place - the single write path - "
        + "or the reload's exclusion is not the only thing deciding what counts", 1, increments);
  }
}
