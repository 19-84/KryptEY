# Building KryptEY reproducibly

The build environment is pinned here rather than described in prose, because every part of it has
been a source of confusion at least once: which JDK, which Android platform, which build-tools, and
whether dependency verification was actually on.

```
docker build -t kryptey-build:38 -f tools/Dockerfile tools/
tools/build-in-docker testDebugUnitTest
```

## What is pinned, and why it matters

- **Temurin 21.** 17 is the *minimum* AGP and libsignal accept, and this image ran it for a long
  time. What 17 cannot do is run lint: `BidirectionalTextDetector` calls `List.removeLast()`, a Java
  21 API, so `lintDebug` aborts with "this is a bug in lint or one of the libraries it depends on"
  and produces no analysis. The CI workflow runs that task, so its Lint step had never passed once.
  Verified on 21 before moving: the full suite, `assembleRelease` with the strip gate, and lint
  itself.
- **platform android-35**, matching `compileSdk 35`.
- **build-tools 35.0.0 and 36.0.0.** AGP asks for 36.0.0; the image carried only 35.0.0 for a long
  time, and everyone who touched this repo worked around it by relabelling a copy of the 35.0.0
  directory. That worked, and it meant nobody was building with the tools AGP actually selects.
  Both are installed now so no relabelling is needed.
- **Gradle 9.7.1**, from `gradle/wrapper/gradle-wrapper.properties`, fetched by the wrapper. The
  distribution is pinned by `distributionSha256Sum`, and the committed `gradle-wrapper.jar` - which
  runs *before* any of that verification applies - is reproducible from that pinned distribution:
  running `gradle wrapper --gradle-version 9.7.1` with the distribution's own binary emits a
  byte-identical jar.
- **NDK 28.2.13676358**, for one reason: `llvm-strip`. Without it AGP cannot strip native libraries,
  only *warns* that it could not, and ships `libsignal_jni.so` whole - 64 MB of DWARF against a
  4.7 MB `.text`, making the release APK 74 MB instead of 9.4 MB. `verifyReleaseNativesStripped`
  fails the build if that happens, so this image is what makes `assembleRelease` produce something
  distributable.

  **The tag moves whenever the image changes** - `:36` to `:37` when the NDK was added, and `:38`
  today, which is what `tools/build-in-docker`, `tools/verify-cold`, `tools/emulator/Dockerfile` and
  the top of this file all reference. An image built under an older tag has no NDK, and reusing it
  fails the strip gate - which looks like a broken build rather than a stale image. Rebuild rather than passing `-Pkryptey.allowUnstrippedNatives=true`; that flag is a
  statement that the artifact is not for distribution.

## Dependency verification

`gradle/verification-metadata.xml` pins the SHA-256 of every artifact - 386 components. A build
whose checksums do not match fails rather than warning.

Verification passing on YOUR machine is weaker than it looks, because a warm Gradle cache never
re-downloads and therefore never re-checks. `tools/verify-cold` builds against an empty cache
volume, which is the only way to find entries that are missing rather than wrong: artifacts resolved
during buildscript classpath resolution are fetched before `--write-verification-metadata` takes
effect, so regenerating the metadata cannot record them, and every warm build passes regardless.
Two such entries once survived dozens of "clean verified build" reports while a fresh clone could
not configure at all.

Last cold verification, at `85c3049`: a `git clone` into a fresh directory, an empty cache volume,
and `testDebugUnitTest assembleRelease` - BUILD SUCCESSFUL in 6m21, 957 tests, zero verification
failures, release APKs of 9.4 MB and 7.7 MB with the strip gate passing unaided.

A clone rather than this working tree, deliberately: every earlier cold run mounted the directory it
was checking, so it proved the dependency story and could not have noticed a build that depended on
an untracked or ignored file.

## Lint

