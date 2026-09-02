package com.amnesica.kryptey.inputmethod;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * CI must still run the things this branch relies on it running.
 *
 * <p>Two tests already read {@code build.yml} - one checks dependency verification is not disabled
 * there, the other that a quoted artifact count matches the metadata. Neither checks the workflow
 * still <em>runs the suite</em>. Delete or rename the "Unit tests" step and CI goes green in about
 * ninety seconds having executed nothing, which is the most flattering possible failure: a green
 * tick on a pull request that ran no tests.
 *
 * <p>That is the same shape as a guard which stops firing - the difference between "passed" and
 * "was not run" is invisible from the outside, and this branch has now been caught by that
 * distinction six separate ways. A workflow is the one place where nobody is watching the output at
 * all.
 *
 * <p>Matched on the gradle task rather than the step name, because the step name is a label and the
 * task is the thing that happens. Renaming "Unit tests" to "Tests" should not fail this; deleting
 * the line that runs them should.
 */
public class CiStillRunsTheSuiteTest {

  /** The gradle invocations CI must still make, and what each one is protecting. */
  private static final String[][] REQUIRED = {
      // The suite this whole branch is built on. Everything else here is downstream of it.
      {"testDebugUnitTest", "the unit suite - without it a pull request is green having run nothing"},
      // Catches what the unit tests cannot: resource linking, manifest merging, dexing.
      {"assembleDebug", "the debug build, which is what catches a resource or manifest break"},
      // The variant that ships. It has its own lint gating and its own native-library strip.
      {"assembleRelease", "the release build, which has different processing from debug"},
      // 602 issues are filtered by a baseline; the point of running lint is the ones that are not.
      {"lintDebug", "lint, whose baseline only means something if lint still runs"},
  };

  private static String workflow() throws IOException {
    for (final String candidate : new String[] {
        ".github/workflows/build.yml", "../.github/workflows/build.yml"}) {
      final Path path = Paths.get(candidate);
      if (Files.exists(path)) {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
      }
    }
    throw new IllegalStateException("could not locate the CI workflow");
  }

  @Test
  public void theworkflowStillInvokesEveryTaskThisBranchDependsOn() throws IOException {
    final String yaml = workflow();

    assertTrue("the workflow must actually contain steps; if this file has become a stub, every "
        + "assertion below holds vacuously", yaml.contains("run:"));

    for (final String[] required : REQUIRED) {
      assertTrue("CI no longer runs " + required[0] + " - " + required[1] + ". If the task moved, "
              + "point this test at its new name; if it was dropped, that is a decision worth "
              + "making out loud rather than by deleting a line from a YAML file.",
          yaml.contains(required[0]));
    }
  }

  /**
   * And the suite must not be allowed to fail quietly.
   *
   * <p>{@code continue-on-error} on the test step, or a {@code || true} after the gradle call,
   * leaves the step red and the job green. That is worse than not running the tests: the reports
   * are uploaded, the run looks thorough, and nothing gates on the result.
   */
  @Test
  public void nostepIsAllowedToFailWithoutFailingTheJob() throws IOException {
    final String yaml = workflow();

    assertTrue("continue-on-error appears in the workflow. On the test or build steps it turns a "
            + "failure into a green tick with the reports still attached, which reads more "
            + "convincing than a run that did nothing",
        !yaml.contains("continue-on-error"));
    assertTrue("a gradle invocation is suffixed with '|| true', which swallows its exit status - "
            + "the same way piping a script into grep does, and this branch has been caught by "
            + "that once already",
        !yaml.contains("|| true"));
  }
}
