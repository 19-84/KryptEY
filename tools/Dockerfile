FROM eclipse-temurin:17-jdk-jammy

ENV DEBIAN_FRONTEND=noninteractive
RUN apt-get update && apt-get install -y --no-install-recommends \
      curl unzip git ca-certificates \
    && rm -rf /var/lib/apt/lists/*

ENV ANDROID_HOME=/opt/android-sdk
ENV ANDROID_SDK_ROOT=/opt/android-sdk
ENV PATH=$PATH:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools

# Android commandline tools (pinned)
ARG CMDLINE_TOOLS_VERSION=11076708
RUN mkdir -p $ANDROID_HOME/cmdline-tools \
 && curl -fsSL -o /tmp/cmdline-tools.zip \
      "https://dl.google.com/android/repository/commandlinetools-linux-${CMDLINE_TOOLS_VERSION}_latest.zip" \
 && unzip -q /tmp/cmdline-tools.zip -d $ANDROID_HOME/cmdline-tools \
 && mv $ANDROID_HOME/cmdline-tools/cmdline-tools $ANDROID_HOME/cmdline-tools/latest \
 && rm /tmp/cmdline-tools.zip

RUN yes | sdkmanager --licenses > /dev/null 2>&1 || true
# The NDK is here for one reason: llvm-strip.
#
# AGP's stripReleaseDebugSymbols uses it to remove DWARF from packaged native libraries, and when it
# is absent AGP only WARNS - "Unable to strip the following libraries, packaging them as they are" -
# and the build reports success. libsignal_jni.so is then shipped whole: 64.2 MB of debug information
# against a 4.7 MB .text, roughly seven times the size it should be. Worse than the size, the same
# source produces materially different artifacts depending on whether the build host happens to have
# an NDK, which is a reproducibility problem for a project distributed through F-Droid.
#
# verifyReleaseNativesStripped fails the build when that happens. Without an NDK in this image, the
# project's own documented build path could not produce a distributable release at all.
ARG NDK_VERSION=28.2.13676358
RUN sdkmanager --install \
      "platform-tools" \
      "platforms;android-35" \
      "build-tools;35.0.0" \
      "build-tools;36.0.0" \
      "ndk;${NDK_VERSION}" \
    > /dev/null
ENV ANDROID_NDK_ROOT=/opt/android-sdk/ndk/${NDK_VERSION}
ENV ANDROID_NDK_HOME=/opt/android-sdk/ndk/${NDK_VERSION}

WORKDIR /workspace
