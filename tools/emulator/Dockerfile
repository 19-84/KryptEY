# An Android 28 x86_64 emulator that boots without hardware virtualisation.
#
# This machine has no /dev/kvm and no vmx/svm CPU flags, so QEMU runs the guest under TCG
# software emulation. That is slow - a cold boot takes roughly ten minutes - but it works,
# and it is the only way the instrumentation tests run at all: they cover the Android
# Keystore, which Robolectric cannot provide because there is no TEE behind it.
#
# API 28 rather than something newer because it is the oldest release with clearPrimaryClip
# and the boundary the keystore ladder is written against.
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

RUN yes | sdkmanager --install "emulator" "system-images;android-28;google_apis;x86_64" > /dev/null
ENV PATH=$PATH:/opt/android-sdk/emulator
RUN echo no | avdmanager create avd -n test28 -k "system-images;android-28;google_apis;x86_64" --device pixel
