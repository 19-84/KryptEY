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

**Release APK: 115 MB → 74 MB** (in this build environment — see below; the saving itself is
environment-independent)**.** The libsignal jar carries its *desktop* builds — macOS `.dylib`
and Windows `.dll`, each in a normal and a `_testing_` flavour — at the jar root. Those are java
resources rather than `jniLibs`, so the existing `jniLibs` exclusion (which correctly drops the
Android `libsignal_jni_testing.so`) never saw them, and all six were packaged into every release
APK: **42.6 MB of the artifact, none of it loadable on Android**, two of the six being testing
builds of the crypto library. They stay on the *test* classpath, where Robolectric genuinely needs
them, so the fix belongs in packaging and not in the dependency. Found by opening the APK rather
than by reading the build script, while checking a claim this file makes about release assembling.

**Tests: 31 → 843 as of `bfb71c1`** (**4 skipped**, not the 1 this line
claimed for weeks: `FixtureGenerator` is a tool rather than coverage and gives up via
`Assume.assumeTrue`, and three assertions in `StripCarriedStateRound5Test` are `@Ignore`d as
deliberate rejections — one vacuous, two superseded by `r0`. All four are now listed in
`IgnoredTestsAreAccountedForTest`, so a fifth fails the build until somebody writes down what it is), all passing, and green from an empty cache rather than only warm. Debug
and release both assemble; dependency verification pins 386 artifacts by SHA-256.

---

## How to read this

**Commit hashes quoted in this document no longer resolve.** Every `verified at <hash>` reference
here and in `tools/README.md` points into history that was rewritten: the branch's 237 commits were
re-authored to strip a personal email address that should never have been published, which changed
every SHA after the base. The content, messages and order are byte-identical — `git log` reads the
same — but the identifiers do not. There is no recovered old-to-new mapping, so rather than invent
one, the hashes are left as markers of *when* something was checked relative to the rest of the
document, and anything that needs re-verifying should be re-verified rather than looked up. It is a
self-inflicted defect and it is recorded here because a reader chasing one of those hashes would
otherwise conclude the claim was fabricated.

Thirty-six sections, written in the order things were found rather than by subject, so the
sweeps are scattered and the deferred list sits between two of them. Grouped here rather than
reordered, because moving this much prose to tidy it is how paragraphs get lost.

**What changed**

