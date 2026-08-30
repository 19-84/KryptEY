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

## Nothing in the keyboard writes the user's text anywhere

REVIVAL's open list names `latin/settings/`, `latin/utils/` and the bulk of `keyboard/` as
genuinely unexamined, with the caveat that the debug switches were looked at "because they are
keyloggers in this app specifically". That is a large surface and most of it is rendering, so the
question worth answering first is the security one, separately from correctness: **does any of it
record what the user typed?**

Swept, and the answer is no. Three separate checks:

- **`latin/settings/` persists no text.** Every `putString`/`putInt`/`putFloat` across the eighteen
  files there writes an appearance or key-press value — colours, sizes, delays, vibration. There is
  no personalisation setting, no gesture-data setting, no user-dictionary write and no
  `allowBackup` toggle. The one `Log` call in the package logs an exception checking whether the IME
  is enabled.
- **No path logs typed text.** A sweep of every `Log.*` in `app/src/main` for a concatenated text,
  word, message, draft or clipboard value returns exactly one live site:
  `E2EEInputConnection.commitCompletion`, which logs a `CompletionInfo` — the host's own completion
  object, not the user's plaintext — behind a `DEBUG` that is a compile-time `false`.
- **The debug switches cannot be turned on.** `DebugFlags.DEBUG_ENABLED` is
  `static final boolean = false` with no setter, no `buildConfigField` override and no flavour that
  sets it, so javac eliminates the guarded branches. This matters most for `PointerTracker`, whose
  debug mode logs key codes and touch coordinates — a keylogger by any other name. It was already
  pinned: `DebugLoggingStaysOffTest` asserts the switch is false in source, **is a constant**, and
  is false at runtime, which is the three ways it could go wrong.

The two `LatinIME` debug branches that do exist log `inputType`/`imeOptions` and selection offsets —
metadata, not content — so even a build with the flag flipped would not print a message.

**What this does not say.** It is a negative result about one question. The rendering and layout
correctness of `keyboard/`, and everything in `latin/utils/` beyond its logging, remain unexamined
and stay on the open list. This narrows that entry rather than closing it.

## Ordering and re-entrancy: what was attacked and held

A round took the angle no previous one had — not what values arrive, but *when things happen
relative to each other*: the IME lifecycle, re-entrancy, callbacks landing mid-transition, and
threading. It found real defects (recorded in REVIVAL), and it also cleared a lot of ground. These
are the negative results, so the next round does not re-spend them.

**Nothing in this app runs off the IME main thread.** A sweep for `Thread`, `Executor`, `AsyncTask`,
`HandlerThread`, `runOnUiThread` and `.post(` across `latin/e2ee` and `signalprotocol` returns only
`ValueAnimator`, which is driven by the main-thread Choreographer. Every `SharedPreferences` write in
the storage layer is `commit()`, never `apply()`. There is no field written on one thread and read on
another, and the only re-entrancy available anywhere is synchronous — focus listeners, text watchers,
visibility changes. That is a strong result: it removes an entire class of finding from this
codebase, and it is why the re-entrancy that does exist is traceable by reading.

**A clipboard change cannot land mid-transition.** `ClipboardManager` dispatches
`OnPrimaryClipChangedListener` through a handler on the process main looper, so a clip arriving during
`showOnlyUIViewInternal` or `setInputView` queues behind it rather than interleaving. The window
between the strip's constructor registering the listener and `LatinIME.setInputView` wiring the
connection is therefore not reachable by a clipboard event.

**`refreshActionButtons` is genuinely re-entrant, and genuinely terminates.** The cycle is
`expireRefusalsSettledByAlaterWrite` → `retireTheStorageCautionFor` → `setInfoTextViewMessage` → the
banner's `TextWatcher` → `refreshActionButtons`. Both repainting helpers null their state *before*
the repaint and the outer call computes its state *after* both expiries, so the outer paint wins and
the depth is two. Worth writing down precisely because of how it terminates: `setInfoTextViewMessage`
has no equality guard, so the watcher fires even for an identical string — termination comes from the
state mutation, not from the text differing. **An edit that repaints before mutating would recurse
without bound.**

