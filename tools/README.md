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

## Instrumentation tests

`tools/test-on-emulator` runs them. There are 17: eleven cover the Android Keystore, which has no
JVM equivalent because there is no TEE behind a desktop provider; three cover whether the platform
will bind a service declared `exported="false"` as an input method; and three establish that the
decrypted-message compose box is never handed to an autofill service. Until recently none of them
had ever executed anywhere.

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

Two things are easy to trip over:

- **x86_64 is not a shipped ABI.** The splits produce `arm64-v8a` and `armeabi-v7a`, neither of
  which installs on an x86_64 emulator. `-PemulatorAbi` adds `x86_64` for a local run; nothing on a
  release path sets it.
- **`am instrument` exits 0 even when tests fail.** The script reads the result out of the output
  stream rather than trusting the exit status. Both directions were checked against real runs.

## What this environment still cannot do

StrongBox. The emulator has none, so the top rung of the key ladder is only ever exercised as a
refusal that gets stepped down from. What a StrongBox-backed device actually does remains untested.

Nothing here drives the keyboard through a real messenger either. The IME tests establish that the
system binds the keyboard and opens an input connection to a field, and that autofill does not
receive the compose box; no test types a message, encrypts it, and reads it back out of another app.
Nor does anything drive the strip's own UI - reaching its compose box needs a tap at coordinates
that depend on which screen the strip is showing.
