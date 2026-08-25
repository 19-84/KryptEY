# KryptEY revival — state of the work

Branch `revival`, on top of 0.1.5. This documents what changed, what is verified, and what is
deliberately still open, because the diff is large and several decisions in it are not obvious from
the code alone.

*Counts in this file are dated to a commit and go stale by design.* `git rev-list --count
master..revival` and `testDebugUnitTest` are the authorities; this header has now been corrected
twice for drifting away from them, which is the same failure the document records about comments,
about its own mutation ledger, and about every entry on its deferred list.

Baseline: KryptEY 0.1.5 (May 2023) — libsignal 0.21.1, cleartext key storage, `jcenter()` build.

**PQXDH is asserted, not assumed.** libsignal records the handshake in the session version - 3 is
X3DH, 4 is PQXDH - and until late in the revival nothing checked it. Every test asserted that a
session could be built and that messages round-tripped, all of which is equally true of X3DH. If the
Kyber pre-key were dropped, ignored, or silently unusable, sessions would still establish and the
suite would stay green while the post-quantum property the upgrade exists for was absent. The
session version is asserted now, on both sides and on the out-of-band path.

**Tests: 31 → 843 as of `bfb71c1`** (842 run, 1 permanently skipped — `FixtureGenerator`, which is a
tool rather than coverage), all passing, and green from an empty cache rather than only warm. Debug
and release both assemble; dependency verification pins 386 artifacts by SHA-256.

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
recorded and permanently ignored. Now replaces — but replacing is not trusting.

The first attempt at this was wrong in a way worth recording, because a green test suite hid it.
`isTrustedIdentity` refused a displaced key for SENDING and allowed RECEIVING, intending to show the
message with a warning. libsignal calls `isTrustedIdentity` **before** `saveIdentity`, so refusing
there means `saveIdentity` never runs — the pending-change flag was never set, `REPLACED_EXISTING`
was unreachable, and the whole mechanism was dead code. The tests passed only because they called
`saveIdentity` directly, producing a state libsignal cannot.

Worse, that version also cleared the pinned identity on contact removal, which opened a fail-open
path that did not exist before: substitute a key, the user is shown generic "delete and re-invite"
advice, follows it, and the attacker's key is accepted as a clean first sighting.

Contact removal was therefore made to *keep* the pin. Then a question none of the first three review
rounds had asked — **who controls entry into the refusing state, and can a legitimate user get back
out?** — showed that half of that was wrong too. An attacker controls entry: one forged bundle to an
address the messenger sees in every envelope. Nobody controlled the exit — accept had no UI,
`removeIdentity` had no caller at all, and deletion deliberately did not help. Messaging carried on
normally against the genuine pinned key, so nothing looked broken; the verified badge was simply
gone, permanently. A remotely-triggerable, unclearable DoS on the one indicator the model rests on
trains the user to disregard it well before any real substitution arrives.

The first attempt at an exit was deletion — clear the pin when the contact is deleted — and it was
**worse than the problem**, which the next review round caught before it went anywhere. The
justification had been that the fail-open was closed at source because the delete-and-re-invite
advice no longer appeared for an identity change. That was false: the advice was the app's standard
response to *any* decryption failure, and the messenger can induce one whenever it likes by replaying
a message (`DuplicateMessageException`) or flipping a bit (`InvalidMessageException`). Neither records
a pending change, so no identity-change warning fires — guarding that one branch closed nothing,
because the attacker has no reason to use it. Replay the message, wait for the user to follow the
advice, then supply your own bundle for the now-unpinned address.

The exit used now is **dismiss**: discard the offered key, *keep the pin*. It opens no window,
because nothing about the stored key changes. It is safe for one specific reason, which is a coupling
rather than a local property — `createFingerprint` always derives the displayed number from the
**pinned** key, so a user pressing verify has compared the key already in use and found it correct;
the right response to that is to throw away what somebody else offered. `PendingChangeExitTest`
guards the coupling directly, so a refactor that made the offered key visible would fail loudly
rather than turning verify into a one-tap adopt.

Deletion keeps the pin, as it did before. `acceptIdentityChange` stays unwired.

**The mirror case: the pin itself is wrong.** Everything above assumes the pinned key is genuine and
treats a newly offered key as hostile — right whenever the pin was set honestly. But the pin is set
by trust-on-first-use, through the same messenger the threat model says can forge anything. A
messenger that swaps the very first invite, keeping the peer's real address name and device id (both
plaintext envelope fields it can read off the genuine bundle), gets its own key pinned with **no
warning of any kind** — there is no earlier key for TOFU to notice a change from.

In that case every control did the wrong thing: dismissing discards the peer's real key, deletion
keeps the impostor's, and confirming paints the badge green over the wrong identity. The genuine
peer was then unreachable at their real address for the life of the install, the only recovery being
to clear app data and destroy the user's own identity. A user doing exactly the right thing —
comparing numbers and finding a mismatch — had no action available.

`rejectContactKey` is that action, wired to a "does not match" control on the verify screen. It
forgets the pin, the session and the badge. Between dismiss (the pin is right) and reject (the pin is
wrong) both directions are now covered, which is what `acceptIdentityChange` was being reached for
before either existed.

**A correction, because this was argued wrongly first.** The original justification was that reject
is safe because "an attacker cannot deliver the user to the verify screen, and cannot make the
comparison fail for a genuine peer". Both halves are false, and the second was already written down
as deferred defect #4 in this very document while the code claimed the opposite. The identity-change
warning tells the user, in as many words, to open the contact and compare — so the app's own text
routes them there, and any forged bundle triggers it. And safety numbers are computed over the
peer-supplied address name, which nothing signs, so a messenger can rewrite it and manufacture a
mismatch between two entirely honest peers.

An attacker can therefore arrange for a careful user to reject a *correct* pin. That does not make
the control wrong — someone who sees a mismatch must be able to act — but it means the state after a
reject cannot be assumed benign. `removeIdentity` wiped every trace of the address, leaving it
indistinguishable from one never seen, so the next bundle was a silent first sighting **including the
forged bundle that provoked the rejection**. The rejection is now recorded separately and outlives
the key, so a pin arriving at a rejected address is a warned event; only a fresh comparison retires
it. What remains true, and is the real reason this is safer than clearing the pin on contact
deletion, is narrower: reject takes a deliberate action on a specific contact, rather than following
generic advice shown after any decryption failure.

This does not *detect* a swapped first invite — nothing can, from inside the app. It makes the
detection the user performs actionable, and makes the aftermath visible.

As it now stands: a displaced key is refused in **both** directions; the change is recorded from the
`UntrustedIdentityException` path, taking the offered key from the bundle (libsignal raises that
exception with a null identity, so `getUntrustedIdentity()` is useless); contact removal does **not**
surrender the pin; and `acceptIdentityChange` takes the key the user was actually shown and refuses
anything else, so a key arriving between display and confirmation cannot slip through.

**Out-of-band exchange** now exists as a mechanism: `exportOwnKeyBundle()` produces transferable
text and `importOutOfBandKeyBundle()` consumes it, with the result recorded in the identity store
and readable via `isKeyOutOfBand(address)`. (This said `KeyOrigin.OUT_OF_BAND`, a type that no
longer exists — the provenance moved onto the store rather than the contact, and this sentence did
not follow it.) That provenance is the point — trust-on-first-use cannot detect a hostile
messenger substituting keys at first contact, because there is no earlier key to have changed, so
the assurance has to come from the bundle not travelling through that messenger. Afterwards the two
cases are indistinguishable from the stored key alone, hence recording it at import time.

**Out-of-band provenance is recorded but does not confer trust.** It briefly did, on the reasoning
that a key which never touched the messenger needs no further check. That was unsound: the exported
bundle is byte-identical to the one the invite flow sends, so the code can only observe that the
import method was called, never how the bytes travelled — a user copying an invite out of WhatsApp
and pasting it into an import field produces an in-band key stamped out-of-band. Granting trust also
*suppressed* the prompt to compare, so on first contact it promoted a substituted bundle from
"unverified pin" to "checked". A comparison is checked against the peer's own device; a transfer is
checked against nothing. Only comparison counts now, and a verified badge is dropped as soon as a
different key is offered.

**Provenance lives with the key, not the contact row.** It was briefly a settable field on
`Contact`, which meant the strongest trust signal in the app could be granted by constructing an
object — and, because `Contact.equals` then depended on it, mutating a copy silently no-opped the
contact-list update. It is now recorded in the identity store by the import that actually observed
the transfer, dropped when the key is forgotten, and not carried over to a replacement key accepted
after a change.

**What is and is not wired to UI**, precisely, because an earlier version of this note overstated
it. The contact list badge consults `isContactKeyTrustworthy` (`ListAdapterContacts:52`). The
identity-change warning now reaches an existing contact and not only a newly added one — it fired
solely from `createSessionWithContact`, which runs on the add-contact path, so the two routes a real
attacker takes (a substituted `PreKeySignalMessage`, and a bundle-only re-invite) showed either the
generic decryption-failure advice or a success-shaped screen with no mention that a safety number had
changed. `verifyContact` now returns whether it took — `false` means only that nothing was
loaded, and the UI says exactly that rather than inventing a security claim.

The generic failure strings no longer tell the user to delete a contact, for any cause. That advice
was attacker-triggerable and could not fix anything even when it was honest; the commonest reason it
appeared is decrypting the same message twice, for which deleting is pure damage.

Still unwired: `exportOwnKeyBundle`, `importOutOfBandKeyBundle` and `getPendingIdentity`, so there is
no in-keyboard out-of-band exchange and no screen showing the offered number beside the pinned one.
`acceptIdentityChange` is unwired **deliberately** — see above. Closing the out-of-band gap needs UI
work.

QR is purely a UX layer over the same string and needs a dependency decision.

**Mutation-sweep ledger.** Six sweeps are recorded below, numbered 1-6; a seventh (151 mutants
against `SignalProtocolMain`) is summarised under Open #3, and round 24's seven `E2EEStripView`
mutants live only in commit messages. The header used to say "four sweeps" while three were
labelled — the count was wrong and had been for a long time, which is a small thing except that
this is a ledger, and a ledger whose total does not match its entries is the one artefact that
should never need checking against itself. This ledger exists because a previous commit claimed "all 21 survivors
killed or documented" with nothing in the tree to audit against — and it had then stopped being a
complete record itself, which is the same failure one level up, twice. It has now done it a third
time: sweeps 4, 5 and 6 below were run and acted on several rounds before anyone added them here.
The lesson is not "remember to update the ledger", which has now failed three times; it is that a
record kept by hand beside the work it describes will drift, and the only claims worth trusting are
the ones a test makes.