**The `FLAG_SECURE` notification covers all three of its inputs.** `notifySensitiveVisibility` is
reached from `setChosenContact` (after the assignment), the compose `TextWatcher`, the `finally` of
`showOnlyUIView` and `adoptState`. The specific worry chased and refuted: "`forgetChosenRecipient` is
the last statement of `onKeyboardHidden`, so the flag is left stuck on" — it is not, because
`setChosenContact(null)` notifies. `mWarningStanding` has no notifier of its own, but every path that
raises a contact-naming warning goes through `setChosenContact` first.

**The `MSG_PENDING_IMS_CALLBACK` suppressor really is dead code**, as its comment claims:
`mIsOrientationChanging` is never assigned `true` anywhere, so the message is never posted, so
`onStartInputViewInternal` — and with it the password guard — runs on every raise. That matters
because the whole password-guard family depends on it.

**The chat-log loader is not re-entrant in production.** `readMessageLog` and its helpers touch only
`SharedPreferences` and the crypto box; nothing calls back into `Account`.

**`getIC()` returning null while the redirect is up** is reachable only between `surrenderState`'s
`setOtherIC(null)` and `adoptState`'s re-point — synchronous, inside `setInputView`, with no input
possible — and after `clear()` on the `onDestroy` path, where the service is going away.

One shape was found that the code no longer matches, and is recorded rather than fixed:
`Account.equals` and `hashCode` both call `getUnencryptedMessages()`, which forces the deferred log
load and can throw unchecked, while the sibling `toString()` was deliberately guarded against exactly
that. There is no production caller of either — no `Account` is put in a `Set` or `Map`, or compared —
so this is a trap for a future caller, not a live defect.

## A send hands typing back and then takes it again

**Refuted by measurement on the device.** The mechanism is described correctly and the consequence
does not follow.

`sendEncryptedMessageToApplication` lowers the redirect and then, three statements later, calls
`mInputEditText.clearFocus()`. Clearing focus inside a focusable container does not leave nothing
focused — the container re-grants it to the next candidate, and on the main view the compose box is
the only `focusableInTouchMode` view up at that moment. Its focus listener calls
`composeInsideTheKeyboard()`, which raises the redirect again. So on paper the send is the one of the
four enumerated lowerings that does not survive its own method, and `TypingDestinationTest` records
the platform half from the other direction: *"Measured: `clearFocus()` on the only focusable view in
a container hands focus straight back."*

Measured after a real send on the emulator: the compose box does **not** hold focus, so nothing
re-raises the redirect. The lowering survives.

**Why the JVM suite could not answer this, stated because it is the reusable part.** A Robolectric
test was written first and passed — and it passed for the fixture's reason. Instrumented, that
fixture reports `composeHasFocus=false` immediately after the same call, so it never produces the
re-grant at all and would have reported success whatever the platform did. That is the second time
this session a JVM test has looked like a result and been vacuous, and both times the thing that
exposed it was printing the intermediate value rather than trusting the assertion.

**The device assertion is a live control**, not a passing observation: deleting the `clearFocus()`
call turns it red with its own message. It stays in
`AdraftSurvivesTheAddContactScreenOnDeviceTest`, so if a layout change ever adds a second focusable
view to the main view — which is what would make the re-grant land on something with a listener —
the device suite says so.

Two fixture hazards were paid for on the way, and both are worth remembering for anything else
added to that class. Splicing a check into the middle of an existing test broke its later assertions,
because the send empties the compose box the rest of the test is about. And swapping the strip's
listener for a recording one leaks: it is the LIVE strip in a shared process, so the next test
inherits it — which broke the following test's anti-vacuity control, that control doing exactly its
job.

## The tablet Tab key moves focus off the compose box while typing is redirected

**Refuted by measurement, and deliberately not "fixed".** The asymmetry is real and the consequence
is not.

`InputLogic.onCodeInput` handles `CODE_ACTION_NEXT`/`CODE_ACTION_PREVIOUS` by calling
`performEditorAction` directly. Forty lines below, the Enter path asks first —
`mConnection.isUsingOtherIC() ? IME_ACTION_NONE : …` — and its comment cites a measurement this
project already made: *"IME_ACTION_NEXT made TextView.onEditorAction move focus off it, which is the
typing redirection above reached by a route the app chooses rather than one the attacker pokes."* So
one branch asks and its sibling does not, which is this codebase's most repeated shape.