`lintDebug` runs against `app/lint-baseline.xml`, which holds 100 errors and 503 warnings. Every one
of them predates this work: 77 are in files the revival never touched, and the other 23 are
`MissingTranslation` on strings that exist in `master`. So the baseline grandfathers the state the
project was already in and gates everything new — `Lint found no new issues` is the passing message,
and a *new* problem fails the build.

Regenerate it with `gradlew updateLintBaseline` deliberately, never to turn a red build green: an
entry added there is a finding nobody will look at again.

## Adversarial review rounds

`tools/review-prompt.md` holds the standing requirements for a review round — the per-finding fields
(including a reachability verdict and the experiment that would settle the finding), the rule against
re-reporting, and the shape of the report. Paste it under whatever scope the round is given.

`REVIEW-SETTLED.md` in the repository root is the other half: claims that were investigated and
refuted, each with the measurement that settled it and the test now holding the property in place.
Reviewers read it before reporting, which is what stops a round spending itself on ground a previous
round already covered.

## Knobs

Every one of these has a default that is what you want; they exist for the awkward runs.

| Variable | Script | What it is for |
| --- | --- | --- |
| `KRYPTEY_TEST_CLASS` | `test-on-emulator` | Narrow the device run to one class or `Class#method`. A full device run is about eight minutes, and settling a device question usually takes three of them — measure, mutate the production code, measure again — which is most of why device questions get deferred. The run prints that it is narrowed, so its output cannot be mistaken for the full suite. |
| `KRYPTEY_IMAGE` | `build-in-docker`, `verify-cold` | The pinned build image. Change it only to test an image bump. |
| `KRYPTEY_EMU_IMAGE` | `test-on-emulator` | The pinned emulator image. |
| `KRYPTEY_EMU_CONTAINER` | `test-on-emulator` | Container name, so two emulator runs can coexist. They otherwise collide on the name. |
| `KRYPTEY_EMU_BOOT_TIMEOUT` | `test-on-emulator` | Seconds to wait for boot, default 1800. Under software emulation a cold boot is ten minutes and a loaded machine is slower. |

One thing that is **not** a knob, and is worth knowing before a long run: the JVM build and the
emulator share a Gradle cache volume, so two `build-in-docker` invocations at once — or a
`build-in-docker` alongside the emulator's own build step — deadlock on `journal-1.lock` and fail
with a message that names neither the other process nor the cause. Run them one at a time.

**`test-on-emulator` counts as one of them**, and that is easy to miss because it does not look like a
build: it boots a device for ten minutes first, and only then reaches its own `assembleDebugAndroidTest`
step. So a check of "is a build running?" comes back clean at the moment you launch it and is wrong
several minutes later, which is exactly how two builds ended up live at once here twice. If anything
else may want the lock, either run the emulator alone or expect the collision - it has so far
serialised badly rather than deadlocked, roughly doubling wall time, but the deadlock is the same
lock.

The second thing, learned the same way: **do not edit a source file while a suite is running.** A
number of the guards here are source scanners - they read the working tree with `Files.readAllBytes`
at test execution time, not from the compiled classes. So an edit made after the compile step and
before the test task reaches that guard is picked up mid-run, and the result is a pass or a failure
about a tree that never existed. It fails in the confusing direction too: the run is green, the
finding is real, and neither matches the code you have. Queue the edit and run again; a full JVM
suite is under seven minutes.

## Instrumentation tests

`tools/test-on-emulator` runs them. There are 41, and the count in this sentence is checked against
the source by `DocsDoNotContradictTheAppTest` — it said 17 for several rounds after the suite had
grown past it, which is the kind of claim this project treats as a defect rather than as tidying.

