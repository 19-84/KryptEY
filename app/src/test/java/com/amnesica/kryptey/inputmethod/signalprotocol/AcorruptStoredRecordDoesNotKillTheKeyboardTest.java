package com.amnesica.kryptey.inputmethod.signalprotocol;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.amnesica.kryptey.inputmethod.signalprotocol.stores.KyberPreKeyStoreImpl;
import com.amnesica.kryptey.inputmethod.signalprotocol.stores.PreKeyStoreImpl;
import com.amnesica.kryptey.inputmethod.signalprotocol.stores.SessionStoreImpl;
import com.amnesica.kryptey.inputmethod.signalprotocol.stores.SignedPreKeyStoreImpl;
import com.amnesica.kryptey.inputmethod.signalprotocol.stores.StoredRecordUnreadableException;
import com.amnesica.kryptey.inputmethod.signalprotocol.util.ProtocolAddresses;

import org.junit.Test;
import org.signal.libsignal.protocol.SignalProtocolAddress;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A corrupt record in this app's own protocol store must not take the keyboard with it.
 *
 * <p>The stores answered an unparseable record with {@code throw new AssertionError(e)} — libsignal's
 * idiom, encoding an assumption this project has already disproved: that bytes the app wrote itself
 * cannot come back corrupt. The store is sealed per value and one flipped byte makes GCM refuse,
 * which is the same reachable state the chat log's unreadable handling exists for.
 *
 * <p>The type is what made it dangerous. {@code AssertionError} is an {@code Error}, and every guard
 * on the paths reaching these stores catches {@code RuntimeException} or {@code Exception} — a
 * neighbouring comment says exactly that about {@code OutOfMemoryError}. These stores are called by
 * libsignal from inside {@code decrypt}, which runs from a click listener, so a corrupt record killed
 * the input method in whatever app the user was typing in; the condition is persistent, so it was
 * crash-on-tap until reinstall.
 *
 * <p>This asserts the property that matters at the boundary — what comes out is something the
 * existing guards catch — rather than a message, because the message is not what keeps the keyboard
 * alive.
 */
public class AcorruptStoredRecordDoesNotKillTheKeyboardTest {

  /** Replaces a store's backing map with one holding bytes that are not a record. */
  @SuppressWarnings("unchecked")
  private static void corrupt(final Object store, final Object key) throws Exception {
    final Field field = store.getClass().getDeclaredField("store");
    field.setAccessible(true);
    final Map<Object, byte[]> map = (Map<Object, byte[]>) field.get(store);
    map.put(key, new byte[] {9, 9, 9, 9, 9, 9, 9, 9});
  }

  private static SignalProtocolAddress address() {
    return ProtocolAddresses.of("11111111-1111-1111-1111-111111111111", 1);
  }

  /**
   * Every failure below must be catchable by a {@code catch (RuntimeException)}.
   *
   * <p>The property is the TYPE FAMILY, not our own class. Some of these paths fail inside
   * libsignal, which raises its own unchecked exceptions - {@code InvalidSessionException} for a
   * session record it cannot parse - and those are caught by the same guards. Requiring
   * {@code StoredRecordUnreadableException} specifically would have been asserting our
   * implementation rather than the thing that keeps the keyboard alive, and it failed here for
   * exactly that reason.
   */
  private static void assertCatchable(final String what, final ThrowingCall call) {
    try {
      call.run();
    } catch (final RuntimeException caught) {
      return;
    } catch (final Exception checked) {
      return;   // a checked exception is declared, so a caller cannot ignore it by accident
    } catch (final Throwable fatal) {
      throw new AssertionError(what + " threw " + fatal.getClass().getName()
          + ", which is not a RuntimeException - every guard on the paths that reach these stores "
          + "catches RuntimeException or Exception, so this kills the input method in whatever app "
          + "the user is typing in, persistently", fatal);
    }
    throw new AssertionError(what + " did not fail at all, so this test is asserting nothing about "
        + "corrupt records");
  }

  private interface ThrowingCall {
    void run() throws Exception;
  }