*Sweep 1 — `EncryptedKeyValueStore`, `GcmCryptoBox`, `SharedPreferencesKeyValueStore`,
`BinaryEnvelope` (93 mutants, 21 survivors).* Killed by new tests: `isEncrypted` (`StorageSchemaStateTest`),
`hasEncryptedData` ×2, the unmarked legacy read, `containsReadableEnvelope` ×1 of 2, sender-name
length, sender-name character bounds ×2, u8 range ×3, u16 length, cursor bounds ×2, device count,
varU8 length, zero-length read. Recorded as equivalent: the in-progress marker write (no early
return, so the completion marker always overwrites it — documented in `StorageSchemaStateTest`), the
`looksLikeEnvelope` structural pre-filter ×2 (documented in the method as never load-bearing), the
nonce-length error-message branch, and the second `&&` in `containsReadableEnvelope`.

That last one was first recorded here as genuinely uncovered, which was wrong — it is equivalent, and
the argument is worth keeping because it is not obvious. Weakening it turns
`A && B && canDecrypt` into `(A && B) || canDecrypt`, so it matters only for a value where the
structural pre-filter says "not an envelope" but decryption nevertheless succeeds. That cannot
happen: `decode` is strictly stricter than `looksLikeEnvelope` (it enforces the GCM tag length as
well as the version byte), and every path out of it — bad base64, short envelope, wrong version,
failed tag, even a null value — raises `StorageCryptoException`, which `canDecrypt` converts to
false. The two expressions therefore agree on every input.

*Sweep 3 — `EncodeHelper`, `FairyTaleEncoder`, `EnvelopeCodec`, `KeyUtil` (51 mutants, 14
survivors).* Killed by new tests: both arms of the two `x == null || x.isEmpty()` guards in
`EncodeHelper` (one of which exists to stop a crash that already shipped — copying any two-line
message reached `new BigInteger("", 2)` and killed the IME process), the partial-group skip in
`convertBinaryToInvisibleString`, the wire-size cap boundary, the one-time pre-key batch size, the
`refreshSignedPreKeyIfNecessary` null guard, and the allocator's bounded search — weakening its
`&&` to `||` turns a step-to-the-next-free-id into a full scan of the id space on the IME main
thread. Recorded as equivalent: the group loop's own bound (the partial-group skip is the real
bound, and the extra iteration it admits is immediately rejected), and the null guard inside the
private `deleteOlderSignedPreKeysIfNecessary`, whose single caller already returns on null. Both `FairyTaleEncoder` lazy-init survivors are now closed, and closing them found a
shipped defect. The deferral said the context arm "can only be exercised with a real Android
Context and the string assets behind it, which needs an instrumentation run" - that was
assumed, not measured. `init` calls `context.getResources().getString(R.string.e2ee_rapunzel)`,
and Robolectric serves string resources from the real `strings.xml`. Writing the test took one
file, and the first run failed with `ZipException: invalid stored block lengths`:
`convertBinaryToByteArray` was `new BigInteger(binary, 2).toByteArray()`, which prepends a
0x00 sign byte whenever the leading byte is >= 0x80 and drops leading zero bytes in the other
direction. The first byte of a DEFLATE stream is >= 0x80 for a large share of inputs, so
roughly half of all FAIRYTALE-encoded messages could not be decoded by the recipient. It
survived every sweep because the suite had one round-trip fixture and the bug is a property of
the message, not the encoder. Scope: `Encoder.RAW` is the default and FAIRYTALE is an opt-in
toggle, so this hit users who switched encoders - but decoding is chosen by sniffing the text
for invisible characters, so a default-RAW user *receiving* a FairyTale message hit it too.

*Sweep 4 — `ListAdapterContacts`, the verified-badge render (5 mutants, 2 survivors).* Both
survivors were the same gap and it was total: **inverting the trust condition, so every untrusted
contact renders as verified, passed the entire suite**, and so did replacing it with `true`.
`isContactKeyTrustworthy` is tested hard as a function; the line turning its answer into an icon was
tested by nothing, and that icon is the only thing a user told to compare safety numbers is given to
act on. Closed by `VerifiedBadgeRenderTest`, which asserts both directions, the substitution and the
rejection cases, and — after a reviewer found the gap — that the *visible* badge is the one carrying
the click listener. This sweep is also the one that was killed by a time cap mid-run and stranded a
mutant in a tracked file; see the section on that.

*Sweep 5 — `E2EEStrip`, the send-side length cap (3 mutants, 2 survivors).* Deleting the invite
refusal outright, and moving its boundary by one, both survived. The cap is the only thing between
the send path and a bundle the recipient cannot decode. Closed from above and below, since a cap
tested only from above is satisfied by refusing everything.

*Sweep 6 — `LegacyKeyMigration` (3 mutants, 2 survivors).* Keeping ambiguous chat-log entries instead
of dropping them, and keeping an unidentifiable retirement's bare name instead of blanking it, both
survived — the two choices that decide whether the migration is safer than what it replaced. Closed
by `LegacyKeyMigrationTest`. The third mutant, removing the marker check, was "killed" only by the
stranded-mutant tripwire noticing `if (false)`, which is what exposed the marker as an efficiency
guard rather than the safety property.

*Sweep 2 — the pre-key/session stores and `StorageHelper` (88 mutants).* Clean through
`KyberPreKeyStoreImpl`, `PreKeyStoreImpl`, `SignedPreKeyStoreImpl`, `SessionStoreImpl` and
`PreKeyMetadataStoreImpl`. Two survivors in `StorageHelper`, both now killed: a partial read
aborting only when *both* the metadata store and the address are missing rather than either, and the
null-guards on the message and contact lists — inverting those replaces a good list with an empty
one, which the next write-back persists, erasing the user's history. The class comment warned about
exactly that; nothing tested it.

**One known equivalent mutant elsewhere.** Swapping the local/remote identifiers in the fingerprint
survives every test, and correctly so: `NumericFingerprintGenerator` sorts the two halves, so both
sides compute the same value either way. A genuine symmetry, not a coverage gap.

---

**A verified badge requires a pinned key, on both sides of the invariant.** `verifyContact` refused
to *set* the badge with nothing pinned, and `isContactKeyTrustworthy` never asked — so a stale
`Contact` carrying `verified = true` over an address holding no key read as trustworthy, and a later
trust-on-first-use pinned an attacker's key onto an already-green contact. Enforced where it is
written and ignored where it is read is how the original defect worked; both sides check now.

**A standing rejection outranks a verified badge.** Telling the app a number did not match records a
rejection, and only a fresh comparison retires it. Without consulting that on read, rejecting and
then letting the attacker's bundle pin by trust-on-first-use gave the green badge back.

**Deleting a contact retires its display name.** Deletion keeps the pin, which closes the
*same-address* door — a substituted bundle for that address is still refused. It does nothing about
the other door: the attacker's own fresh address carrying the deleted contact's name, where nothing
is pinned and TOFU accepts silently. With the name gone from the list, the duplicate warning had
nothing to fire on, so a warned attack became an unwarned one. `rejectedAddresses` was deliberately
made to outlive `removeIdentity` on exactly this reasoning; the display name got no such treatment
until now. Bounded at 100 distinct names, and it does **not** fire for a re-add at the same address,
where the surviving pin already proves identity — that was the commonest firing and it used wording
the user could see was false.

Its limit is stated rather than implied: it matches the exact folded name. For a live duplicate that
suffices, because a dodge still leaves two rows distinguishable by their tags. After a deletion there
is no second row, so `Bob`, `Bob J`, `Robert Jones` and `Bob Jones (new phone)` all pass unwarned.
Matching loosely would fire on ordinary names and habituate the user, which is the failure mode the
whole control exists to avoid.

**The warning banner is the only lasting control, and almost everything could erase it.** Every
mechanism above ends in one place: a line of text on a strip four buttons wide. There is no
notification, no badge that persists across screens, no modal. So "can the messenger overwrite that
line?" is not a UI question — it is the question of whether any of this reaches the user at all.

`mWarningStanding` marks a banner as a warning, and passive events must not write over one. The flag
was correct; the set of writers that respected it was not. Found and closed, each of them costing an
attacker one ordinary chat message:

- A paste that fails to decode, and declining an invite, both wrote "No contact chosen" over a
  warning — and left the flag set, so the strip was *wedged*: the flag said a warning was on screen,
  which blocked replacement, while the text said nothing was wrong and the watcher read it as a
  reason to disable both buttons. Declining an unexpected invite is the *correct* response to a
  suspicious one; it was the action that erased the reason for taking it.
- Three banners on the decrypt path — "Keybundle detected" and two "Detected contact:" lines. The
  press is the user's, but the payload is the attacker's.
- `selectContact` cleared the flag outright, on the reasoning that choosing a contact means having
  seen what was on screen. True of a notice; false of a pending identity change, which is a *state*.
  The screen a user opens when something looks wrong was the screen that made it stop looking wrong.
- The duplicate-name and same-address warnings were posted as ordinary banners, so one more post
  removed them. The duplicate-name one is the only control covering the case the pin cannot.

Two of these had a mutation-testing wrinkle worth recording. Deleting either copy of the
post-rejection or duplicate-name warning changed nothing observable, because each was posted twice —
once before session creation and again after — and each copy masked the other's deletion. Consolidated
to one writer, both mutations die. The same pass found that a failed session then painted "ask for a
fresh invite" over the surviving warning: the delete-and-re-invite advice the warning exists to talk
the user out of, left standing over a flag that made it uncorrectable.