Measured on the field that actually matters: with the redirect up, `performEditorAction(
IME_ACTION_NEXT)` reaches the compose box and **focus is retained**. The compose box declares no
`imeOptions` and no editor-action listener, so `TextView.onEditorAction` does not take the
focus-moving path there — which is precisely the link the reviewer named as unverified and declined
to assert.

The test carries its own anti-vacuity control: an editor-action listener records that the call
reached the view, because "focus was kept" would otherwise be satisfied by the action doing nothing
at all. The listener returns false so default handling still runs.

**Not fixed, on the reviewer's own reasoning.** Copying the Enter guard verbatim would make
`IME_ACTION_NONE` the answer while redirected, turning the tablet Tab key into a dead key inside the
compose box — a usability regression bought with no security, now that the platform link is known to
be false. The asymmetry stays, with a test pinning the behaviour that makes it harmless: if a future
change gives the compose box `imeOptions` or an action listener, this goes red and the guard becomes
worth adding.

Narrow in any case: it needs `sw600dp` resources, the user to have added the PC subtype, and the host
to declare a navigate flag, and only the last is the adversary's to choose. Nothing reaches the
messenger on this path in either outcome, because nothing here lowers the redirect.

## Landscape is a second window mode, and FLAG_SECURE survives it

**Half-settled by measurement, and the half that is settled is the one that decides urgency.**

`values-land/config.xml` is the only folder in the tree that sets `config_use_fullscreen_mode` to
true. Android resolves `smallestWidth` above orientation, so `values-sw430dp` and larger give false
on tablets — but on a phone under 430dp none of those match and `values-land` wins.
`onEvaluateFullscreenMode` ANDs that bool with the framework's own answer, so **in landscape, on most
phones, this keyboard runs in the platform's fullscreen/extract mode**, with an `ExtractEditText`
the app does not own and has never considered. Nobody had looked at that mode; eight rounds of Java
review could not have seen it, because the switch is one line of XML.

The question that decides whether an audit of it is urgent is whether the app's only protective flag
still works there. `FLAG_SECURE` is what stops every screen holding decrypted plaintext being
screenshottable, and a second window mode is exactly where a flag applied to "the window" can end up
on the wrong one.

Measured on the emulator, which is `--device pixel` at 411dp and therefore does resolve to the
landscape branch: with the chat log on screen in landscape, the IME window **is** marked secure. The
check lives in `FlagSecureReachesTheWindowOnDeviceTest` beside its portrait twin, and it carries an
anti-vacuity control that the portrait one cannot supply for it: the landscape window block must
differ from the portrait block, or `setRequestedOrientation` did not take and the new assertion is
simply the old one run twice. That control passes, so the rotation is real.

**And the second unknown is now measured too.** The reviewer could not settle by reading whether
extract mode clears `FLAG_NOT_FOCUSABLE`, which matters because the autofill negative result rests
partly on the IME window being non-focusable and `AutofillDoesNotReachTheKeyboardTest` ran in
portrait only.

Rather than measure the flag — a proxy — the test now measures the property the flag exists for.
That class is parameterised by orientation and runs its whole experiment twice, so the landscape run
inherits all three of its controls: a new autofill request genuinely arrived after the keyboard was
up, the structure it produced is populated and names fields by id, and the keyboard was still
running when it was built. With those holding, the compose box is absent from the structure in
landscape exactly as in portrait. The autofill result survives the second window mode.

One harness fact worth keeping, because it cost a run: `setRequestedOrientation` destroys and
recreates the activity, so the test's reference goes stale and it fails on "the host field never
gained window focus" — which the test correctly names as a harness failure rather than reporting it
as a finding about autofill. The debug-only test activity now declares `configChanges` for
orientation, so the rotation is delivered rather than restarted. Nothing that ships is affected.

What is still unexamined is the extract mode itself: the `ExtractEditText` the platform creates in
that mode is not this app's view, and nothing has looked at what it holds or where its content goes.
The two properties this project relies on — `FLAG_SECURE` and autofill's blindness to the compose
box — are now measured there, and that is a floor rather than an audit.

## The clipboard listener's adversary, named