- [What was done, by phase](#what-was-done-by-phase)

**What is verified, and how**

- [Verified vs. reasoned](#verified-vs-reasoned)
- [Verifying a build honestly](#verifying-a-build-honestly)
- [The release APK built here is not the one users would get](#the-release-apk-built-here-is-not-the-one-users-would-get)
- [Phase 1's crypto box, swept and clean](#phase-1s-crypto-box-swept-and-clean)
- [Phase 1's encrypted store, swept and clean](#phase-1s-encrypted-store-swept-and-clean)
- [Phase 3's parser, swept and clean](#phase-3s-parser-swept-and-clean)
- [Phase 3's decoder, swept and clean](#phase-3s-decoder-swept-and-clean)
- [Phase 4's trust predicates, swept and clean](#phase-4s-trust-predicates-swept-and-clean)
- [The typing-redirect seam, swept and clean — and two ways a sweep lies](#the-typing-redirect-seam-swept-and-clean--and-two-ways-a-sweep-lies)
- [Where the sweep programme ends](#where-the-sweep-programme-ends)
- [The instrumentation tests run now, on an emulator with no hardware acceleration](#the-instrumentation-tests-run-now-on-an-emulator-with-no-hardware-acceleration)
- [What the double ratchet gives this app, measured](#what-the-double-ratchet-gives-this-app-measured)

**Decisions, and what is still open**

- [Settled](#settled)
- [Open](#open)
- [Settled during review](#settled-during-review)
- [Known-deferred defects](#known-deferred-defects)
- [Not verified on hardware, and most needing it](#not-verified-on-hardware-and-most-needing-it)

**Defect classes this review found**

- [A predicate can be tested and wired to nothing](#a-predicate-can-be-tested-and-wired-to-nothing)
- [Two siblings, and the call site picked the weaker one](#two-siblings-and-the-call-site-picked-the-weaker-one)
- [A guard that was false on exactly the path that needed it](#a-guard-that-was-false-on-exactly-the-path-that-needed-it)
- [A record kept, and never shown on the route that matters](#a-record-kept-and-never-shown-on-the-route-that-matters)
- [A decision read from rendered text](#a-decision-read-from-rendered-text)
- [What the adversary gets to declare](#what-the-adversary-gets-to-declare)
- [State that grows without bound](#state-that-grows-without-bound)
- [An unchecked throw out of a click listener kills the keyboard](#an-unchecked-throw-out-of-a-click-listener-kills-the-keyboard)
- [The text is a security surface, and it had never been read as one](#the-text-is-a-security-surface-and-it-had-never-been-read-as-one)
- [The help offered a choice the app does not](#the-help-offered-a-choice-the-app-does-not)
- [One record keyed three ways, and what each fix cost](#one-record-keyed-three-ways-and-what-each-fix-cost)
- [The seam nobody had looked at](#the-seam-nobody-had-looked-at)
- [The one structural lesson from the review rounds](#the-one-structural-lesson-from-the-review-rounds)

**How this document, and its tests, have been wrong**

- [The comment-drift problem, and why it has no test](#the-comment-drift-problem-and-why-it-has-no-test)
- [The way the harness lied six times, and how it stopped](#the-way-the-harness-lied-six-times-and-how-it-stopped)
- [A mutant was committed and pushed](#a-mutant-was-committed-and-pushed)
- [Checked this round and clean](#checked-this-round-and-clean)

---
## What was done, by phase

### Phase 0 — the build

`jcenter()` was the only non-Google repository and has been decommissioned, so the project could not
resolve `libsignal` at all. Replaced with `mavenCentral()` under `dependencyResolutionManagement`.

- AGP 7.3.1 → **9.3.1**, Gradle 7.4 → **9.7.1**, Java 11 → **17**, compileSdk 33 → **35**
- Jackson 2.14.1 → **2.22.2** via BOM; `protobuf-javalite` **deleted** (a HIGH CVE carried for one
  call that nothing invoked)
- `gradle/verification-metadata.xml` — 386 components pinned by SHA-256, enforced in CI
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
  fails immediately. The 11 instrumentation
  tests are no longer only compiled — they run, and pass, on an emulator (see below). **"And compile" is now
  enforced**: nothing used to build `androidTest` during an ordinary run, so the only coverage of the
  real Keystore could have been broken by a rename and stayed broken silently for months — it is the
  code most exposed to that, because an emulator run costs ten minutes and nobody does one on every
  edit. `testDebugUnitTest` now depends on compiling it, which costs a few seconds and turns silent
  rot into a build failure. It does not make them run in that task — `tools/test-on-emulator` does
  that — and nothing here should be read as claiming otherwise.

  **The instrumentation tests were read by hand before anything could run them, which was then
  the only review available.** All eleven of the keystore tests carry real assertions — none is a compiling shell — and they
  are order-independent on the resource that matters: every one operates on the same Keystore alias
  and several destroy or regenerate the master key mid-test, but `@Before` and `@After` both reset
  it, so each starts from a known state whatever order the runner picks. That is the same question
  the JVM suite was checked for, asked about a resource global to the *device* rather than the
  process.

  `InstrumentationTestsCleanUpTheKeystoreTest` keeps both properties, because they are the two this
  environment can never catch by running anything: a test added without the reset would compile,
  leave this suite green, and surface as a mysterious failure for whoever first runs them on
  hardware — the person least able to tell a harness bug from a real one. Declaring
  `src/androidTest/java` a task input was necessary first: `dependsOn` compiles those sources but
  does not track them, and the control reported a removed `@After` as "survived" until it did. Sixth
  instance of that trap, and the first one predicted before the result was believed.

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

- ~~**APK size accepted** at 115.1 MB (arm64) / 109.2 MB (armeabi-v7a)~~ — **withdrawn: the size was
  accepted on a false premise, and about 90% of it was avoidable.** The entry said "libsignal 0.86 is
  ~74 MB of native code per ABI; that is the cost of PQXDH". None of the three claims survives
  measurement:

  - **42.6 MB was not libsignal's Android library at all.** The jar carries libsignal's *desktop*
    builds — macOS `.dylib` and Windows `.dll`, each in a normal and a `_testing_` flavour — at its
    root, as java resources rather than `jniLibs`, so the existing `jniLibs` exclusion never saw
    them. Excluded in `521eb74`: arm64 115 → 74 MB, armeabi-v7a 109 → 68 MB, on every build host.
  - **Of the 74 MB that remains, 64.2 MB is DWARF debug information**, not code. `.debug_str` alone
    is 43.8 MB against a `.text` of **4.7 MB**. AGP's `stripReleaseDebugSymbols` runs and prints
    *"Unable to strip … packaging them as they are"* because the build image has no NDK; a host with
    one strips automatically and lands near 10 MB.
  - **So "the cost of PQXDH" is roughly 10 MB, not 115.** The decision to accept 115 was reasonable
    given the premise and the premise was never checked.

  What makes this worth writing at length is not the megabytes. A *settled* entry is one nobody
  looks at again, and this one closed off the question with a plausible technical reason — the
  binary really is large, PQXDH really does cost native code — so every later reader had an
  explanation to accept rather than a number to test. It was found only by opening the APK while
  verifying an unrelated claim. **A settled decision inherits the accuracy of the measurement it
  was settled on, and nothing re-checks it.**
- **Invite threshold accepted** at 4096 characters, up from 500. A PQXDH bundle is 2484 and
  irreducible (Kyber-1024 is 87% of it, and key material does not compress). Clears Telegram and
  above; does not fit SMS, but neither did 500. *Re-checked while withdrawing the entry above:*
  `CHAR_THRESHOLD_PRE_KEY_RESPONSE` is 4096, the bundle is a fixed 2484, the margin is 1612, and
  `PreKeyBundleSizeTest` pins all three. This one is exactly as recorded.

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
**The instrumentation tests.** They run. This entry used to say the opposite — "not possible in
   this environment, and that is now settled rather than pending" — and that conclusion was wrong,
   which is worth leaving visible rather than quietly replacing. The premise was right: there is no
   `/dev/kvm` and the host CPU exposes no virtualisation extensions. The inference from it was not.
   QEMU does not need them; without KVM it falls back to TCG and emulates the guest in software,
   which is slow rather than impossible. A boot takes about ten minutes and the tests then take nine
   seconds. See [the instrumentation tests run
   now](#the-instrumentation-tests-run-now-on-an-emulator-with-no-hardware-acceleration).

   The 11 `AndroidKeystoreCryptoBox` tests have now executed, on Android 28 x86_64, and all 11
   pass.

   Two pieces have since been pulled onto the JVM. `CallerNonceProhibitedTest` covers
   `randomizedEncryptionRequired` — the one Keystore behaviour a desktop JVM does not share, which
   makes a Keystore key reject a caller-supplied IV. And the key ladder's *ordering* — ask for the
   strongest protections first, degrade only when refused — was extracted from the nested loops it
   was welded into, so `KeyCandidateLadderTest` can check it without hardware. Getting that order
   backwards would silently mint a weaker key on a device that could have done better, and the log
   line says what was created, not what was possible.

   What needed a device: that a key created with those flags actually *has* them, which is a
   `KeyInfo` check no JVM shim can stand in for. That check now runs. `CallerNonceProhibitedTest`
   remains useful on the JVM regardless — it registers a provider that imposes the
   caller-nonce rule and runs the real seal/open path against it, so the bug that already shipped
   once cannot recur silently even without an emulator to hand.

## The instrumentation tests run now, on an emulator with no hardware acceleration

For most of this branch's life the 11 `AndroidKeystoreCryptoBoxTest` methods were the one piece of
the project nothing could execute. They were compiled (a build failure now guards against silent
rot) and they were read by hand, which is the weakest form of review there is. They have now been
run: **17 tests, 0 failures, on Android 28 x86_64** — the original 11 keystore tests, plus three
that settle the `exported="false"` question below and three that settle the autofill one.

The reason they could not run before was a wrong inference, not a missing capability. This machine
has no `/dev/kvm`, and `/proc/cpuinfo` advertises no `vmx` or `svm` — nested virtualisation is not
exposed. From that it was concluded that no emulator could start. But KVM is an *accelerator*, and
QEMU without one falls back to TCG, which emulates the guest instruction by instruction in software.
`-no-accel` asks for exactly that. It is slow — a cold boot is about ten minutes against seconds
with KVM — and it is not impossible, which is what the entry in "Settled during review" claimed.

The first attempt did in fact fail, and it failed in the way most likely to confirm the wrong
conclusion. The emulator process died at once with

```
emulator: error while loading shared libraries: libX11.so.6
```

and the harness around it went on polling `sys.boot_completed` for ten minutes against a process
that had never existed, then reported no boot. A dead emulator and a slow one are indistinguishable
from outside unless something checks — which is why `tools/test-on-emulator` now checks for the
`qemu-system` process on every poll and prints the log the moment it is gone, rather than letting a
crash spend the full timeout impersonating slowness. The launcher and the QEMU binary beneath it
link against X11 and a dozen other libraries even under `-no-window`; `tools/emulator/Dockerfile`
installs them.

**x86_64 had to be built on purpose.** The shipped APKs are `arm64-v8a` and `armeabi-v7a` only,
because that is what phones are, and libsignal's native code is ~74 MB per ABI so a universal APK is
not an option. An x86_64 emulator cannot install either of them. `-PemulatorAbi` adds `x86_64` to
the splits for a local run; no release path sets it, so what ships is unchanged.

**What running them actually bought.** These are the Keystore tests, and the Keystore is the one
component a JVM cannot stand in for — there is no TEE behind a desktop provider. Two pieces had been
pulled onto the JVM to get *some* coverage (`CallerNonceProhibitedTest` for the caller-nonce rule,
`KeyCandidateLadderTest` for the ladder's ordering), and both remain worth having. But the property
they could never reach is whether a key created with a set of flags actually comes back *carrying*
them, which is a `KeyInfo` question about real hardware. That now has an answer instead of an
argument.

**Two facts that had been open questions, read off the running device rather than the manifest.**
`dumpsys package` reports the IME service as

```
com.amnesica.kryptey/.inputmethod.latin.LatinIME filter permission android.permission.BIND_INPUT_METHOD
```

I wrote here that an input method *must* be exported, that `exported="false"` would simply break
the app, and that asking about it was the wrong question. **That was wrong, and it was wrong in the
worst available way: it contradicted the manifest sitting two directories away**, which declares the
service `android:exported="false"`. It was reasoning presented in the same voice as the measurements
around it, which is the failure this document is supposed to make hard.

What is actually true, now measured rather than argued: the manifest says `exported="false"`, and
the platform binds the service anyway. `ImeBindsDespiteExportedFalseTest` selects the keyboard, puts
a real editable field in front of it, and waits for the service to *start* — not for a setting to
say it is selected, which is all `dumpsys` reports and all that "selected" ever meant. It starts.
`exported` is not the gate for an input method; the `BIND_INPUT_METHOD` signature permission is, and
the input-method framework binds through it. The requested-permission list on the installed package is exactly
`android.permission.VIBRATE`: **no `INTERNET`**, confirmed by the package manager rather than
inferred from merged XML. That is the app's whole defence against exfiltration, and it is the kind
of property a mutation sweep structurally cannot test, because it is the absence of something.

**How the runner knows it passed.** `am instrument` exits 0 whether the tests passed or not — that
was verified, not assumed, by running a deliberately failing instrumentation and watching the shell
report success. The result has to be read out of the stream, so the script greps for `OK (`. Both
directions were checked against real output: the passing run matches, the failing run (`FAILURES!!!
Tests run: 1, Failures: 1`) does not.

**The emulator image is load-bearing, which took a wrong answer to discover.** The first runs of the
binding test failed, reporting that the service never started — which is exactly what a real
`exported="false"` problem would look like, and I nearly wrote it up as one. It was the harness.
On the `google_apis` image, SystemUI cannot keep up with an emulated CPU and goes not-responding
within minutes of boot; the ANR dialog takes window focus, so the test's field never gets focus,
never opens an input connection, and no keyboard is ever bound. `dumpsys window` is what showed it:
`mFocusedApp` was the test activity while `mCurrentFocus` was `Application Not Responding:
com.android.systemui`. Raising the core count was tried and made it worse — the guest's system
server took a native crash inside four minutes and every app died with `DeadSystemException`. The
AOSP (`default`) image carries none of that weight, boots in a third of the time, and does not ANR.
That is why `tools/emulator/Dockerfile` pins it, and why the runner does not pass `-cores`.

The general lesson is the one this branch keeps relearning: a negative result from a harness nobody
has validated is not a finding. The first two times the test "failed", the correct next step was to
ask what the device was actually doing, not to write down what the failure appeared to mean.

**What this still is not.** An emulator is not a phone. There is no StrongBox on it, so the top rung
of the key ladder is exercised only in the sense that it is correctly refused and stepped down from;
what a StrongBox device does still has no test here. Eleven of the suite's seventeen tests are about
the crypto box; three bind the keyboard and three watch what autofill is handed, but none of them
types anything. Nothing here drives the IME through a real messenger, and nothing drives the strip's
own UI. What changed is that the category "cannot be run in this environment" is smaller than
it was, and it was worth an hour to find out that it was never as large as the document said.

## The release APK built here is not the one users would get

`assembleRelease` succeeds, and that claim is now checked rather than asserted. But the artifact it
produces in this container is **not** the artifact a proper build produces, and anything measured
from it has to say so.

`lib/arm64-v8a/libsignal_jni.so` is 74 MB, of which **64.2 MB is DWARF debug information** —
`.debug_str` alone is 43.8 MB against a `.text` of 4.7 MB. AGP knows: `stripReleaseDebugSymbols` runs
and prints *"Unable to strip the following libraries, packaging them as they are: libsignal_jni.so"*.
It is a **warning**, not a failure, so an ordinary build says BUILD SUCCESSFUL and ships it.

The cause is the build image, which has the SDK but no NDK — stripping needs the NDK's `llvm-strip`,
and the container's own `strip` is x86_64 binutils that cannot parse an aarch64 ELF at all
(*"Unable to recognise the format of the input file"*). A machine with the NDK strips automatically
and produces an APK around 10 MB rather than 74 MB.

Two consequences worth separating, because they are not the same kind of thing:

- **The desktop-binary exclusion is real and universal.** It is a packaging configuration change:
  42.6 MB comes off the APK on every build machine, with or without an NDK.
- **The 64 MB of debug info is an artefact of this environment.** Users installing from F-Droid
  almost certainly get a stripped library. What is genuinely wrong here is that *the same source
  produces materially different artifacts depending on the build host*, and nothing fails when it
  does — which is a reproducibility problem for a project whose README points at F-Droid, and it
  means no size or content claim about "the release APK" is meaningful without naming the host.

**Fixed at the source.** `tools/Dockerfile` now installs `ndk;28.2.13676358`, which is what AGP needs
for `llvm-strip`. The release APK goes from **74 MB to 9.4 MB** (arm64) and 68 MB to 7.7 MB
(armeabi-v7a); the native library itself drops from 74 MB to 6.7 MB with **zero** DWARF sections
remaining. Combined with excluding the desktop binaries, the artifact is **115 MB → 9.4 MB**, and the
withdrawn "that is the cost of PQXDH" claim was out by more than a factor of ten.

I had called this "a build-image change of a different order" and left it. That was wrong in a way
worth recording: the image is *committed to this repository* and is the documented way to build, so
an image that cannot produce a distributable artifact is a defect in the tooling, not an environment
limitation. What changed my mind was adding a gate that made the project's own build path fail.

**And the gate itself was broken.** It searched the raw bytes of each `.so` for the string
`.debug_info`. `llvm-strip` removes the DWARF sections but leaves their **names** in the section-header
string table — so a correctly stripped library still contains that substring, and the gate failed
every build. It would have turned CI permanently red the moment an NDK appeared.

It was controlled in both directions when written: unstripped fails, and a marker string that exists
nowhere passes. **Both controls were synthetic.** There was no NDK in the image, so there was no
genuinely stripped library to test against, and a fabricated negative proved only that the code could
say yes. It now parses ELF section headers — handling 32- and 64-bit, and refusing to guess at
anything it cannot parse — and is verified against real artifacts in both directions: stripped passes,
unstripped fails naming 64 MB and 60 MB of DWARF.

What **is** done is that the silence is gone: `verifyReleaseNativesStripped`
runs after `assembleRelease` and fails if a packaged `.so` still carries DWARF sections, naming each
file and its size.

`-Pkryptey.allowUnstrippedNatives=true` allows it and says so loudly in the log — which is what this
container needs to pass, and stating that explicitly is the point. The gate exists because the
warning AGP already prints was not enough: it printed on every release build here, the build reported
success, and a **settled** entry in this document then accepted 115 MB as "the cost of PQXDH" on a
premise nobody had measured. A warning that does not stop anything is indistinguishable from no
warning at all.

Checked in both directions rather than only the failing one: with the marker changed to a string no
ELF contains, the gate passes — so it is the debug sections driving the result, not an unconditional
failure, which is the evidence that it will go green on a properly stripped artifact rather than
having to be disabled.


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

Last cold verification, at `8eb728f`: `clean testDebugUnitTest` from an empty Gradle volume, with
verification ON — BUILD SUCCESSFUL in 6m, 39 of 41 tasks executed, **894 tests green from scratch**
(0 failures, 4 skipped) rather than warm. `assembleRelease` was run cold too, with
`-Pkryptey.allowUnstrippedNatives=true`, so the new packaging exclusions and the strip gate are both
exercised from nothing.

**Run because the trigger fired, not on a schedule.** The tick before this one deliberately skipped a
cold run and said why: no build-input file had changed in nineteen commits, so it would have
confirmed habit. Since then `app/build.gradle` gained 93 lines — the desktop-native exclusions, a
task input declaration, and `verifyReleaseNativesStripped` — which is exactly the case a warm cache
speaks for least, because it changes what the build has to *do* rather than only what it runs. The
previous cold run, at `af17117`, was triggered the same way by two commits that changed the build's
work rather than its inputs.
The run before it covered `clean assembleDebug` the same way (39 of 39 tasks executed, zero
verification failures across all 386 pinned components).

**A cold cache is not a fresh clone, and until now only the first had been tested.** Every cold run
on this branch mounts the working tree — the same directory, with its build outputs and anything
untracked or ignored sitting in it. Such a run proves the *dependency* story (an empty Gradle volume,
verification on, 386 components fetched and checked) and says nothing about whether the tracked
content is sufficient. A build quietly depending on an ignored file would pass every one of them.

Tested at `49a451e`, and again at `85c3049` once the build image gained an NDK and the strip gate was
corrected. The second run is the one that matters, because it covers the whole path rather than the
tests alone: `git clone` into a fresh directory, an empty Gradle volume, the committed image, and
`testDebugUnitTest assembleRelease` — **BUILD SUCCESSFUL in 6m21, 957 tests, 0 failures, 4 skipped**,
and release APKs of **9.4 MB / 7.7 MB** with `verifyReleaseNativesStripped` passing on its own, no
opt-out flag.

So what a new contributor gets from `git clone` alone is a working build *and a distributable
artifact*. The inference was available beforehand — only build outputs and `.claude/` are ignored —
but this document has spent its time replacing exactly that kind of inference with a run, and the
second run found the first one's claim had quietly become too narrow: it proved the tests build, at a
moment when `assembleRelease` could not have succeeded at all.

*The committed script itself has now been run, which is not the same as running a copy of it.* Every
cold verification quoted above used a wrapper in a scratch directory; `tools/verify-cold` is what the
README tells a contributor to use, and after the image tag moved to `:38` nothing had exercised it.
It works: **BUILD SUCCESSFUL in 8m1, 965 tests, 0 failures**.

Worth reading the task counts rather than the headline, though: **6 executed, 33 up-to-date**. An
empty Gradle volume is a cold *dependency* cache, not a clean build directory — the script mounts the
working tree, so anything already built is reused. That is exactly what the script is for, and its own
comment says so, but "cold verification, 965 tests" invites a reader to hear "rebuilt from nothing".
The tests did genuinely re-run here — `testDebugUnitTest` appears without an `UP-TO-DATE` marker, and
compilation ran with it — which was worth checking rather than assuming, since a task that skips
silently is how a stale number gets reported as a fresh one. The fresh-clone run above is the one that
covers what this does not.

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

**One half of it turned out to be testable after all, and the split is worth naming.** A comment
explaining *why* is prose and stays unverifiable — which is how a comment came to explain contact
deletion by naming `hasExactlyOneContactNamed`, a method whose only occurrence anywhere in the
codebase was that comment. Nothing mechanical could have caught it. But a `{@link}` is not prose: it
is a machine-checkable claim that a named thing exists, and this build never checked one, because
javadoc is not run. A link to a deleted method compiles, ships, and reads to the next author as a
live cross-reference.

`JavadocLinksResolveTest` resolves all 56 in the protocol layer and the strip — against declared
types, members of the file itself, and the file's own imports. **None were broken**, which is worth
stating plainly: the contribution is the guard, not a defect. Scoped deliberately, and not extended
to the AOSP-inherited packages, whose links point at platform internals (`SoftInputWindow`,
`SuggestionStripView`) that were already dead when the keyboard was forked; adopting those would mean
a large allowlist saying nothing about this project's drift.

It also caught its author twice before it caught anything else, both times through the vacuity rule
this section already recommends. The file-count assertion fired on a threshold I had asserted without
counting — "over 100 links" against an actual 56 — and its only reported finding was its own parser
taking javadoc's line-leading `*` as part of a link that wrapped across two lines. A guard that has
never failed for a real reason is worth less than one that has failed for a wrong one and been
corrected.

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


## Two siblings, and the call site picked the weaker one

The hunt for "a predicate tested and consulted by nothing" turned up a variant that is worse, because
the predicate *was* consulted — by a caller that should have been asking its sibling.

`StorageHelper.secureStore` computed `EncryptedKeyValueStore.isEncrypted(raw)`, which asks only
whether a schema marker survived, and passed it to the Keystore box as `alreadyEncrypted`. That
boolean is the **sole** input to the box's refusal to mint a replacement master key over data it
cannot decrypt — the guard that stops a start-up from silently orphaning the user's identity.
`hasEncryptedData` is the predicate for that question, exists precisely for "ciphertext with the
marker unset", and had five references, every one from a test. `isEncrypted`'s own javadoc told
callers deciding whether data is at stake to prefer it, and the single caller that had to did not.

The state is ordinary rather than exotic. `put` and `putAll` write the values and the completion
marker as two separate durable commits, and an IME is killed routinely, so ciphertext with no marker
above it is an interrupted save — and still the user's identity. Setting `alreadyEncrypted = false`
outright, disarming the refusal on every device, left all 854 tests green.

**Why this shape hides better than an unwired predicate.** A predicate nothing calls is at least
visibly unused. Here both predicates are used, both are tested, the names differ by one word, and the
javadoc that distinguishes them lives on the *weaker* one — where a reader who already picked it has
no reason to look. The test suite covered the two predicates thoroughly and never asked which one the
decision consulted; `StorageSchemaStateTest` even names the attacker version of this exact state and
asserts the predicate rather than the wiring.

The rule that falls out: **where two predicates answer nearly the same question, the security
decision must name which one it needs and a test must pin the call site, not the answer.** The
javadoc now says callers *must* call `hasEncryptedData`, rather than *should prefer* it — the earlier
wording was true, unheeded, and cost nothing to ignore.


## Phase 3's parser, swept and clean

Mutation has found a real gap in this branch three times — a Keystore refusal consulted by nothing, a
`hasEncryptedData` sibling never called, a warning banner one rebuild could wipe — so a clean sweep
is worth recording with its numbers rather than left as an impression.

Every rejection in `BinaryEnvelope` was removed or disarmed, one at a time, each against the **full**
suite rather than a scoped subset:

| guard removed | result |
|---|---|
| trailing bytes accepted after a valid envelope | killed (2 tests) |
| non-printable / non-ASCII sender name accepted | killed (4) |
| any envelope version accepted | killed (1) |
| unknown flag bits accepted | killed (1) |
| device id outside libsignal's [1,127] accepted | killed (3) |
| decode accepts an envelope carrying nothing | killed (1) |

Nothing survived. Two of those are load-bearing beyond ordinary parsing hygiene and are worth naming:
the printable-ASCII rule on sender names is what keeps `U+001F` — the address-key separator — off the
wire, which is the hole the chat-log migration had to be written around; and refusing trailing bytes
is what stops a hostile envelope smuggling data past a parser that has otherwise succeeded.

The thin margins are honest rather than alarming: a guard killed by one test is a guard with exactly
one test, and for a parser that is often correct — the assertion is "this input is rejected", and
there is one way to say it. What would be worth acting on is a zero, and there are none here.


## A guard that was false on exactly the path that needed it

The sharpest instance yet of "correct code at the wrong moment", and the one worth keeping as a
pattern. `getPreKeyBundle` persisted the account **inside** `if (!containsKyberPreKey(active))` — the
branch that lazily mints a Kyber key for pre-upgrade accounts. The comment on that persist states the
hazard exactly: *"if the private half is still only in memory when the keyboard is dismissed,
reloadAccount() replaces the account from disk and the key is gone."*

Once a month `refreshSignedPreKeyIfNecessary` rotates the signed pre key and, inside
`rotateSignedPreKey`, the Kyber pre key with it. By the time the guard is evaluated the store already
holds the active Kyber id, so the branch is **skipped** — and the bundle carrying both freshly
rotated public halves leaves with neither private half on disk. `reloadAccount` runs on every
`setInputView`: a theme flip, a rotation, the next raise at all.

What the user gets: after the first month, the first invite they send produces a contact whose first
message can never be decrypted. The app's advice is to ask for a fresh invite — which rotates again
and loses again. The messenger decides when configuration changes happen, so it can force the reload
whenever it likes.

**The pattern to carry forward:** the code was present, correct, reached, and documented with the
exact failure it prevents. What was wrong was the *condition around it*, and it excluded precisely
the case the comment described. A reviewer reading that method sees a persist call with a
well-argued comment and moves on; only asking "when is this guard false?" finds it. Mutation finds it
too, because deleting the call changes nothing on the path that already skips it.

## Phase 4's trust predicates, swept and clean

Companion to the Phase 3 parser sweep, same method — every guard disarmed one at a time against the
full suite:

| guard removed | result |
|---|---|
| TOFU pin defeated — `isTrustedIdentity` returns true always | killed (49 tests) |
| a displaced key silently replaces the pin | killed (3) |
| an identity change is never recorded, so no warning fires | killed (50) |
| `acceptIdentityChange` ignores which key the user was shown | killed (4) |
| a rejection is never remembered | killed (10) |
| a deleted contact's display name is never retired | killed (15) |

Nothing survived. The two headline numbers are the ones to read: defeating trust-on-first-use fails
49 tests and losing the pending-change record fails 50, which is what it should look like for the
two properties the whole trust model rests on.


## The typing-redirect seam, swept and clean — and two ways a sweep lies

Third companion sweep, after the Phase 3 parser and the Phase 4 trust predicates. The seam that
decides **where the user's plaintext goes** — `RichInputConnection`'s redirect — disarmed one guard
at a time against the full suite:

| guard removed | result |
|---|---|
| host-reported selection trusted while typing is redirected | killed (1 test) |
| `getIC` ignores the redirect and returns the host field | killed (19) |
| `forgetCachedText` leaves the third commit buffer holding the last message | killed (4) |

Four more guards in the same layer, swept after those:

| guard removed | result |
|---|---|
| `FLAG_SECURE` never applied to the window | killed (1) |
| the messenger controls Enter in the compose box | killed (2) |
| the window is never told the content is sensitive | killed (3) |
| changing recipient keeps the staged message | killed (5) |

Nothing survived either round. The 19 is the one to read: routing plaintext to the messenger's own
field instead of the strip fails a fifth of the strip suite, which is what that should look like.

**Where the sweeps now stand.** Nineteen guards disarmed one at a time across four areas — the wire
parser (6), the trust predicates (6), the redirect seam (3), the window and input guards (4) — each
against the full suite. **One survivor**, `refreshOpeningMessage` overwriting a standing warning,
found and fixed. That is a coverage claim with a method and a number behind it, which is what this
document has been wrong about often enough to stop asserting any other way.

**Two ways this sweep reported a result that was not true, both caught here rather than believed.**
They belong beside the recorded mutation discipline, because each produces a confident, plausible,
wrong answer:

- *A mutation that never applied.* The anchor did not match, the script threw, the harness ran the
  build anyway and reported **SURVIVED** — of unmutated code. A survivor is the interesting outcome,
  so this is the direction that invents findings. The harness now refuses to report anything unless
  the replacement is verified present in the file afterwards.
- *A substring anchor matching more than its line.* `    x.clear();` with four spaces is a substring
  of `      x.clear();` with six, so a "unique" anchor silently matched two places. Anchoring to the
  preceding newline fixes it. An anchor that over-matches mutates code you did not choose and
  attributes the result to the line you meant.

Together with the two already recorded — a sweep killed mid-run leaving a mutant in the tree, and a
control whose "nothing killed" was a compile failure — that is four distinct ways to get a wrong
answer from mutation testing, every one of which has happened on this branch. The method is worth
its cost; the harness around it is not optional.


## The way the harness lied six times, and how it stopped

`ReleasePackagingTest` reads `app/build.gradle` at run time. Gradle has no way to know that, so the
task stayed **UP-TO-DATE** when the script changed and the test replayed its previous pass. Two
controls against it reported *survived* — the mutation applied, the build succeeded, no error
anywhere — and the test had been biting correctly the whole time. Forcing `--rerun-tasks` showed the
kill immediately.

This one is worse than the other four, because it does not only corrupt a control: it makes the test
itself useless in ordinary use. Anyone editing the build script and running the suite would get a
green replay. Fixed by declaring the file as a task input, and verified the way it should have been
in the first place — mutate the script, run the suite *without* any force flag, watch it fail.

The scanner tests that read Java sources are not affected: those files are already compilation
inputs, so changing one invalidates the task. `build.gradle` is the case where the file a test reads
is not on any path Gradle tracks for it.

**It then happened five more times** — `README.md`, `HELP.md`, `KRYPTEY.md`, the F-Droid description,
and `src/androidTest/java`, which `dependsOn` compiles without tracking. Each was fixed by declaring
one more input, which is fixing an instance rather than the pattern. By the sixth I expected it before
believing the result, and that is the only reason the guard involved is not sitting in the tree
passing for the wrong reason.

`EveryFileATestReadsIsATaskInputTest` closes it: any repo path a test names that Gradle has no reason
to know about fails the build until it is declared, and any declared input nobody reads fails too, so
the list cannot rot in either direction. Sources under `src/main/java`, `src/test/java`,
`src/main/res` and `src/test/resources` are exempt because compiling or processing them already makes
them inputs — and that exemption is precisely why the trap was subtle, since the scanner tests reading
Java sources always worked and gave no hint the markdown ones would not.

Two things about writing it are worth keeping. Its first version scanned for `Paths.get("literal")`,
which this codebase almost never writes — the idiom is a candidate array tried in turn, because the
working directory is the module and not the repository root — so it found two literals in the whole
tree and was caught by its own vacuity assertion. And it flagged a classpath fixture as an undeclared
file, which is now filtered by asking whether the path exists on disk rather than by the shape of the
string: a rule that stays right when either set changes.


## A record kept, and never shown on the route that matters

A round sent to hunt "the app knows something, stores it correctly, and never surfaces it where the
adversary actually goes" found three, and the class turns out to have three distinct shapes.

**Erased by the gesture its own text asks for.** The post-rejection banner tells the user to open the
contact list and compare the number. Tapping the row calls `selectContact`, which clears the standing
warning and then re-asserts exactly one record — `hasUnacceptedIdentityChange` — arguing in-method
that a pending change "is a state, not a notice that has been read". A rejection is a state by the
same argument, and every other reader treats it as one: its own javadoc says it is "cleared only by a
fresh comparison … never by anything an attacker can trigger", and it outranks a verified badge. It
was not re-asserted here. Measured end state: `Chosen contact: Bob Jones #6646-2750b6`, byte-identical
to a healthy contact, over the attacker's freshly pinned key. The sibling was pinned by a test; this
one was not.

**Reaching one screen and not the one next to it.** An unreadable store surfaces on the main banner
and nowhere else. The contact-list button is not disabled, and one tap renders an **empty list** under
"if you want to chat with someone new, invite them via the add button" — verbatim the fresh-install
reading `StorageHelper`'s javadoc says must never be presented, because it invites the user to
re-invite everyone and discard every pin they verified. And the invite button that line points at
**crashed**: `NullPointerException` out of `View.performClick`, which takes the IME process down in
whatever app the user is in.

**Computed and then thrown away.** `duplicateNameMessage` asked "is this name known AND not retired",
so any retirement suppressed the live wording — including when a live row of that name was in the
list at the same time, which is the attacker's *second* attempt at a name this user already deleted
once. The suppressed sentence is the only one that says "both now appear in your list, tagged by
address": the sentence pointing at the tag the whole disambiguation rests on, dropped in the case
furthest along.

**A survivor kept deliberately, and why it is not a gap.** The fix declines to put a second
`storageIsUnreadable()` check on the invite listener. A duplicated condition would mask the real
guard — delete either and nothing observable changes, so both mutations live. That pattern has
already cost this file two undetectable deletions. The unchecked-`RuntimeException` net behind it is
kept as defence in depth and is *not* killable while the guard stands; the two layers carry
deliberately different toast text, which is what makes the guard itself killable.


## An unchecked throw out of a click listener kills the keyboard

A round hunting this class found three, all reproduced out of `View.performClick`, all in one state
nobody had covered — and the messenger arms the trap itself.

**The state.** `UNREADABLE` is defended: the strip notices, posts a standing warning, and the text
watcher disables Encrypt and Decrypt off that banner. The undefended state is **no account loaded
with `storageState() == NONE`** — a reload that yields nothing, or a storage helper that could not be
built. `NONE` is also the right answer for a fresh install, so the ordinary "No contact chosen" line
renders and nothing is disabled.

**And the messenger can arm it.** The clipboard listener needs no account, so the first KryptEY-shaped
payload it posts writes *"Keybundle detected: click on decrypt to save the content"* over the banner —
and `afterTextChanged` treats any banner other than the two named ones as a reason to **enable both
action buttons**. Measured: decrypt `enabled=false` before the paste, `true` after. The user then
presses the button the app just told them to press, and the input-method process dies in whatever app
they are typing in.

Three guards, at the three places that can see `mAccount`: `getAccountName` (reached by Decrypt *and*
the chat-log button), `createAndAddContactToList` (the add-contact screen the first press delivers
them to), and `encrypt`. Each returns null, which every caller already handles — checked rather than
assumed: the decrypt comparison has the envelope's name as receiver and the account name as argument,
so a null loses the comparison, and `ListAdapterMessages` null-checks before every use.

**No catch-all net was added, deliberately.** A `catch (RuntimeException)` mirroring the one
`sendPreKeyResponseMessageToApplication` got would have been an immortal mutant: with the guards in
place there is no second reachable unchecked throw on that path to kill it with. Recorded as the
right move *if* a new unchecked source appears below that listener — which is a different statement
from "defence in depth is always worth it".


## A decision read from rendered text

Whether Encrypt and Decrypt are usable was decided by looking at *the string currently in the info
banner*: two named messages meant disabled, and **anything unrecognised meant enabled**. A round sent
to hunt that shape found three defects and one of them is the sharpest single sentence on this branch.

**Focusing another app's password box was the only event in this app that turned the action buttons
on by announcing that they were off.** `setHostFieldIsPassword(true)` writes "Encryption and
decryption are turned off here"; the watcher saw a string that was not one of the two named ones, and
lit both buttons. The clicks were still refused — both paths check `actionsAreAvailable()` — so this
was a lie about state rather than a way to run the action. That distinction matters less than it
sounds: an app whose only lasting surface says one thing while its buttons say the other has spent
the credibility it needs for the warnings that do matter.

Two worse corners followed from the same coupling. Lowering the guard wrote nothing, so the notice
outlived the field it described — an ordinary field in the same app, both actions working, the banner
still saying they are off. And with a security warning standing, `setInfoUnlessWarned` correctly
refuses to write the notice, so there is no banner change, so there is no watcher: over a password box
**with a substitution warning on screen**, both buttons stayed lit. That is the one state where both
of the app's reasons to refuse are live at once.

**And the invite path asked nothing at all.** Its two siblings open with `actionsAreAvailable()`;
`sendPreKeyResponseMessageToApplication` did not, and it ends in the same `mListener.onTextInput` they
do — with the longest string this app produces. Over another app's password box, two taps committed a
whole encoded key bundle into it, handed to that app's storage, autofill and whatever it syncs. That
is the sentence `mHostFieldIsPassword`'s javadoc exists for, on the one screen button the app
deliberately never disables.

Third: `FLAG_SECURE` was computed from view properties — visibilities plus whether the compose box had
characters — and answered *false* while the banner read `Chosen contact: Bob Jones #46f9-2ab88e`. Who
the user talks to, and the tag distinguishing them from a second contact of the same name: the exact
pair this method's own javadoc gives as its reason for covering the contact list, and which
`forgetChosenRecipient` spends a paragraph calling a disclosure worth a tap to prevent. Two sections
of the file agreed; the predicate between them did not. Not a moment — the whole interval between
choosing a recipient and typing, plus every standing warning.

The fix keeps the banner-text half, because it is still load-bearing: dropping it alone fails
pre-existing tests. What changed is that it is no longer the *whole* answer. And
`isShowingSensitiveContent` asks `chosenContact` rather than reading the banner, which would have been
the same coupling moved one method over.

## Phase 1's encrypted store, swept and clean

The sweep programme closed at six areas and this was the seventh, unswept: the marker and migration
logic that decides whether stored data is readable at all.

| guard removed | result |
|---|---|
| cleartext laundered into a migrated store accepted | killed (2 tests) |
| an undecryptable envelope overwritten rather than refused | killed (1) |
| a schema key with an unreadable marker accepted | killed (1) |
| migration re-runs over a completed store | killed (5) |

Nothing survived. **33 guards across seven areas, one survivor** — with the caveat that still stands:
this measures the guards someone thought to disarm, and every real defect on this branch came from a
reviewer asking a question nobody had asked.


## What the adversary gets to declare

The round that found this **died mid-run** — stalled with no progress, its last line "Now the
password-predicate fix", no report, no reasoning, no control numbers. Its worktree held six modified
production files and three new test classes. Everything below was verified here from scratch: eight
of its eleven tests fail at unmodified HEAD, and each fix was reviewed and controlled independently.

**A visible password field did not arm the guard.** `isPasswordInputType` mirrors
`TextView.isPasswordInputType`, whose distinction is *whether to paint dots* — so it answers false for
`textVisiblePassword`, which is what a Wi-Fi passphrase box, a "show password" login, a recovery
phrase and a PIN box with a reveal toggle all declare. This app already treated those as password
fields everywhere it did not matter: `InputAttributes` suppresses suggestions for them,
`KeyboardId` picks the password layout, both by spelling out the disjunction inline. **The one place
that decides whether a key bundle or a decrypted message may be typed into the field asked the half
that says no.** Three inline copies of a security-relevant disjunction, and the third differed; there
is one predicate now.

**A hostile selection could kill the keyboard.** `onUpdateSelection` is delivered verbatim from
`InputMethodManager.updateSelection`, an unprivileged call whose integers the host app picks, and
`EditorInfo.initialSelStart/End` arrive by the same route. A reversed pair is not exotic — dragging a
selection backwards produces one in an ordinary TextView — but downstream `handleBackspaceEvent` and
`performRecapitalization` both compute `end - start` and pass it as a character count, reaching
`mComposingText.setLength(length - beforeLength)`. A negative count sizes a service-lifetime buffer
with a number the messenger chose, and at `Integer.MAX_VALUE` that is an `OutOfMemoryError` from one
key press — an **`Error`**, so none of the `catch (Exception)` handlers this branch added stops it.
Normalised at the single write point, because the invariant belongs to the pair rather than to either
reader, and a backwards drag still deletes exactly what it selected.

**And a fix from an earlier round rested on a false premise — mine.** Its comment said the plain
signal-message arm "carries no bundle, so nothing is pinned there". `SignalProtocolMain.decrypt`'s own
comment says the opposite and is right: a `PreKeySignalMessage` carries its own identity key and needs
no attached bundle. So an attacker who simply **omits a field** reached the same pin, at the same
rejected address, through the one arm of three that never asked. `getMessageType` reads field presence
and nothing else, so choosing that arm costs them nothing.


### What the host *returns*, as opposed to what it declares

The re-spawned round finished the surface the first one never reached, and the three findings share a
shape the first three did not: **the app bounded the number the host announces, not the bytes it
sends.**

- **A request for 1024 characters was answered with 1,000,000.** `reloadTextCache` asks for
  `EDITOR_CONTENTS_CACHE_SIZE` and appends the reply whole into a buffer on the one
  `RichInputConnection` the service owns. `LatinIME.onWindowHidden`'s own comment justifies clearing
  those buffers by saying "the IME keeps up to 1024 characters of text around the cursor" — which is
  a bound on the *request*. Measured: the cache held 1,000,000. It runs on every cursor move, every
  `setSelection`, and every input-session start.
- **Recapitalisation bounded the selection the host declared.** Its comment — *"we don't want to suck
  possibly multiple-megabyte data"* — guards `selectionEnd - selectionStart`, which comes from
  `onUpdateSelection`. It then calls `getSelectedText` and uses the reply unchecked. Measured with a
  host declaring **five** characters and answering with a million: `RecapitalizeStatus` held all of
  it, and so did two service-lifetime buffers — the same buffers a decrypted message travels through.
  The declared-length check is kept, because it avoids the IPC entirely; the two check **different
  values**, so this is not a second guard on the first.
- **A process-lifetime cache keyed on a string the host picks.** `KeyboardId` mixes
  `EditorInfo.actionLabel` into `equals` and `hashCode`, and `sKeyboardCache` is a static map whose
  *values* are soft references but whose *keys* are strong — each pinning that session's whole
  `EditorInfo`, including the arbitrary `Bundle` in `extras`. Measured from empty: 200 sessions with
  distinct labels left 201 entries; 200 more on one label added one. Exactly one permanent entry per
  string the host has ever declared, and no forgery required — the app on screen decides how often a
  field takes focus and what label it carries.

Also worth keeping is what the round measured and left alone: `packageName` is never trusted to
identify the host (its only consumer is a debug dump behind a `false` constant), and `fieldId`,
`hintText`, `label` and `extras` are read nowhere in `app/src/main`. A clean negative on the rest of
`EditorInfo` is what makes the three above worth acting on.


## State that grows without bound

**The other half of a buffer bounded one round earlier.** That round clamped what the *host returns*
into `mCommittedTextBeforeComposingText` — 1,000,000 characters answered to a request for 1,024. It
did not bound what the keyboard **appends itself**, which happens once per keystroke and is the same
buffer. Nothing resets it while a session continues: `reloadTextCache` fires only on a cursor move the
keyboard did not predict, so a host reporting exactly the expected position — what a correct app does
— never triggers one. Measured through the real key path: **100,000 keystrokes left 100,000
characters**, and a silent host gives the same number, so being well-behaved does not help.

`LatinIME.onWindowHidden` clears that buffer precisely so the user's text is not left in the
input-method process, and its comment puts the stake at 1024 characters. That was a bound on the
request, twice over. The fix routes all five growth sites through one append and drops the **head**,
because every reader takes the tail and the trimmed contents are byte-for-byte what `reloadTextCache`
already produces — so the bounded state is one the code reaches on any cursor move rather than a new
one.

**And the replay guard's own memory.** `usedBaseKeys` records every base key ever seen against a Kyber
pre-key, and returning false from `seen.add` is what refuses a replayed handshake. It only grew: 200
handshakes left 200 entries under one id, persisted and re-encrypted on every save, and
`removeOldKyberPreKeys` only reaches ids *below* the active one.

The round that found it then **argued its own severity down**, which is worth more than the finding.
One captured bundle replayed 300 times leaves **one** entry, because the consumed one-time pre-key is
regenerated and the other 299 fail their MAC; a message failing authentication burns nothing, since
libsignal reaches `markKyberPreKeyUsed` only after the handshake verifies; and fifty first-messages
from one peer session leave one entry. So the growth is real and unbounded but **paced by the user**,
one entry per invite exported and consumed — not by the wire.

The fix is the part to read. It bounds by **retiring the pre-key record**, never by evicting a base
key: a forgotten base key is one that can be replayed, which is exactly what the map exists to deny.
Dropping the record is strictly stronger — `loadKyberPreKey` then throws for that id, so every
handshake against it is refused, replays included — and ids are monotonic, so a retired id never
returns. `getPreKeyBundle` mints a fresh Kyber pre-key when the active one is missing, so the next
invite heals it. Verified through 300 real sessions: entries peak at 257, drop to 0 as the record
retires, and **all 300 messages decrypt**, including the one that crossed the limit.

**A negative worth as much as the findings.** A probe walked every collection reachable from a live
`LatinIME` plus 428 static roots — **1,675 collections** — across 300 input sessions with distinct
host-chosen labels, a configuration change and an `onFinishInput` each: **zero growth anywhere**. The
instrument was validated from a cold start by catching the previous round's `sKeyboardCache` growth
*and* the nested `sUniqueKeysCache` a static-field scan cannot see. Session records grow 9.7× under
replayed invites and saturate at libsignal's 40 archived states; `retiredDisplayNames`,
`rejectedAddresses`, `pendingIdentities` and `trustedKeys` are all bounded by construction.


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

**A second device question, since answered on a device.** *Resolved: autofill never sees the
compose box, and the mitigation is still not added — now because it was measured to be unnecessary
rather than because nobody had checked. The entry below is the original reasoning; the measurement
follows it.* The compose box that holds decrypted plaintext
(`e2ee_input_field`) is an ordinary `EditText` declaring `inputType="textMultiLine"`, and neither the
layout nor any code sets `importantForAutofill` or `importantForAccessibility` — checked across
`app/src/main`, which contains no occurrence of either.

Whether that matters turns on a question nothing here can answer: does Android's autofill framework
ever target views inside an **IME's own** window, rather than the app being filled? Autofill exists
from API 26, which is this app's floor, and an `EditText` is autofill-eligible by default. If it can
be targeted, a field holding decrypted messages is offered to whatever autofill service the user has
enabled — and `FLAG_SECURE` does not touch autofill or accessibility, both of which read text rather
than pixels.

`android:importantForAutofill="no"` would be a one-line mitigation and *is* testable here — the
inflated view can be asserted. It is **not** added, deliberately: this branch has twice declined
guards it could not demonstrate a need for, and adding hardening against a mechanism that may not
reach IME views would be exactly that, with a test that pins a decision nobody has justified. What is
recorded instead is the observation, the mitigation, and the one experiment that settles it — enable
an autofill service and focus the compose box.

**The experiment, run.** `RecordingAutofillService` is a debug-only autofill service that fills
nothing and writes down every `AssistStructure` it is handed. With it registered as the device's
autofill provider and the KryptEY keyboard bound and connected over a real text field,
`AutofillDoesNotReachTheKeyboardTest` reads back what the framework actually delivered.
**`e2ee_input_field` never appears.** The structure carries the host activity's own field and
nothing belonging to the keyboard's window.

**The first version of this experiment proved nothing, and the reason is worth more than the
result.** It asserted the same absence and was green — but an adversarial review traced the
ordering: autofill builds a structure when a view takes focus, the activity focused its field in
`onCreate`, and the test then *waited for window focus*, which is the very event that fires the
request. So the only structure it could capture was built before any keyboard window existed. It
would have been equally green on a platform that did include IME windows. The conclusion was
unfalsifiable, which is worse than wrong.

What the test does now is force a request at a moment when the keyboard is demonstrably up: it
waits until the IME is bound and connected, then moves focus to a *second* host field, and inspects
that request specifically, identified by sequence number. The recorded structure is

```
[autofill_probe_field, autofill_probe_field_two]
```

— the host activity's two fields, and nothing else, with the keyboard on screen.

It stands on four controls, not on the absence:

1. A request arrived *after* the keyboard was up (`sequence > before`). Without that there is
   nothing to inspect that could have contained a keyboard view.
2. That structure is populated and names fields by resource id — the field just focused is in it,
   via an id that exists only so this control can be written.
3. The keyboard was bound, connected, and its service running across the measurement.
4. The detector discriminates: add a field that *is* in the structure to the list of ids it
   searches for, and the assertion fails. Verified by running it that way.

The absence is also checked across the keyboard's whole layout — all seventeen ids in
`ee2e_main_view` and `main_keyboard_frame` — rather than the compose box alone, so the sentence
"nothing from the keyboard's window" is the sentence being tested.

**What this does and does not settle.** It settles the pathway that mattered: a fill request for
some other app's field does not carry the keyboard's views along with it, so the decrypted message
sitting in the compose box is not handed to whatever autofill provider the user installed. It does
*not* prove the IME could never start a session of its own. Autofill sessions are begun by an
autofill *client*, which is an Activity; an `InputMethodService` is not one. That is an argument
rather than a measurement, and it is written here as an argument — driving the strip's own compose
box to focus needs a tap at coordinates that depend on which of the strip's six screens is showing,
which is a harness this does not have. `uiautomator dump` was tried as a cross-check and cannot see
IME windows at all, so it settled nothing either way.

So `android:importantForAutofill="no"` is still not added, and the reason has changed from "no
demonstrated need" to "measured absent on the pathway that would have carried it".

**A manifest question, since answered on a device.** *Resolved: the platform binds it. See
[the instrumentation tests run
now](#the-instrumentation-tests-run-now-on-an-emulator-with-no-hardware-acceleration) — the entry
below is the original doubt, kept because its reasoning was sound and its resolution cost turned out
to be accurate.* The IME service is
declared `android:exported="false"` alongside `android:permission="android.permission.BIND_INPUT_METHOD"`.
The system's InputMethodManagerService runs in a different UID, so on the plain reading of `exported`
it could not bind the service at all and the keyboard would never appear in the input-method list —
which would be a total failure, not a subtle one.

Almost certainly it is fine: the attribute has been there since the initial commit, predating this
revival entirely, and the app is published on F-Droid and used. The platform gates IME binding on the
`BIND_INPUT_METHOD` permission and plausibly treats these services specially. But "the app exists, so
it must work" is exactly the reasoning this branch has spent its time dismantling, and nothing here
can run an IME.

So it is written down as a **question**, not a finding: on a device, does the keyboard appear in
Settings → Languages & input, and does setting `exported="true"` change anything? One minute of
hardware answers it. It is listed because a reader auditing the manifest will have the same doubt,
and the useful thing to leave them is the doubt plus its resolution cost, rather than silence or a
claim I cannot support.

**The answer, measured.** It appears and it binds. `ime list -a` shows it among the available input
methods; selecting it and putting a real editable field in front of it starts the service, which
`ImeBindsDespiteExportedFalseTest` asserts by watching for the service to run rather than by reading
the setting that says it was chosen. `exported` is not what gates an input method — the
`BIND_INPUT_METHOD` signature permission is, and the input-method framework binds through it. The
"one minute of hardware" estimate was right about the experiment and wrong about the cost: most of
the work was proving the harness honest, because the first two runs failed for reasons that had
nothing to do with the question.

*Also checked while there, and clean:* the app declares exactly one permission, `VIBRATE`, which is
what the README lists; the boot receiver is `exported="false"`; and the IME service is the only
component carrying a `BIND_*` permission.

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
  exported; the only exported component is the launcher activity. Exactly one permission is declared,
  `VIBRATE`, which is what the README lists.

  **One correction to this entry, made later and worth leaving visible.** It used to end "Nothing to
  fix", and that closed a question it should have left open: `exported="false"` on a service the
  *system* must bind is, on the plain reading of the attribute, a service the system cannot bind. It
  almost certainly works — the attribute predates this revival and the app is published and used —
  but nothing here can run an IME, and this document has spent its time removing exactly this kind of
  confident sentence. The open form of it is on the hardware list. Two sections of one file disagreed
  for two days, which is the same failure the invite advice produced: an audit that reads sections in
  isolation cannot see them contradict each other.
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

  **Re-run at 920 tests, and the figures above are from 796.** 124 tests were added after that check,
  many of them using the very static seams that make leakage possible, so the claim was 124 tests out
  of date and nobody had noticed — the check is repeatable precisely so it does not have to be
  believed. The isolated run is clean again at 920, but it did not come back clean the first time:
  it surfaced a genuinely failing test, which turned out not to be leakage at all (see below).

  That check rules out leakage *between* classes and says nothing about order *within* one, which is
  a distinction worth keeping rather than letting "the suite is order-independent" stand for both.
  So the second half was measured too: every class temporarily annotated
  `@FixMethodOrder(NAME_ASCENDING)` — a deterministic order, and a different one from JUnit's
  hash-based default — 114 classes, **796 tests, zero failures again**. Not proof over all orders;
  evidence over a second one, which is what was actually available.

- **No test in the suite passes without checking anything.** Scanned all 938 `@Test` methods for
  bodies with no assertion, no `fail(` and no `assertThrows`. Twenty-four came back, and every one is
  legitimate on inspection — which is the result, since the scan was written expecting otherwise.

  Eight are the inherited 0.1.5 protocol tests, whose assertions live in shared helpers
  (`sendMessageAfterSessionBuild` and its siblings carry 85 between them). Fourteen are fuzz and
  safety tests whose contract is that *nothing escapes* — `singleBitFlipsNeverEscape` feeds every
  single-bit corruption of a real ciphertext through the decrypt path, and its helper calls `fail()`
  on any `Throwable`, so an assertion in the body would be the wrong shape. One is the fixture
  generator, a tool. One is a pre-28 keystore test where reaching the end without throwing is the
  property.

  **No guard was added, deliberately.** A check reading only the method body flags all twenty-four;
  one that follows helpers would have to resolve calls across files and would still be wrong about
  the fuzz contract. A guard with twenty-four false positives on a clean suite teaches people to
  ignore it, which is worse than not having it — the same reasoning that declined a catch-all
  exception net and a redundant second condition earlier here.

- **The ProGuard rules work, which nothing had ever established.** `minifyEnabled` is false, so
  `proguard-rules.pro` is 63 lines of configuration that has never been applied — and its own comment
  says why that is a hazard: AGP 9 forces `proguard-android-optimize.txt`, which drops `-dontoptimize`,
  so whoever turns minification on next would hit silent breakage in exactly the code that persists
  the user's identity keys.

  Tested by turning it on in a throwaway build. **R8 completed with no errors and no missing-class
  warnings**, and the minified dex still contains every reflective entry point the rules exist to
  keep: `Account`, `GcmCryptoBox`, `Contact`, `PreKeyWithStatus`, libsignal's `IdentityKey`, and
  Jackson's `jsr310` module — the last being the `ServiceLoader` lookup whose absence would silently
  change how an `Instant` is stored rather than failing loudly.

  "R8 ran" is not "the rules worked", which is why the probes matter rather than the exit code. The
  flag stays **false**: this measured that the configuration is sound, not that minification is safe
  to ship, and runtime reflection breakage is precisely what a JVM test suite cannot see.

  One number worth carrying: the minified dex is **1.4 MB against 6.0 MB**. That does not change the
  withdrawn APK-size decision — 42.6 MB of desktop binaries and 64 MB of DWARF dwarf it — but it is
  the third measurement showing how much of that artifact was never the cost of PQXDH.

- **The CI workflow was read, and it is sound — but my own gate could break it.** Sixty-six lines of
  executable config that runs on every push with repository credentials, added by this revival and
  never audited. It holds up well: every action is pinned to a commit SHA rather than a tag, with the
  2025 `tj-actions/changed-files` retag compromise cited as the reason; `permissions: contents: read`
  is least-privilege; `persist-credentials: false`; the trigger is `pull_request` and not
  `pull_request_target`, so untrusted code never runs with write access; and dependency verification
  is deliberately left enforced, because CI is exactly where a substituted artifact should be caught.
  Validated as YAML rather than eyeballed — ten steps, parsed.

  Two corrections. Its comment said the metadata pins "~368 artifacts" and the count is **386**. And
  `verifyReleaseNativesStripped`, which I added two days earlier, finalizes `assembleRelease` — the
  step CI runs without the opt-out flag. **I added a build gate without considering the one place the
  build runs automatically.** Whether that breaks CI depends on whether the runner has an NDK, which
  cannot be tested from here, so the interaction is now documented at that step with the two correct
  responses: make an NDK available, and never pass `-Pkryptey.allowUnstrippedNatives=true` in CI. That
  flag is a statement that an artifact is not for distribution, and CI is the one place it must never
  be made quietly.

- **The Gradle wrapper jar is reproducible from the pinned distribution.** Dependency verification
  pins 386 components by SHA-256, and every one of those checks happens *inside* a build that
  `gradle-wrapper.jar` has already started. That jar is 47 KB of executable code, it is committed to
  this repository, this branch modified it, and nothing covered it — a tampered wrapper could ignore
  `distributionSha256Sum` entirely, since it is the thing that enforces it.

  Closed, offline and decisively: running `gradle wrapper --gradle-version 9.7.1` using the *pinned
  distribution's own* binary emits a jar byte-identical to the committed one —
  `7a9ce74cff467ca1bf60a4fcd9f05185acceda4d0f382434d393e17864262c5d`, 47505 bytes. So the trust chain
  closes on itself: the properties file pins the distribution by hash, and the distribution
  reproduces the jar that fetches it.

  Two weaker checks were run first and are recorded because neither is sufficient. The jar's
  *structure* is clean — 34 entries, only `org/gradle/{cli,wrapper,internal/file,util/internal}`,
  no stray resources or scripts — which rules out obvious padding but not an altered method body.
  And comparing its classes against the distribution's own `gradle-cli` and `gradle-wrapper-*` jars
  reported **31 of 32 differing**, which looks alarming and means nothing: the wrapper embeds a
  trimmed copy, so the same class name is 2316 bytes there against 14075 in the distribution.
  Reporting that as a finding would have been exactly the confident wrong answer this file keeps
  cataloguing.

- **All four CI tasks pass at `e14bf8b`**, re-run because the baseline was generated four commits
  earlier and production changed under it: 965 tests, `assembleDebug`, `assembleRelease` producing
  stripped 9.4 MB and 7.7 MB APKs with the gate passing unaided, and *"Lint found no new issues"*. A
  baseline that stops covering the code is worth less than no baseline, since it turns a real finding
  into a silent one — so the check is which commit it was last run at, not whether it exists.

- **The branch history was replayed against its own doc guards, and holds.** Several tests here read
  markdown and build files, which means a documentation-only commit can turn the suite red — and one
  did: `8bac6f6` added a section without indexing it, and a red test sat on `origin` until an
  unrelated isolation run happened to catch it.

  That raised the obvious question, so it was answered rather than assumed. Both guards were replayed
  over every commit since each landed, by evaluating the rule against each historical revision of the
  files rather than by building 32 commits: the index guard would have failed at **exactly one**
  commit — the known one — and the pinned-count guard at **none** of the eight since it exists.

  So the one red commit was the one already found, and it is fixed. Worth stating because "we caught
  one" and "there was one" are different claims, and only the second is checkable.

- **No test in this suite depends on the date.** A test that passes today and fails in a month, or
  that quietly stops exercising anything as time passes, would undermine every number in this file
  and would be found by nobody until it broke. Checked: every wall-clock use in the tests is a
  *relative* offset from `System.currentTimeMillis()` — `+ 60_000` for "not yet due", `- 1` for
  "overdue" — and there is no absolute date anywhere in the tree. The 30-day rotation and the 2-day
  archive window are exercised by moving the stored deadlines, never by waiting.

  While there, the sharpest historical bug in that area was re-checked by reintroducing it rather
  than by reading its comment. `deleteOlderSignedPreKeysIfNecessary` once compared
  `currentTimeMillis()` (~1.7e12) against `SIGNED_PRE_KEY_ARCHIVE_AGE` (a *duration*, ~1.7e8), so the
  comparison was unconditionally true and retired keys were dropped the instant they were replaced —
  breaking any peer still holding the previous bundle, which is exactly what the archive window
  exists to protect. Putting that line back kills two tests. A comment saying a regression is covered
  is not the same as the regression being covered.

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
  at 10 tests, the instrumentation `@Test` count (11 when audited, 14 since — the count is dated,
  and `InstrumentationTestsCleanUpTheKeystoreTest` now floors it), the 4096-character invite
  threshold, and the
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

**Corrected, and the correction is the interesting part.** The "Open" list said, in an entry written
long before this round, that out-of-band export *already works*: the invite goes to whichever app has
focus, so the user picks the channel by picking the app. That is true — `onTextInput` reaches the
current input connection — and it means the fix above was too pessimistic. There was never a missing
capability. What was missing was the **order of operations**: open the notes app or the email draft
*first*, then tap invite, and the messenger genuinely never sees that first key. Tap it inside the
messenger and it is lost before the user can route anything.

Neither the original wording nor my correction to it said which order to do things in, which is the
whole of the defect. The help now does, and `InviteAdviceMatchesTheFlowTest` changed shape with it:
it used to ban the "never sees" claim outright, which would now suppress a true and useful sentence,
so it requires the instruction beside the claim instead.

Worth recording as a process point rather than only a wording one. Two accurate findings — "the flow
commits the invite to the host app" and "out-of-band export already works" — sat in this document at
the same time, in different sections, contradicting each other's implications, and I acted on one
without reading the other. **The audit that keeps finding stale entries is also how the entries talk
to each other**, and a fix derived from one section needs checking against the rest.

A copy action on the strip would still be an improvement — it would remove the ordering trap
entirely — but it is a convenience now rather than a missing capability, which is a materially
different thing to have on a deferred list.

## Known-deferred defects

**~~The plaintext chat log has no cap, and it is read on every keyboard raise.~~ Partly fixed —
the log is no longer parsed to raise the keyboard, and the remaining cost is now measured and
attributed.** The original entry is kept below because its reasoning was right and its numbers are
still the basis for what follows; what changed is written after it. No size limit, no age
limit; entries go only when a contact is deleted or a send is discarded. Measured at ~358 characters
per message in SharedPreferences: **20,000 messages is 7.16 MB, 194 ms to save and 72 ms to load** on
a desktop JVM — and the load is on the `setInputView` path, so it runs every time the keyboard is
raised, in every app.

Two things make this less alarming and one makes it harder than it looks.

Cost scales with the log, so it is a **tail problem**: parsing is roughly linear, which puts a
thousand-message log near 4 ms and most users nowhere near noticing. That is an extrapolation from the
one measured point, not a second measurement, and it is stated as such. It is also **peer-paced** —
a correspondent can send messages, and each one is kept forever.

The obvious fix is not to cap it, which deletes the user's history, but to stop reading it on the
raise path: it has its own storage key (`UNENCRYPTED_MESSAGES`), so it could load when the message-log
screen is opened. **Checked, and there is a complication:** `LegacyKeyMigration.apply` runs during
`getAccountFromSharedPreferences` and iterates the message list to re-key pre-upgrade entries. A lazy
load either skips that migration or forces the read it was meant to avoid, so the fix needs the
migration moved or made lazy with it — which is exactly the code whose four previous attempts are
documented three sections above.

Left as a decision with its numbers rather than taken: capping loses history, and the lazy load is a
larger change to the one component on this branch with the worst track record for being changed.

**Taken, and the complication was smaller than it looked.** `Account` now holds the log as a loader
rather than as contents, and `StorageHelper` hands it one instead of reading it. The migration
worry was real but narrow: `migrateLegacyKeys` returns at its marker check without touching the log,
so `LegacyKeyMigration` only runs on the *one* load per install that actually migrates. On that load
the read is forced deliberately and the migration is unchanged — which is the point, since it is the
component with the worst record for being changed. Everywhere else, the log is never read.

The write matters as much as the read and the original entry did not say so: a reload is followed by
a write-back, so a raise was paying for a full parse *and* a full re-serialisation. The log is now
omitted from the save batch entirely when nothing has loaded it. That is the dangerous half of the
change — if omitting a key cleared it, one raise would erase the user's history silently — so it
rests on `EncryptedKeyValueStore.putAll` writing the keys it is given and clearing nothing, and on a
test that reloads afterwards and checks the messages are still there. Control: make the save write an
empty list when the log was not loaded, and that test fails.

**Measured, on the same desktop JVM as the original numbers, as load-then-write-back:**

| stored log | before | after |
|---|---|---|
| empty | 36 ms | 36 ms |
| 1,000 messages | 50 ms | 42 ms |
| 20,000 messages | 294 ms | 199 ms |

**So it is a third off at 20,000 and it does not make the cost constant, which is the honest
result.** What the deferral removes is the JSON parse, the AES-GCM open, the re-seal and the
re-serialisation of the log. What remains — 163 ms of the 199 ms at 20,000, against a 36 ms empty-log
baseline — is the `SharedPreferences` file itself: the log shares one XML file with everything else,
that file is read whole on first access, and **any** `commit()` rewrites it whole regardless of which
keys changed. No amount of laziness at the JSON layer reaches that.

**What is still open, now with a specific shape rather than a vague one:** the log needs its own
store file, so that raising the keyboard neither reads nor rewrites 5.35 MB of message history. That
is a storage-layout change rather than a lifecycle one, and it is separable from everything above.
The cap-versus-keep question is untouched by any of this — the log still grows forever, still
peer-paced.


**~~The CI workflow's Lint step has never passed.~~ Fixed in `5fb0a91`** — Temurin 21 plus a lint
baseline, after which all four CI tasks pass and lint reports "no new issues". The finding is kept
because how it was deferred and then undeferred is the useful part. The workflow runs four Gradle
tasks. Three were exercised constantly here; `lintDebug` had never been run by anyone, and it failed
in two different ways depending on the JDK.

- **On Temurin 17, the toolchain the image and the workflow both declare, lint crashes.**
  `BidirectionalTextDetector` calls `List.removeLast()`, which is Java 21 API, so lint aborts with
  "this is a bug in lint or one of the libraries it depends on" and produces no analysis at all. Note
  which detector: bidirectional-text spoofing is a class this app cares about specifically, so the
  suggested workaround — disable that detector — costs a check that matters here.
- **On Temurin 21 lint runs correctly, and reports 100 errors and 503 warnings.** Measured by
  building the image and running it. Almost all are inherited AOSP issues (`MissingSuperCall` and
  similar). The full test suite also passes on 21 — 958 tests, 0 failures — so the toolchain move is
  viable on its own terms.

So the step cannot pass today on either JDK: on 17 the tool breaks, on 21 the code does. **Not fixed
here, because the remaining choice is a project decision with real costs on each side:** accept a lint
baseline (which is the standard mechanism and gates *new* problems, but freezes 100 unreviewed
findings as approved), fix the inherited errors, or drop the lint gate from CI and stop claiming it.
Each is defensible; none should be chosen by whoever happens to notice the red build.

**One measurement makes that choice much easier, and it was worth taking before deferring.** Of the
100 errors, **none are in code this revival wrote.** Seventy-seven are in files the branch never
touched; the other 23 are all `MissingTranslation` in `strings.xml`, and every flagged string exists
in `master` — the help text this branch rewrote is `translatable="false"` and is not among them.

So a baseline would grandfather exactly the state the project was already in, and nothing this work
introduced. That was the objection to it — freezing unreviewed findings as approved — and it does not
apply. The remaining cost is honest and much smaller: lint would then gate *new* problems only, and
the 100 inherited ones stay recorded rather than fixed.

Recording the measurement was what made the decision easy, and it was then taken rather than left:
both objections — that 21 might not work, and that a baseline would freeze unreviewed findings — were
checked and neither survived. **Deferring is only honest while the thing that makes a decision hard is
still unmeasured.**

The reason it went unnoticed is worth keeping: three of the four CI steps are run here every day, and
the fourth had never been run once — a gate nobody executes is indistinguishable from a gate that
passes.


**~~Nine durable writes are still asserted by nothing.~~ All closed** — see the disposition table at
the end of this entry, which was added later and contradicted this headline for two days. A reader
skimming headlines got the opposite of the truth, which is the risk this list carries by construction:
it is appended to, and its headlines outlive the work they describe. A review round swept all 18
`storeAllAccountInformationInSharedPreferences()` call sites in `SignalProtocolMain`, deleting one per
run: **13 survived**. `reloadAccount` runs on every `setInputView`, so a decision that never reaches
disk is a decision the next theme change undoes — and the method returns `void` and swallows
`StorageCryptoException`, so no caller can tell a completed write from a lost one.

Four are now closed: `rejectContactKey` and `dismissIdentityChange` (a rejection and a dismissal must
still be in force after the next raise), `encrypt` (a sent message and the ratchet it advanced), and
`decrypt`'s `recordIdentityChange` arm — the incoming-`PreKeySignalMessage` substitution path, whose
*sibling* was pinned while it was not. That last one strips the verified badge as well as recording
the change, so losing the write puts a green tick back on a contact whose key the app has just
refused.

**Two of those thirteen were not gaps at all, and finding out changed what the list means.**
`processPreKeyResponse` and `buildSession`'s success arm both wrote the account, for the same fact,
one immediately after the other — and the second is reachable only when the first has run, because a
failed build returns above it. So each mutant was immortal: delete either and nothing observable
changes. A sweep reports that as *two surviving guards*, when neither was guarding anything alone.

Measured rather than assumed: `PinnedIdentitySurvivesTheNextRaiseTest` passes with either one
deleted and fails with both, which is what a duplicated write looks like from the outside. The
redundant one is now gone and the remaining one is killable — the same reasoning that declined a
catch-all `RuntimeException` net on the decrypt listener, applied to a write instead of a catch.

The property itself was worth pinning regardless. Trust-on-first-use is only "first" if the app
remembers the first: without that write the store has no identity for the address after a reload, so
the *next* bundle is a clean first sighting and is accepted in silence — no warning, because nothing
was displaced. The attacker never has to defeat the pin, only arrive after it was forgotten.

`createAndAddContactToList` is now closed too, and closing it found the same trap a second time. The
first version of the test pinned a bundle immediately after adding the contact — the realistic-looking
sequence — and the persist inside *that* path wrote the contact as well, so deleting the contact's own
write changed nothing and the mutant survived. Adding a contact **on its own** isolates the write, and
it dies. **Three duplicated writes have now been found by trying to kill one**, which is the argument
for the technique in one line: a mutant that survives is at least as often a redundancy as a gap, and
you cannot tell which by reading.

### What the thirteen survivors turned out to be

The raw number was read as "thirteen unguarded durable writes". Working through them, it is four
different things, and only one of them is a coverage gap:

| disposition | sites | what it means |
|---|---|---|
| **genuine gap, now closed** | `rejectContactKey`, `dismissIdentityChange`, `encrypt`, `decrypt`'s `recordIdentityChange` arm, `buildSession`'s success arm, `createAndAddContactToList` | a fact that reached memory and not disk, with a test that now fails if the write goes |
| **duplicated write** | `processPreKeyResponse` (removed) | two writes for one fact; each deletion unobservable, so a sweep counts two survivors where neither guards anything alone |
| **acknowledged equivalent mutant** | `reloadAccount`'s write-back | the inner method guards null itself, so inverting the outer guard changes nothing — verified, not assumed, and kept because the inner guard lives in a different method and a refactor could remove it |
| **genuine gap, closed on the second attempt** | `decrypt`'s final persist | see below |

Two of those four categories are *not* defects, and one is the opposite of one — a redundancy that
made the sweep's own output misleading. **A survivor count is not a coverage figure**, which is the
same caveat recorded against the 29-guard sweep and is now demonstrated rather than argued.

The remainder of the thirteen were not individually examined, and that is stated rather than papered
over: the six closed above were chosen by consequence, not by working down a list.

**Nothing on this list is open any more, and how the last one closed is the useful part.** The first
attempt at `decrypt`'s final persist failed three times and was recorded as blocked on the harness.
It was not. The scenario was too big: peer sends, victim decrypts, victim replies, peer reads the
reply — four state swaps through one singleton and one `SharedPreferences`, and every failure came
from that machinery rather than from the code. One was libsignal being right and me being wrong.

The version that works asks the smallest question that still bites: one message, one reload, is it in
the log? It passes, and deleting the write fails it. The property never needed the round trip — the
round trip was me testing the ratchet as well as the persist, in a harness that could not carry both.

Worth keeping because the instinct it corrects is a common one: when a test will not go green, the
scenario is usually the thing to shrink, not the harness to fight. Recorded as blocked was the honest
call with what I had; it was still the wrong diagnosis. The receive-side one was
attempted and left undone deliberately — the obstacle is the harness, not the property. This suite
drives two "devices" through one singleton and one `SharedPreferences`, swapping the storage helper in
and out, and each attempt to make the peer send twice hit an artefact of that arrangement rather than
anything about the code. One was instructive: with no reply, libsignal correctly keeps sending
`PreKeySignalMessage`s against a one-time pre-key the receiver has already consumed, so the failure
was right and the *scenario* was wrong. A test that passes because its scenario was bent until it did
is worth less than none. Each needs the same shape of test — do the thing, reload, assert it is still
true — and they are listed rather than batch-fixed because a sweep that closes nine at once tends to
produce nine tests that all pass for the same reason.

## Phase 3's decoder, swept and clean

The earlier Phase 3 sweep covered `BinaryEnvelope`; it did not reach the code that parses the
attacker-supplied *text* around it. Five more guards, disarmed one at a time against the full suite:

| guard removed | result |
|---|---|
| decompression-bomb budget removed | killed (4 tests) |
| truncated stream accepted as a prefix | killed (1) |
| non-bit characters accepted in the bit string | killed (2) |
| partial byte accepted (length not a multiple of 8) | killed (2) |
| empty bit string decoded as zero bytes | killed (2) |

Nothing survived.

## Phase 1's crypto box, swept and clean

The last unswept area, and the one everything at rest depends on:

| guard removed | result |
|---|---|
| AAD not bound in on `seal` | killed (43 tests) |
| AAD not checked on `open` | killed (43) |
| provider nonce length unchecked | killed (2) |
| short envelope accepted | killed (2) |
| any storage envelope version accepted | killed (3) |

Nothing survived. The two 43s are the ones to read: the AAD is what binds a sealed blob to the key it
was stored under, so dropping it fails a twentieth of the suite in each direction.

## Where the sweep programme ends

**36 guards, eight areas, one survivor.**

| area | guards | survivors |
|---|---|---|
| Phase 1 — crypto box | 5 | 0 |
| Phase 1 — encrypted store (markers, migration) | 4 | 0 |
| Phase 1 — storage helper (load, migration gating) | 3 | 0 |
| Phase 2 — one-time pre-key handling | 2 | 1 (`removePreKey`, judged redundant rather than a defect) |
| Phase 3 — wire parser | 6 | 0 |
| Phase 3 — decoder | 5 | 0 |
| Phase 4 — trust predicates | 6 | 0 |
| Phase 4 — redirect seam, window and input guards | 7 | 1 (`refreshOpeningMessage`, fixed) |

*This table said "29 guards, six areas" for a day after two more areas had been swept and written up
in prose three sections earlier.* Recorded rather than silently corrected: it is the same drift this
document keeps finding elsewhere, in a table I wrote, about the accuracy of my own measurements —
and the reason the counts in this file are dated and the tests, not the prose, are the authority.

Two qualifications, because the number is worth less than what it does not cover. **Mutation measures
the guards you think to disarm** — every real defect on this branch was found by a reviewer asking a
question nobody had asked, not by this sweep, and the sweep's own survivor count would have been zero
if I had not thought to mutate `refreshOpeningMessage`. And **five separate times the harness returned
a confident wrong answer**, each recorded above. The programme is worth its cost as evidence that the
guards which exist are pinned; it is not evidence that the right guards exist.


**An invite stops working once ~50 later invites have been published.** Every bundle allocates its
own one-time pre-key id — deliberately, because the allocator used to hard-code id 1 and regenerate
*in place*, so handing out a second invite destroyed the material the first invitee already held.
Consumed records are then retained only to `USED_PRE_KEY_RETENTION` (50), which means "each bundle
gets its own id" holds until enough later bundles push an earlier one out of retention.

Measured, not reasoned: with 10 later invites published, an invite handed out earlier still opens;
past the retention bound it does not, and the invitee's first message can never be read by the
sender. `AbandonedInviteRetentionTest` pins both sides, and the control confirms it distinguishes
them — set below the bound, the "pruned away" test fails because the message decrypts.

This is reachable without anyone abandoning anything deliberately: an invite that fails its length
check has already allocated its id, and there is no rollback. That is the right call rather than a
defect — rolling the id back means handing the same number out twice with different material, which
is the hazard the per-bundle design exists to prevent, and the app cannot know whether the bundle
escaped. Retention has to be bounded too; the store cannot grow forever. So the line exists on
purpose, and what was missing was knowing where it is.

Not fixed here, and the fix is a product decision rather than a correction: either raise retention,
or tell a user whose invite has aged out that the invite is stale instead of letting the invitee send
a message nobody can read. The app's standard decryption-failure advice — delete and re-invite — is
for once exactly right in this case, which is why it is deferred rather than urgent.


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
  caller — only the instrumentation tests, which run on an emulator rather than in this task — so a
  user whose Keystore key is
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