**Who the user talks to also outlived it, and the obvious fix was a trap.** The banner naming the
chosen contact rode through an app switch, so the keyboard rose in the next app still saying "Chosen
contact: Bob ⁨#a1b2⁩". Blanking it on hide would have been a one-call warning eraser handed straight
to the adversary — any app may hide the keyboard whenever it likes, and `mWarningStanding` exists
precisely so that a security warning survives everything the messenger can cause. Blanking the text
while keeping the recipient is worse than either: encryption would still go to a contact the screen
no longer names, which is a mis-send rather than a disclosure, and the same shape as the
cross-recipient bug already fixed here.

So the *recipient* is forgotten instead. It costs a tap to re-choose and nothing else — the compose
field is already cleared on the same event, so no draft is lost that was not lost before. A standing
warning keeps the banner: a warning that names a contact is a disclosure the user needs more than
they need the privacy, and that trade is stated rather than assumed. What remains uncovered is
someone reading the screen over the user's shoulder while a warning stands. That residue is
accepted, because the adversary in this threat model is the messenger, and the messenger cannot
capture the IME window at all.

The dismissal path is now one method on the strip rather than a list of calls in `LatinIME`. It was
a list for about an hour, and in that hour the test covering it was already a stale copy of the old
list — the same "a test that re-implements the body proves only that the copy behaves" failure this
document keeps recording, arriving faster than usual.

**And the plaintext outlived the keyboard.** `clearDecryptedContent()`, which `onWindowHidden` calls,
cleared the compose field only. The chat-log screen stayed visible with the entire decrypted
conversation in its adapter, and because the IME view is not recreated on an app switch, that history
was still on screen the next time the keyboard rose — in whatever app that was. `FLAG_SECURE` stops a
screenshot of it and does nothing about the person next to you. It now leaves the screen and drops the
adapter: returning to the main view alone would have kept the log one button-press away with no
further decryption.

## Verified vs. reasoned

**Verified by execution:**

- 701 JVM tests as of `cd67fb3`, including an end-to-end conversation across all four phases and a
  real MITM identity substitution driven through libsignal. This figure is point-in-time and goes
  stale on every commit; `testDebugUnitTest` is the authority, not this line
- A golden wire vector, re-checked against the three mutants that previously survived
- Robolectric tests against real SharedPreferences
- Negative controls on the highest-stakes regressions (legacy-peer crash, one-time pre-key
  overwrite, Jackson fixture corruption)

**A note on which SDK the tests run at.** Robolectric runs at `compileSdk` — 35 — unless a test
says otherwise, and `minSdk` is 26. Every `SDK_INT <` branch in the app is therefore code real users
on API 26–32 execute and that no test had ever entered. `LegacyApiClipboardTest` closes the one that
is a security behaviour rather than a compatibility detail: `clearClipboard()`, called on every exit
of the decrypt path, uses `clearPrimaryClip()` only from API 28 and overwrites the clip below that.
It is asserted at 26, 27, 28 and 35, and each branch has a control that fails exactly its own two.

StrongBox selection is now entered too, at 26, 27 and 28 — see the predicate-wired-to-nothing section
for why that took reshaping the branch rather than adding a `@Config`. **This paragraph said it
"remains unentered" and was made stale by that fix, in this session, three commits later.**

The wider count was also wrong. There are **15** `SDK_INT` comparisons in the app, not the three this
paragraph named, and eight of them are not "unentered" but *unreachable*: `<= KITKAT` (19) is false
on every device that can install this app and `>= N` (24) is true on all of them, so one side of each
is dead at `minSdk` 26. No `@Config` can reach them — a test that entered one would be testing a
device configuration that cannot exist — and with `minifyEnabled false` they ship and read to an
auditor like code that runs. All eight are inherited from AOSP, where `minSdk` was lower.
`MinSdkDeadBranchTest` computes that set from the source rather than listing it, so the ninth fails;
the eight are named with the reason, and a second test stops those names outliving the code. The
navbar colour and `RECEIVER_NOT_EXPORTED` genuinely remain unentered.

**Reasoned but NOT verified:**

- `AndroidKeystoreCryptoBox`'s Keystore CALLS are executed by zero JVM tests; its key-resolution
  DECISIONS now are, and more thoroughly than this entry used to say: `KeyResolutionTest` (10 tests,
  via a `KeystoreOps` seam) covers the resolve behaviour — refusing to generate when data is at
  stake, stepping down a rung on a failed self-test, clearing the alias between attempts, and
  self-testing every candidate — and `KeyCandidateLadderTest` (8 tests) pins the ladder's *order*,
  which is a security preference rather than an implementation detail: strongest first, weakest
  last, `unlockedDeviceRequired` given up only after StrongBox, and the API-28 floor. Checked by
  mutation: reversing the StrongBox preference so a non-StrongBox key is chosen over a StrongBox one
  fails immediately. 11 instrumentation
  tests are written and compile, but need hardware or a KVM runner. **"And compile" is now
  enforced**: nothing used to build `androidTest` during an ordinary run, so the only coverage of the
  real Keystore could have been broken by a rename and stayed broken silently for months — it is the
  code most exposed to that, precisely because it cannot run here. `testDebugUnitTest` now depends on
  compiling it, which costs a few seconds and turns silent rot into a build failure. It does not make
  them run, and nothing here should be read as claiming it does.

  **Not** the `CALLER_NONCE_PROHIBITED` fix, which this entry went on calling "sound in principle and
  untested in fact" long after `CallerNonceProhibitedTest` had verified it. That test registers a JCE
  provider imposing the Keystore's rule and runs the real `seal`/`open` against it, so a regression
  fails on the JVM rather than only on a device nobody here can run. The stale sentence cost real
  work: a later round read it, believed the gap, and wrote a second copy of that test from scratch
  before noticing the first. This document drifts the same way comments do, and a reader deciding
  what still needs doing trusts it the same way.
- No part of this has run on an Android device.

---

## Settled

- **APK size accepted** at 115.1 MB (arm64) / 109.2 MB (armeabi-v7a), re-measured at `fc83fc9` from
  the cold build above. libsignal 0.86 is ~74 MB of native code per ABI; that is the cost of PQXDH.
  The previously recorded 114/108 was close but stale — these are debug APKs and the figure drifts
  with the code, so it is dated rather than absolute.
- **Invite threshold accepted** at 4096 characters, up from 500. A PQXDH bundle is 2484 and
  irreducible (Kyber-1024 is 87% of it, and key material does not compress). Clears Telegram and
  above; does not fit SMS, but neither did 500.

## Open

1. **Out-of-band exchange: the capability is already reachable; what is missing is guidance.** This
   entry read as "the mechanism exists, the UI does not", and that overstates the gap in one
   direction and understates a subtlety in the other.

   *Export already works.* The invite button commits the bundle into whichever app's field currently
   has focus — the user picks the channel by picking the app. Sending it through a notes app, an
   email draft or a device-to-device transfer is out-of-band exchange, today, with no new code.

   *Import already works too.* An out-of-band bundle pasted anywhere the strip can see it is
   detected by the ordinary clipboard path and handled by `processPreKeyResponseMessage`.
   `importOutOfBandKeyBundle` is that same call plus one thing: `markKeyOutOfBand`. And **nothing
   reads that flag** — `isKeyOutOfBand` has no caller outside the store — because this project
   decided, correctly, that provenance cannot be observed: the exported bundle is byte-identical to
   the invite one, so the code can only see that a method was called, never how the bytes travelled.

   So wiring that method up is not the task, and would add a stamp that means nothing. What would
   genuinely help is wording — telling the user that handing this text over in person avoids the
   messenger entirely — and that has to be written without implying the app can tell the difference,
   which is the false assurance the design deliberately removed. QR would be a dependency decision
   (ZXing); string transfer needs none, and needs no new mechanism either. *Not* on this list any more: UI for accepting an identity change. That is
   now a decision rather than a gap — the exit is discard via deletion, and adopt-in-place stays
   unavailable on purpose.
2. ~~**A screen showing the offered safety number beside the pinned one.**~~ **Blocked, and on
   purpose — it should not be built while adopt-in-place stays unavailable.** The mechanism is
   trivial: `getPendingIdentity` supplies the offered key and `createFingerprint` would need only to
   take the remote identity as a parameter rather than reading the pin. What stops it is the
   question the screen would put to the user.

   Today the model is coherent. The warning says *"It was refused and is not in use — your messages
   still go to the key you already had. Open them in your contact list and compare the number."* The
   number on that screen is the **pinned** one, the user can see it, comparing it confirms the key
   actually in use, and confirming dismisses whatever someone else offered. Every part of that
   agrees with every other part.

   Showing the offered number breaks it. The user compares the new number, and if it **matches** —
   the peer really did reinstall and this really is their new key — the only available actions are
   dismiss (throw the correct key away) and reject (forget the pin). Neither adopts it. The screen
   would invite a comparison the user cannot act on, which is worse than not showing it: a control
   that asks a question and ignores the answer teaches people to stop answering.

   So this is not a UI task. It is downstream of the adopt decision, which was taken deliberately in
   the other direction and was removed from this list as "a decision rather than a gap". Those two
   entries were coupled and the list did not say so. If adopt-in-place is ever revisited, this comes
   back with it; until then the correct state is the current one, and the strings already match it.
3. ~~**`SignalProtocolMain` has never been mutation-tested.**~~ Done for `signalprotocol/`.
   **This entry used to end "which closes the last coverage gap reachable in this environment", and
   that was false when written and stayed at HEAD for twenty rounds.** `E2EEStripView` — 1300 lines
   holding every decision the user makes — was reachable in this environment the whole time;
   Robolectric inflates it cleanly, which a later round proved by doing it, and seven mutations then
   survived the entire suite. The sentence was the most consequential in this file, because a reader
   deciding whether review had converged would read it and conclude yes.
   **And the correction went stale in its turn**, which is the same failure one level deeper and is
   why it is being rewritten rather than patched. What is true as of `f3c1667`:

   - `signalprotocol/` is swept, and an independent round that went looking there specifically
     reported back that it could not break it — the first well-evidenced convergence signal this
     branch has had. Treat it as done unless something changes underneath it.
   - `latin/e2ee/` has had several rounds against `E2EEStripView`, a sweep of `E2EEStrip`'s
     send-side cap (sweep 5) and of `ListAdapterContacts`' badge render (sweep 4, where inverting
     the trust condition passed the whole suite). `ListAdapterMessages` was examined and found
     already covered. The same round called this directory converged too.
   - The **seam** between the inherited keyboard and the strip is where the live defects are: seven
     in two rounds, including a redirection of the user's plaintext into the messenger's own field.
     It has its own section below.
   - Genuinely unexamined still: the rendering and layout bulk of `keyboard/`, `latin/utils/`,
     `latin/settings/`. Only the debug switches have been looked at there, and only because they
     are keyloggers in this app specifically. The `SignalProtocolMain` sweep was 151 mutants and 44 survivors, and almost all of
   them were one pattern: guards written `a == null || b == null` where every test supplies both, so
   only the both-present arm ever runs. Nine of those were closed with tests; the rest were either
   already covered by later commits (the sweep runs against a snapshot, so re-verifying before
   chasing is essential) or genuinely equivalent — `logMessageType`'s branches emit only log lines,
   and `reloadAccount`'s write-back guard is redundant with the null check inside the method it
   calls.

   The pattern is worth recording because it is systematic rather than incidental: **fixtures
   construct the healthy state, so guards that fire on the unhealthy state are never
   discriminated.** Line coverage reports those lines as covered.