`REVIVAL.md` frames the strip's clipboard listener as something "the messenger can arm". On Android
8.0–9 — API 26 to 28, which this app supports and whose floor its README advertises — **any**
installed app with zero permissions can read and write the primary clip from the background.
Background clipboard restriction arrived in API 29.

**Checked first, because the obvious worry is wrong: there is no confidentiality loss.** This app
never puts anything on the clipboard. The only `setPrimaryClip` call in `app/src/main` is the
pre-API-28 arm of `clearClipboard()`, writing an empty string. Ciphertext reaches the messenger
through `commitText`, and so does an invite. A third-party clipboard reader therefore gets only the
envelope the user pasted in — which the messenger already has by construction, since it delivered
it.

What is actually wrong is **attribution**, and it is a documentation defect rather than a code one.
On 26–28 a background app can raise the strip's banners, light Decrypt and drive the clipboard-fed
state machine at a moment the user is typing into their bank app and attributes nothing to their
messenger. And `clearClipboard()` exists precisely for this adversary — it protects nothing against
the messenger, which authored the ciphertext it is clearing — yet no comment or document names the
adversary it is for.

Deliberately not fixed with a banner saying the clipboard is public. The banner is a contested
resource in this app: four separate defects have been found where one message erased a standing
security warning, and an advisory the user cannot act on competing for that line risks erasing a
warning about a substituted key.

## Key material is not zeroed on any retirement path

Retiring a key is the one place zeroing would buy something, and no path does it: `removePreKey`,
`pruneUsedPreKeys`, `removeSignedPreKey`, `removeOldSignedPreKeys`, `removeKyberPreKey`,
`deleteSession` and `deleteAllSessions` all drop a reference to a `byte[]` and let the collector
have it. This fork also makes copies of its own — the identity private scalar is serialised into a
JVM array on every `Account` construction, and Base64'd into a `String`, which cannot be zeroed even
in principle.

**Recorded, not fixed, and the reason is that the obvious fix is dangerous.**
`Arrays.fill(record, (byte) 0)` before `store.remove(id)` is unsafe as written: `PreKeyStoreImpl`
hands the store's own array to `new PreKeyRecord(...)` without copying, and `pruneUsedPreKeys` runs
*inside* bundle construction. A zeroing prune that races a record libsignal is still reading, or one
Jackson is mid-serialisation on, produces an invite or a stored record full of zeros — trading an
unobservable residue for a corrupted bundle.

**No user-facing text overstates this**, which was checked rather than assumed: the README scopes
its claim to the master key and the Keystore box narrows it further in the right direction. The
honest statement is narrower than "keys are erased": after a used one-time pre-key, an old signed
pre-key or a deleted session is removed, the private half is gone from disk on the next commit and
still in the heap.

**The half that was unverified is now measured, and it sharpens the finding rather than softening
it.** Whether libsignal keeps private keys in native memory decides whether this fork's own copies
are the only JVM-heap ones or merely extra. Read straight off the shipped artifact with `javap`
rather than from memory of the library:

`org.signal.libsignal.protocol.ecc.ECPrivateKey` extends `NativeHandleGuard$SimpleOwner`, whose only
field is `private final long nativeHandle`, and which declares `protected abstract void release(long)`
and a `protected void finalize()`. So the private scalar lives in Rust memory behind a handle; the
Java object holds a number, and the bytes are freed through `release` on finalisation.

Which means the copies named above are **additions**, not duplicates of something the JVM already
held: `identityKeyPair.getPrivateKey().serialize()` pulls the scalar out of native memory into an
un-zeroed Java array on every `Account` construction, and `JsonUtil` Base64s the same key into a
`String` on every save. Those are the JVM-heap exposure, and they are this fork's rather than the
library's.

Still unmeasured, and named so it is not read as covered: whether libsignal's Rust side zeroes the
buffer on `release` rather than merely freeing it. That is a question about the library's internals,
not about anything this repo can see with `javap`.

## The sender-key store cannot grow, because nothing ever writes to it

A round asking what grows without bound over a long-lived install left this one open, saying plainly
it had not established whether `SenderKeyStoreImpl` has a live writer. It does not.

