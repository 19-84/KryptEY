package com.amnesica.kryptey.inputmethod.signalprotocol.storage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import android.content.Context;
import android.content.SharedPreferences;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The batch write must reach disk as ONE commit.
 *
 * <p>That is the whole of what makes it atomic. SharedPreferences writes its file by rename, keeping
 * the previous contents in a .bak until the new file is complete, so a single commit either lands
 * entirely or not at all against process death. A loop of eight commits has none of that property
 * however tidy it looks from the caller's side.
 *
 * <p>Counting commits is the only way to see it: eight commits and one commit leave identical state
 * behind when nothing goes wrong, so every state-based assertion passes on both. A mutation
 * replacing the single commit with one per entry survived the entire suite before this existed.
 */
@RunWith(RobolectricTestRunner.class)
public class BatchCommitsOnceTest {

  private int commits;
  private int editors;
  private SharedPreferences counting;

  /**
   * A SharedPreferences that delegates everything but counts {@code edit()} and {@code commit()}.
   * A dynamic proxy rather than a hand-written stub: the interface is large, and every method I did
   * not implement would be a silent behaviour change rather than a compile error.
   */
  @Before
  public void wrapPreferences() {
    final Context context = RuntimeEnvironment.getApplication();
    final SharedPreferences real = context.getSharedPreferences("batch-test", Context.MODE_PRIVATE);
    real.edit().clear().commit();
    commits = 0;
    editors = 0;

    final InvocationHandler editorHandler = (proxy, method, args) -> {
      final SharedPreferences.Editor editor = (SharedPreferences.Editor) currentEditor;
      if ("commit".equals(method.getName())) {
        commits++;
        if (commitsFail) return Boolean.FALSE;
      }
      final Object result = method.invoke(editor, args);
      // Editor methods chain: hand back the proxy so the chain keeps being counted.
      return result == editor ? proxy : result;
    };

    counting = (SharedPreferences) Proxy.newProxyInstance(
        SharedPreferences.class.getClassLoader(), new Class<?>[] {SharedPreferences.class},
        (proxy, method, args) -> {
          if ("edit".equals(method.getName())) {
            editors++;
            currentEditor = real.edit();
            return Proxy.newProxyInstance(SharedPreferences.Editor.class.getClassLoader(),
                new Class<?>[] {SharedPreferences.Editor.class}, editorHandler);
          }
          return method.invoke(real, args);
        });
  }

  private Object currentEditor;
  private boolean commitsFail;

  private static Map<String, String> eightEntries() {
    final Map<String, String> entries = new LinkedHashMap<>();
    for (int i = 1; i <= 8; i++) entries.put("key-" + i, "value-" + i);
    return entries;
  }

  @Test
  public void abatchOfEightIsOneCommit() {
    new SharedPreferencesKeyValueStore(counting).putAll(eightEntries());

    assertEquals("eight entries must be one durable commit, not one per entry", 1, commits);
    assertEquals("and one editor", 1, editors);
  }

  @Test
  public void everyEntryIsActuallyWritten() {
    final SharedPreferencesKeyValueStore store = new SharedPreferencesKeyValueStore(counting);
    store.putAll(eightEntries());

    for (int i = 1; i <= 8; i++) {
      assertEquals("entry " + i + " was not written", "value-" + i, store.get("key-" + i));
    }
  }

  /** An empty batch must not commit an empty editor - a save with nothing to save is not a write. */
  @Test
  public void anEmptyBatchStillBehaves() {
    new SharedPreferencesKeyValueStore(counting).putAll(new LinkedHashMap<>());

    assertEquals("an empty batch may commit at most once", 1, commits);
  }

  /** Single writes are unaffected: one entry, one commit, as before. */
  @Test
  public void asingleWriteIsStillOneCommit() {
    new SharedPreferencesKeyValueStore(counting).put("solo", "value");

    assertEquals(1, commits);
  }

  /**
   * A failed batch commit must throw, not be absorbed.
   *
   * <p>The reason is specific and easy to get wrong: on a full disk or any I/O error,
   * SharedPreferences deletes the partial file, restores the previous contents from its .bak on the
   * next load, and returns false - while the IN-MEMORY map keeps the new values. So the running
   * process looks perfectly healthy and the account it believes it saved does not exist on disk.
   * Nothing else in the system can notice; the next restart simply comes back to the old account.
   */
  @Test
  public void afailedBatchCommitThrowsRatherThanLookingHealthy() {
    commitsFail = true;

    assertThrows("a batch that did not reach disk must not return normally",
        SharedPreferencesKeyValueStore.StorageWriteException.class,
        () -> new SharedPreferencesKeyValueStore(counting).putAll(eightEntries()));
  }

  /** Same for a single write, which is the path the migration uses. */
  @Test
  public void afailedSingleCommitThrowsToo() {
    commitsFail = true;

    assertThrows(SharedPreferencesKeyValueStore.StorageWriteException.class,
        () -> new SharedPreferencesKeyValueStore(counting).put("solo", "value"));
  }
}