## Settled during review

These were open and are not any more. The reasoning is kept because several of them were got wrong
first, and the wrong version is the part worth not repeating.

**Contacts were listed by display name only.** Addressed, then found bypassable, then fixed
again — three rounds. The app cannot *refuse* a second contact under a familiar name — a genuine reinstall
   really does arrive at a new address, which is what makes the attacker's "I reinstalled" story
   credible — so the goal is visibility, not prevention. What that took:

   - **Name folding that actually folds.** NFKC + trim + lowercase does not fold scripts (Cyrillic
     А, Greek Α and Latin A are three characters drawing one glyph) and does not strip format
     characters (a zero-width space survives it). Names are now reduced to a skeleton: invisible
     characters removed, NFKC applied, and the Cyrillic/Greek letters that share a Latin glyph
     mapped onto it. Both defences were gated on this one comparison, so a single invisible
     character had suppressed the warning *and* removed the tag from both rows.
   - **A tag that cannot be aimed at.** This was got wrong twice. It began as 40 bits of SHA-256
     over the address; that was widened to 96 on the reasoning that 40 were grindable. Both the
     diagnosis and the fix were wrong: the input is an address the peer chooses freely and the
     adversary knows the address it wants to collide with, so it can compute the target tag and
     grind towards it at any width — matching the leading group took **nine seconds on one JVM
     thread**, and widening made things worse, because a longer string is one a user reads less of.
     The tag is now an HMAC under a per-install secret. There is nothing to aim at, which in turn
     lets it be short enough to read end to end — and the bits a person actually compares are the
     only ones that were ever protecting anyone.
   - **A name cannot attack the tag it sits beside.** Names may not contain `#`, which stopped an
     attacker's invite text supplying a counterfeit tag in the same field, same style, same size.
     They are also length-capped, but **the cap is not what protects the tag** and an earlier version
     of this bullet said it was. A cap counts characters while the layout consumes width, so eleven
     EM SPACEs beat any cap on any device. What protects the tag is the layout: it has its own view
     anchored to the row's end, with both name views inside a container bounded by it, so the name
     ellipsises and the tag always renders. That invariant is asserted by a test that measures the
     rendered row, because it was got wrong twice and neither time could the suite see it.
   - **One address is one identity.** Adding a second contact at an address already in use is
     refused outright — an exact check on the address rather than a name heuristic, so it cannot be
     dodged. It has to refuse rather than warn: `updateContactInContactList` matches by address and
     replaces the first match, so verifying the newer row would silently overwrite the older one,
     erasing the very evidence the warning pointed at.
   - **The tag is not gated on the name comparison.** That was the structural error: both defences
     hung off one predicate, so any dodge of the folding produced a total blackout rather than a
     missing warning. The tag is a pure function of the address, so it now renders whenever there is
     more than one contact. Folding gaps are inevitable — homoglyphs are open-ended — and this is
     what keeps each one partial.
   - **The tag renders where the user acts**, not only on the contact-list row: every surface that
     names a contact goes through one label builder, so the set is checkable rather than a claim.
     It is shown whenever the account holds any contact — deliberately *not* gated on whether a name
     is shared, which is the same structural error as gating it on the folding, and an earlier
     version of this bullet described the gated behaviour.
**Safety numbers were bound to the peer-supplied address name**, which is covered by neither the
   bundle signatures nor the message MAC. A messenger that rewrites that field consistently in both
   directions cannot forge a *match*, but can manufacture unlimited *mismatches*. Signal binds to a
   server-attested identifier; there is no equivalent here.

   **Fixed.** The fingerprint is now computed over the two identity keys and nothing else, so the
   messenger has no input to it. The claim that this was "a wire-format-visible change, hence
   deferred" was wrong: the fingerprint is derived independently on both sides and never
   transmitted, so nothing on the wire depends on it. The only cost is that the digits shown for
   existing contacts change once, and anyone who already compared has to compare again.

   Note what this does *not* fix: nothing still attests that an address belongs to a given person.
   The number now proves "these two keys are the ones in use", which is what the comparison is for;
   it does not prove the address you are sending to is your contact's. That is the duplicate-name
   problem, addressed separately by warning and tagging.
**The instrumentation tests.** Not possible in this environment, and that is now settled
   rather than pending: there is no `/dev/kvm` and the host CPU exposes no virtualisation
   extensions at all, so an emulator cannot run here even slowly. The 11 `AndroidKeystoreCryptoBox`
   tests have therefore never executed anywhere.

   Two pieces have since been pulled onto the JVM. `CallerNonceProhibitedTest` covers
   `randomizedEncryptionRequired` — the one Keystore behaviour a desktop JVM does not share, which
   makes a Keystore key reject a caller-supplied IV. And the key ladder's *ordering* — ask for the
   strongest protections first, degrade only when refused — was extracted from the nested loops it
   was welded into, so `KeyCandidateLadderTest` can check it without hardware. Getting that order
   backwards would silently mint a weaker key on a device that could have done better, and the log
   line says what was created, not what was possible.

   What still needs a device: that a key created with those flags actually *has* them. That is a
   `KeyInfo` check on real hardware and nothing here can substitute for it. It registers a provider that imposes that rule and runs the real
   seal/open path against it, so the bug that already shipped once cannot recur silently. That is
   one property, not the whole suite; the rest still needs a device.

## Verifying a build honestly

"The tests pass" is not the same claim as "this builds". Every build during the revival ran against
a warm Gradle cache, and a warm cache hides dependency-verification gaps: artifacts resolved during
buildscript classpath resolution are fetched *before* `--write-verification-metadata` takes effect,
so regenerating the metadata cannot record them and every warm build passes regardless of whether
they are pinned. Two such artifacts were missing for the whole revival, and a fresh clone could not
configure at all — while dozens of ticks reported a clean verified build.

So a build claim needs a run from empty caches, not just a `clean` task. `tools/verify-cold` does
that: fresh volume, no warm `~/.gradle`, discarded afterwards. It takes a few minutes, which is the
only reason to reach for the warm path at all.

The environment itself is now in `tools/` and versioned with the code — it previously lived only in
a scratch directory, so this section pointed at something that was not part of the repository and
every reviewer had to be handed its location out of band. `tools/README.md` records what is pinned
and why. The image also installs build-tools 36.0.0, which AGP actually selects; it previously
carried only 35.0.0, and everyone working here relabelled a copy of the 35.0.0 directory to get
past it — which worked, and meant nobody was building with the tools AGP chooses.

Last cold verification, at `af17117`: `clean testDebugUnitTest` from an empty Gradle volume, with
verification ON — BUILD SUCCESSFUL in 5m25, 39 of 41 tasks executed, **843 tests green from scratch**
rather than warm. Twenty-three commits since the previous cold run, including deleting 329 lines from
the vendored `Base64` and wiring `androidTest` compilation into the unit-test task — both of which
change what the build has to do rather than only what it runs, which is the case a warm cache is
least able to speak for.
The run before it covered `clean assembleDebug` the same way (39 of 39 tasks executed, zero
verification failures across all 386 pinned components).

One hazard learned the hard way: `verify-cold` mounts **this** repository, so running it in the
background while doing foreground builds means two Gradle invocations sharing one `build/`
directory, and the `clean` pulls it out from under the other one. A foreground build failed that
way and the failure looked like a real one for several minutes. Run it alone. The task count is quoted because the first attempt was not a cold build
at all: the Gradle volume was fresh but the project's `build/` directory was mounted warm, so 33 of
37 tasks were up-to-date and nothing was really recompiled. `clean` is part of the claim. Every declared dependency is at its latest stable
release; Robolectric's only newer version is a beta, which is not appropriate for the harness that
decides whether the security tests measure anything.

## The text is a security surface, and it had never been read as one

Every control in this trust model ends at a sentence. There is no server, no attestation and no
second channel the app controls: the app refuses a substituted key, records the change, and then
*tells the user to go and compare a number*. If that sentence is incomplete, the control is
incomplete, and nothing in the code can tell.

Two were, and neither was false:

- The verify screen — the screen where the comparison actually happens — said "compare the numbers
  above with their device". A user satisfies that **exactly** by pasting the numbers into the chat
  they are already in. That is not a check: whatever could substitute the keys could substitute the
  numbers being compared. Every banner in the app already said "by voice"; the one place a user
  performs the action did not.
- The help said the same, and never mentioned that an invite can be handed over out of band at all —
  which is the only defence against a hostile messenger at first contact, since trust-on-first-use
  has no earlier key to notice a change from.

The class is worth naming because it is not "wrong text". It is text a careful user can follow to
the letter and end up unprotected, which is the same shape as a warning banner that is on screen but
erasable, or a guard that is present but never reached. The wording is the last link and it was the
only one nobody had reviewed.

Two related rules fall out, both now pinned by tests:

- **An instruction needs its reason.** "Compare by voice" without "because anything that can change
  your keys can change the numbers you send each other" is an arbitrary-looking rule, and
  arbitrary-looking rules are what get dropped as clutter by the next person editing the string.
- **Text must not claim what the app cannot know.** KryptEY cannot tell whether a bundle travelled
  out of band — the exported bytes are identical either way — so the help says so in the same breath
  as recommending it. Provenance was removed as a source of trust in the code once already; wording
  is where it would come back.