`storeSenderKey` is called from exactly one place in `app/src/main` — `SignalProtocolStoreImpl`'s
implementation of the same interface method, which delegates. That method exists because
`SignalProtocolStore` requires it, and its real caller would be libsignal's `GroupSessionBuilder`.
There is no `GroupSessionBuilder`, no `GroupCipher` and no `SenderKeyDistributionMessage` anywhere in
the tree, so nothing ever reaches it: this app has no group-session path at all, which is consistent
with the README's "1-to-1 conversations" scope.

The class already said so in its own javadoc, and the reason to check rather than read it is that
this branch has now corrected several comments that were true when written and false later. This one
is still true, and it is worth having as a measurement rather than a claim: the map is empty for the
life of every install, so it is not a growth surface and does not need a cap.

## What the shipped keyboard layouts can actually do

The 196 layout files under `res/xml*` — `kbd_*`, `rowkeys_*`, `key_styles_*` — have never been read
by anyone. REVIVAL records a round against the layout *parser*; the shipped XML it parses was
unexamined, and it is a plausible place to hide something, because a key is a declaration rather
than code and eleven rounds of Java review would not see it.

Swept for the only question that matters on that surface: **can a key do something the strip does
not know about, or send anything off the device?** Every action code any layout emits, enumerated
from the key styles:

`key_delete`, `key_language_switch`, `key_settings`, `key_shift`, `key_space`, `key_tab`,
`key_switch_alpha_symbol`, and a zero-width non-joiner. That is the whole set.

Three absences are worth stating, because each is a channel this app would otherwise have:

- **No voice-input key.** AOSP ships one — `key_shortcut`, which hands audio to a recogniser. Not in
  the layouts, not in `Constants`, not in `KeyboardId`: `grep` for `key_shortcut`, `shortcut_key`,
  `voice_key` and `CODE_SHORTCUT` across `res/` and `java/` returns nothing at all. A keyboard whose
  premise is that plaintext never leaves the device cannot have a microphone key, and this fork does
  not have one.
- **No clipboard or paste key.** Nothing in any layout references the clipboard, so the only
  clipboard interaction is the strip's own listener.
- **No emoji or search key**, both of which are network- or provider-backed on stock keyboards.

`key_settings` is the one that opens an app screen, and it reaches `SettingsActivity` through an
explicit class rather than an implicit action.

A clean negative result, and worth recording as one: the layout surface adds no capability beyond
editing text and switching layouts. It does not close the rest of that directory — the rendering and
geometry of those files is still unexamined, and this says nothing about it.

## In landscape the keyboard's window holds a copy of the host's text, and FLAG_SECURE has no term for it

An audit of the fullscreen/extract mode found the one thing that surface can cost, and it is not
about this app's own plaintext.

In extract mode the framework mirrors the **host** field's text into an `ExtractEditText` inside the
IME's window. `isShowingSensitiveContent()` is a closed enumeration of the app's own views and model
state — the chat log, the verify screen, the contact list, a chosen recipient, a standing warning, a
non-empty compose box — with no term for anything the app does not own. So in the ordinary landscape
state, empty compose box and no strip screen up, the IME window is deliberately **not** secure while
displaying another application's text. A host that protects itself with `FLAG_SECURE` loses that
protection through the keyboard.

**Not fixed, and the reason is that this project already tried the fix and reverted it.** REVIVAL
records that a `FLAG_SECURE` IME window blanks the *entire system screenshot*, so the flag was
deliberately narrowed to the states where this app's own plaintext is on screen. Adding a term for
"extract mode is up" reinstates the reverted behaviour under a new name: screenshots dead in every
app whenever the phone is sideways. The other lever — setting `config_use_fullscreen_mode` false in
`values-land` — is not free either: the verify screen's `ScrollView` was added on a landscape
overflow measurement taken *in the mode that would be removed*, and two other screens were
deliberately left unwrapped on the strength of the same analysis.

**Measured, and the exposure is real.** A host activity that sets `FLAG_SECURE`, rotated to
landscape, with the strip's own predicate false: the host's window block carries `SECURE` — asserted
as an anti-vacuity control, so `addFlags` demonstrably took — and the IME window block does not. The
text the framework mirrors into the keyboard's window is therefore screenshottable while the
application it came from believes it is protected.

