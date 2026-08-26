package com.amnesica.kryptey.inputmethod;

import android.app.assist.AssistStructure;
import android.os.CancellationSignal;
import android.service.autofill.AutofillService;
import android.service.autofill.FillCallback;
import android.service.autofill.FillRequest;
import android.service.autofill.SaveCallback;
import android.service.autofill.SaveRequest;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * An autofill service that fills nothing and writes down everything it was shown.
 *
 * <p>It exists to answer a question REVIVAL.md has carried as open since the layouts were audited:
 * the compose box that holds decrypted plaintext is an ordinary {@code EditText} and nothing sets
 * {@code importantForAutofill} on it, so <em>if</em> Android's autofill framework reaches views
 * inside an IME's own window, every decrypted message is offered to whatever autofill service the
 * user has installed. {@code FLAG_SECURE} does not help: it protects pixels, and autofill reads
 * text.
 *
 * <p>The document declined to add {@code importantForAutofill="no"} as a mitigation, deliberately,
 * on the grounds that this branch has twice added guards it could not demonstrate a need for. It
 * recorded the experiment that would settle it instead: enable an autofill service and see what it
 * is handed. This is that service.
 *
 * <p>It is in {@code src/debug} because an app that ships its own autofill service is a different
 * app. {@code ReleaseManifestHasNoTestScaffoldingTest} derives its scaffolding list from that
 * directory, so this file is covered by it automatically.
 *
 * <p>No API guard is needed: autofill arrived in API 26 and this project's minSdk is 26.
 *
 * <p><b>Everything here is static and process-global</b>, which is normally worth avoiding and is
 * correct here: the framework instantiates this service itself, so a test has no reference to hand.
 * The service and the instrumentation run in the same process, which is what makes the recording
 * readable at all.
 */
public class RecordingAutofillService extends AutofillService {

  /**
   * One fill request, as the framework delivered it.
   *
   * <p>Kept per-request rather than merged into one flat list, because the question this service
   * exists to answer is about <em>when</em> a structure was built, not just what was in it. A
   * merged list cannot distinguish a structure captured before the keyboard window existed from one
   * captured after — and an adversarial review found that the first version of this could only ever
   * have captured the former, which made its conclusion unfalsifiable.
   */
  public static final class Recorded {
    public final int sequence;
    public final int windowCount;
    public final List<String> viewIds;
    public final Set<String> packages;

    Recorded(final int sequence, final int windowCount, final List<String> viewIds,
        final Set<String> packages) {
      this.sequence = sequence;
      this.windowCount = windowCount;
      this.viewIds = Collections.unmodifiableList(viewIds);
      this.packages = Collections.unmodifiableSet(packages);
    }
  }

  private static final List<Recorded> RECORDED = Collections.synchronizedList(new ArrayList<>());

  public static void reset() {
    RECORDED.clear();
  }

  /**
   * How many fill requests have been fully recorded.
   *
   * <p>Derived from the list rather than kept as its own counter, and that is the whole point: an
   * earlier version incremented a counter on entry to {@code onFillRequest} and populated the ids
   * afterwards. {@code onFillRequest} runs on the main thread and the test polls from the
   * instrumentation thread, so the test could see "a request arrived" and read a half-walked
   * structure — host field already recorded, keyboard views not yet. That races toward "safe" in
   * exactly the case worth catching. Nothing is published here until the walk is finished.
   */
  public static int requestCount() {
    return RECORDED.size();
  }

  public static List<Recorded> recorded() {
    synchronized (RECORDED) {
      return new ArrayList<>(RECORDED);
    }
  }

  /** The most recently completed request, or null if none. */
  public static Recorded latest() {
    synchronized (RECORDED) {
      return RECORDED.isEmpty() ? null : RECORDED.get(RECORDED.size() - 1);
    }
  }

  @Override
  public void onFillRequest(final FillRequest request, final CancellationSignal cancellationSignal,
      final FillCallback callback) {
    final List<String> viewIds = new ArrayList<>();
    final Set<String> packages = new LinkedHashSet<>();
    int windowCount = 0;

    for (int c = 0; c < request.getFillContexts().size(); c++) {
      final AssistStructure structure = request.getFillContexts().get(c).getStructure();
      if (structure == null) continue;
      packages.add(String.valueOf(structure.getActivityComponent() == null
          ? null : structure.getActivityComponent().getPackageName()));
      windowCount += structure.getWindowNodeCount();
      for (int i = 0; i < structure.getWindowNodeCount(); i++) {
        record(structure.getWindowNodeAt(i).getRootViewNode(), viewIds, packages);
      }
    }

    // Published only now that the walk is complete. See requestCount().
    RECORDED.add(new Recorded(RECORDED.size() + 1, windowCount, viewIds, packages));

    // Offering no datasets is the point: this service is an observer, and a null response is the
    // documented way to say "nothing to fill" without leaving the session hanging.
    callback.onSuccess(null);
  }

  private void record(final AssistStructure.ViewNode node, final List<String> viewIds,
      final Set<String> packages) {
    if (node == null) return;
    final String id = node.getIdEntry();
    if (id != null) viewIds.add(id);
    final String pkg = node.getIdPackage();
    if (pkg != null) packages.add(pkg);
    for (int i = 0; i < node.getChildCount(); i++) {
      record(node.getChildAt(i), viewIds, packages);
    }
  }

  @Override
  public void onSaveRequest(final SaveRequest request, final SaveCallback callback) {
    callback.onSuccess();
  }
}