A second review round, spawned specifically to hunt that class rather than to proofread, found four
more. The pattern in three of them is sharper than the original two:

- **The app warned on every derivative case and green-lit the base case.** Four outcomes of
  accepting an invite write a message. Three — a key at a previously rejected address, a name
  collision with a live contact, one with a deleted contact — all said "compare by voice". The
  fourth, the ordinary first contact, said *"You can send messages now"*. Those three fire **because
  the app noticed something**; the fourth fires because it noticed nothing, which is exactly what a
  successful substitution looks like from inside. The one outcome with nothing else standing behind
  it was the one that told the user to go ahead.
- **A guarantee the app cannot know it has.** The help said messages "cannot be read by third
  parties", unconditionally, in a section three below the one explaining why the voice comparison is
  the check. The messenger *is* the third party, and the claim holds only against a key the user
  actually compared. Stated without its condition, it tells the reader the comparison is optional.
- **A feature described by what it looks like rather than what it does.** Fairy Tale mode "hides all
  information in a decoy text". It hides it from someone glancing at the screen. From the messenger
  it hides nothing: one sentence from the app's own shipped stories, then two characters per payload
  byte from a fixed 16-character invisible alphabet, so a single substring test identifies a KryptEY
  message. The only reason to prefer a decoy over Raw is to stop the messenger knowing you encrypt,
  and that is precisely what it does not do. The reviewer asserted the encoder's real output first,
  so the wording is measured against behaviour rather than taste.

The fourth was the received-invite walkthrough — copy, save the name, send — which never mentioned
the safety number, on the side that actually pins an incoming key.

**Whether a string needs the warning is not a matter of tone.** It follows from whether the app
noticed anything, and the answer is inverted from where attention naturally goes: the alarming cases
defend themselves, and the quiet one is where the user is alone.

## The comment-drift problem, and why it has no test

Nine review rounds found a security comment the code contradicts, several written in the very commit
that introduced the behaviour they misdescribe. The failure is not ignorance — knowing about it has
not reduced the rate — it is that a comment written at edit time is never re-read when the next edit
invalidates it.

I tried to make it mechanical: flag any javadoc naming a code point that the method it documents no
longer references. It does not work, and the reason is worth keeping. Every citation it flagged in
the current tree is legitimate — `normalizeForDisplay` names Cyrillic А and Greek Α to explain that
NFKC does *not* fold them, and `rendersAsNothing` names U+3164 to say it is deliberately handled
elsewhere. The detector cannot tell "cites as handled" from "cites as explicitly not handled", and
that distinction is the whole property. Tuning the exemption list until it passed would have been
fitting the test to the code.

It did teach one thing before being deleted. Its first version read no files at all — Gradle runs
unit tests with the working directory at the module, not the repository root, so every source path
resolved to nothing and the test passed without checking anything. A file-count assertion turns that
class of vacuity into a failure, and any test that reads paths from disk should carry one.

So this stays a review-caught defect rather than a test-caught one. The honest mitigation is that the
claims which *are* checkable have been turned into assertions — the fold rule, the layout invariant,
the session version — so the comments increasingly point at tests rather than restate them.

## One record keyed three ways, and what each fix cost

Worth following end to end, because it is the clearest instance of the pattern below: three rounds,
each one finding a HIGH defect in what the previous round had just written.

**First it was the address name.** Chat-log messages were filed under `signalProtocolAddressName`
alone. The address name is public — it travels in every envelope the messenger relays — and the
device id beside it is one byte the sender chooses, so two contacts differing only in device id
shared one conversation. The messenger pins its own key at `(bobsName, otherDevice)`, is warned about
but permitted (the app cannot *refuse* a same-name contact; a genuine reinstall really does arrive at
a new address), and its messages then render inside the genuine contact's thread, under their name,
tag and badge. Deleting the impostor — where the warning steers the user — took the real history
with it. Found by asking a question the design had never been posed: *which trust records are keyed
by the name alone, and what does the attacker-chosen device id do to them?*

**Then it was the rendering of the full address.** Keying on `name + "." + deviceId` fixes that, and
two such keys can never collide — device ids are dot-free integers, so the last dot splits uniquely.
That is the collision the obvious analysis looks for, it was checked, and it was reported as safe. It
is also the wrong pair to compare. The live collision is between a key and a **bare address name**,
because a legacy arm still matches name-only keys so an upgrade does not silently empty everyone's
history. The messenger writes address names: it picks `bobsName.7`, an ordinary printable name at a
fresh unpinned address that collides with no contact and fires no duplicate warning, and every
message filed for the real Bob at device 7 matches it.

The fix is a separator no address name can contain. `BinaryEnvelope.requireDisplaySafeName` rejects
any sender name outside printable ASCII, on every envelope, in both directions — so `U+001F` puts
rendered keys and bare names in provably disjoint spaces. That property is a coupling to a validator
in another file, which is exactly the kind of thing that goes stale, so it is asserted where the
separator is defined.

**And the ambiguity gate was asked at the wrong moment.** The legacy arm only fires when the address
name identifies exactly one contact. `removeContact` pruned the contact list *before* deleting the
messages, so that question was put to a list the contact had already left — wrong in both directions.
Deleting an impostor made the shared name look unambiguous and deleted the **genuine** contact's
pre-upgrade history, which is the round-one defect exactly, still live for every message written
before the change. Deleting a contact alone under its name made it look ambiguous, so its own
plaintext survived the only action a user has for erasing it, with no row left to reach it from.

**Then the legacy arm itself went.** A review round was asked the design question rather than only
being sent hunting, and its answer settled it: the read-time arm is a *fixed point*. Its gate — "does
this address name identify exactly one contact?" — is a property of the contact list at the moment it
is asked, and the messenger moves the contact list. It adds a rival row so the history is correctly
withheld, replays a message until decryption fails, the user follows the app's own delete-and-re-invite
advice, and the name is unambiguous again with the attacker's row the one left to inherit the whole
conversation and to delete it. Asking before the prune instead only swaps which required behaviour
breaks: **a delete cannot tell an impostor from the contact it imitates, because that is what
ambiguous means.** The retired-name arm had the same opposition — a legacy entry records no device id,
so the legitimate re-add and the attack are literally the same string.

The same round found why the separator was not sufficient alone. `requireDisplaySafeName` guards the
wire, but contacts are also read back off disk, and 0.1.5 validated nothing — so a contact planted
before the upgrade could carry the separator in its address name.

So the question is asked **once**, at the first load after the upgrade, when the contact list is still
exactly what the pre-upgrade binary wrote. Unambiguous chat-log entries are re-keyed; ambiguous ones
are deleted rather than orphaned, because plaintext no row can reach cannot be erased by the user
either and can later be handed to whoever survives. Retired names are re-keyed from the contact row
or, failing that, from the surviving pin — deletion keeps it, which is the case the arm existed for —
and blanked when neither identifies the address, which leaves the warning on. Both read arms are gone,
and nothing anywhere compares a chat-log key to a bare address name. That last part is what makes the
stored-name gap unexploitable without retrofitting validation onto the deserialiser.

Three things are worth taking from this. The legacy arm was the source of all of it: every one of
these defects is a consequence of matching two key formats at read time rather than migrating once.
The analysis that cleared the dot separator was not sloppy — it was rigorous about the wrong pair of
objects, which no amount of care about the pair you chose will catch. And the round that ended it was
the one asked for an opinion rather than a bug list; three rounds of finding defects in each other's
fixes had not produced the observation that no fix existed at that layer.

## The seam nobody had looked at

Twelve rounds went into the crypto, the storage, the trust model and the strip's own state machine.
A round told to look *where previous rounds had not* found seven defects in two days, all in one
place: the join between the inherited AOSP keyboard and the E2EE strip. Its verdict on the rest was
that `signalprotocol/` and `latin/e2ee/` have converged — everything it probed there came back clean
or already covered — and that is worth as much as the findings.

**One root cause.** `RichInputConnection.shouldUseOtherIC` switches where an edit *goes*: true, and
the character lands in the strip's compose box; false, and it is committed into the host
application's field. Nothing switches the state the edit is *computed from*, which is still the
host's — and the host is the adversary.

- **Focus loss redirected typing into the messenger's own field, in cleartext.** Any app may call
  `InputMethodManager.showSoftInput`; it reaches `LatinIME.onShowInputRequested`, whose first
  statement clears focus on the compose box. The focus listener lowered the flag and every
  subsequent keystroke went to the host — with the box still on screen holding the draft, the only
  visible change being two small buttons disappearing. It also fired with no adversary: opening any
  other strip screen sets the main layout `GONE`, which clears focus. Focus loss is not the user
  asking to type into the host; a send and a dismissal are, and they lower it explicitly.
- **The messenger decided what Enter did** inside the compose box, because Enter is dispatched from
  the host's `EditorInfo`. `IME_ACTION_SEND` — what chat apps declare — made it a silent no-op;
  `IME_ACTION_NEXT` moved focus off the box, reaching the disclosure above by a route the app chooses.
- **A selection the host reported for its own field deleted the draft.** `mExpectedSelStart/End` are
  written only by `LatinIME.onUpdateSelection`. One backspace took the whole message, or an
  attacker-chosen prefix of one the user then encrypted and sent.
- **The plaintext survived pressing send**, in the IME's own caches, on an object living as long as
  the service, while the keyboard stayed up in the messenger's app. Then the same for the clear
  button, a recipient change, and the password-field guard — four paths, found one at a time by
  asking "what else ends a message's life?". They are now one operation, and the test is an
  invariant over every path rather than one test per button, because one-test-per-button is how it
  was missed three times.
- **A configuration change discarded the lot.** The strip is rebuilt on a theme change, on night
  mode at sunset, on rotation — and the redirect went on pointing at the *discarded* compose box, so
  typing after a rebuild landed in an object no clearing path could reach and survived dismissal. A
  standing identity-change warning went with it: a rebuild does not erase the banner, it throws away
  the object the banner is drawn on, which reaches every defect in the banner-erasure family without
  touching any path those fixes guard.

**And then the fix needed fixing, twice.** Carrying state across the rebuild has now been through
three review rounds, each finding defects in what the previous one wrote:

1. the rebuild discarded the standing warning, the draft, and left the redirect pointing at the
   discarded compose box;
2. against that fix — the password-field guard was not carried at all, an ordinary banner *was*
   carried and painted over a fresher warning, a warning the user had already resolved came back,
   and the chosen wire encoding reset to RAW;
3. and the worst of those was not the dropped guard but the carried banner: the strip went on saying
   "encryption is turned off here" over a password box while the actions were back on. Carrying the
   reassurance while dropping the thing it reassures about is worse than dropping both.

The recurring shape is one sentence: **security state was living on an object whose lifetime is
shorter than the threat it defends against.** `mWarningStanding`'s javadoc says nothing the messenger
can cause may clear it — and the messenger can cause a rebuild. The password guard is re-armed by
`onStartInputViewInternal`, which then triggers a synchronous rebuild eight lines later, so it was
armed on a view already condemned. Neither is a bug in a line of code; both are a bug in where the
field lives.

Enumerating fields to carry has now failed twice, which is the signal that enumeration is the wrong
instrument — the same signal the chat-log legacy arm gave before it was replaced wholesale rather
than patched again. What is *not* wrong is the direction: the round that checked confirmed with a
passing test that the recipient, the visible screen and the add-contact envelope are genuinely safe
to lose, because a carried draft cannot reach the wrong person — re-choosing anyone is a change from
null, and `setChosenContact` empties the box on any change.

**Round four found the frame itself was wrong: nothing is lost, it is orphaned.** The discarded
strip registers a process-wide clipboard listener in its constructor that nothing ever removed, and
`RichInputConnection.mOtherIC` — created once, for the life of the service — went on wrapping its
compose box whenever the redirect was down. So the view that "goes away" is retained for the life of
the process, still holding the entire decrypted conversation in its chat-log adapter, a safety number
in the verify screen, and the contact list; and still running `EnvelopeCodec.fromWire` on
messenger-chosen bytes every time the clipboard changes, once per rebuild. Every clearing path the
app has runs on the LIVE strip, so none of them can reach it. Round three asked "is the chat-log
screen safe to lose?" and answered yes. The true answer is that it is not lost.

`surrenderState` now performs the same last rites the dismissal path performs — clear the decrypted
content, reset the add-contact fields, blank the fingerprint digits — plus the two things that kept
the view alive: unregister the clipboard listener, and hand the connection a null `otherIC`. And the
carried warning is no longer written over one the rebuild itself raised: `refreshOpeningMessage` can
raise `INFO_STORAGE_UNREADABLE`, and overwriting it wiped "do NOT re-invite anyone" *and*, because
button state is derived from the banner text, flipped encrypt and decrypt back on for an install
whose account cannot be decrypted.

**Round five says converged, and the evidence is a probe rather than an absence.** It took a weak
reference to the strip, forced a rebuild, and asserted the strip is collected — it is, and reverting
round four's `releaseClipboardListener()` makes that probe fail. So the mechanism is genuinely
fixed, and the discarded strip is now garbage rather than a retained object holding a conversation.

That probe then disqualified two of the same round's own findings: it had a failing test showing the
contact list still on the discarded strip, and threw it out, because content on an object the
collector takes is true of everything the collector takes. Its own words for what continued review
of this area now produces: *restatements of a problem already solved.* Two findings survived — the
destroy path (`LatinIME.onDestroy` → `clear()`) had never been given the last rites `setInputView`
got, which is round four's defect on the one entry point round four did not look for; and the carried
encoding was applied after the indicator that renders from it, so the strip showed RAW while it would
encode FairyTale. Both fixed.

There is a cost side, and it is recorded because it is the argument for stopping: `adoptState` is now
six ordered steps whose *ordering* has itself become a defect source, `surrenderState` duplicates the
dismissal path, and `EveryStripFieldIsClassifiedTest` is 130 lines guarding ten fields with two
exclusion holes the same round named — `final` fields are skipped, so `mCodeAnimators` is listed but
never examined, and "views are rendering only" is falsified by `CarriedState.banner`, which *is* a
`TextView`'s text. **This thread is closed.** The next thing that would genuinely move it is the
structural work below, or a device.

**Named work, not yet done.** The reviewer's argument for why enumeration cannot converge is
convincing and is recorded here rather than acted on inside a review round:

1. *Six of the things a rebuild loses are not fields at all* — they live in an `Editable`, two
   `ListAdapter`s, twelve `TextView`s, a lambda's captured `MessageEnvelope`, two `isEnabled` flags
   derived from a `TextView`'s text, and six visibilities. Reading the field list cannot produce
   them, so "which fields are safe to lose" was never the whole question.
2. *Collect the non-view state into one `State` object* so the recurring question stops being "did
   you remember to add it to `CarriedState`?" — which has no mechanical answer — and becomes "is this
   state or rendering?", which does.
3. *Derive the warning instead of carrying it.* `hasUnacceptedIdentityChange`, `storageState()` and
   the current `EditorInfo` all outlive the view. Storing "a warning is on screen" as a boolean plus
   a rendered string is why ranking two warnings happens implicitly, by whoever writes the `TextView`
   last. `Warning { Kind kind; SignalProtocolAddress about; }` re-rendered on rebuild makes that
   collision impossible by construction.
4. *Move user-intent state to service scope* — a small session object beside `InputLogic`, which the
   strip renders and mutates but does not own. Roughly 25 mechanical call sites in one file. The real
   cost is that the strip fuses state and rendering today (`setChosenContact` clears the compose box;
   `setWarningMessage` writes a view *and* sets a flag), so every moved field needs its render side
   split out, and getting that split wrong is a fresh way to lose a warning.

Deliberately deferred rather than attempted mid-round, for the reason the history above demonstrates:
changing this code under pressure to close a finding is how each round introduced the next one.

**Clearing on rebuild is the wrong fix**, and the round that found it said so about its own control:
clearing lowers the redirect, so the next keystroke after a rotation goes into the messenger's field
in plaintext — a residue defect traded for a disclosure one. State is carried instead, which is also
what the user means mid-message.

**Why twelve rounds walked past it.** The comment at the top of `clearFocusEditTextView` records
someone reading that method, writing down that it "clears focus, not text", fixing the text-lifetime
bug they were hunting, and never asking what clearing focus *does*. Every round before this one was
pointed at a subsystem. This one was pointed at a join, and the difference is the whole finding.

## A predicate can be tested and wired to nothing

`AndroidKeystoreCryptoBox.needsApi28` was extracted, in its own words, "so the refusal can be
tested", and it was: four assertions pin it as a pure function, a fifth checks it against the
ladder. Every one of them passes when the branch that consults it is deleted.

That was measured, not inferred. Removing the refusal outright — so a pre-28 device asking for
StrongBox silently received a key with neither StrongBox nor unlocked-device-required — left the
suite at **843 tests, 0 failures**, and left `needsApi28` a production method called only from
tests. Nothing noticed that either.

The cause is worth stating carefully, because the fix for one problem created it. `generate()` calls
the real Keystore provider and cannot run off a device, so the `KeystoreOps` seam was introduced to
make the ladder testable — and every ladder test therefore drives a *fake* `KeystoreOps` and never
reaches the branch. **The seam that put the ladder in reach put this out of it.** A seam relocates
the untestable part; it does not shrink it, and the part it relocates to is the part nobody looks at
again.

The general form: *extracting a decision for testability tests the decision, not the wiring.* A
predicate answering correctly and being consulted are separate claims, and only the first is cheap
to assert. Where the two are split, something has to pin the join — here the branch was reshaped to
take a `KeyGenParameterSpec.Builder`, which needs no Keystore, so the real code runs on both sides
of it under Robolectric at a chosen SDK.

Both directions are load-bearing, and only one is obvious. Pre-28 must **refuse**, because the
ladder logs the protections it believes it obtained: a silent downgrade makes rung one claim
StrongBox for a software key, indistinguishable afterwards from the good outcome. But pre-28 must
also **accept** the plain rung, or an API 26–27 device could never mint a key at all. A branch that
refused everything below 28 would be exactly as broken, in the direction nobody tests for.


## The one structural lesson from the review rounds

Two findings in a row came from the same shape of mistake, and it is worth stating separately from
the defects themselves.

**A sweep is only as good as the pairs it generates.** Every pixel test in `RenderedNameAgreementTest`
compared candidates against one fixed baseline, so it produced pairs of the form
`(baseline, baseline+X)` and never `(baseline+X, baseline+Y)`. The property being claimed — that two
names a reader cannot tell apart fold together — is about pairs in general. 8520 BMP code points all
paint the same notdef box and folded to different keys; nothing in a 480-test suite could see it.

**A filter that hides a wrong answer looks exactly like a filter that excludes an irrelevant one.**
`canRender` kept the C1 controls out of view for as long as they were mishandled. `OVER_FOLD_EXCEPTIONS`
then did the same for U+FFF9–FFFB, with a stated reason ICU contradicts. Both were added in good
faith to suppress noise. The rule that came out of it: an exclusion gets a written reason and a
negative control proving something still fails without it, or it does not go in.

---

## What the double ratchet gives this app, measured

Ciphertext travels as text the user copies and pastes by hand, so out-of-order delivery is the
ordinary case here rather than an edge one — they paste what is in front of them, scroll back to
older messages, skip ones they cannot be bothered with, and occasionally paste the same thing twice.
`OutOfOrderDeliveryTest` pins what actually happens:

- Reversed, shuffled and newest-first delivery all decrypt completely.
- A message the user never pastes does not block the ones after it.
- Pasting the same message twice is refused, and the session is undamaged by the attempt.
- **The window is exactly 2000 skipped messages.** Skip 2000 and the oldest still opens; skip 2001
  and it is gone for good.
- Past the window the failure is `DuplicateMessageException`, **not** an "invalid message" error. An
  evicted key is indistinguishable from a used one, so libsignal reports the same thing for both.
  That reaches the user: the app's failure text says "Most often this means it was already decrypted
  once", which is wrong for a message more than 2000 behind. Wrong in a harmless direction — it is
  unrecoverable either way — but a user scrolling a long way back is told they have already read
  something they have not. Distinguishing the two needs a counter libsignal does not expose.

---

## Not verified on hardware, and most needing it