**And the other half is now resolved: the platform does not propagate, so this app strips nothing.**
The worry was that `onSensitiveContentVisibilityChanged` calls `clearFlags` unconditionally, which
would mean the app removes a protection the platform applied. `WhoSetsFlagSecureInExtractModeOnDeviceTest`
answers it by construction: it never shows a sensitive strip screen, so the app never raises the flag
either, and any `SECURE` on the IME window could only be the platform's. Run with the clear disabled,
the window is still not secure. Nothing in this app touches it, and it is not secure — so the
exposure is a property of the platform's extract mode rather than a defect in the flag handling.
`clearFlags` is exonerated, which is worth stating because the alternative reading would have made
this a serious bug in this code.

Getting there took three failed runs, and all three failures were the harness rather than the
subject. The first probe was confounded: with nothing clearing the flag it persisted from an earlier
phase of the same test where a sensitive screen had legitimately raised it, so the assertion passed
for the wrong reason and a different control caught it. The second and third failed on "must find
the input-method window" — the new test waited for `imm.isActive`, which says a client has an input
connection and not that the keyboard window exists. The sibling class states that distinction in as
many words and waits for the key view. The lesson is not the fact, which was already written down;
it is that it was written down in the class next door and still repeated.

The measured state is **pinned** in `FlagSecureReachesTheWindowOnDeviceTest` as `assertFalse`, not
left unasserted. If it ever becomes true — a platform that propagates the flag, or a deliberate fix
— that assertion fails and this entry has to be rewritten rather than quietly rotting, which is the
failure this branch has corrected in its own documents four times.

Two related notes from the same audit, both worth keeping:

- **Clean negative on the redirect.** The framework's extract machinery holds the platform's own
  `InputConnection` to the host and never sees `mOtherIC` — `LatinIME` does not override
  `getCurrentInputConnection()`, and the app overrides none of the eight extract hooks. So committed
  text while redirected goes to the compose box regardless of window mode, and the extract view
  mirrors the host field, which under this design holds ciphertext or nothing.
- **`mApplicationSpecifiedCompletionOn` is dead.** It has no reader anywhere, because this fork
  deleted `onDisplayCompletions`. Restoring that method from upstream — an ordinary merge outcome —
  would give the messenger a suggestion strip inside the keyboard, live only in landscape, which is
  the configuration nobody tests.

## The suite-wide vacuity audit, re-run at 1451 tests

A round that pointed at the test suite found eight tests passing for the fixture's reason rather than
the code's, so the crude version of that question — which tests contain no assertion at all — is
worth re-asking as the suite grows. Re-run over both source sets: **1451 `@Test` methods, 42 with no
`assert`/`fail` token.**

Forty-two sounds like a lot and is not. Every one checked falls into three groups, and the grouping
is the useful part because it says what to look at next time:

- **Must-not-throw contracts**, which are the majority and are named as such: a corrupted wire text
  must fail as a checked exception rather than an unchecked one, a legacy store without Kyber fields
  must still deserialise, the fuzz corpora must not crash. The absence of an assertion IS the
  assertion; adding one would weaken it.
- **Delegating tests.** The eight in `SignalProtocolTest` looked like the worst case — the protocol
  layer with no assertions — and are not: both helpers they call assert throughout, five assertions
  in the session builder alone. Checked rather than assumed, because "the protocol tests assert
  nothing" would have been a serious finding.
- **The measurement harness**, which is not a test and is already excluded by name elsewhere.

What this audit cannot see is the failure this branch actually keeps hitting: a test with plenty of
assertions, all of which hold for a reason unrelated to the code — an envelope with no ciphertext, a
deletion that rolled back, a fixture whose write never lands. Counting `assert` tokens says nothing
about that, and the eight found this session all had assertions. The technique that finds those is
mutation: break the production code the test names and see whether it still passes. This audit is a
cheap floor, not a substitute, and it is recorded that way so the next round does not mistake a
clean count for a clean suite.

## What a peer can make the session record weigh

The last open growth question. The Double Ratchet retains message keys for messages it has not seen,
so out-of-order delivery still decrypts — and the peer decides how many that is, because they simply
send. This app stores whatever libsignal serialises and sets no cap of its own: the retention limits
live inside the library, not in any Java this fork owns.

