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

## The binary wire format, attacked field by field

A round spent entirely on `BinaryEnvelope`, `EnvelopeCodec` and the issuing signature — a different
surface from the FairyTale carrier settled above — found no reachable attack on the layout. What it
produced instead were three claims, all fixed. The negative results are worth not re-spending:

- **Absence versus zero-length is decidable at every field that has both states.** Ciphertext and
  bundle signature refuse length 0 explicitly; a zero-length key fails `new ECPublicKey` /
  `new KEMPublicKey` before any length check; the sender name and identity key likewise. The
  signature is not optional — it is written unconditionally with the bundle and read unconditionally
  — so there is no "omit the signature" spelling.
- **One envelope, one byte string.** `writeBundle` and `readBundle` were walked field by field: same
  order, same widths, nothing written that is not read or read that is not written. Every key field
  is pinned to its own canonical encoding, the signature fields are copied verbatim so re-encoding
  is the identity, and `requireExhausted` plus the text layer's canonical check close the tail.
- **Signature coverage is complete.** Every field of `PreKeyResponse`, `PreKeyResponseItem`,
  `PreKeyEntity`, `SignedPreKeyEntity` and `KyberPreKeyEntity` is inside `canonicalBundleBytes`.
  `git log` on the encoding and prekey packages shows no field added since the signature landed, so
  there is no "added later and missed" gap.
- **Cross-protocol signature reuse is not reachable.** The identity key signs three message shapes
  and their lengths are disjoint by construction; the attacker-controllable slack is two ≤255-byte
  signature fields, which cannot close any gap.
- **Bounds hold.** `Cursor.require` cannot overflow (lengths are `u8`/`u16`), `bytes()` allocates
  after the bounds check so a declared length cannot drive an allocation, and no loop is
  attacker-counted — `deviceCount` is forced to exactly 1.
- **`ciphertextType` is deliberately unconstrained** and must stay that way: both consumers branch
  on it consistently, libsignal validates the body independently, and every out-of-range value lands
  in a handled branch. Constraining it at the parser would turn a peer on a newer libsignal into
  "not a valid envelope", and the decoder is the boundary the two sides upgrade through
  independently.
- **The encoder is looser than the decoder in three places**, all in the harmless direction and all
  unreachable from production inputs.

## The chat log's deferred loader

Attacked for a path that reads, mutates and writes back losing entries, and for one that leaves the
account reporting itself loaded while holding nothing. Both are closed, verified rather than taken
from the comments: the loader is cleared only on the success line, so a throwing read leaves the
account deferred and the write path then skips the key; the only two "loaded while empty"
constructions are guarded; and nothing inside the read path re-enters `getUnencryptedMessages`, so
the re-entrancy guard is unreachable. Worth remembering for any change that makes the loader call
back into the account — that guard throws unchecked, and the chat-log screen catches only two
checked types.

Message ordering is also sound: the display sorts a copy by timestamp, and the timestamp is not
attacker-supplied — the wire format does not carry one, so a received message is stamped at local
decode time. A messenger that delays delivery reorders the log, but the times shown agree with the
order, so the display is not lying about itself.

## The Kyber replay-set burn needs an unpinned address

Measured rather than argued, because the ordering lives in the Rust layer: a first message carrying
a different identity at a **pinned** address is refused *without* spending a Kyber base key, while
an accepted one spends exactly one. So `isTrustedIdentity` runs before `markKyberPreKeyUsed`, and
the burn analysis recorded elsewhere — which assumes each burn costs the attacker a session
establishment the app accepts — holds.

## The deletion promise across an interrupted chat-log move

A round left this as the one row of its deletion table it could not settle by reading: the chat log
moves into its own file by copy-verify-delete, so a process kill between the copy and the delete
leaves it in **both** files, and the account-file copy is then unreachable through the reader, which
prefers the new location. A sweep operating on the loaded list would write the pruned log to the
message store and leave the un-pruned copy in `protocol.xml` — with the help text saying the history
is gone. An input-method process is killed as a matter of routine, so the interrupted state is
ordinary.

Measured now, through the real deletion path: the promise holds. The reader finishes the interrupted
move before returning, so the account-file copy is removed on the first load after the kill, and a
deletion is exactly such a load — `removeAllUnencryptedMessages` goes through the deferred loader.
Both files end up without the plaintext, the message file itself survives (its existence is what
says this device holds data), and another contact's messages are untouched. Deleting the cleanup
turns the test red.