`FLAG_SECURE` is applied to the IME window while the strip shows decrypted plaintext, the chat log,
the verify screen or the contact list. Window flags on an IME window behave differently across
vendors, and nothing in this environment can run the keyboard — so *which* screens are protected is
tested and *whether the flag takes effect* is not. Of everything in this branch, this is the change
that most needs a device before it is trusted.

The same *was* true of one smaller change, and is now only half true. The safety-number digits count
up over a second, and nothing cancelled those animators, so switching contacts left the previous
contact's animation to finish painting its number into the views a moment later — under the new contact's name, on the one
screen whose whole purpose is comparing that number by voice. The animators are now cancelled when
the digits are blanked and when a new number is loaded. It has no test: under Robolectric an
un-cancelled animator delivers no further frames once the looper is idled past the view change, so
the late repaint never happens and a test of it passes either way.

What IS testable is the cancel itself, and asserting that instead found the fix incomplete: only
`surrenderState` blanked the digits, so dismissing the keyboard left the animators running — for a
second of painting into hidden views, and for as long as they ran, holding the whole strip alive
through the process-wide `AnimationHandler`. Every clearing path blanks them now. The device half —
whether the digits really stay blank on hardware — remains unverified, and that is a smaller claim
than the one this paragraph used to make.

**Which window a thing is drawn in decides whether the flag covers it**, and that question had not
been asked of anything except the strip. Two answers, one good and one open.

A keyboard draws a magnified preview of every key as it is pressed, so while the user types a
secret each character is rendered twice. That second rendering is inside the IME's own window —
`MainKeyboardView` adds its placer view to `getRootView().findViewById(android.R.id.content)` — and
nothing under `keyboard/` or `latin/e2ee/` constructs a `PopupWindow` or calls
`WindowManager.addView`. So it is covered. AOSP upstream has historically drawn more-keys panels in
a `PopupWindow`, which is a live direction for a future change to take and would silently move every
keystroke of a plaintext message outside the protection, so
`NothingRendersOutsideTheSecureWindowTest` pins both halves.

**Accessibility is a third channel, and it is not covered either — but it is not fixable.** A service
with `canRetrieveWindowContent` reads the compose box like any other text field, and `FLAG_SECURE`
does nothing about that. The two mitigations Android offers both break the app for the people they
exist to serve: a password `inputType` hides the message from the user typing it, and
`importantForAccessibility="no"` makes the compose box unusable to anyone who needs a screen reader.
Checked and worth knowing: this fork has **no** `accessibility/` package at all — AOSP's spoken key
feedback was stripped — so nothing announces individual keystrokes, and the exposure is passive
reading of a field the user must be able to read too. Recorded as a limit of the design rather than
a defect.

Also checked while there: no setting in `latin/settings/` touches the strip, the clipboard or the
E2EE path at all, so there is no configuration that weakens any of this.

**Toasts are the open one.** A toast is a separate system window and the flag does not reach it.
None of the **30** the strip raises carry message plaintext — but that sentence used to be the whole
protection, and it was wrong in its own count: it said 26, and there are 25 in `E2EEStripView` plus
5 in `E2EEStrip`, with two more commented out. What an asserted count is worth, asserted nowhere.

The property is now pinned by `NoToastCarriesMessageContentTest`, and writing it turned up the part
that mattered. Two sites toast `e.getMessage()` verbatim, and they are safe **only** because each
sits under `catch (TooManyCharsException e)` — all four throw sites of that exception build their
text from byte counts. Merging those two catches into the `catch (IOException e)` directly below
them is a plausible tidy-up, a one-word edit that does not touch the toast line, and it puts
arbitrary exception text into an unprotected window from a method whose local variable is the
user's plaintext. Nothing would have failed. That mutant is now a control.

What remains open is unchanged: several toasts carry a contact's display name, in most cases
duplicating text also written to the banner, which *is* covered. So the exposure is a contact's
identity to a screen recording taken while the strip itself is protected. Not fixed here, because
deciding which of these need to be toasts at all is a product question and several are the only
feedback a user gets for an action. Recorded so it is a decision rather than an oversight — and the
test permits display names deliberately, rather than by omission.

The wider point stands on its own: there was not one occurrence of `FLAG_SECURE` in the source. The
E2EE surface is a view inlined into the IME rather than an Activity, so it never inherited one, and
an app whose premise is that the messenger cannot read the message was painting the message into a
screen-recordable window. Whether it should be a user setting, as Signal makes it, is a product
decision nobody had made — because nobody had been in that file.

---

## A mutant was committed and pushed

Recorded because the mechanism matters more than the incident, and because it is the second time.

A mutation sweep against `ListAdapterContacts` was run in the foreground under a 10-minute tool cap
and was killed by it mid-run. The runner restores each file in a `finally`, and a killed process runs
no `finally`. The mutant left behind was `M-E` — the verified and unverified badges swapped on the
trusted arm — so **a contact the user had compared rendered as unverified**, and the next commit,
made with `git add -A`, committed and pushed it.

Nothing caught it, which is the point: the whole 716-test suite passed with the badge inverted. It
was found only because the test being written to close that exact gap started failing for a reason
its author did not expect, and the several rounds of "the fixture must be wrong" that followed are
the honest cost of a mutant sitting in a tracked file.

Three things follow, in order of how much they matter:

1. **The gap was real and is now closed.** `VerifiedBadgeRenderTest` renders rows through the real
   adapter and asserts both directions, plus that a substitution and a standing rejection each take
   the tick off the row. The shipped mutant is one of its negative controls.
2. **`git add -A` is not safe after a sweep.** Read `git diff` against the last known-good commit for
   every production file before committing, not just the ones you meant to touch.
3. **A git-based restore in the runner is not the fix.** It was tried immediately and made things
   worse: with a legitimate uncommitted fix in the tree, `git checkout --` reverted *that*. The
   working restore is to keep sweeps well inside the time cap and to diff before committing.

## Checked this round and clean

Recorded so the next round does not spend itself re-deriving them.

- **The manifest.** `allowBackup="false"`, and `data_extraction_rules` excludes everything from both
  cloud backup and device-to-device transfer — which matters because `allowBackup` alone does not
  stop transfer on Android 12+, and a transferred store is one the new device can never decrypt. The
  IME service is `exported="false"` behind `BIND_INPUT_METHOD`; the broadcast receiver is not
  exported; the only exported component is the launcher activity. Nothing to fix.
- **The inherited keyboard's debug switches**, all nineteen of them. Several are keyloggers in this
  app specifically: `KeyboardState.DEBUG_EVENT` logs every key pressed and released,
  `PointerTracker.DEBUG_LISTENER` resolves `CODE_OUTPUT_TEXT` to the literal output string, and
  `RichInputConnection.DEBUG_PREVIOUS_TEXT` dumps the text around the cursor — which here is the
  plaintext of an encrypted message. All are `static final false`, so javac folds the branches away
  and the `Log` calls are not in the APK. That was already true; what was missing was anything
  stopping one character from changing it, in files nobody in this revival had had reason to open.
  `DebugLoggingStaysOffTest` now asserts it over the whole source tree rather than a hand-kept list.
- **The suite does not depend on test-order state leakage**, checked rather than assumed. This
  project shares one singleton across every test, with static seams on it (`testIsRunning`,
  `storageStateForTest`, `storageHelperFactory`), and only 24 of ~112 classes call `resetForTest`.
  All four classes that force a storage state leave it set. So a test passing because a previous
  class left something behind was a live possibility, and one that passes for a borrowed reason is
  indistinguishable from one that passes on its merits. Ran the whole suite with `forkEvery = 1`,
  every class in a fresh JVM: **796 tests, zero failures, identical to the shared-JVM run**. The
  flag is kept as `-Dkryptey.isolateClasses=true` so the check is repeatable rather than a one-off
  someone has to reconstruct; it costs about seven minutes against under two, because JVM start
  dominates.

  That check rules out leakage *between* classes and says nothing about order *within* one, which is
  a distinction worth keeping rather than letting "the suite is order-independent" stand for both.
  So the second half was measured too: every class temporarily annotated
  `@FixMethodOrder(NAME_ASCENDING)` — a deterministic order, and a different one from JUnit's
  hash-based default — 114 classes, **796 tests, zero failures again**. Not proof over all orders;
  evidence over a second one, which is what was actually available.

- **No other code-execution sink is shipping.** Deleting `Base64`'s dead deserialisation raised the
  obvious follow-up — what else is dead, dangerous and shipping, given `minifyEnabled` is false?
  Scanned for dynamic class loading (`DexClassLoader`, `PathClassLoader`), process execution
  (`Runtime.getRuntime`, `ProcessBuilder`), reflection (`Class.forName`, `getDeclaredMethod`,
  `setAccessible`) and `WebView`/`addJavascriptInterface` across all of `app/src/main`. **Not one
  occurrence of any of them.** `NoWeakCryptoTest` now guards the first two categories; reflection and
  `Class.forName` are deliberately left out, because those have honest uses and a guard that cries
  wolf gets deleted rather than heeded.

- **The messenger picks the compose box's shift state.** `InputLogic.getCurrentAutoCapsState` reads
  `getCurrentInputEditorInfo().inputType` — the HOST's — to decide auto-capitalisation, and goes on
  doing so while the user is typing into the strip. So an app that declares
  `TYPE_TEXT_FLAG_CAP_CHARACTERS` on its own field gets the user's message typed in capitals, and one
  that declares nothing suppresses sentence caps. Cosmetic, and left alone deliberately: the fix
  means giving the compose box its own `EditorInfo`, which is a larger change than the defect
  warrants, and nothing about the ciphertext or the trust model depends on letter case. Recorded
  because it is the same root cause as three HIGH findings — code reading host-owned state while the
  destination has been switched — and the next person to meet it should know it was seen and judged,
  not missed.