So the answerable question is what the record actually weighs when a peer pushes on it, and that is
measured rather than reasoned: a settled session is **1,079 bytes**; after 300 messages sent and
never received it is **12,680 bytes** — about **39 bytes per skipped message**.

With libsignal's own per-chain retention limit that puts the worst case at tens of kilobytes per
chain and a few hundred at most per contact. Large enough to notice in a store that is rewritten on
every save, small enough that a peer cannot fill the device with it, and — the point — **bounded by
the library rather than by anything here**. The per-key figure is the useful half, because it is what
changes if the library changes.

**Not guarded, and the reason matters.** A cap of this app's own would mean discarding keys libsignal
expects to hold, which breaks out-of-order delivery — the exact thing the retention exists for. An
out-of-order message is ordinary on a messenger, not an attack, so a guard here would trade a bounded
disk cost for dropped messages.

The test carries the control that makes the number mean something: the last message must still
decrypt after the skips. If it did not, the keys were not retained, the growth would be measuring
something else, and the figure above would be noise.

## The legacy migration never widens the cleartext window

A round on data lifetime named this as unchecked: whether the preferences map holds cleartext during
the one-time 0.1.5 conversion, since that map is backed by a file.

It does, and it cannot not — but the app introduces none of it. The conversion reads values that are
**already** cleartext on disk, written by a version that predates the sealing, and replaces each with
a sealed one. There is no path that writes a new cleartext value: every write in the storage layer
goes through the sealing store. So the map holds what the old version left there, for as long as it
takes to convert it, and the migration only ever narrows that exposure.

Two properties of that loop are worth having recorded alongside, because they are what make the
narrowing safe rather than merely intended. It writes a `MIGRATING` marker **before touching any
value**, so a death half-way through is distinguishable by the next run from a substitution. And it
reads each sealed value back before continuing, refusing to mark the store encrypted if a write did
not persist — a storage layer can fail a write silently, and marking the store converted over
surviving cleartext is unrecoverable.

What remains is already recorded in the crypto box's own scope note and is not new: `SharedPreferences`
rewrites by rename, so the *pre-migration* file's blocks are unlinked rather than overwritten, and
that cleartext can persist in freed blocks. That is a bound on what any migration can promise, not a
defect in this one.

## The contact adapter's map fallback is unreachable, and safe if it ever is not

The trust-model round left this open, phrased exactly right: `ListAdapterContacts.getItem` catches a
`ClassCastException` and rebuilds the row from a `LinkedHashMap`, reading `"verified"` straight out
of it — *if it is reachable, it is a badge built from a map rather than from the store.*

Both halves are now measured rather than argued, in `VerifiedBadgeRenderTest`.

**Reachability.** It cannot be read off the declared type. `contactList` is an `ArrayList<Contact>`,
but erasure means that field is not a promise about what is in it at runtime: a generic
deserialisation lands `LinkedHashMap`s in a list whose static type says `Contact`, and nothing
complains until somebody casts. So the element is fetched through a wildcard after a real store round
trip. It comes back a `Contact`.

It comes back a `Contact` because of **one line**. The store genuinely hands back maps;
`JsonUtil.convertContactsList` is what turns them into contacts. Neutering that one call — return the
input unchanged, which compiles — makes the round-trip test fail. The fallback is therefore not
paranoia about a hypothetical: it is a second line of defence behind a single conversion call that
nothing else in the suite was pinning. Two tests now fail if it is removed.

**Consequence if it were reached.** None, and this is the half worth keeping. The fallback branch is
driven for real — the map goes into the adapter's own list, so production throws and catches the
`ClassCastException` and the row really is built from `LinkedHashMap.get("verified")` — claiming
`verified=true` at an address holding no pinned key. It renders **unverified**, because
`isContactKeyTrustworthy` re-derives the answer from the store rather than believing the flag on the
object handed to it.

That control found the gap that was actually worth finding. Mutating the badge to read
`contact.isVerified()` instead of consulting the store kills only the new test: every pre-existing
badge test stays green, because in their fixtures the flag and the store always agree. The suite
could not distinguish *a badge asserted by the object* from *a badge derived from the store* — which
is the distinction the whole trust model rests on. It can now.

Also recorded, not chased: `Contact` carries exactly the five fields the map copies, so a map-built
row would not even lose data if `updateContactInContactList` replaced a live row with one.
