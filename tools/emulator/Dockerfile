# An Android x86_64 emulator that boots without hardware virtualisation.
#
# Parameterised by API level: `docker build --build-arg API=35 -t kryptey-emu-35:1 tools/emulator`.
# One image per level rather than one image carrying several, because a system image is about a
# gigabyte and most runs want exactly one of them. tools/test-on-emulator does the build; see
# KRYPTEY_EMU_API there.
#
# This machine has no /dev/kvm and no vmx/svm CPU flags, so QEMU runs the guest under TCG
# software emulation. That is slow - a cold boot takes roughly ten minutes - but it works,
# and it is the only way the instrumentation tests run at all: they cover the Android
# Keystore, which Robolectric cannot provide because there is no TEE behind it.
#
# The default is 28: the oldest release with clearPrimaryClip, and the boundary the keystore
# ladder is written against. It is not the only level worth running, and for a long time it was
# the only one that ever ran - which meant minSdk 26, and everything from 29 up to the compile
# target, had never executed a line of this app. The two that buy the most beyond 28:
#
#   API 26 - the minSdk floor, and the low rung of the keystore ladder. applyApi28Protections is
#            by name the code that does NOT run here, and 26 is where that is decided.
#   API 35 - the compile and target level. Every behaviour change the targetSdk raise opts into
#            (receiver export flags, edge-to-edge activities) is inert at 28 by definition, so at
#            28 the code answering them is checked by source-reading tests and by nothing else.
#
# Both are AOSP `default` images, for the reason below. Both were confirmed present in the SDK
# repository before this was written; `sdkmanager --list` is where to check if one disappears.
#
# The AOSP image rather than google_apis, and this is load-bearing rather than a preference. On
# google_apis, SystemUI could not keep up with an emulated CPU and went not-responding within
# minutes of boot, every time. An ANR dialog takes window focus, so the test's editable field never
# got focus, never made an input connection, and the keyboard was never bound - which reads exactly
# like a keyboard that cannot be bound. Chasing that produced a nearly-published claim that
# exported="false" breaks the IME. It does not. The AOSP image carries no Play services, no
# launcher of that weight and no quick-search, boots in a third of the time and does not ANR.
#
# Passing -cores was also tried, on the theory that the ANR was CPU starvation. The guest's system
# server took a native crash inside four minutes and every app died with DeadSystemException, so
# the runner deliberately does not set it.
FROM kryptey-build:38

# The emulator launcher and the QEMU binary under it link against X11 even with -no-window.
# Without these the process dies before it prints anything, which looks exactly like a boot
# that is merely slow - ten minutes of polling a process that was never alive.
USER root
RUN apt-get update && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
      libx11-6 libxcb1 libxdamage1 libxext6 libxfixes3 libxrender1 libxi6 libxtst6 \
      libxcursor1 libxrandr2 libxinerama1 libsm6 libice6 \
      libnss3 libgl1 libglu1-mesa libegl1 libpulse0 libasound2 libdbus-1-3 \
      libfontconfig1 libfreetype6 libxkbfile1 libxkbcommon0 libpng16-16 \
      libopus0 libvpx7 libgbm1 libdrm2 \
    && rm -rf /var/lib/apt/lists/*

# Declared after the apt layer so changing API does not re-run it.
ARG API=28
ENV KRYPTEY_EMU_API=${API}

RUN yes | sdkmanager --install "emulator" "system-images;android-${API};default;x86_64" > /dev/null
ENV PATH=$PATH:/opt/android-sdk/emulator
RUN echo no | avdmanager create avd -n "test${API}" \
      -k "system-images;android-${API};default;x86_64" --device pixel

# The AVD's own hardware profile, not just the emulator command line. avdmanager writes a
# config.ini from the device profile, and the emulator honours what is in there for anything the
# command line does not override - so a guest that needs more heap than `pixel` grants gets it
# here rather than dying with a system_server OOM twenty minutes into a cold boot.
RUN set -e; \
    cfg="$HOME/.android/avd/test${API}.avd/config.ini"; \
    sed -i '/^hw\.ramSize=/d;/^vm\.heapSize=/d;/^disk\.dataPartition\.size=/d' "$cfg"; \
    if [ "${API}" -ge 30 ]; then \
      printf 'hw.ramSize=4096\nvm.heapSize=512\ndisk.dataPartition.size=6G\n' >> "$cfg"; \
    else \
      printf 'hw.ramSize=3072\nvm.heapSize=256\ndisk.dataPartition.size=4G\n' >> "$cfg"; \
    fi