Worth recording how the first version of that test lied: it seeded the log with
`setUnencryptedMessages`, which marks the log **loaded**, so `getUnencryptedMessages` never called
the loader — and the cleanup lives inside the loader. It failed, and it failed for the fixture's
reason rather than the code's. The fixture has to reload the account so the log is deferred, which
is the state a kill actually leaves.

## Every input the host declares, enumerated

A review round died mid-sweep with four findings, and all four were the same shape: a value the
host application declares about its own field, trusted without a bound or an order. They were
`inputType` read through the narrow password predicate, the `initialSelStart`/`initialSelEnd` pair
read unordered, `actionLabel` retained unbounded in a cache key, and — one level along — the
password-field notice erased by a clipboard event. All four are fixed and under test.

Four findings of one shape is a reason to enumerate rather than to keep hunting, so the sweep was
finished: every field of `EditorInfo` that `app/src/main` reads at all, and what bounds it.

| Field | Disposition |
| --- | --- |
| `inputType` | `isAnyPasswordInputType` — the disjunction, so `textVisiblePassword` counts |
| `initialSelStart` / `initialSelEnd` | ordered at the write site with `Math.min`/`Math.max` |
| `actionLabel` | `boundedActionLabel`, 128 chars, bounded **at the copy** so key and label agree |
| `imeOptions` | an `int` read only through bitmask tests |
| `actionId` | an `int` handed to `performEditorAction` |
| `privateImeOptions` | read only by `containsInCommaSplittableText` and by equality |
| `packageName` | assigned to one field, read only by `toString()`; one live reference per session |
| `hintText`, `label`, `extras` | read nowhere |

Nothing new fell out of the four that had not already been recorded. The one that looks alarming
and is not: `KeyboardId` retains the whole `EditorInfo`, so a cache key drags in the host's
arbitrary `extras` `Bundle` even though `mCustomActionLabel` beside it is carefully bounded — but
the `SoftReference` on the value is not what saves it, since the *keys* are strong. What saves it is
`MAX_CACHED_KEYBOARDS`, which drops the whole map at 64 entries. That the bound counts entries
rather than bytes is recorded already, in the constant's own javadoc and twice in REVIVAL.

So this is a clean negative on the rest of the seam, and the value of it is the table: the next
round that finds a host-declared input mishandled can check here whether the field was looked at
before spending itself re-deriving one of these rows.

## The add-contact re-point raises the redirect under a GONE ancestor, and focus is granted anyway

**Refuted by measurement, on the platform this suite runs.** The mechanism is real and the
consequence does not follow.

`showOnlyUIViewInternal` calls `composeInsideTheKeyboard()` before the visibility flip: the block is
gated on the add-contact view still being `VISIBLE`, and `mLayoutE2EEMainView.setVisibility(VISIBLE)`
is seven lines below it. So `requestFocus()` runs while the compose box's ancestor is `GONE`, and
`composeInsideTheKeyboard`'s own javadoc says that call *"returns false silently whenever the view
cannot take focus at that moment — a GONE ancestor"*, then raises the redirect regardless of the
return. The reviewer that found the ordering said plainly it could not settle the platform half by
reading, and that `canTakeFocus` checks only the view's own visibility plus its measured size, so it
might well succeed.

It succeeds. Measured on the emulator: after the add-contact screen is shown and dismissed with a
draft in the box, the compose box holds focus and the clear button is `VISIBLE` — so the app's
statement that the user is composing inside the keyboard matches where the keystrokes actually go.
The predicted failure was not a disclosure in any case (typing lands in the box either way; the cost
would have been the app saying the opposite while Clear and the encoding selector were unreachable),
but it does not happen.

**The assertion is a live control rather than a passing observation.** Removing the `requestFocus()`
call turns it red with exactly its own message, which is what distinguishes this from a test that
happens to be true. That check now sits permanently in
`AdraftSurvivesTheAddContactScreenOnDeviceTest`, so a platform or layout change that starts refusing
focus there fails the device suite instead of shipping.

**The limit, stated rather than left implicit.** This was measured at API 28, which is what the
emulator image runs; the app's `targetSdk` is 33. Android's focus rules are not guaranteed stable
across that gap, and nothing here has measured API 33. What the check buys is that the answer is
now observed on every device run rather than assumed — and that if it ever changes, it changes
loudly. Robolectric cannot substitute: it grants focus unconditionally, so the JVM suite cannot tell
the two outcomes apart and would report success either way.
