# Claims already investigated

Read this before reporting. Every entry is a claim a review round made that was **investigated and
settled**, with the evidence that settled it. Re-reporting one of these without new evidence is the
churn this file exists to stop; refuting one *with* new evidence is welcome and is exactly how an
entry should be overturned.

Settled does not mean unimportant. Two of these describe attacks that would work against a slightly
different build, which is why each has a test holding the property in place.

---

## Refuted — the mechanism is real, the consequence does not follow

**A corrupted first message poisons every genuine copy, via the Kyber replay guard.**
The guard records the sender's base key in `markKyberPreKeyUsed`; libsignal calls that during session
derivation; derivation precedes authentication. If all three held, one flipped byte would burn the
base key while the message carrying it failed, and every later copy — all carrying the same base key
— would be refused as a replay.
*Measured against libsignal 0.86.5, two corruptions (a byte in the body; a byte in the trailing MAC,
which parses and fails authentication): no base key recorded, no pre-key marked used, genuine copy
opens.* The store callbacks are not reached until the message verifies.
Held by `AcorruptedFirstMessageDoesNotPoisonTheGenuineOneTest`, including a replay-refusal floor so
the test cannot pass against a build with the guard deleted. **A libsignal upgrade could overturn
this** — that is what the test is for.

**`loadPreKey` marks a one-time pre-key used on a decrypt that then fails.**
Same path, same measurement: the used flags are identical before and after a failed decrypt.

**A wrong-length bundle signature throws out of the decrypt click listener and kills the IME.**
The decoder accepts a signature field of 1..255 bytes and the verifier checks only that it is
non-empty, so an attacker-chosen length reaches `ECPublicKey.verifySignature` with only `IOException`
caught on that path. The reasoning is right about the reach; the library does not throw.
*Measured against libsignal 0.86.5: signature lengths 1, 63, 65, 100 and 255 all return `false`.*
Held by `AwrongLengthSignatureIsRefusedRatherThanThrownTest`.

**Removing the screen lock destroys the storage key, silently and unrecoverably.**
The master key is generated with `setUnlockedDeviceRequired` on any device with a secure lock, and a
comment in `StorageHelper` listed "a Keystore key invalidated by a credential change" among the
causes of an unresolvable store. If that applied to this key, then removing a PIN — an ordinary thing
a person does — would turn the identity key, every session and the whole history into unreadable
ciphertext on a build with no RESET path.
*Measured on the emulator at API 28: a lock-bound key sealed before the PIN was removed opened
normally afterwards.* The flag binds use to the device being unlocked, not to the credential
existing; `setUserAuthenticationRequired` and `setInvalidatedByBiometricEnrollment`, which do carry
invalidation semantics, are not used.
Held by `AlockBoundKeyAcrossLockRemovalTest`, which sets the PIN, generates, seals, clears the PIN
and opens. **This is API-level-dependent** — the measurement is API 28, which is what the instrumentation
runner uses, not the minimum this project supports (`minSdk` is 26, and on 26–27
`setUnlockedDeviceRequired` is not applied at all, so those two releases have nothing to invalidate
and no on-device measurement either) — and the test asserts the survival, so a platform that changes it fails loudly rather than
losing a user's history quietly.

**Robolectric's `android-all` jars execute unverified in the build that produces the release APK.**
Dependency verification pins 386 artifacts, and older Robolectric fetches a ~100 MB `android-all` jar
at test time into `~/.m2`, outside Gradle's resolution — so a substituted one could make the whole
suite report whatever it liked, in the job that builds the release artifact. The mechanism is real
for the versions it describes, and the CI workflow's own comment said it applied here.
*Measured against Robolectric 4.16.1 as pinned: the suite runs to completion with `--rerun-tasks` in
a container started with `--network none`; no `android-all` jar exists anywhere in the image before
or after, and no `~/.m2` is created.* Its Android runtime comes from `nativeruntime` and
`shadows-framework`, both pinned and verified.
**Version-dependent** — a future Robolectric could reintroduce the fetch, and the workflow comment
now says what to re-measure.

## Refuted — the code already does what the finding asks

**The per-raise reachability guard is satisfied by the comment naming the safe call.**
`AconditionWarningIsLoweredWithItsConditionTest` reads `LatinIME` through `sourceOf`, which strips
block and line comments before scanning. Mutant: delete the call, keep its comment — the test fails.
The *smaller* half of that finding was right and was fixed: the ordering check excused a missing
landmark, so rewording a log line would have made it pass vacuously.

---

## How to overturn an entry

State the version or condition under which the measurement no longer holds, and the experiment that
shows it. "The reasoning still looks right to me" is not new evidence — the reasoning is recorded
above precisely because it looks right.

## The FairyTale carrier and the encoders, attacked with nothing found

Round 38 spent an entire agent on the carrier and the encoders and produced no reachable attack.
Recorded so the next round does not re-spend it. Each of these was checked and is sound:

- **The decompression budget is checked before the expensive step**, not after:
  `out.size() + produced > MAX_DECOMPRESSED_BYTES` precedes every write, and `DecompressionBudgetTest`
  pins it against literals rather than against the constant. Inflate input is bounded at 4096 bytes
  by the 8192-character paste cap and two invisible characters per byte.
- **Truncated streams are rejected rather than returned as a prefix**, covered by
  `CompressionBombTest.atruncatedStreamIsRejectedRatherThanReturnedAsAPrefix` with half-, 4- and
  1-byte prefixes. Deleting the guard turns it red.
- **`EnvelopeCodec.fromWire` never throws unchecked**: both `Base64.decode` arms are wrapped,
  `Base64.encodeBytes(byte[])` swallows its own `IOException`, and `BinaryEnvelope`'s cursor
  bounds-checks every read.
- **`deSimplifyJsonKeys` cannot throw on replacement syntax**: all fourteen replacements are literal,
  `$`- and `\`-free, and `getMapKeyFromValue` returns non-null for every one of them.
- **The `produced == 0` continue in the inflate loop cannot spin**: zlib reports no progress only
  when input is exhausted, which is exactly when `needsInput()` is true.
- **Crossing the encoders gains an adversary nothing.** One appended zero-width character makes a
  genuine RAW paste undecodable; stripping the invisible run makes a FairyTale paste undecodable.
  Both are denials of service already available to a messenger that can drop the message.
- **All sixteen alphabet code points are `Cf`**, so the `\p{C}` router cannot miss a genuine payload.
- **`Base64.encodeBytes` uses `NO_OPTIONS`** and never breaks lines, so `minifyJSON`'s whitespace
  stripping is a genuine no-op on wire text.
- **The near-cap decoy collapse does not ship.** The pool falls to one sentence only for payloads of
  8171 to 8190 invisible characters — a 20-character window out of 8192 — and the measured rotation
  path sits at 7904 with ~288 characters of headroom, where 171 of 173 sentences fit.