- **Eight inherited compatibility guards that can no longer branch.** `minSdk` is 26, so three
  `SDK_INT <= KITKAT` tests are always false, four `SDK_INT >= N` tests are always true, and one
  `SDK_INT < LOLLIPOP` test is always false — in `EditorInfoCompatUtils`, `RichInputMethodManager`,
  `PreferencesSettingsFragment`, `ViewOutlineProviderCompatUtils`, `PreferenceManagerCompat`,
  `LocaleUtils`, `TwoStatePreferenceHelper` and `SubScreenFragment`. All inherited AOSP, none
  security-relevant, none worth the churn of removing. Recorded because a reader meeting one of
  these reasons about a branch that cannot execute — and because whoever eventually raises `minSdk`
  needs to know these are where the dead code accumulates. One of them, `PreferenceManagerCompat`'s
  `>= N`, is the reason preferences land in device-protected storage: always taken, not sometimes.
  No test: a test asserting a fixed list of dead branches records the status quo and fires only when
  someone adds a ninth, which is not worth a file.

- **The numbers in this document**, audited against the tree: 386 pinned components, `KeyResolutionTest`
  at 10 tests, 11 instrumentation `@Test` methods, the 4096-character invite threshold, and the
  strip's six screens all check out. The test count did not, and is now dated rather than absolute.
  The 2484-byte bundle figure has now been re-measured too, and pinned: `PreKeyBundleSizeTest`
  builds ten bundles, asserts they are all the same size, asserts that size is 2484, and asserts the
  margin under the 4096 threshold is 1612. It is the number the threshold was chosen against, so
  silent growth eats the margin and the first symptom would be that nobody can send an invite.

## The help offered a choice the app does not

Sending an invite is the one moment trust-on-first-use has nothing behind it: there is no earlier
key, so a messenger that substitutes this bundle is caught by nothing. The help says so, and then
told the user what to do about it — hand it over "in person, in an email, in a note", which "keeps
the messenger from ever seeing that first key".

It cannot. The invite button generates the bundle and immediately commits it into the messenger's own
compose field, and it does so **deliberately**: `sendEncryptedMessageToApplication` lowers the typing
redirect first, precisely so the text lands in the messenger rather than in the strip. Nothing in the
app hands the user their invite any other way — the strip's only `setPrimaryClip` writes an *empty*
clip, because its job is clearing. By the time the user could route it elsewhere, the app they were
told to route it around is holding it in an EditText it owns.

The sentence conflated two adversaries the rest of this codebase separates carefully: the messenger's
**servers**, which see the invite only if the user presses send, and the messenger **app**, which
already has it. `FLAG_SECURE`, the typing redirect, clearing the IME caches on send, clearing the
clipboard — every one of those exists because the app is the adversary. Advice protecting only
against the servers, in the section explaining the one uncovered moment in the trust model, tells a
careful reader they have closed a hole they have not.

This one was written during this revival, in `ab07778`, as the fix for the *previous* round of this
same class. That is the thing worth recording: the correction introduced a new instance of what it
was correcting, because it was reasoned from the threat model rather than checked against the flow.
`InviteAdviceMatchesTheFlowTest` asserts the behaviour first — the commit path, and the absence of
any copy affordance — and only then judges the wording, so the words are measured against the code.

**The real fix is deferred and is a product change:** give the user a way to obtain their invite
without committing it to the host app — a copy action on the strip, or a confirmation step before
the text is typed out. Until that exists the honest wording is what shipped: the invite is in that
app's text box before you decide anything, and what you still control is whether it travels onward.
If the affordance is added, `theinviteGoesStraightIntoTheMessengerAndNothingOffersItAnyOtherWay`
fails on purpose, and the wording should be revisited rather than the scan loosened.

## Known-deferred defects

**Deleting a contact does not erase an un-attributed legacy log entry.** The help says "if you delete
the contact, the message history will be deleted too". For every message this version writes that is
true. For one class of pre-upgrade entry it is not.

The chat log used to be keyed by a bare address name. The load-time migration re-keys each entry onto
the owning contact's full rendered address, and when the name identifies no single contact — two rows
sharing an address name, which a reinstall produces without any attacker — it cannot attribute the
entry and keeps it. Keeping is right: deleting was tried and rejected because no reader matches a
bare name any more, so the entry is invisible to every row including an impostor's, and erasing it
turned a safety measure into a destruction primitive where one ordinary pre-upgrade invite was enough
to destroy a genuine conversation with no prompt and no way back.

The consequence nobody had written down is that `removeAllUnencryptedMessages` matches `belongsTo`,
which compares the full rendered address — so it cannot reach such an entry either. The user deletes
every contact that could relate to the conversation and the plaintext stays in the store, unreachable
by any screen and not erased by the only action they have.

`InertLogEntrySurvivesDeletionTest` pins all three halves, because the trade is only defensible if
they all hold: the migration keeps the entry, no contact can see it, and deleting every contact
leaves it. Both rejected designs are the negative controls — making the migration delete what it
cannot attribute kills two of the tests, restoring the bare-name read arm kills two.

**The fix is a "clear all message history" action, not a smarter per-contact delete:** a per-contact
delete cannot attribute what the migration could not, which is the whole reason the entry is inert.
Deferred because it is a product addition rather than a correction. The help is left unqualified
deliberately — the history the user can *see* is genuinely deleted, and warning every user about an
invisible pre-upgrade edge case is the kind of clutter this document has already recorded as getting
dropped by the next editor.

*Found by auditing the help's behavioural claims against the code rather than by reading the code.
Three of those claims had already been verified this way; this was the fourth, and the first to
fail.*


- **Store rollback.** Restoring an old `protocol.xml` presents envelopes that verify perfectly,
  rewinding the ratchet. Needs a monotonic counter the attacker cannot rewind; not solvable at the
  storage layer. **Now measured** (`StoreRollbackTest`), because the entry beside it turned out to be
  wrong in two ways once anyone ran it. A rollback: takes away a verification the user performed —
  the badge returns to unverified, which is the safe direction; **does not clear the pinned
  identity**, so it cannot turn file access into a silent key substitution by making the next bundle
  a clean first sighting; and rewinds the chat log, so the visible cost is losing history rather than
  history being altered. The earlier wording said it "resets `trustedKeys`", which reads as the
  dangerous direction and is not what happens — the pin is in the same snapshot and comes back with
  it. Note also the capability required: write access to the app's private storage, strictly larger
  than anything the messenger has in this threat model. That is why it stays deferred, and the tests
  are why that judgement can now be checked instead of trusted.
- **Bundle replay.** No freshness check, so replaying a captured envelope forces a session rebuild.
  Halved (the bundle was being processed twice per message) but not eliminated. **Now measured
  rather than described** (`BundleReplayTest`): a replay is accepted and replaces the session, but
  it does **not** move the pin and does **not** disturb the chat log, so it costs availability and
  the forward secrecy of the current chain — not confidentiality. A held-back message survives
  because libsignal archives the session it displaces; it takes **41** replays to exhaust the
  archive and lose that message, and the number is pinned so a change in either direction fails a
  test. Two wrong versions of that measurement are recorded in the test: a pre-key message carries
  its own session and survives any number of replays, and a loop that stops at the first success
  measures nothing.
- **No RESET path when the Keystore key is gone.** This entry used to begin "no user-visible signal",
  and that half is stale — it was fixed and the entry never followed. `INFO_STORAGE_UNREADABLE` is
  raised by `refreshOpeningMessage`, `OpeningMessageTest` asserts that an unreadable store does not
  look like an empty app and that the wording tells the user not to re-invite, and
  `StorageHelperTest` covers the state detection that feeds it. The banner also disables both action
  buttons, which is asserted separately.

  What remains true is narrower and worth keeping: `destroyMasterKey()` still has no production
  caller — only the instrumentation tests, which cannot run here — so a user whose Keystore key is
  gone is correctly *told*, and has no way to act on it except clearing app data. Telling someone
  their identity is unrecoverable without offering the one action that recovers the app is a product
  gap rather than a security one, and it is the last thing on this list that a user would meet.
- ~~**Non-atomic account write** — 7 independent `commit()`s per save, on the IME main thread.~~
  Resolved. `KeyValueStore` gained a `putAll`; `SharedPreferencesKeyValueStore` overrides it with one
  editor and one commit, and `EncryptedKeyValueStore.putAll` seals the whole batch before handing
  the delegate anything. A save is now one durable write instead of eight, and a failure to seal
  any value writes none of them. The torn state it prevented was the dangerous kind: every value
  individually well-formed, so reload could not detect that a new protocol store sat beside an old
  contact list.
- ~~**At 320dp with fontScale 2.0 the contact row shows no name at all.**~~ Resolved. The tag moved
  onto its own line, so the names get the full column instead of whatever the tag left them.
  Measured, "Alice"/"Smith" now renders in full at every cell of the
  {320,360,411,480}dp × {1.0,1.15,1.3,1.5,2.0} grid, where at 320dp/2.0 it previously drew nothing
  at all — no characters, no ellipsis. Rows are taller: 55px instead of 40 at default scale, 97px at
  fontScale 2.0. That is the trade, and it is worth it — fontScale 2.0 is set by the people who most
  need to read the names.
- **A long CJK first name squeezes the last name at 320dp.** Ten CJK characters want about twice
  the advance of ten Latin ones, so the two weighted name views run the column short. **Measured**
  (`ContactRowLayoutTest#thecjkStarvationLimitIsWhereThisSaysItIs`): the last name gets **39dp**,
  roughly three or four Latin characters — an ellipsised "Jon…" rather than the nothing this entry
  used to claim. It said the column ran out "before the last name gets a single character", and that
  was never checked. The measurement is pinned from both sides, so a fix retires the entry on
  evidence and a degradation to real starvation fails too. A `minEms` floor fixes it and breaks the empty-last-name case,
  because the floor is reserved whether or not the view holds anything — trading one defect for
  another. The real fix is probably to render the two fields as one ellipsised string, which is what
  most contact lists do, but that changes the adapter and the sanitiser together and is not
  something to do with reviews in flight. `everyNameShowsAtLeastOneCharacterEverywhere` covers
  ordinary names in four scripts and deliberately excludes this case rather than asserting it away.
- ~~`E2EEStripView` enables the *encrypt* button on detecting an encrypted message.~~ Resolved: it
  was a copy-paste slip, and inert — `setInfoTextViewMessage` fires a `TextWatcher` that enables
  both buttons for any info text other than `INFO_NO_CONTACT_CHOSEN`, so all three sibling
  `changeImageButtonState` calls are dead. Corrected rather than deleted, so the branch reads as
  what it means.
