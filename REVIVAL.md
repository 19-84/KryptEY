# KryptEY revival — state of the work

Branch `revival`, six commits on top of 0.1.5. This documents what changed, what is verified, and
what is deliberately still open, because the diff is large and several decisions in it are not
obvious from the code alone.

Baseline: KryptEY 0.1.5 (May 2023) — libsignal 0.21.1, cleartext key storage, `jcenter()` build.

**Tests: 31 → 222, all passing.** Debug and release both assemble; dependency verification pins 382
artifacts by SHA-256.

---

## What was done, by phase

### Phase 0 — the build

`jcenter()` was the only non-Google repository and has been decommissioned, so the project could not
resolve `libsignal` at all. Replaced with `mavenCentral()` under `dependencyResolutionManagement`.

- AGP 7.3.1 → **9.3.1**, Gradle 7.4 → **9.7.1**, Java 11 → **17**, compileSdk 33 → **35**
- Jackson 2.14.1 → **2.22.2** via BOM; `protobuf-javalite` **deleted** (a HIGH CVE carried for one
  call that nothing invoked)
- `gradle/verification-metadata.xml` — 382 components pinned by SHA-256, enforced in CI
- CI actions pinned to commit SHAs, not mutable tags

**targetSdk stays at 33.** compileSdk is 35, but Android 15 enforces edge-to-edge and this fork has
*zero* inset handling anywhere — the bottom key row would render behind the navigation bar, making
space and enter partially dead. That is feature work, not a build fix. It is the main thing blocking
a targetSdk bump.

### Phase 1 — keys at rest

0.1.5 stored the identity private key, pre-keys and all session state as **cleartext JSON** in
SharedPreferences.

- `GcmCryptoBox` — AES-256-GCM, `version ‖ nonce ‖ ct+tag`, AAD binds each value to its storage key
- `AndroidKeystoreCryptoBox` — StrongBox → TEE ladder, each candidate self-tested through the real
  seal/open path before being trusted
- `EncryptedKeyValueStore` — three-state **sealed** marker (absent / migrating / complete) so a
  legitimate interrupted migration is distinguishable from an attacker substituting cleartext
- Migration reads each value back before proceeding; the marker is written only when all succeed

Two non-obvious decisions:

- **The marker is encrypted.** An attacker can delete it but cannot forge one, and deletion lands in
  the "refuse" branch. Without this, deleting the marker and planting cleartext would get it re-sealed
  under the real Keystore key and thereafter trusted.
- **Membership is decided by trial decryption, not by inspecting bytes.** An envelope sealed under a
  lost key looks identical to one under the current key; skipping it and marking the store clean
  would orphan the identity permanently.

### Phase 2 — libsignal 0.86.5 and PQXDH

Sessions are now **version 4 (PQXDH)**, not 3 (X3DH), asserted end-to-end through the real wire path.

The migration blocker was not the one the plan predicted: **libsignal 0.86 validates protocol
addresses and requires `deviceId ∈ [1,127]`**, while 0.1.5 generated them with `nextInt(10000)`. ~99%
of existing installs hold an address the library cannot construct — the store fails to deserialize
and takes the whole account with it. `ProtocolAddresses` folds legacy ids back into range
deterministically. Identity keys and safety numbers survive; local sessions survive; **the user's
public address changes permanently**, which breaks mixed-version pairs until both sides upgrade.

Also: `libsignal_jni_testing.so` excluded (82 MB/ABI of test-only native code), ABI splits enabled,
core library desugaring required.

### Phase 3 — wire format

`BinaryEnvelope` replaces base64(JSON). **Not for size** — measured against what the app actually
transmits it is a ~1% difference, and the class docs say so explicitly. It earns its place on:

- no plaintext JSON field names
- no wall-clock timestamp (received messages now use local decode time; a sender-supplied timestamp
  let a peer reorder the recipient's history)
- strict bounds-checked parsing of clipboard input
- a byte string to build on when masking becomes possible

`EnvelopeCodec` is the single seam; previously this conversion was open-coded at six call sites.

**Masking is not implemented and cannot be yet** — it needs a secret shared before the first
message, which does not exist until out-of-band exchange lands. The original plan had these ordered
backwards.

### Phase 4 — trust model

`trustedKeys` **appended** while the lookup returned the first match, so a changed identity was
recorded and permanently ignored, and contact deletion did not clear it. Now replaces — but
replacing is not trusting: a displaced key is flagged and `isTrustedIdentity` refuses **SENDING**
until acknowledged, while **RECEIVING** stays allowed so the message can be shown with a warning.

The naive fix here fails open. The broken version at least failed closed.

**Out-of-band exchange** now exists as a mechanism: `exportOwnKeyBundle()` produces transferable
text and `importOutOfBandKeyBundle()` consumes it, with the result recorded on the contact as
`KeyOrigin.OUT_OF_BAND`. That provenance is the point — trust-on-first-use cannot detect a hostile
messenger substituting keys at first contact, because there is no earlier key to have changed, so
the assurance has to come from the bundle not travelling through that messenger. Afterwards the two
cases are indistinguishable from the stored key alone, hence recording it at import time.

`Contact.isTrustworthy()` is true when the user explicitly verified the safety number *or* the key
came out of band; treating an out-of-band exchange as still-unverified would ask for the same work
twice. Legacy contacts default to `IN_BAND`, which under-claims rather than over-claims trust.

Still missing: any UI invoking these. The mechanism and its provenance are done and tested; the
buttons are not. QR is purely a UX layer over the same string and needs a dependency decision.

---

## Verified vs. reasoned

**Verified by execution:**

- 222 JVM tests, including an end-to-end conversation across all four phases
- A golden wire vector, re-checked against the three mutants that previously survived
- Robolectric tests against real SharedPreferences
- Negative controls on the highest-stakes regressions (legacy-peer crash, one-time pre-key
  overwrite, Jackson fixture corruption)

**Reasoned but NOT verified:**

- `AndroidKeystoreCryptoBox` is executed by **zero** tests. 11 instrumentation tests are written and
  compile, but need hardware or a KVM runner. This includes the `CALLER_NONCE_PROHIBITED` fix — an
  Android Keystore key rejects a caller-supplied IV, so `seal()` would have thrown on every call on
  every real device while all JVM crypto tests passed. That fix is sound in principle and untested
  in fact.
- No part of this has run on an Android device.

---

## Settled

- **APK size accepted** at 114 MB (arm64) / 108 MB (armeabi-v7a). libsignal 0.86 is ~74 MB of native
  code per ABI; that is the cost of PQXDH.
- **Invite threshold accepted** at 4096 characters, up from 500. A PQXDH bundle is 2484 and
  irreducible (Kyber-1024 is 87% of it, and key material does not compress). Clears Telegram and
  above; does not fit SMS, but neither did 500.

## Open

1. **UI for out-of-band exchange and for accepting an identity change.** Both mechanisms exist and
   are tested; nothing in the keyboard invokes them yet. QR would be a dependency decision (ZXing);
   string transfer needs none.
2. **Run the instrumentation tests.** The single largest remaining unknown — see below.

## Known-deferred defects

- **Store rollback.** Restoring an old `protocol.xml` presents envelopes that verify perfectly,
  rewinding the ratchet and resetting `trustedKeys`. Needs a monotonic counter the attacker cannot
  rewind; not solvable at the storage layer.
- **Bundle replay.** No freshness check, so replaying a captured envelope forces a session rebuild.
  Halved (the bundle was being processed twice per message) but not eliminated.
- **No user-visible signal when the Keystore key is gone** — it currently looks identical to
  "no data". `destroyMasterKey()` has no caller, so there is no reset path.
- **Non-atomic account write** — 7 independent `commit()`s per save, on the IME main thread.
- `E2EEStripView` enables the *encrypt* button on detecting an encrypted message while the sibling
  branches enable *decrypt*. Looks like a copy-paste error; there is a coherent alternative reading,
  so it was flagged rather than changed.
