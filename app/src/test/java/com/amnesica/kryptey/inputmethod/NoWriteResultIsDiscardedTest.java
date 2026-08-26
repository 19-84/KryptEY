package com.amnesica.kryptey.inputmethod;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A method that answers "did this happen" must not be called as if it answered nothing.
 *
 * <p>Three separate defects on this branch were this shape, and each cost a review round to find:
 *
 * <ul>
 *   <li>{@code processPreKeyResponseMessage}'s refusal was discarded inside {@code decrypt}, so a
 *       bundle the app had rejected was reported to the user as an accepted invite.
 *   <li>{@code verifyContactInContactList} ended {@code storeAll…(); return true;} — the one failure
 *       that means "this verification was not recorded" was the one it could not report.
 *   <li>{@code storeMessageLog} swallowed its exception and its caller returned true on the account
 *       batch alone, so a message could be delivered and silently absent from the history.
 * </ul>
 *
 * <p>They are hard to see by reading because a discarded return looks exactly like a void call. So
 * this scans for the shape: a call, as a bare statement, to a method in the same file that returns
 * {@code boolean}.
 *
 * <p><b>What it cannot do.</b> It reasons about calls to methods declared in the same file, so a
 * discarded result from another class is invisible to it. It also cannot tell a result that matters
 * from one that does not — which is the point of {@code DELIBERATE}: an entry there is somebody
 * arguing that the answer is genuinely not needed at that call site, in writing.
 */
public class NoWriteResultIsDiscardedTest {

  private static final String[] FILES = {
      "com/amnesica/kryptey/inputmethod/signalprotocol/SignalProtocolMain.java",
      "com/amnesica/kryptey/inputmethod/signalprotocol/helper/StorageHelper.java",
      "com/amnesica/kryptey/inputmethod/signalprotocol/Account.java",
      "com/amnesica/kryptey/inputmethod/latin/e2ee/E2EEStripView.java",
  };

  /**
   * Call sites where discarding the answer is deliberate, keyed by {@code caller -> callee}.
   *
   * <p>Per SITE, not per name. A name-level list is too coarse to be worth much here: the same
   * method is consumed where its answer matters and ignored where it does not, and exempting the
   * name would hide the site that matters. Each entry is an argument about one call.
   */
  private static final Map<String, String> DELIBERATE = new TreeMap<>();

  static {
    // The warn* methods are WRITERS whose boolean is a convenience for callers that need to know
    // whether they fired. Calling them for their effect and ignoring the answer is their ordinary
    // use, and the short-circuit guard covers the mistake that actually matters for them.
    DELIBERATE.put("processSignalMessage->warnIfKeyWasRejected", "called for its effect");
    DELIBERATE.put("processPreKeyResponse->warnIfKeyWasRejected", "called for its effect");
    DELIBERATE.put("processUpdatedPreKeyResponse->warnIfKeyWasRejected", "called for its effect");
    DELIBERATE.put("addContact->warnIfKeyWasRejected", "called for its effect");
    DELIBERATE.put("decryptMessageAndShowMessageInMainInputField->warnIfIdentityChanged",
        "the result is captured into a local on the line above; this is the second, effect-only use");
    // The persists below are best-effort saves on paths with no channel to the user. Every
    // operation that makes the user a PROMISE consumes the result instead: verify and reject report
    // it, deleting a contact reports it, and the chat log has its own flag - those were the three
    // this guard was built after. Inventing a notice for each of the rest would mean designing UI
    // for states nobody has specified, and a failed write on any of them is surfaced by the next
    // operation that does have a channel.
    DELIBERATE.put("buildSession->storeAllAccountInformationInSharedPreferences",
        "the caller reports whether the session was built; a lost write is re-derived from the peer");
    DELIBERATE.put("createAndAddContactToList->storeAllAccountInformationInSharedPreferences",
        "the add-contact path reports its own outcome and the row is visible immediately");
    DELIBERATE.put("decrypt->storeAllAccountInformationInSharedPreferences",
        "the chat-log half has its own flag, which is the half with a user-visible consequence");
    DELIBERATE.put("dismissIdentityChange->storeAllAccountInformationInSharedPreferences",
        "no production caller; the wired exit is verifyContactInContactList, which does report");
    DELIBERATE.put("encrypt->storeAllAccountInformationInSharedPreferences",
        "the chat-log half has its own flag; the ciphertext has already gone to the messenger");
    DELIBERATE.put("getPreKeyBundle->storeAllAccountInformationInSharedPreferences",
        "a lost pre-key allocation costs one invite, and the allocator mints again on the next");
    DELIBERATE.put("importOutOfBandKeyBundle->storeAllAccountInformationInSharedPreferences",
        "no production caller");
    DELIBERATE.put("recordIdentityChangeIfOffered->storeAllAccountInformationInSharedPreferences",
        "the record is re-made by the next bundle carrying the same substituted key");
    DELIBERATE.put("processUpdatedPreKeyResponse->decryptMessageAndShowMessageInMainInputField",
        "this arm asks lastAttachedBundleWasRefused instead, which is the fact it wanted - the "
            + "return value had stopped meaning 'the bundle was accepted'");
    DELIBERATE.put("addContact->decryptMessageAndShowMessageInMainInputField",
        "the ciphertext arm has already warned about anything it pinned, and the add-contact banner "
            + "is written by the arm above rather than by whether a message came out");
  }

