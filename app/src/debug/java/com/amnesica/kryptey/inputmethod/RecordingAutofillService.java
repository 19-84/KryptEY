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

  /** Every autofill id string the framework has shown this service, newest last. */
  private static final List<String> SEEN_VIEW_IDS = Collections.synchronizedList(new ArrayList<>());

  /** Every package the framework has attributed a structure to. */
  private static final Set<String> SEEN_PACKAGES =
      Collections.synchronizedSet(new LinkedHashSet<>());

  private static volatile int requestCount;

  public static void reset() {
    SEEN_VIEW_IDS.clear();
    SEEN_PACKAGES.clear();
    requestCount = 0;
  }

  /** How many fill requests arrived. Zero means the experiment measured nothing. */
  public static int requestCount() {
    return requestCount;
  }

  public static List<String> seenViewIds() {
    synchronized (SEEN_VIEW_IDS) {
      return new ArrayList<>(SEEN_VIEW_IDS);
    }
  }

  public static Set<String> seenPackages() {
    synchronized (SEEN_PACKAGES) {
      return new LinkedHashSet<>(SEEN_PACKAGES);
    }
  }

  @Override
  public void onFillRequest(final FillRequest request, final CancellationSignal cancellationSignal,
      final FillCallback callback) {
    requestCount++;
    final List<AssistStructure> structures = request.getFillContexts().isEmpty()
        ? Collections.emptyList()
        : new ArrayList<>();
    for (int i = 0; i < request.getFillContexts().size(); i++) {
      structures.add(request.getFillContexts().get(i).getStructure());
    }
    for (final AssistStructure structure : structures) {
      SEEN_PACKAGES.add(String.valueOf(structure.getActivityComponent() == null
          ? null : structure.getActivityComponent().getPackageName()));
      for (int i = 0; i < structure.getWindowNodeCount(); i++) {
        record(structure.getWindowNodeAt(i).getRootViewNode());
      }
    }
    // Offering no datasets is the point: this service is an observer, and a null response is the
    // documented way to say "nothing to fill" without leaving the session hanging.
    callback.onSuccess(null);
  }

  private void record(final AssistStructure.ViewNode node) {
    if (node == null) return;
    final String id = node.getIdEntry();
    if (id != null) SEEN_VIEW_IDS.add(id);
    final String pkg = node.getIdPackage();
    if (pkg != null) SEEN_PACKAGES.add(pkg);
    for (int i = 0; i < node.getChildCount(); i++) {
      record(node.getChildAt(i));
    }
  }

  @Override
  public void onSaveRequest(final SaveRequest request, final SaveCallback callback) {
    callback.onSuccess();
  }
}
