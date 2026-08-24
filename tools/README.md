# Building KryptEY reproducibly

The build environment is pinned here rather than described in prose, because every part of it has
been a source of confusion at least once: which JDK, which Android platform, which build-tools, and
whether dependency verification was actually on.

```
docker build -t kryptey-build:36 -f tools/Dockerfile tools/
tools/build-in-docker testDebugUnitTest
```

## What is pinned, and why it matters

- **Temurin 17.** libsignal 0.86 and AGP both require it.
- **platform android-35**, matching `compileSdk 35`.
- **build-tools 35.0.0 and 36.0.0.** AGP asks for 36.0.0; the image carried only 35.0.0 for a long
  time, and everyone who touched this repo worked around it by relabelling a copy of the 35.0.0
  directory. That worked, and it meant nobody was building with the tools AGP actually selects.
  Both are installed now so no relabelling is needed.
- **Gradle 9.7.1**, from `gradle/wrapper/gradle-wrapper.properties`, fetched by the wrapper.

## Dependency verification

`gradle/verification-metadata.xml` pins the SHA-256 of every artifact - 387 components. A build
whose checksums do not match fails rather than warning.

Verification passing on YOUR machine is weaker than it looks, because a warm Gradle cache never
re-downloads and therefore never re-checks. `tools/verify-cold` builds against an empty cache
volume, which is the only way to find entries that are missing rather than wrong: artifacts resolved
during buildscript classpath resolution are fetched before `--write-verification-metadata` takes
effect, so regenerating the metadata cannot record them, and every warm build passes regardless.
Two such entries once survived dozens of "clean verified build" reports while a fresh clone could
not configure at all.

Last cold verification: `assembleDebug` from an empty cache, BUILD SUCCESSFUL, zero verification
failures.

## What this environment cannot do

Instrumentation tests (`app/src/androidTest`) need `/dev/kvm` or a physical device. There are 11 of
them, they compile, and they have never run here - the Android Keystore has no JVM equivalent.
`KeyResolutionTest` covers the decisions around those calls; the calls themselves remain untested.
