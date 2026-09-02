package com.amnesica.kryptey.inputmethod.latin.utils;

import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.util.Xml;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.xmlpull.v1.XmlPullParser;

import java.io.StringReader;

/**
 * A layout file that is not shaped the way the parser expects must fail loudly.
 *
 * <p>These two guards are reached only from keyboard inflation - {@code KeyboardBuilder},
 * {@code KeyStylesSet}, {@code KeyboardLayoutSet}, eleven call sites and no others - so the obvious
 * assumption is that inflating a real keyboard covers them. <b>It does not, and that was measured
 * rather than assumed:</b> deleting {@code checkEndTag}'s refusal leaves both
 * {@code EveryKeyIsWhereItIsDrawnTest} and {@code HostChosenCacheKeyTest} green. The 196 shipped
 * layouts are well-formed, so the refusal never fires on the happy path, and a guard that only
 * fires on bad input is not exercised by good input.
 *
 * <p>The input is trusted - layout XML ships inside the signed APK, so no attacker supplies it.
 * That makes this a guard against <em>our own</em> mistake: someone editing one of 196 layout files
 * leaves a tag non-empty, and the choice is between a parse that fails naming the tag and a
 * keyboard quietly built wrong. This pins the first.
 */
@RunWith(RobolectricTestRunner.class)
public class AmalformedLayoutIsRefusedLoudlyTest {

  /** A parser positioned on the start tag of {@code <Key>...}, as the builder leaves it. */
  private static XmlPullParser positionedOnKey(final String xml) throws Exception {
    final XmlPullParser parser = Xml.newPullParser();
    parser.setInput(new StringReader(xml));
    int event = parser.next();
    while (event != XmlPullParser.START_TAG && event != XmlPullParser.END_DOCUMENT) {
      event = parser.next();
    }
    assertTrue("fixture: the parser must be sitting on a start tag",
        event == XmlPullParser.START_TAG);
    return parser;
  }

  /** The premise: an empty tag is accepted, so the refusal below is about the shape. */
  @Test
  public void thepremiseThatAnEmptyTagIsAccepted() throws Exception {
    XmlParseUtils.checkEndTag("Key", positionedOnKey("<Key/>"));
  }

  @Test
  public void atagCarryingChildrenIsRefusedNamingTheTag() throws Exception {
    final XmlPullParser parser = positionedOnKey("<Key><Unexpected/></Key>");

    final XmlParseUtils.ParseException refused = assertThrows(
        "a tag the parser requires to be empty must be refused when it is not - otherwise the "
            + "children are skipped and the keyboard is built from a layout nobody wrote",
        XmlParseUtils.NonEmptyTag.class, () -> XmlParseUtils.checkEndTag("Key", parser));

    assertTrue("and the refusal must name the tag, because the person who meets this is editing "
            + "one of 196 layout files: " + refused.getMessage(),
        refused.getMessage().contains("Key"));
  }

  /**
   * Text content counts as content too.
   *
   * <p>The likelier editing slip of the two - a stray newline or label typed between the tags looks
   * like nothing in a diff.
   */
  @Test
  public void atagCarryingTextIsRefusedAswell() throws Exception {
    final XmlPullParser parser = positionedOnKey("<Key>stray text</Key>");

    assertThrows("text between the tags is content, and a tag required to be empty must refuse it",
        XmlParseUtils.NonEmptyTag.class, () -> XmlParseUtils.checkEndTag("Key", parser));
  }

  /**
   * Where this guard's job ends, recorded because the obvious fourth case does not belong to it.
   *
   * <p>A first version asserted that {@code <Key></Row>} is refused as a {@code ParseException}. It
   * is refused - but by the XML parser, as a plain {@code XmlPullParserException}, because that is
   * not well-formed XML and the mismatch is caught a layer below {@code checkEndTag} ever seeing
   * it. Widening the expected type to {@code XmlPullParserException} would have made it pass while
   * no longer distinguishing "this app's guard refused" from "the XML parser refused", which is the
   * vacuity this branch keeps rejecting.
   *
   * <p>So the boundary is: well-formedness is the parser's, and "this particular tag must have no
   * content" is this guard's. The case is left here as prose rather than as a test that would
   * measure someone else's code.
   */
  @Test
  public void thewellFormednessBoundaryBelongsToTheParserNotToThisGuard() throws Exception {
    final XmlPullParser parser = positionedOnKey("<Key></Row>");

    final Exception refused = assertThrows("malformed nesting must still be refused - by someone",
        Exception.class, () -> XmlParseUtils.checkEndTag("Key", parser));
    assertTrue("and it is the XML parser that refuses it, not NonEmptyTag - if this ever becomes a "
            + "NonEmptyTag, the guard has taken on well-formedness and this file should say so: "
            + refused.getClass().getName(),
        !(refused instanceof XmlParseUtils.NonEmptyTag));
  }
}