  private static final Set<String> KEYWORDS = new TreeSet<>(Arrays.asList(
      "if", "for", "while", "switch", "catch", "try", "synchronized", "else", "do", "return"));

  /** The name of the method a position sits inside, for per-site exemptions. */
  private static String enclosingMethodName(final String text, final int position) {
    final Matcher declaration = Pattern.compile(
        "\n  (?:public |private |protected |static |final |synchronized )*"
            + "[\\w<>\\[\\], .?]+ (\\w+)\\s*\\([^)]*\\)\\s*(?:throws [\\w, .]+)?\\{").matcher(text);
    String enclosing = "<none>";
    while (declaration.find()) {
      if (declaration.start() > position) break;
      // `if (...) {` and friends match the same shape as a method declaration. Without this the
      // enclosing name comes back as "if", and a per-site exemption keyed on it would be nonsense.
      if (KEYWORDS.contains(declaration.group(1))) continue;
      enclosing = declaration.group(1);
    }
    return enclosing;
  }

  /** Whether the previous non-space character says this line continues an earlier statement. */
  private static boolean isContinuation(final String text, final int start) {
    for (int i = start - 1; i >= 0; i--) {
      final char c = text.charAt(i);
      if (Character.isWhitespace(c)) continue;
      return c != ';' && c != '{' && c != '}' && c != ':';
    }
    return false;
  }

  private static Path mainSources() {
    for (final String candidate : new String[] {"src/main/java", "app/src/main/java"}) {
      final Path path = Paths.get(candidate);
      if (Files.isDirectory(path)) return path;
    }
    throw new IllegalStateException("could not locate the main source tree");
  }

  private static String withoutCommentsAndStrings(final String source) {
    return source.replaceAll("(?s)/\\*.*?\\*/", " ")
        .replaceAll("(?m)//[^\n]*", " ")
        .replaceAll("\"(?:[^\"\\\\]|\\\\.)*\"", "\"\"");
  }

  /** Methods in this file that return a boolean, by name. */
  private static Set<String> booleanMethods(final String text) {
    final Set<String> found = new TreeSet<>();
    final Matcher declaration = Pattern.compile(
        "\n  (?:public |private |protected |static |final |synchronized )*boolean (\\w+)\\s*\\(")
        .matcher(text);
    while (declaration.find()) found.add(declaration.group(1));
    return found;
  }

  @Test
  public void noboolReturningMethodIsCalledAsABareStatement() throws IOException {
    final Map<String, List<String>> offenders = new LinkedHashMap<>();
    int declarationsSeen = 0;

    for (final String relative : FILES) {
      final Path path = mainSources().resolve(relative);
      assertTrue("expected to find " + path, Files.exists(path));
      final String text = withoutCommentsAndStrings(
          new String(Files.readAllBytes(path), StandardCharsets.UTF_8));

      final Set<String> answering = booleanMethods(text);
      declarationsSeen += answering.size();

      // A bare statement: start of a line, an optional receiver, the call, then a semicolon.
      final Matcher statement = Pattern.compile(
          "(?m)^\\s*(?:[\\w]+\\.)*(\\w+)\\s*\\([^;]*\\)\\s*;").matcher(text);
      while (statement.find()) {
        final String called = statement.group(1);
        if (!answering.contains(called)) continue;
        // Not a bare statement if it is the continuation of one. A multi-line assignment puts its
        // second operand at the start of a line and ending in a semicolon, which looks identical to
        // a discarded call - and `final boolean x = a(...) || b(...);` is exactly that shape.
        if (isContinuation(text, statement.start())) continue;
        final String site = enclosingMethodName(text, statement.start()) + "->" + called;
        if (DELIBERATE.containsKey(site)) continue;
        offenders.computeIfAbsent(relative, key -> new ArrayList<>()).add(site);
      }
    }

    assertTrue("no boolean-returning methods were found at all, so this test is reading nothing",
        declarationsSeen >= 10);

    final List<String> lines = new ArrayList<>();
    for (final Map.Entry<String, List<String>> entry : offenders.entrySet()) {
      lines.add(entry.getKey() + " :: " + new TreeSet<>(entry.getValue()));
    }

    assertEquals("a method that answers \"did this happen\" is being called as if it answered "
        + "nothing. Three defects on this branch were exactly that, and each was invisible on "
        + "reading because a discarded return looks like a void call: a refused key bundle "
        + "reported as an accepted invite, a verification that was never recorded reported as "
        + "success, and a message delivered and silently missing from the history. Consume the "
        + "result, or add the name to DELIBERATE with the argument for why the answer is genuinely "
        + "not needed there:\n" + String.join("\n", lines), 0, lines.size());
  }
}