Eleven cover the Android Keystore, which has no JVM equivalent because there is no TEE behind a
desktop provider, and two more ask the platform questions no argument could settle: whether a key
bound to the screen lock survives that lock being removed (it does, at API 28), and whether the key
this install actually generated is bound to the lock at all — asserted as a refusal while the screen
is locked, because the flag has no accessor at this API level and being unusable while locked is the
property the flag exists to obtain. The key ladder steps down silently when a device cannot honour a
rung, and records nowhere which rung it took, so that second test is the only place the degradation
would be visible. Three more exercise the chat-log split against that same real keystore. Three
cover whether the platform will bind a service declared `exported="false"` as an input method, and
three establish that the decrypted-message compose box is never handed to an autofill service.
Three run a protocol round trip on real hardware and two run the strip's own round trip; the
remaining five are single assertions that only a device can make — ciphertext and plain text
crossing into a foreign app, typing on the key surface, `FLAG_SECURE` actually reaching the window,
the banner not clipping the recipient line, and one that only a real frame loop can answer: that
switching contacts on the verify screen does not let the previous contact's safety number paint
itself in late, under the new contact's name. Until recently none of them had ever executed
anywhere.

The last two groups need device state that only shell can set - a selected input method, a
registered autofill service - so the script sets it and the tests assert it as a precondition
rather than passing vacuously when it is missing. The script also reboots the device once after
writing `hide_error_dialogs`, because ActivityManager only re-reads that on a configuration change
and an ANR dialog that steals window focus is this harness's most reliable way to produce a
confident wrong answer.

    tools/test-on-emulator

It builds `tools/emulator/Dockerfile` on first use (an Android 28 x86_64 system image, so expect a
long first run), boots the emulator, builds the APKs, installs both, and runs the suite. Budget
about ten minutes for the boot and nine seconds for the tests.

There is no `/dev/kvm` on this machine and the CPU exposes no virtualisation extensions, which was
read for a long time as "an emulator cannot run here". It was the wrong conclusion: KVM is an
accelerator, and `-no-accel` makes QEMU emulate the guest in software instead. Slow, not impossible.

**The suite is retried once, loudly.** Two failures have now been observed that did not reproduce:
a native abort deep in the framework's view-tree draw with no application frame in the stack, and an
instrumentation process that died before a single test started with an empty crash buffer. Neither
is a test result, and re-running by hand to find out costs a cold boot. So the script runs the suite
a second time when the first does not pass — once, never more — and a run that only passed the
second time says `PASSED ON THE SECOND ATTEMPT` rather than reporting a clean pass, with the first
attempt's crash log, main log tail, window focus and input-method state printed above it. A retry
can hide a test that fails intermittently for a reason of its own, which is why the result line
refuses to look like a clean one: treat it as a flake to be explained. A reproducible failure fails
both attempts and is reported exactly as before.

Two things are easy to trip over:

- **x86_64 is not a shipped ABI.** The splits produce `arm64-v8a` and `armeabi-v7a`, neither of
  which installs on an x86_64 emulator. `-PemulatorAbi` adds `x86_64` for a local run; nothing on a
  release path sets it.
- **`am instrument` exits 0 even when tests fail.** The script reads the result out of the output
  stream rather than trusting the exit status. Both directions were checked against real runs.

## What this environment still cannot do

StrongBox. The emulator has none, so the top rung of the key ladder is only ever exercised as a
refusal that gets stepped down from. What a StrongBox-backed device actually does remains untested.

No real messenger. This paragraph used to say more than that - "no test types a message, encrypts
it, and reads it back out of another app. Nor does anything drive the strip's own UI" - and both
clauses have since become false without the sentence being re-read. The suite now types on the real
key surface, drives the strip through the add-contact screen and back, and sends across a genuine
process boundary into `ForeignAppActivity`, which lives in the test APK under a different package.

What remains true is narrower and worth stating exactly: the far side of that boundary is an
activity written for the test, not Signal or Telegram or WhatsApp. Nothing here has ever been driven
against a shipping messenger, so what no test covers is a real app's own field behaviour - its
input types, its `restartInput` habits, its autofill and its clipboard use - which is precisely the
surface this app's threat model calls the adversary.