  /**
   * The session store keeps a list of its own wrapper type rather than a raw map, so the corruption
   * is placed through its own writer - which is closer to how a real corrupt record arrives anyway.
   */
  @Test
  public void acorruptSessionRecordIsCatchable() throws Exception {
    final SessionStoreImpl store = new SessionStoreImpl();
    store.storeSession(address(),
        new org.signal.libsignal.protocol.state.SessionRecord(new byte[] {}));
    final Field field = SessionStoreImpl.class.getDeclaredField("sessions");
    field.setAccessible(true);
    final java.util.List<?> sessions = (java.util.List<?>) field.get(store);
    final Object session = sessions.get(0);
    final Field record = session.getClass().getDeclaredField("serializedSessionRecord");
    record.setAccessible(true);
    record.set(session, new byte[] {9, 9, 9, 9, 9, 9, 9, 9});

    assertCatchable("loadSession", () -> store.loadSession(address()));
  }

  @Test
  public void acorruptSignedPreKeyIsCatchable() throws Exception {
    final SignedPreKeyStoreImpl store = new SignedPreKeyStoreImpl();
    corrupt(store, 7);
    assertCatchable("loadSignedPreKey", () -> store.loadSignedPreKey(7));
  }

  /** The pre-key store wraps its bytes, so the corruption goes inside the wrapper. */
  @Test
  public void acorruptPreKeyIsCatchable() throws Exception {
    final PreKeyStoreImpl store = new PreKeyStoreImpl();
    final Field field = PreKeyStoreImpl.class.getDeclaredField("store");
    field.setAccessible(true);
    @SuppressWarnings("unchecked")
    final Map<Integer, Object> map = (Map<Integer, Object>) field.get(store);
    map.put(7, new com.amnesica.kryptey.inputmethod.signalprotocol.PreKeyWithStatus(
        new byte[] {9, 9, 9, 9, 9, 9, 9, 9}, false));

    assertCatchable("loadPreKey", () -> store.loadPreKey(7));
  }

  @Test
  public void acorruptKyberPreKeyIsCatchable() throws Exception {
    final KyberPreKeyStoreImpl store = new KyberPreKeyStoreImpl();
    corrupt(store, 7);
    assertCatchable("loadKyberPreKey", () -> store.loadKyberPreKey(7));
  }

  /**
   * And no store answers a corrupt record with an Error, which is the shape of the defect.
   *
   * <p>A source scan, because the four cases above cover the stores that can be driven directly and
   * this covers the ones that cannot without a session fixture. The idiom is what is being
   * forbidden, not one instance of it.
   */
  @Test
  public void nostoreThrowsAnErrorForAcorruptRecord() throws Exception {
    final java.nio.file.Path dir = java.nio.file.Paths.get(
        "app/src/main/java/com/amnesica/kryptey/inputmethod/signalprotocol/stores");
    final java.nio.file.Path root = java.nio.file.Files.isDirectory(dir) ? dir
        : java.nio.file.Paths.get("src/main/java/com/amnesica/kryptey/inputmethod/signalprotocol/stores");
    assertTrue("the stores package must be findable, or this test checks nothing",
        java.nio.file.Files.isDirectory(root));

    final List<String> offenders = new ArrayList<>();
    int scanned = 0;
    try (java.util.stream.Stream<java.nio.file.Path> files = java.nio.file.Files.list(root)) {
      for (final java.nio.file.Path file : (Iterable<java.nio.file.Path>) files::iterator) {
        if (!file.toString().endsWith(".java")) continue;
        scanned++;
        final String source = new String(java.nio.file.Files.readAllBytes(file),
            java.nio.charset.StandardCharsets.UTF_8).replaceAll("(?s)/\\*.*?\\*/", " ");
        if (source.contains("new AssertionError")) offenders.add(file.getFileName().toString());
      }
    }

    assertTrue("no store files were scanned", scanned > 3);
    assertEquals("a store answers a corrupt record with an Error. Errors are not caught by any "
        + "guard on the paths that reach these stores, and the condition is persistent - so it is "
        + "crash-on-tap in every app until reinstall:\n" + String.join("\n", offenders),
        0, offenders.size());
  }
}
