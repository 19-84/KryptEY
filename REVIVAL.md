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

**Tests: 31 → 843 at that point** (**4 skipped**, not the 1 this line
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

One hundred and twenty-eight sections, written in the order things were found rather than by subject, so the
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
- [Three defects in three fixes, again](#three-defects-in-three-fixes-again)
- [A displacer that is re-derived in the same pass](#a-displacer-that-is-re-derived-in-the-same-pass)
- [The one notice a later write does not settle](#the-one-notice-a-later-write-does-not-settle)
- [What the fix for the false permission then deleted](#what-the-fix-for-the-false-permission-then-deleted)
- [The carrier, attacked and found sound, with four things worth fixing anyway](#the-carrier-attacked-and-found-sound-with-four-things-worth-fixing-anyway)
- [The number on screen and the key the button acts on](#the-number-on-screen-and-the-key-the-button-acts-on)
- [A refusal and the sentence that justifies it are one fact](#a-refusal-and-the-sentence-that-justifies-it-are-one-fact)
- [A control on a slot the adversary can overwrite is not a control](#a-control-on-a-slot-the-adversary-can-overwrite-is-not-a-control)
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
- [A field libsignal lets you omit, and this app never sends](#a-field-libsignal-lets-you-omit-and-this-app-never-sends)
- [Two arms pin, and the fix asked only one](#two-arms-pin-and-the-fix-asked-only-one)
- [A refused invite that looked exactly like an accepted one](#a-refused-invite-that-looked-exactly-like-an-accepted-one)
- [The refusal that switched off the only substitution detector](#the-refusal-that-switched-off-the-only-substitution-detector)
- [A record that meant less than four messages claimed](#a-record-that-meant-less-than-four-messages-claimed)
- [The warning weakened to protect a message, and the erase that bought](#the-warning-weakened-to-protect-a-message-and-the-erase-that-bought)
- [One invariant, swept instead of exampled](#one-invariant-swept-instead-of-exampled)
- [A corrupted chat log was an amplifier for key substitution](#a-corrupted-chat-log-was-an-amplifier-for-key-substitution)
- [Two storage findings that make a recorded residual wrong](#two-storage-findings-that-make-a-recorded-residual-wrong)
- [The laundering primitive, closed from outside the file](#the-laundering-primitive-closed-from-outside-the-file)
- [A fix that was true of the store and false of the app](#a-fix-that-was-true-of-the-store-and-false-of-the-app)
- [FLAG_SECURE was down on the route users actually take](#flag_secure-was-down-on-the-route-users-actually-take)
- [A writer on the right of an &&](#a-writer-on-the-right-of-an-)
- [The first clean round in eleven, and a guard for the class](#the-first-clean-round-in-eleven-and-a-guard-for-the-class)
- [The canonicality check, the fix that was not one, and what is actually true](#the-canonicality-check-the-fix-that-was-not-one-and-what-is-actually-true)
- [Sweeping for the class instead of the bug, and what it found](#sweeping-for-the-class-instead-of-the-bug-and-what-it-found)
- [A gate that changed the verb and nothing else](#a-gate-that-changed-the-verb-and-nothing-else)
- [Three informational lines the user never sees](#three-informational-lines-the-user-never-sees)
- [Text crosses into another application, and three writes that could not fail](#text-crosses-into-another-application-and-three-writes-that-could-not-fail)
- [Ciphertext crosses the process boundary, measured](#ciphertext-crosses-the-process-boundary-measured)
- [A guard for the third instance of the discarded answer](#a-guard-for-the-third-instance-of-the-discarded-answer)
- [The cross-app tests, and what they cost to make honest](#the-cross-app-tests-and-what-they-cost-to-make-honest)
- [A green test that could not go red](#a-green-test-that-could-not-go-red)
- [An unreadable history is not an empty one](#an-unreadable-history-is-not-an-empty-one)
- [The round that fixed a sentence by breaking its opposite](#the-round-that-fixed-a-sentence-by-breaking-its-opposite)
- [The inviter's side pinned a key and said nothing](#the-inviters-side-pinned-a-key-and-said-nothing)
- [The true thing faded and the false thing stayed](#the-true-thing-faded-and-the-false-thing-stayed)
- [A claim about an event, made without checking the event](#a-claim-about-an-event-made-without-checking-the-event)
- [The fail-open that made every fixture mean two things](#the-fail-open-that-made-every-fixture-mean-two-things)
- [The refusal that locked the keyboard](#the-refusal-that-locked-the-keyboard)
- [Stating the rule instead of fixing the case](#stating-the-rule-instead-of-fixing-the-case)
- [The refusal the adversary could switch off](#the-refusal-the-adversary-could-switch-off)
- [A deletion that undid itself everywhere but on disk](#a-deletion-that-undid-itself-everywhere-but-on-disk)
- [Four of my own fixes, undone](#four-of-my-own-fixes-undone)
- [Keeping a fact where nothing else owns it](#keeping-a-fact-where-nothing-else-owns-it)
- [Two mutants that survived, and what they were hiding](#two-mutants-that-survived-and-what-they-were-hiding)
- [The file the deletion did not check](#the-file-the-deletion-did-not-check)
- [A decision, rather than a third flip](#a-decision-rather-than-a-third-flip)
- [Comparing the arms instead of remembering them](#comparing-the-arms-instead-of-remembering-them)
- [One sentence doing four jobs](#one-sentence-doing-four-jobs)
- [A row with no key, and the arm that filled it](#a-row-with-no-key-and-the-arm-that-filled-it)
- [Two facts, one slot, again](#two-facts-one-slot-again)
- [A bound that counted what the app does not distinguish](#a-bound-that-counted-what-the-app-does-not-distinguish)
- [A sentence with no caller](#a-sentence-with-no-caller)
- [The advice that must never be given for a storage error](#the-advice-that-must-never-be-given-for-a-storage-error)
- [A method with no callers, and an invite dead on arrival](#a-method-with-no-callers-and-an-invite-dead-on-arrival)
- [One warning slot, and what fits in it](#one-warning-slot-and-what-fits-in-it)
- [A sentence that travelled further than its meaning](#a-sentence-that-travelled-further-than-its-meaning)
- [An invariant that was written down and false](#an-invariant-that-was-written-down-and-false)
- [A warning nobody can answer](#a-warning-nobody-can-answer)
- [Three call sites, three answers to one question](#three-call-sites-three-answers-to-one-question)
- [The failure the app had no words for](#the-failure-the-app-had-no-words-for)
- [A displaced warning is one that comes back](#a-displaced-warning-is-one-that-comes-back)
- [Twenty-eight seams and no way in](#twenty-eight-seams-and-no-way-in)
- [Nine Errors nobody could catch](#nine-errors-nobody-could-catch)
- [One slot, two facts, four defects](#one-slot-two-facts-four-defects)
- [A warning raised from a condition has to come down with it](#a-warning-raised-from-a-condition-has-to-come-down-with-it)
- [The warning that needed a way to be answered](#the-warning-that-needed-a-way-to-be-answered)
- [Conditions and events, decided once](#conditions-and-events-decided-once)
- [A fix that could not run, and a test that could not tell](#a-fix-that-could-not-run-and-a-test-that-could-not-tell)
- [Two questions, one list](#two-questions-one-list)
- [The clause the argument rested on, and did not mention](#the-clause-the-argument-rested-on-and-did-not-mention)
- [Once per process is not "every time the keyboard comes up"](#once-per-process-is-not-every-time-the-keyboard-comes-up)
- [A control on one branch is not a control](#a-control-on-one-branch-is-not-a-control)
- [A return value that meant something else](#a-return-value-that-meant-something-else)
- [Two attacks that would work if the library were built differently](#two-attacks-that-would-work-if-the-library-were-built-differently)
- [Newest first, when it meant oldest first](#newest-first-when-it-meant-oldest-first)
- [Fields that were never issued together](#fields-that-were-never-issued-together)
- [What the signature does not close](#what-the-signature-does-not-close)
- [Eighty-four characters, and where they landed](#eighty-four-characters-and-where-they-landed)
- [The half of the deletion that did happen](#the-half-of-the-deletion-that-did-happen)
- [Asked once, and the answer written down](#asked-once-and-the-answer-written-down)
- [Cannot, and cannot right now](#cannot-and-cannot-right-now)
- [What the shipped thing actually contains](#what-the-shipped-thing-actually-contains)
- [Three fields that were offering their text away](#three-fields-that-were-offering-their-text-away)
- [A line that was doing security work without saying so](#a-line-that-was-doing-security-work-without-saying-so)
- [A hole that was not there](#a-hole-that-was-not-there)
- [The instruction that vanished when it was needed](#the-instruction-that-vanished-when-it-was-needed)
- [Nothing was testing the number itself](#nothing-was-testing-the-number-itself)
- [The tag fell off the bottom of the list](#the-tag-fell-off-the-bottom-of-the-list)
- [The one thing a list actually does](#the-one-thing-a-list-actually-does)
- [Which kind of hollow](#which-kind-of-hollow)
- [Three states called two, and a response that cleared the wrong warning](#three-states-called-two-and-a-response-that-cleared-the-wrong-warning)
- [The one structural lesson from the review rounds](#the-one-structural-lesson-from-the-review-rounds)

**How this document, and its tests, have been wrong**

- [The comment-drift problem, and why it has no test](#the-comment-drift-problem-and-why-it-has-no-test)
- [The way the harness lied six times, and how it stopped](#the-way-the-harness-lied-six-times-and-how-it-stopped)
- [A mutant was committed and pushed](#a-mutant-was-committed-and-pushed)
- [Checked this round and clean](#checked-this-round-and-clean)
- [A test that could not reach its own branch, and a device test in the wrong state](#a-test-that-could-not-reach-its-own-branch-and-a-device-test-in-the-wrong-state)

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

**Settled by the user (2026-08-26): mixed-version compatibility is not a requirement.** So the
address change above is accepted rather than a cost to be managed, and nothing needs to be built to
soften it.

It is worth being exact about what that does *not* license removing, because the two are easy to
confuse and one of them is a crash. Talking to a 0.1.5 peer is compatibility. Surviving a message
that *claims* to be from one is not — a device id outside libsignal's `[1,127]` range makes the raw
address constructor throw `IllegalArgumentException`, nothing on the clipboard-decrypt path catches
unchecked exceptions, and the keyboard dies in whatever app the user is typing in. An attacker can
put any integer in that field. `LegacyPeerInteropTest` guards the paths that consume a peer-supplied
device id, and it stays for that reason, not for compatibility. The same applies to `ProtocolAddresses`
folding and `LegacyKeyMigration`: those are about the user's own store surviving an upgrade, which
has nothing to do with who they are talking to.

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

- 701 JVM tests at that point, including an end-to-end conversation across all four phases and a
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
    them. Excluded once measured: arm64 115 → 74 MB, armeabi-v7a 109 → 68 MB, on every build host.
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
   which is the false assurance the design deliberately removed.

   **The wording is written.** The help now has a section saying the capability exists, that it
   matters most for the FIRST invite — the one moment the app cannot check anything, because there
   is no earlier key to compare against — and, in as many words, that **this app cannot tell which
   route an invite took**. That last sentence is the one under test:
   `OutOfBandHelpClaimsNothingUnsupportedTest` requires it to be present and refuses a list of
   phrasings an author reaching for encouragement would write ("trusted automatically", "no need to
   compare"). It also requires the text to keep pointing at the voice comparison, because avoiding
   one exposure is not a replacement for the check. QR would still be a dependency decision (ZXing);
   string transfer needs none, and needed no new mechanism either. *Not* on this list any more: UI for accepting an identity change. That is
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
   why it is being rewritten rather than patched. What was true at that point:

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

**The strip's own UI now runs on a device.** Everything exercising the strip was Robolectric, which
is the right place for its logic and cannot speak for its behaviour on a phone: shadow views, no
layout pass, and the desktop build of libsignal. The instrumentation suite covered the Keystore, the
IME binding and the protocol, and had never touched the strip.

`StripRoundTripOnDeviceTest` puts the halves together — a real inflated strip, real Android views,
the Android libsignal, and the button a user presses. Type a message, press Encrypt, and assert the
two things the app exists for: what leaves the keyboard is not the message, and the intended
recipient gets it back.

Getting a control for it took three attempts, and the first two are the interesting part. The first
raced: `tools/test-on-emulator` rebuilds the APKs, and the source was restored while it was still
building, so it tested unmutated code. The second was **semantically equivalent** — it sent
`mInputEditText.getText()` instead of the ciphertext, but `setText(encryptedMessage)` runs on the
line above, so the field already held the ciphertext and the mutant changed nothing. Only the third,
capturing the plaintext before that `setText`, actually made the plaintext leave the keyboard, and
it fails on exactly the assertion that matters. An equivalent mutant looks identical to a passing
control from the outside.

Still not covered, and worth saying plainly: nothing types on the IME's own key surface, and nothing
moves text through a real messenger.

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

Last cold verification: `clean testDebugUnitTest` from an empty Gradle volume, with
verification ON — BUILD SUCCESSFUL in 6m, 39 of 41 tasks executed, **894 tests green from scratch**
(0 failures, 4 skipped) rather than warm. `assembleRelease` was run cold too, with
`-Pkryptey.allowUnstrippedNatives=true`, so the new packaging exclusions and the strip gate are both
exercised from nothing.

**Run because the trigger fired, not on a schedule.** The tick before this one deliberately skipped a
cold run and said why: no build-input file had changed in nineteen commits, so it would have
confirmed habit. Since then `app/build.gradle` gained 93 lines — the desktop-native exclusions, a
task input declaration, and `verifyReleaseNativesStripped` — which is exactly the case a warm cache
speaks for least, because it changes what the build has to *do* rather than only what it runs. The
previous cold run was triggered the same way by two commits that changed the build's
work rather than its inputs.
The run before it covered `clean assembleDebug` the same way (39 of 39 tasks executed, zero
verification failures across all 386 pinned components).

**A cold cache is not a fresh clone, and until now only the first had been tested.** Every cold run
on this branch mounts the working tree — the same directory, with its build outputs and anything
untracked or ignored sitting in it. Such a run proves the *dependency* story (an empty Gradle volume,
verification on, 386 components fetched and checked) and says nothing about whether the tracked
content is sufficient. A build quietly depending on an ignored file would pass every one of them.

Tested when the strip gate was written, and again once the build image gained an NDK and the gate was
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

## Three defects in three fixes, again

The round after the last three commits found one defect in each of them. Recorded together because
the pattern is now the most reliable thing this file records: a fix lands, and the next round finds
what it opened.

**The write-based exit retired a notice on a write that did not settle it.** The argument for
reclassifying a failed rejection was that `rejectContactKey` leaves the decision in memory, so the
next landed write persists it. That holds until the in-memory account is replaced — and
`reloadAccount` does exactly that on a host-forceable theme change, restoring the pinned key and
emptying the rejected set. Its write-back is deliberately *not* counted, so the reload is invisible
to the counter, and `adoptState` carries the caution and its captured count across the same rebuild.
The caution therefore survived the event that made it permanently true and was retired by the next
unrelated write — removing the only durable sentence saying the refused key had come back, exactly
when it had. The exit now asks the decision rather than the counter: if the caution names an address
whose rejection is no longer held, nothing was persisted and the sentence stays. When the recipient
is gone it cannot ask, and keeps the sentence — which is load-bearing rather than cautious, because
the recipient is deliberately not carried across the rebuild that causes this.

**The retirement repainted the banner unguarded.** Both siblings guard that repaint; this one copied
the store notice's count and not its repaint. `warningWithRecipient` returns null when nothing stands
and no contact is chosen, so a bare `setText` painted the banner *blank* — and an empty banner
matches no prefix in `disablesActionButtons`. The no-recipient case is not a corner: the Invite path
posts its caution with a null contact, and giving that caution an exit is what the retirement was
added for.

**The composed warning was read by the wrong rule.** Composing put the shared-name text in front of
the refusal, so the stored string starts with the shared-name warning's opening words — which is
exactly what `standingWarningIsAboutAsharedName` tests. Its lowering branch then read a composed
warning as its own and cleared the whole thing, refusal included. `standingWarningIsAboutAsharedName`'s
own javadoc names this hazard: *two readers disagreeing about which warning is standing is how a
warning gets silenced by the wrong rule.* Composing created the disagreement. The fold ending now
*reduces* the composed warning to the refusal alone rather than clearing it, which is what the
condition ending actually means.

**And the test for that one had to change route to see it.** Written against `selectContact`, it
passed with the fix reverted — because that path re-derives the refusal one writer later, so the loss
is invisible there. The reachable route is a recipient moved by an arriving message, which re-derives
only the shared-name warning. The mutant is what said so.

---

## A displacer that is re-derived in the same pass

The banner holds one warning and the writers displace each other. That is survivable because every
one of them can be worked out again — and this branch spent several rounds making the last hold-out,
the invite refusal, recomputable. Making it recomputable is what broke the argument.

`selectContact` re-derives four warnings in reverse severity and the last writer wins. The refusal
sits second, above a warning derived from the contact list and below a rejection or a detected
substitution. So for any row that has **both** a folded name and a refusal record, the duplicate-name
warning was recomputed and immediately overwritten — on every selection, for the life of the record.
Recomputed and never rendered is not a displacement; it is a permanent loss, and it lands on the one
control covering the case the pin cannot: two rows the user cannot tell apart. The attacker's cost is
one stripped one-time pre-key on a message relayed from that address, plus the user pressing Decrypt
once.

The argument had been checked against a one-shot displacer, which is a different property.
`thesharedNameWarningComesBackAfterBeingDisplaced` displaces with `setWarningMessageForTest` — not
hollow, and blind to this. Production was the mutant.

**Reordering only moves the loss.** The refusal is second of four precisely because being below the
shared name reduced it to a three-second toast for exactly these contacts; that is recorded two
sections up as a defect that was found and fixed. So both are said. They are about the same row and
the same tap, the slot is not widened, and the composition is idempotent — the next selection
re-derives the shared name first and composes again to the same string.

**Scoped to one subject.** `warnIfNameIsShared` lowers its warning only for the contact it is about,
so a shared-name warning about one row stays standing while a different row is selected. Composing
without checking the subject would paint "you already have a contact called Bob Jones, and this is a
different one" directly above a refusal about Carol, as one warning about one tap. Two people merged
into a single sentence, on the surface whose whole job is to be believed, is worse than either
warning being lost. Three tests; overwriting turns one red, composing across subjects turns another
red, and the third holds the composition idempotent so the banner cannot grow a copy per tap.

**And it must not make Reject look like the answer.** The refusal stands at an address with nothing
pinned — that is what the sentence says — so Verify is dark for want of a number, and the escape
hatch lit Reject because an addressed warning stood. The one live control on the screen then looked
like the response. It is not: the warning is about a bundle altered in transit, its own advice is
"ask them to send another", and `rejectContactKey` marks the address whether or not anything was
pinned, permanently. The peer's next *genuine* invite would be met with "you told this app not to
trust keys arriving for them", which is untrue, and their row could not show verified until a fresh
comparison cleared it. This file already withdrew Reject from the storage caution for exactly those
two outcomes; the refusal became reachable in that cell only when it was made recomputable, because
before that the next repaint removed it. Not a dead end: the response it asks for is out of band, and
the in-app one is deleting the row, which clears the warning and the record together — the same exit
the storage caution was left with.

**And the cap's stated reason was false.** It said the addresses need not be contacts, so a relay
could flood the map. A reviewer showed both insertion sites record for a row that exists at the
moment of insertion, so the map is self-bounded by the contact list and nothing can fill it. The cap
stays — welding a map's size to an invariant living in three other dispatch methods is how a guard
becomes wrong later — but it is now set well above any plausible contact list and described as what
it is: the sole eviction mechanism on a map nothing can flood.

---

## The one notice a later write does not settle

Every member of the `*_NOT_SAVED` family reports a write that did not land, and every one of them is
ended by a later write that does, or by simply retrying the operation. `INFO_DELETE_NOT_SAVED` is
not like that. It reports that a contact the user tried to remove is **still on disk**, with their
pinned key and their plaintext, and that the next raise brings all three back. Nothing a later write
does settles that.

`retireTheStorageCautionFor` already knew: it checks `mStandingStorageCautionIsAboutAdeletion` and
refuses. Two paths beside it did not.

**`clearStorageCautionIfAbout` cleared it.** Reached from the Verify and Reject listeners, both
gated on the response reaching disk — so the route needs the storage trouble to end first, which is
ordinary. The user frees space, then rejects the key of the contact they just failed to delete,
which is a plausible next move for somebody who wanted them gone. The sentence saying the deletion
had not happened went with it, and after that the screen reads like an ordinary success. The exit
that keeps this from being the "notice nobody can clear" dead end this file has closed twice is
narrower and correct: a deletion that actually lands.

**And `postStorageCaution` overwrote it, flag and all.** The slot is single. One relayed message
carrying a bundle is enough — the rotation's own write fails during the same episode, posts "a key
update could not be saved", and the deletion sentence is gone. Worse in a second step: the
replacement *is* settled by a later landed write, so the next successful write clears that too. A
lesser storage caution now yields to a standing deletion notice.

**And there was a second notice of that kind, with a worse consequence.**
`INFO_REJECTION_NOT_SAVED` was the last member of the family with only a toast. What it costs is now
measured rather than described — `rejectContactKey`'s own comment stated it, and a reviewer flagged
that nobody had checked. `ArejectionThatDidNotLandIsForgottenTest` runs both halves: after a
rejection whose write fails, a reload brings the refused key back **pinned**, and the address comes
back **unmarked**, so the next bundle there is a clean first sighting and nothing warns. That is the
silent trust-on-first-use `markKeyRejected` exists to prevent, reached from the other side. The user
performed this app's strongest deliberate refusal, was told for three and a half seconds that it had
not stuck, and had no way to check afterwards. It now says so on the surface that lasts.

**And the kind it was first given was the wrong one, which produced two defects at once.** A
reviewer found both. Classified as a second *protected* kind, the rejection notice collided with the
deletion notice in the single slot — neither yields to the other, so a failed rejection during the
same disk-full episode overwrote "that contact was not removed", and nothing re-raises it because
`removeContact` posts only on a fresh failed attempt. And it stranded itself: rejecting removes the
pin from memory, so that contact's verify screen has no number, both its buttons go dark, and
"reject again" — the exit the protection demanded — could not be reached. My own comment claimed the
exit was reachable "because the contact is still in the list"; the contact being in the list is not
what gates Reject, the pin is, and the raise itself removed it.

The classification was wrong because the two failures look alike and are not, and the difference is
measurable. `removeContact` rolls the row, its messages and its session back into memory when the
write fails, so a later write persists the *restored* contact — nothing a later write does completes
the deletion. `rejectContactKey` has no rollback: the state the user asked for is already in memory
and only the write failed, so the next landed account write puts it on disk.
`ArejectionThatDidNotLandIsForgottenTest` measures that directly. A failed rejection is therefore
settled by a later landed write like the rest of the family, and yields to the one notice that is
not.

Which exposed something the family had been missing all along: an ordinary storage caution had no
write-based exit. The only thing that retired one was the refusal sweep, which acts only on an
address that also has a not-on-disk entry — so a caution raised without one could be ended only by
acting on the contact it names, and after a failed rejection those controls are gone. It now retires
when the account-write count advances past the value recorded when it went up, which is the same
idiom the store notice already used.

**And the durable sentence now asks what the toast beside it always asked.** Pressing Reject at an
address already marked on disk changes nothing, so "it will not be remembered the next time the
keyboard opens" would be false. Reachable through the escape hatch, which needs a standing warning
and no pin. A sentence the user can catch out is one they stop believing, and this one is durable
and hard to clear.

**Which forced the flag to become a kind.** A boolean was enough while only the deletion needed
protecting. With two, the escape that lets a landed deletion end its own notice would also let a
landed *rejection* end it — and a landed rejection says nothing about whether a contact was removed;
the row, its key and its plaintext are all still there. `mStandingStorageCautionKind` carries which
of the two it is, so each is ended by exactly the event that makes it false, and everything else in
the family keeps the ordinary "settled by a later landed write" rule. Collapsing the kinds back into
one flag turns two tests red.

Three tests for the deletion. The mutant for the second guard survived twice before it landed, and both reasons are
worth keeping. The first attempt drove Verify and Reject while writes were still failing, where both
listeners' clears are gated off — a route production cannot take. The second reached the clear and
still passed, because `clearStorageCautionIfAbout` nulls the **field** and nothing on that path
repaints: the banner still held the old string. That is this file's own rule with the two swapped —
assert against the rendered view, not the text that produces it — and here the rendered view was the
stale one, so the assertion had to come after a real repaint. A mutant caught both.

---

## What the fix for the false permission then deleted

Two review agents attacked the previous entries and found four things. Three were defects in the
fixes themselves, which is the pattern this file keeps recording: the round that closes something
opens the next one.

**The recovery raise deleted the one sentence that was still true.** Retiring the storage caution
alongside the refusal is right when the refusal is settled by a later landed write. It is wrong on
the other arm. A contact added while writes are refused exists in memory only; the raise that
recovers the store adopts the stored account and discards it — deliberately, and it has to, because
the in-memory list is the empty substitution plus whatever was added on top. So the moment the row
vanishes is precisely the moment its caution becomes the only true sentence on screen, and the sweep
retired it there, dropped the refusal, and lit Encrypt. The user saw a healthy banner at the instant
their session's work was thrown away.

The refusal is now kept for **the contact the user is standing on**, and only that one. Keeping it
for every vanished row would be an entry no user action can clear — a vanished contact is
unselectable and undeletable — which is the dead end this file has closed twice. Choosing anybody
else drops it, and adding a contact is always available, which is the exact action the sentence
asks for. `TheButtonsNeverContradictTheBannerTest`'s new `RELOAD` case passed over this state
because both halves had gone silent together: the invariant was satisfied vacuously in one
direction, which is its own lesson about sweeps.

**The "recomputable" argument was true of three warnings and asserted of four.** `selectContact`
re-derives the shared-name, rejection and identity-change warnings, and the last also re-raises on
every decrypt from that sender — so painting over them is a displacement. Nothing re-derives the
**invite-refusal** warning: no per-address record says the last attached bundle was refused, and its
only exit is a retraction by a later good invite. A raise during a fault can therefore repaint over
*"that invite was changed on the way here"* and leave only the 3.5-second toast, which is exactly
the silence that warning exists to buy back. INFERRED, not reproduced — the route needs the refusal
raised into a free-or-shared-name slot first.

**Since fixed, the way the finding said to fix it.** `mRefusedInvites` records, per address, the
sentence that was said, and `selectContact` raises it second of four — the position it already held
by severity, above a warning derived from the contact list and below a rejection or a detected
substitution. So painting over it is now a displacement like the others. The *sentence* is stored
rather than a flag, deliberately: the three outcomes have three texts and they are not
interchangeable — one says nothing was set up, one says a key was pinned anyway and to compare the
number, one says what you already had is unchanged. Re-deriving from a flag would have to pick, and
picking wrong writes a false sentence onto the only durable surface this app has. Carried across a
rebuild, because a configuration change is host-forceable and would otherwise hand the erasure back
by another route; bounded at 32, because the addresses need not be contacts and a relay chooses how
many it staples a tampered bundle to; not persisted, because the fact is about a paste in this
session and is retracted by a later good invite. Removing the re-derivation turns one new test red;
keeping the record past the retraction turns the other red.

**And then two reviewers, independently, found the same thing wrong with it: the record was written
inside the guard that decides whether to PAINT.** So the erasure the change was written to close was
still open in exactly the case its own commit message described. Raise any cheap warning first, and
every tampered invite afterwards took the toast-only arm and recorded nothing — silence for one
unsigned byte per message, which is the trade the refusal warning exists to close. This file already
records that separation as one it has needed three times, most recently at the neighbouring site
where `sessionCreationFailed` was recorded inside the same guard. The rule got applied to the local
and not to the map. **Record the fact; decide separately whether to paint it** — now done, with the
outcome computed above the guard and no earlier, because it re-reads `hasPinnedKey` and computing it
before the pin caution would attribute pins that did not come from this paste.

Fixing that alone would have been worse than not fixing it, and both reviewers said so. Two more
things had to land with it:

**The record had one exit and needed three.** It was retracted by a later good bundle or a landed
deletion. Verifying and rejecting cleared the *banner* and left the *record*, so the refusal came
back on the next selection after the user had done exactly what it asked, held `mWarningStanding`,
and suppressed every routine line for that contact for the life of the process. This file's own
classification says what ends an event warning: *verifying, rejecting, or deleting the contact.* The
record had only the third. Worse in the reject case — `rejectContactKey` discards the pin, so the
sentence that came back was `INFO_INVITE_REFUSED_BUT_KEY_PINNED`, claiming a key had been set up at
an address whose key had just been discarded, on a screen whose only live control toasts "already
rejected". The retraction is address-scoped explicitly rather than routed through
`clearStandingWarningIfAbout`, which treats a null address as "about anybody" — right for a
one-slot banner, wrong for a per-address record, and routing it there would let rejecting Alice
erase Bob's.

**And a stored sentence can outlive the state it describes.** "Nothing has been set up" is retracted
by a later good *bundle*. A bundle-less PreKey message from the same address pins a
messenger-supplied key by trust-on-first-use and carries no bundle, so it retracted nothing — and
the next selection painted "Nothing has been set up. Ask them to send another" over an address that
now holds a key the app is encrypting to. That is the state a successful substitution leaves the app
in, and the danger of storing a sentence rather than a flag, which is the same property that made
storing it right. Upgraded rather than dropped at the transition that was already being detected for
the pin caution: the invite really was changed in transit, and the sentence it becomes is the one
written for this state, which tells the user to compare the number.

Three fixes, three tests, one mutant each and each kills exactly its own.

**Left open and named.** Recording unconditionally removes an accidental brake a reviewer spotted:
while the record was written only when painted, a standing warning blocked the next entry, so the
32-entry bound could not be filled. It can be now — but each entry still costs the attacker a user
paste plus a Decrypt press at an address that already has a contact row, so 32 of them is 32
deliberate user actions, not a relay's free move. The other gap was the add-contact arm.

**That one is now closed too.** An invite from somebody not yet in the contact list goes to the
add-contact screen, where a refused bundle was reported by a plain banner write — plain meaning
anything that repaints destroys it and nothing puts it back. The update arms had been re-derivable
since the record was added; the *first* invite from a new contact had not, and that is the common
case, the one the help text describes, and the point where a substitution is cheapest and the user
has least to compare against. The `!successful` arm now records the plain sentence, which is the
true one there — `createSessionWithContact` returned false, so nothing was set up — and only when
the identity-change warning did not fire, because that one is more specific, is already re-derived
from the pending change, and two sentences about one envelope do not fit a slot that holds one. If
the same paste then pins a key through its ciphertext arm, the transition detected there upgrades
this to the sentence that tells the user to compare the number.

And a reviewer's warning about that fix was taken: the add arm records against an address whose
contact row may itself have failed to save, and the recovery re-read discards such a row. A record
kept for a contact who is unselectable and undeletable could never be answered and never raised
again, so it is dropped on exactly the terms the send refusal is dropped on — including staying
while the user is still standing on the row it names.

**A test measured the recovery route without using it.** The trade above rests on "the user gets the
warning back by tapping the contact", and the test asserted it by calling `selectContact` directly.
A reviewer proposed the mutant: guard the list rendering on the fault, so the row is never drawn and
the recovery route disappears from the UI. It survived. It survived a second time after the test was
changed to `showContactListForTest`, which only flips visibility and leaves whatever adapter was
there — so the assertion was reading a stale one. Driving `e2ee_button_select_recipient`, which is
the button the user presses, catches it. *Assert against the route, not the handler behind it* is
now three separate rounds old in this file.

**And a comment that the code beside it contradicts.** The gate's javadoc defended asking
`storageIsUnreadable()` on the grounds that the decrypted store is cached on the helper. When the
gate answers yes, `reloadAccountIfStorageRecovered` rebuilds the `StorageHelper` first, and
`mSecureStore` is a per-helper field — so the `storageState()` inside `refreshOpeningMessage`
afterwards reconstructs it, CryptoBox and all. That is deliberate rather than wasteful (a stale
secure store is what would make a recovered store keep reporting UNREADABLE), it is measured at one
crypto box per raise, and the sentence now says so.

**Also from the same round, on the verify screen.** The refusal toast chose its sentence before the
repaint, so on the arm where the reloaded account pins nothing it said "the number below is the
current one — compare it" over blank digits and two dark buttons. Corrected by repainting first and
choosing from what is on screen, with a second constant for the empty cell; both carry "nothing was
recorded". The claim that a theme change leaves digits painted was false in four places including
this file — `setInputView` calls `surrenderState` three statements later, which blanks them — and
the entry now says why that path is safe rather than dropping it, because under-stating reachability
is the direction that gets a guard removed. And the animator note claiming the cancel is untested is
now stale: it is measured on a device, which is what closes the one cell where the key binding fails
open.

---

## The carrier, attacked and found sound, with four things worth fixing anyway

A round spent entirely on the FairyTale carrier and the encoders found **no reachable attack**. The
decode budget is checked before every expensive step, the alphabet is golden-tested in both
directions, `EnvelopeCodec.fromWire` never throws unchecked, and carrier malleability is already
settled and pinned. What it found instead was four smaller things, all fixed here.

**`compressString` never ended its `Deflater`.** `DeflaterOutputStream.close()` calls `end()` only
on a deflater it built itself — `usesDefaultDeflater` is set only by the constructors that do `new
Deflater()`. This one is handed a caller-built deflater, so closing the stream released nothing and
every FairyTale message sent left its zlib state in the native heap of a long-lived input-method
process until the collector got to it. Encode-side and user-paced, so no attacker drives it — but
`decompressString` one screen below already ends its inflater in a finally, and this is the same
obligation. The `end()` goes after the close and in a finally: ending a deflater before `finish()`
completes raises unchecked exceptions out of native code, and this sits inside a click listener that
catches only `IOException`.

**Nothing checked that the decoy corpus contains no character from the payload alphabet.**
`FairyTaleGoldenTest` writes those sixteen characters as escapes precisely because a stray edit to
them would be invisible in a diff — and then twenty thousand characters of prose feed the same
pipeline unchecked. One zero-width character anywhere in either story prepends stray nibbles to
every payload: an odd count fails the byte-boundary check, an even one fails the inflate, and either
way every recipient sees "could not decode message" for messages that encoded fine. Both stories are
pure ASCII today, which is the point of writing it down rather than assuming it — the nearest
existing coverage catches it only if the offending sentence happens to be drawn, and the draw is a
fresh `Random`.

**Three source comments still named the messenger as the party the mode fools.** The shipped
strings, HELP.md, README.md, KRYPTEY.md and the store listing were all corrected and are pinned by
tests; the `.java` comments were not, and one of them said outright that *"the messenger picks when
rebuilds happen"* about the encoding choice. This file already records a round re-deriving the wrong
threat model for this feature from its own description. No guard was added for them — grepping
`.java` for `steg` would flag honest historical notes and is the substring-ban shape that produces
vacuous passes. The three lines are simply correct now.

**Two comments counted fifteen expanding `replaceAll` passes where there are fourteen.** The 9×
expansion factor both arguments rest on is right, so the decompression budget's justification
survives; the count did not, and one of them is the javadoc that is the whole argument for
`MAX_DECOMPRESSED_BYTES`.

**Left open, deliberately.** The decoy corpus runs 2 to 445 characters — the shortest is the bare
word `'Ah'` and the longest a 445-character passage about Cinderella. The app's one surviving claim
for the mode is that it "makes a message look unremarkable to somebody glancing at your screen", and
neither of those is unremarkable. Trimming the pool is not free: `FairyTaleGoldenTest.thedecoySentenceVaries`
exists because a small pool is itself the fingerprint, and `'Ah'` is the only sentence that fits a
payload in the 21-to-49-character budget window, so removing it turns some sends into refusals.
Recorded with the measurement rather than changed on a guess.

---

## The number on screen and the key the button acts on

The verify screen shows a safety number and offers two buttons that act on a key. Nothing bound them
to each other.

The digits are painted once, from the account held at that moment, and the account object
underneath can be replaced afterwards without anything repainting them. Two production paths replace
it and only one is a hazard — a distinction the first version of this entry got wrong, and a
reviewer corrected. `reloadAccount` runs from `LatinIME.setInputView` on a theme change the host app
can force, but three statements later that same method calls `surrenderState`, which blanks the
digits and the binding together, and `adoptState` restores neither the chosen contact nor this
screen. No press can land in it. The hazard is `reloadAccountIfStorageRecovered`, on every keyboard
raise while a store fault stands: it repaints nothing and disables nothing, and
`onStartInputViewInternal` runs on any `restartInput` or focus move — the window need not hide, and
`onWindowHidden`, which is what clears the digits, does not run. So the messenger picks the moment,
mid voice-call, by presenting a text field.

The theme path is named rather than dropped, because under-stating reachability is the direction
that gets a guard removed later: if `reloadAccount` gains a caller that does not rebuild, or
`setInputView`'s last rites are reordered, that sentence is what records what was being relied on.

`verifyContactInContactList`'s only key check is `getIdentity(address) != null` — *something* is
pinned, never *the pinned key is the one these digits were built from*. So a user can read a number
aloud, hear it match, press Verify, and record "I compared this" against a key that was never on
screen. That is the one failure this trust model has no recovery from, because the badge is what the
user checks **instead of** comparing again. `rejectContactKey` is worse in the other direction: it
acts purely by address on whatever the account holds now, and its record is deliberately permanent.

The strip now records the pinned `IdentityKey` the digits were derived from, and the address it
belonged to, at the moment the number is painted — before the buttons go live, so there is no window
in which they are pressable without a binding. Both listeners refuse unless the store still pins
exactly that key. Cleared with the digits by `clearFingerprintViews`, which is what keeps the Reject
escape hatch working for a contact with nothing pinned: no number painted, no comparison to
invalidate.

A refusal that only refuses is the dead end this screen has produced three times, so the refusal
repaints: the digits become the current ones, both buttons come back, and the response the screen
asks for — compare it again — is available immediately.

**And the sentence is chosen after the repaint, not before it.** The first version picked it from
what the presser hoped for. The reloaded account can pin nothing at that address — a rejected
address, or a session whose write never landed — and then the repaint takes the null-fingerprint
arm, blanks all twelve digits and darkens both buttons. "The number below is the current one —
compare it" would then sit for three and a half seconds over a line saying there is no number, next
to a control that is not live. This file had already rejected that exact reuse once, for
`INFO_VERIFY_AFTER_REJECTION`, and the argument is the same: a sentence the screen disproves is one
the user stops believing, and everything else this app has to say is a sentence. There are two
constants now, and both carry "nothing was recorded" — the clause a user who pressed Verify has no
other way to learn.

Five tests. Removing the binding turns both harm tests red; removing the repaint turns the dead-end
test red; choosing the sentence before the repaint turns the fifth red.

**The fail-open cell, named because it is a real one.** A null binding means the guard passes, and
`clearFingerprintViews` nulls it in the same call that cancels the count-up animators. So an
animator that outlived its cancel would be the one way digits can stand on screen with the guard
open — and Reject can be live there through the deliberate escape hatch. That is not reachable:
`AcontactSwitchDoesNotRepaintThePreviousNumberTest` measures the cancel on a device, and reverting
it turns that test red. Making the binding fail closed instead would break the escape hatch, which
is the dead end this screen has produced three times.

**What was not fixed, and why it is a decision rather than an omission.** The contact-list and
message-list adapters also hold `Contact` objects from the account that was replaced. Every action
on them resolves by address, or by address plus device id, and `Contact.equals` is field-based, so
nothing matches by object identity and nothing observable changes today. The one substitution it
allows is `updateContactInContactList` writing a stale object over a freshly loaded row, whose only
mutable field is `verified` — which is exactly what the caller is setting. If `Contact` ever gains a
field the store owns, that becomes a live rollback.

---

## A refusal and the sentence that justifies it are one fact

`expireRefusalsSettledByAlaterWrite` drops `mContactsNotOnDisk` entries for contacts the account no
longer holds. It dropped the entry and left the caution.

So: a contact is added while writes are refused. The row never reaches disk, the refusal is
recorded, and the banner reads *"Not saved: contact Bob Jones was set up here, but it could not be
saved… Do not send them anything until you have added them again successfully."* Then anything
replaces the account — `reloadAccount` on a theme change the host app can force, or the per-raise
recovery re-read — and the row, having never been written, does not come back. The sweep drops the
refusal, `sendingIsRefusedForTheChosenContact()` goes false, Encrypt lights up, and the sentence
forbidding exactly that is still on the banner underneath it.

The comment beside the sweep had reasoned about this case and got the direction wrong: *"It cannot
produce a false refusal … so this is a leak rather than a hazard."* It cannot produce a false
refusal. It produces a false *permission*, which is the invariant
`TheButtonsNeverContradictTheBannerTest` was written for.

That sweep's 128-state cross product missed it because none of its five interferences replaced the
account — `NOTHING`, `HIDE_AND_RAISE`, `TAP_THE_BANNER`, `CLIPBOARD_TRAFFIC`, `REBUILD`, and the
rebuild reuses the same one, so the row never leaves the list. A sixth, `RELOAD`, is added, and it
fails on six of the sweep's cases before the fix.

The fix is one method called from both arms: `retireTheStorageCautionFor(address)`. Not for a
caution about a deletion — a refusal is settled by a later landed write and a failed deletion is
not, because the row, its pinned key and its plaintext are all still there.

Nothing leaves the device either way: `encrypt` returns null on `NoSessionException` and the strip
renders the encryption-failed line. What was spent was the credibility of the durable surface, which
is the only thing this app has to tell a user something the messenger cannot overwrite.

---

## A control on a slot the adversary can overwrite is not a control

The keyboard re-reads the store on every raise. That re-read is the only thing that replaces an
account carrying `contactsWereUnreadable`, and `StorageHelper` refuses **every** write while one is
carried — so it is not a cosmetic path that lowers a banner, it is the app's only way back to
persisting anything after the device is unlocked.

It was gated on `hasStandingConditionWarning()`, which asks what the banner is currently saying.

The banner holds one warning and any other writer takes the slot. A relayed message offering a
different key at a pinned address does it; so does tapping a contact whose name is shared, and a
refused invite. After that the gate answered no, the store was never re-read again, and the fault
latched for the life of the process: contacts added, keys pinned, verifications, rejections and the
whole chat log living in RAM until the process died, on a keyboard that looks entirely healthy. Each
individual write still reports its own failure — but its advice ("free up space or unlock the
device") has become false, because the state is latched in memory, and the one durable sentence that
explained it, including *do NOT re-invite anyone, because re-inviting replaces keys you have already
checked*, was removed by the party that benefits from removing it.

Reachable, and cheaply: the fault begins with the device locked; Decrypt stays live on the contacts
arm, so one relayed envelope carrying a different key for a pinned address raises the
identity-change warning and takes the slot. The user then resolves that warning the way its text
tells them to, and the banner goes quiet. Everything after that is memory-only, silently.

**The gate now asks the fact.** `theStoreMustBeRereadOnThisRaise()` is
`hasStandingConditionWarning() || contactsAreUnreadable() || storageIsUnreadable()`. The first is a
field read on the account in hand — no store read, no Keystore work — and the third is a trial
decryption `refreshActionButtons` already performs on ordinary clipboard traffic, against a store
cached on the helper, so it is not a new class of cost.

**The test that should have caught it was hollow in the way this branch had already fixed one step
along.** `AwarningDisplacedIsAwarningThatComesBackTest.thestorageWarningComesBackOnTheNextRaise`
displaced the warning and then called `refreshOpeningMessage()` directly, commented *"What LatinIME
does on every setInputView."* LatinIME does not do that; it asks the gate first, and after the
displacement the gate said no. That is verbatim the defect this file records about the *lowering*
direction — same file, same sentence, opposite direction, left standing. The sibling
`onlyTheconditionWarningsAreOfferedToThePerRaisePath` went further and asserted the wrong answer as
desirable. Both now ask the gate.

**The trade this opens, taken deliberately.** With the gate on the fact, `refreshOpeningMessage` runs
on every raise while a fault stands, so it repaints the storage sentence over a key-substitution
warning at moments the messenger picks by presenting a field. A yield was written for that and
reverted, because it is worse: during a contacts fault the contact list *appears empty*, this
sentence is the only thing between the user and re-inviting everybody, and it cannot be recovered by
anything the user can do while the fault stands — Verify and Reject clear a standing warning only
once the response reaches disk, which is exactly what the fault refuses. The warning it displaces,
by contrast, is recomputed on every decrypt from that sender and on every selection of that contact,
both of which its own text asks the user to do. An eviction the subject re-derives is a
displacement. `AstorageFaultOutlivesTheSentenceThatDescribesItTest` pins the ordering and the
recomputation together, so a later round cannot re-open it by reading only half.

Three tests, two of which go red when the gate is put back to asking the banner. The third is about
the ordering and correctly survives that mutant.

---

## Not verified on hardware, and most needing it

*Resolved on both counts; the section title is left as it was written, because it was true when it
was written. The original reasoning stands below and the measurements follow it.*

`FLAG_SECURE` is applied to the IME window while the strip shows decrypted plaintext, the chat log,
the verify screen or the contact list. Window flags on an IME window behave differently across
vendors, and nothing in this environment can run the keyboard — so *which* screens are protected is
tested and *whether the flag takes effect* is not. Of everything in this branch, this is the change
that most needs a device before it is trusted.

The same *was* true of one smaller change, and is now only half true. The safety-number digits count
up over a second, and nothing cancelled those animators, so switching contacts left the previous
contact's animation to finish painting its number into the views a moment later — under the new contact's name, on the one
screen whose whole purpose is comparing that number by voice. The animators are now cancelled when
the digits are blanked and when a new number is loaded. It had no test, because a Robolectric one
would have been worthless: under Robolectric an un-cancelled animator delivers no further frames
once the looper is idled past the view change, so the late repaint never happens and a test of it
passes either way.

**Both measured, on the emulator the device suite now runs on.**
`FlagSecureReachesTheWindowOnDeviceTest.asensitiveScreenMakesTheImeWindowSecure` binds the real
keyboard over a real text field, finds the live strip *inside the running IME* — not one the test
constructed, because the flag is applied by `LatinIME` to its own window and only the real one has a
window at all — and reads `dumpsys window`. With the chat log on screen the input-method window
block carries `SECURE`. With the ordinary keyboard up it does not, and that is the assertion that
makes the first one mean something: a platform marking every IME window secure would satisfy the
first with the flag never applied at all. It comes back off when the sensitive screen closes, so
ordinary typing does not stop screenshotting for the rest of the keyboard's life. The request
reaches the window manager here. One platform, not all of them — the vendor-variance worry is
narrowed, not answered.

The animator has a device test now, and it exists precisely because the Robolectric version would
pass either way. `AcontactSwitchDoesNotRepaintThePreviousNumberTest` opens contact A's verify screen,
waits 200 ms — long enough that the count-up is mid-flight, short enough that it has not finished —
switches to contact B, waits two seconds, and reads the twelve `code_*` views. They must hold B's
number. Reverting the `cancelCodeAnimations()` call in `setFingerprintViews` turns it red; without
that check it would be one more test that passes either way, which is the whole reason the property
sat here undefended for so long.

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

- **All four CI tasks pass**, re-run because the baseline was generated four commits
  earlier and production changed under it: 965 tests, `assembleDebug`, `assembleRelease` producing
  stripped 9.4 MB and 7.7 MB APKs with the gate passing unaided, and *"Lint found no new issues"*. A
  baseline that stops covering the code is worth less than no baseline, since it turns a real finding
  into a silent one — so the check is which commit it was last run at, not whether it exists.

- **The branch history was replayed against its own doc guards, and holds.** Several tests here read
  markdown and build files, which means a documentation-only commit can turn the suite red — and one
  did: a section was added without being indexed, and a red test sat on `origin` until an
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

This one was written during this revival, as the fix for the *previous* round of this
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

(The 20,000-message log here serialises to 5.35 MB rather than the 7.16 MB quoted at the top of this
entry; the original figure assumed ~358 characters per message and the harness's messages are
shorter. Same shape, different constant.)

**So it is a third off at 20,000 and it does not make the cost constant, which is the honest result.**
What the deferral removes is the JSON parse, the AES-GCM open, the re-seal and the re-serialisation
of the log.

**Where the remaining 163 ms goes — measured, after a review pointed out that the first version of
this paragraph reasoned it out instead.** Committing a single unrelated key to a `SharedPreferences`
file costs **13 ms** when the file is small and **146 ms** when a 5.35 MB sibling value shares it.
That is the residual, and it is entirely on the **write** side: `SharedPreferencesImpl` serialises
its whole in-memory map to XML and fsyncs on every `commit()`, so the log is paid for by any write
to the file it lives in, whatever that write actually changed. The whole-file *read* is not part of
it — `ContextImpl` caches the instance per file name, so that happens once per process, not once per
raise. The first version of this paragraph claimed both halves recurred, which was wrong.

**Done — the log now lives in its own file, and the raise no longer scales with it.**

| stored log | before any of this | after deferring the parse | after the split |
|---|---|---|---|
| empty | 36 ms | 36 ms | 31 ms |
| 1,000 messages | 50 ms | 42 ms | 42 ms |
| 20,000 messages | 294 ms | 199 ms | **25 ms** |

That is the whole defect closed on the performance side: a keyboard raise costs the same whether the
user has no history or twenty thousand messages. Reading the log still costs what it costs — the
"force the read" measurement is 366 ms at 20,000 and should be, since that is the message-log screen
actually doing its job — but nothing on the raise path forces it.

The design, which the implementation followed:

- **The log moves to its own preferences file** (`protocol_messages`), holding only
  `UNENCRYPTED_MESSAGES`. The account batch stops carrying it.
- **The hazard that makes this more than a rename.** `hasEncryptedData` on the protocol file is the
  sole input to the Keystore box's refusal to mint a replacement master key, and both stores would
  share one Keystore alias. A second store built on a file that is empty on every device that has
  not yet moved its log would answer "no existing data" and *authorise minting a fresh key over the
  user's identity* — every session, on every device with history. The answer has to be computed
  across both files, because the question is "does this user have an identity", and that is not a
  per-file question.
- **The move is a copy, a verify, and only then a delete**, in that order. A kill after the write
  leaves the log in both files, which costs disk and nothing else: the next load prefers the new
  copy and clears the old, so the move is idempotent rather than one-shot. The other order loses the
  history. Verifying before deleting covers a write that appeared to succeed and did not survive.
- **The log is written before the account batch, not after.** Two files means two commits and a kill
  can land between them. The batch carries `KEY_SCHEMA_MIGRATED`, which asserts every key in the log
  is a rendered address. Batch-first, a kill seals that marker over a log still holding pre-upgrade
  keys and those entries are unattributable for good. Log-first, the surviving state is a re-keyed
  log with no marker, which the next load simply migrates again — re-keying is idempotent.
- **What it bought**, measured rather than predicted: the numbers in the table above. A raise leaves
  the log's file byte-identical, which is asserted rather than timed — every seal draws a fresh
  nonce, so an unchanged sealed value is proof it was not written.

**The one test worth reading in `ChatLogLivesInItsOwnFileTest`** is the identity one, and it took a
control to make it real. Its first version put the log back in the account file by removing one key
from the log's file, and passed even with the two-file check reduced to one — because
`EncryptedKeyValueStore` leaves a schema row behind and `hasEncryptedData` returns true on the
strength of that row alone. A device that has never moved its log has no such file at all. Clearing
it entirely is what makes the test describe a real device, and with that fixed, reducing the check
to one file fails it.

**The split turned the log's file into a laundering oracle.** Found by re-sweeping Phase 1 after
the store was split, and it is the most serious defect this branch has produced. Two halves that
only combine because of the split:

- `messageStore()` ran the 0.1.5 cleartext migration on `protocol_messages` unconditionally. That
  file was created by *this branch*, after encryption existed, and every write to it goes through
  `EncryptedKeyValueStore.put` — so cleartext in it is never legitimate, yet the cleartext branch
  was fully live on it. The migration seals whatever key names it finds.
- The AAD binds the format version and the storage **key name**, not which file the value came from.
  Both files resolve one Keystore alias, so a value sealed in one opens in the other.

The anti-laundering check does not fire: it refuses only when the *same* file also holds a
decryptable envelope, and the log's file legitimately holds exactly one payload key — which an
attacker with the data directory replaces with cleartext, leaving nothing decryptable behind.

So: plant a cleartext `PROTOCOL_STORE` of your own authorship in `protocol_messages` alongside a
cleartext `[]` log, let the app seal it under the real master key, copy the sealed value into
`protocol`, and the user's identity key, sessions and pinned peers are replaced by yours — opening
correctly, with nothing in the app able to tell. That is exactly the threat model
`EncryptedKeyValueStore` is written against: an attacker with the files but not the key.

Fixed by never running that migration on the log's file. `requireEncryptedOnly` instead refuses any
key that does not belong there and any payload that is not a decryptable envelope. The one gap a
legitimate write can leave — `put` commits the value and the marker separately, so a kill between
them leaves an envelope with no marker — still reads back, and a test pins that.

The stronger fix the review also offered — binding the file name into the AAD — was not taken: it
breaks every existing envelope and the move path itself.

**What that leaves, stated properly, because the first version of this entry said "closing the
oracle is sufficient" and that was an overclaim.** The account file still runs the cleartext
migration, and it has to: accepting 0.1.5 cleartext is the entire upgrade path. So an attacker who
replaces `protocol.xml` wholesale with cleartext of their own authorship still gets it sealed under
the real key. That is **pre-existing** and predates the split — the anti-laundering check fires only
when the same file also holds a decryptable envelope, which is a condition the attacker chooses by
emptying it.

What the fix actually removed is the *stealthy* version. Through the log's file the attacker could
replace `PROTOCOL_STORE` **alone**, leaving `CONTACTS`, `RETIRED_DISPLAY_NAMES` and the display-tag
secret — values they cannot read — sealed and intact, so the app came up with the user's own contact
list under the attacker's identity and nothing looked wrong. Through the account file they must
supply cleartext for every key at once, and the user's contacts visibly vanish. A reduction in
stealth, not a closure, and the asymmetry between the two stores is now written into
`secureStore`'s javadoc rather than left for a reader to infer.

**One residual worth naming**, from the same review and not acted on: `MARKER_MIGRATING` is written
as its own durable commit before the values it vouches for, so during any genuine 0.1.5 upgrade a
sealed copy of it sits on disk in a readable window. The marker's unforgeability argument assumes it
is never observable. An attacker who harvests one can then present cleartext beside genuine
envelopes and pass the check that exists to stop exactly that. It needs winning a read race during a
one-time upgrade, so it is recorded rather than fixed.

**The split created a survivor, and one gate did not know about it.** Found by review, and it is
the most interesting thing about this change. `hasExistingProtocolData()` is what stops
`initialize()` generating a fresh identity over an existing one — it asks the store rather than the
"first run?" boolean, because that boolean lives in device-protected storage while the identity
lives in credential-protected storage and the two can be lost independently. It asked only the
account's file. `SharedPreferencesImpl.loadFromDisk` swallows a parse failure and installs an empty
map, so a corrupt `protocol.xml` reads as *no data* while its sibling is untouched: the gate says
fresh install, a new account is generated, and a brand-new account reports its (empty) log as
loaded — so the very next save writes `[]` over the history that survived. Before the split the log
was inside the file that was lost, so there was nothing left to destroy. The split is what created
something worth protecting, and the gate now asks both files. `storageState()` follows it, so the
strip says *unreadable* rather than showing first-run text over the user's own data.

The same asymmetry existed one layer down: `secureStore()` computed its `hasExistingData` from the
account file alone, and it is always built first — so a mint there would leave the log's store
finding a freshly created alias and its own cross-file refusal never reached. Both now ask both
files.

**And two further defects the same review found, both regressions this work introduced.**

`hasExistingProtocolData()` was doing two jobs and they are not the same question. `initialize()`
uses it as *"did the identity I just generated actually reach disk"*, and the chat log is written
**before** the account batch — so an install whose log commit landed and whose account batch failed
(a full disk, one unserialisable value) satisfied the merged predicate. `initialize()` reported
success, `LatinIME` recorded "setup done" permanently, and the device was left with no identity and
no way back, while the strip told the user their contacts were safe but locked. There is now a
separate `identityReachedDisk()`, which asks the account's file and only that.

And the gate tripped on an *empty* log. Every save wrote the log, and a brand-new account reports
its empty log as loaded, so every install created the log's file on its first save — meaning an
install that had never sent a message looked like one with history worth protecting. Lose the
account file after that and the keyboard refuses to re-initialise, permanently, to preserve nothing.
The log's file is no longer created just to record that there is nothing in it; an empty list is
still written once something has been stored, because that is a user clearing their history.

**One claim withdrawn.** `ChatLogSplitAgainstARealKeystoreTest` carried a test asserting the identity
survives building the second store. It could not fail, and the second attempt to fix it could not
either: seeding through `initialize()` mints the key, so the box finds the alias and returns before
the refusal is consulted; clearing the account file to get past that produces a state where nothing
ever decrypts, so no key is resolved and nothing could mint either way. Run with the cross-file
check reduced to one file, it still passed. **The downstream consequence is not reachable in any
state that test can construct**, so the claim is withdrawn rather than dressed up: the contract is
pinned by the JVM test that captures the boolean handed to the box, and the on-device test now
asserts only what it establishes — a device whose key has gone comes back with its data untouched
and nothing minted in place of the old key.

**What this does not fix.** The log still grows forever and is still peer-paced; the cap-versus-keep
question is untouched. And a rollback now has a new shape worth knowing about: restoring the account
file alone no longer rewinds the log, so the messages stay while the contact list goes back. Entries
belonging to a contact the rolled-back list no longer holds match nobody — `belongsTo` compares the
full rendered address — so they are inert rather than misattributed, which is the same disposition
the legacy migration settled on. `StoreRollbackTest` pins both the full rollback and that partial
one. The cap-versus-keep question is untouched by any of this — the log still grows
forever, still peer-paced.

**One pre-existing gap closed on the way past.** `migrateLegacyKeys` refused to run against a
contact list that failed to load — the answer is one-shot and irreversible — but had no equivalent
guard for the chat log, so an unreadable log yielded an empty one, the migration ran against that,
and the marker was sealed beside the result. The read now distinguishes "nothing is stored" from "we
could not read what is stored": only the key's presence separates them, and keys are stored in the
clear. An unreadable log is refused rather than presented as an empty history, and the migration
returns without sealing. Controls: fall back to empty as before, two tests fail; seal the marker
anyway, one fails.

The numbers in this entry come from `ChatLogRaiseCostHarness`, which is in the tree so they can be
re-derived rather than taken on trust. It is `@Ignore`d: a timing threshold tight enough to mean
something is flaky, and one loose enough to be stable asserts nothing. What guards the behaviour is
`ChatLogLoadsLazilyTest`, which pins the structural facts instead — the log is not read to load an
account, and a raise leaves its stored bytes byte-identical.


**~~The CI workflow's Lint step has never passed.~~ Fixed** — Temurin 21 plus a lint
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


**An incoming message could destroy the pre-key an invite was offering.** Found by an adversarial
sweep of the session layer — the first review of Phase 2 in a while, and it turned up the most
serious defect on this branch.

The decrypt path regenerated a one-time pre-key at the id an incoming message declared, on *every*
pre-key message. That is right when libsignal actually consumed one, and libsignal consumes nothing
when it short-circuits because the session record already holds a state for that base key — which is
what happens for every message a peer sends before hearing back. For a copy-paste keyboard, writing
twice before a reply is ordinary.

The regenerated record is fresh material marked **unused**, and the allocator hands out the lowest
unused id. So the id the next invite offers is exactly the id the peer's next message overwrites —
not a race, the default ordering. Read a peer's second opening message after inviting someone new,
and that new contact can never establish a session: the material they hold is simply gone, with
nothing to recover from. This is verbatim the failure `KeyUtil.getUnusedOneTimePreKeyId`'s javadoc
says was fixed. The allocator closed the front door; the decrypt path had reopened it.

**And an attacker can aim it.** On the short-circuit path libsignal reads none of the message's
outer fields except the identity and base keys, so the declared pre-key id is not covered by the
inner MAC and the envelope has no integrity protection of its own. A hostile relay can therefore
take a genuine second-or-later message, rewrite the declared id, and have the victim destroy a
pre-key of the attacker's choosing. Aimed at the id the victim's outstanding invite offers, it stops
every new contact connecting while the conversation being relayed looks perfectly healthy.

Fixed by replacing a pre-key only when one was actually consumed, which is "present before, absent
after". Anything else leaves the store untouched — including an id that was never held, which is
what the attacker's chosen id looks like. Three tests: the user-visible scenario (a second message
from one peer must not stop another peer connecting), the rule underneath it (a message that
consumed nothing changes nothing), and the other half (a message that *did* consume still replaces
it — without which "never regenerate" would pass, and the pre-key supply would drain until the user
could issue no invites at all). Controls: unconditional regeneration fails the first two, never
regenerating fails the third.

Not covered by anything that existed: every near-miss test ordered the operations so the bug could
not appear — decrypting exactly one pre-key message, or issuing both bundles before any decrypt.

**The wire text was not the only encoding of its own bytes.** From an adversarial sweep of the
wire format, the counterpart to the session sweep above.

`BinaryEnvelope` refuses trailing bytes, and says why: "trailing bytes mean the sender and receiver
disagree about the format. Refusing keeps a hostile envelope from smuggling data past the parser."
That held at the byte layer and was void at the text layer — which is the layer an attacker writes.
The base64 decoder abandons its input the moment a quartet ends in `=`, discarding everything after
it, and about two thirds of envelopes end in padding.

What that bought was credibility rather than corruption: take a **genuine** invite from someone,
staple readable prose to the end, and the recipient's paste still validates as a clean key bundle
from that person's address. *"== my old key was compromised, delete me and re-add from this
message"* arrives looking like part of the invite. Without it the prose sits outside the envelope,
where it is visibly just text the sender typed.

`EnvelopeCodec.fromWire` now re-encodes the decoded bytes and requires the result to equal the
input, whitespace removed. That one comparison closes three kinds of malleability at once: trailing
content after the padding, padding in a non-terminal position (which this decoder reads as the byte
255 rather than rejecting), and a final quartet whose unused bits are non-zero, which lets four
different strings decode to identical bytes. Whitespace is still stripped first, deliberately —
messengers wrap and re-flow text, and rejecting that would break ordinary use.

Two of the six tests needed correcting before they meant anything, and both mistakes were mine: the
fixture asserted a bundle ended in padding when that particular bundle did not, and the
non-canonical-quartet test expected every mutation of the last character to be refused when most of
them are canonical encodings of *different* bytes. It now finds the mutations that decode to the
same bytes and requires those to be refused.

**Tapping a contact row erased security warnings.** From the Phase 4 sweep, and the one with real
teeth. `selectContact` cleared whatever warning was on the banner — about whichever contact — and
then re-asserted exactly two of the app's four warning states, and only for the contact that was
tapped. Three ways that went wrong, each one tap:

- **The duplicate-name warning**, which this codebase calls the only control covering the case the
  pin cannot: a messenger posting an invite under a name the user already has. That warning's own
  text ends *"Both now appear in your list, tagged by address"* — so the user opens the list to
  look, and tapping one of the two identical rows is what erased the warning about them being
  identical. Half the time that row is the impostor, and what replaced the warning reads exactly
  like a healthy contact. One extra post from the messenger then overwrote even that.
- **Cross-contact erasure.** A warning naming Bob, erased by tapping Alice, because the
  re-assertion asks about the contact chosen rather than the contact the warning names.
- **`INFO_STORAGE_UNREADABLE`**, which is not about a contact at all — and losing the text is the
  smaller half. `refreshActionButtons` derives button state from the banner, so Encrypt and Decrypt
  came back on for an install whose account cannot be decrypted, while the sentence telling the user
  not to re-invite anyone was gone. It does not come back on its own.

Selection now clears nothing, and does not write over a standing warning either — what the user
reads is the banner, so replacing the text is the same erasure whether or not the flag came down
with it. The deliberate responses still clear it: comparing a safety number, or saying it does not
match. An existing test asserted the old behaviour, calling a tap "a deliberate act"; it encoded the
defect and now asserts the opposite. Control: restore the unconditional clear and four tests fail.

**Making warnings sticky broke three things, and a review found all three.** Recorded because the
fix that caused them was right and the cost was not noticed at the time — removing the only
dismissal path is not the same as removing an erasure.

- **Decrypt could be left permanently dark.** Decrypting disables the Decrypt button, and the only
  thing that re-enabled it was the banner being rewritten, through a `TextWatcher`. Once a warning
  held the banner the clipboard listener returned early before re-enabling anything, so one decrypt
  left the keyboard unable to decrypt again until the user pressed Verify or Reject on somebody.
  The buttons are now re-armed *before* the banner guard, through `refreshActionButtons` so the
  password-field and unreadable-storage answers still win.
- **The chosen recipient stopped being shown.** The banner is the main view's only recipient
  indicator, so tapping Alice while a warning about Bob stood left the screen naming Bob with Alice
  as the recipient — the "invisible recipient … a mis-send" this file rules out elsewhere in as
  many words. The banner now carries the warning *and* a "Sending to: X" line, rather than choosing
  between them. That also caught a second defect in the same change:
  `disablesActionButtons` matched by equality, so appending that line silently re-enabled the
  buttons on an unreadable store. It matches by prefix now.
- **A warning about a deleted contact was unclearable.** Its verify screen is gone with the row, so
  the only remaining exit was Verify or Reject on someone unrelated — asserting a comparison the
  user never made, or destroying a key they never doubted. Warnings now carry the address they are
  about, and deleting that contact clears it. Deliberately *not* used to let selection clear one:
  that was the original hole.

**And that fix had five defects of its own, which is the point of running the rounds.** A third
review of the same area, and two of the five were introduced by the fix rather than found in the
original code:

- **A refused deletion cleared the warning anyway.** The clear ran before `removeContact`, which
  throws when the chat log will not read — so the contact stayed, the flag came down, and the
  warning text stayed on screen. Screen says warning, model says none, so the messenger's next
  clipboard event overwrites it. A one-tap warning eraser that does not even delete the contact.
  The clear now runs after the deletion succeeds.
- **The "Sending to: X" line was written twice and invalidated never.** Every other path that moves
  the recipient is deliberately banner-silent while a warning stands — a decrypt that identifies a
  sender, an invite being processed, the recipient being forgotten — so the line kept naming
  whoever was chosen when the warning went up. Reposting another contact's own earlier invite is
  enough to move the recipient silently, and this document already records that replay. **That is
  worse than what it replaced**: an invisible recipient says nothing, a stale one says something
  false. The repaint moved into `setChosenContact`, so it cannot be forgotten by the next path that
  moves the recipient, and it covers null — otherwise the name rode into the next app through
  `forgetChosenRecipient`, which exists to stop exactly that.
- **Three of the four contact-scoped warnings recorded no address**, including the duplicate-name
  one, whose entire subject is two rows the user cannot tell apart and whose resolution is deleting
  one of them. So the deletion route could not clear the one warning that most needs it. The test
  could not see this because it posted its warning through a test-only seam.
- **The test seam re-implemented the ordering it was supposed to pin.** The re-arm could be deleted
  from the real listener with the suite still green. There is now one implementation that both the
  listener and the seam call — which is what `mayOverwriteInfoBanner` was extracted for in the
  first place, and duplicating the ordering beside it put the mistake straight back.
- **The button decision read "storage is unreadable" out of the banner's prose.** Any warning that
  overwrote that banner took the button state with it, which mattered the moment the re-arm moved
  ahead of the guard: messenger-driven clipboard traffic could light Encrypt and Decrypt on a store
  that cannot be decrypted. It asks the store directly now, as well.

**Round four: a bare rejection left a false warning that could never be cleared.** The first thing
in this area that was neither a fix's regression nor already known, and it is reached by a user
doing exactly the right thing.

Rejecting a key removes the identity, the pending change and the session — so nothing is pinned at
that address at all. Tapping the row afterwards, which is the ordinary gesture and the one the
warning's own last sentence invites, posted `INFO_PINNED_AFTER_REJECT`: *"This **is a new key for
that address**"*. There was no key. And the claim was unclearable: following its instruction to the
verify screen finds no fingerprint, so `clearFingerprintViews` disables Verify **and** Reject —
both deliberate responses physically unavailable — while the flag rides across strip rebuilds and
suppresses every routine banner from then on. The only exits were deleting the contact or the
attacker delivering another key. This file makes the same argument about a different warning: one
that is provably wrong where it fires is worse than the gap it closes.

The condition belongs on the selection path and not in the shared helper — the three arrival paths
call it *while a bundle is being pinned*, so requiring a pin there suppressed the warning on exactly
the paths it was written for. Four existing tests said so immediately, which is the useful kind of
failure.

**Round five: the same wedge, through the door a messenger can open.** Round four scoped its fix to
the selection path and left a comment claiming the arrival paths must warn without a pin, "because
the pin can land after the check". That is true only when the bundle is good. `buildSession` catches
`InvalidKeyException` when a signed pre-key's signature does not verify, returns false **without
saving an identity**, and `decrypt` discards that return value — so one flipped byte in a relayed
invite produced *"this **is a new key** for that address"* at an address holding none, with the
verify screen it points at showing no fingerprint and both buttons down. Reachable by the messenger
alone, on three arrival paths and the add-contact path.

Two changes, because one of them is a rule rather than a repair:

- **The warning asks whether a key is actually there**, and every caller now runs *after* the pin
  attempt rather than before it. The ordering is load-bearing in both directions: check too early
  and a good bundle's legitimate warning is suppressed, which three existing tests said immediately
  when the reorder was reverted.
- **A standing warning must always leave one deliberate response available.** Verify and Reject both
  live on the verify screen and both went down when there was no fingerprint — which is exactly when
  no key is pinned, so any warning that can stand in that state was a dead end. Reject now stays
  available there. Verify does not: confirming a number that is not on screen would be a lie. Two
  rounds found dead ends in this one cell by two different routes, so the third defence is the
  invariant rather than another special case.

Round five also reported the rest of the state table as converged: `(no pin, change pending)` is
structurally unreachable, `(pin, rejected, verified)` is refused in both directions and defended
again on the read side, and every remaining live cell shows text that is true with both buttons
available.

**Round six: the add-contact path had its own copy of the warning, and my own commit message had
named that path.** Round five's fix taught `warnIfKeyWasRejected` to require a pinned key and moved
its callers after the pin attempt. The add-contact path was not a caller — it was a hand-rolled
second copy of the same warning, posted before `createSessionWithContact` — so neither half reached
it. A bundle whose signature fails still announced *"this **is a new key** for that address"* at an
address holding none, and the standing false warning then suppressed `INFO_SESSION_CREATION_FAILED`,
so the user was told a key had arrived and not told the invite had failed. Reachable by
reject-then-delete plus one flipped byte, with no crypto capability.

Two copies of a rule drift; there is one now, and the add-contact path calls it from the point that
knows the pin landed. An existing test asserted the old behaviour — that the post-rejection warning
must be "the thing on screen" in exactly that state — so it encoded the defect and now asserts the
reverse. The half of it that survives, that failure advice must not paint over a *true* standing
warning, is tested separately with the duplicate-name warning, which is about contact rows rather
than about a key.

**And the new Reject exit could say something false.** Round five left Reject enabled when a warning
stands with no fingerprint, which is what stops that state being a dead end. Its confirmation opens
*"Forgot the stored key for %s"* — and in that state nothing was stored. The action is real and
worth keeping; only the sentence was wrong, and only its first line, so the other two are unchanged.

**The verify screen never mentioned a standing rejection.** Same sweep. Pressing Verify there is
what *clears* a rejection — `rejectedAddresses` is documented as retired only by a fresh comparison,
and `isContactKeyTrustworthy` ranks a standing rejection above a verified badge. Yet the screen
carried a notice for a pending identity change and nothing for a rejection, so the user reached the
one screen that undoes their earlier "these numbers do not match" and read only the ordinary
comparison advice. It now names it, and says that confirming clears it. The reason the
pending-change notice exists — tell the user before they compare, so they compare attentively —
applies here at least as strongly.

It also had a gap in the state that needs it most: an `else if` meant a pending change suppressed
the rejection notice, and one extra post reaches the state where both hold — reject, let the bundle
be re-pinned, post again. Confirming there calls both `clearRejection` and `dismissIdentityChange`,
so a screen showing only the pending-change text described one of the two things it does. Both
notices now appear.

Its sibling finding was **checked and found wrong**, which is worth recording because not every
reported defect is one. The review said the verify and reject buttons stay live for a contact with
no pinned key; they do not — `clearFingerprintViews` disables both, and that arm calls it. I had
already written the redundant disabling before testing the claim, and a control caught it: removing
my lines changed nothing, removing them from `clearFingerprintViews` fails the test. The duplicate
is gone and the test now guards the line that actually does the work.

What *was* wrong there is the message: `verifyContact` returning false meant "no contact or no
account loaded" when this string was written, and it also refuses when nothing is pinned. Its own
javadoc says a caller rendering false as "nothing is loaded" is now sometimes wrong, and this is its
only caller. It now says the check could not be recorded, which covers both without inventing a
security claim out of a failed load.

**And a landmine in the same sweep.** `JsonUtil`'s `SenderKey` map-key codec was broken in both
directions and neither half could ever have run: the serializer called `writeStartObject` in a
map-key position, which Jackson refuses outright, so a non-empty sender-key store could not be
written at all; and the deserializer split the key on its first dots and ran `Integer.parseInt` on
whatever landed in the middle — unchecked, out of a deserializer, on the store-load path, with a
peer-supplied name that the wire format explicitly permits dots in. Nothing noticed because the
store is only populated by libsignal's group-session API, which this app never calls. That is a
landmine rather than dead code: the day group messaging lands, every account save throws. Both
halves are fixed and parse from the right, which is unambiguous however many dots the name contains.

**That fix was incomplete, and the review of it said so.** Repairing the map *key* left the map
*value* armed: `SenderKeyRecord` is a handle onto native memory with no no-arg constructor, no
getters and no Jackson properties, so a map holding them serializes to an
`InvalidDefinitionException` — which `JsonUtil.toJson` catches and turns into `null`, which
`EncryptedKeyValueStore` refuses to seal, which `StorageHelper` logs and carries on from. The first
sender key ever stored would have stopped the **whole** protocol store being written — sessions,
pre-keys, identity — silently, on every save from then on. That is worse than the throw the first
fix removed. The store now holds serialized bytes, the way `SessionStoreImpl` already does, and the
test populates it through libsignal's real `GroupSessionBuilder` rather than a hand-made record.

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


## A field libsignal lets you omit, and this app never sends

**A relay can delete the one-time pre-key from any invite it carries, and the session builds
anyway.** Found by the second adversarial sweep of Phase 2 — the first sweep of that layer found the
pre-key clobbering defect, and nothing had swept it since, which is exactly the wrong phase to
assume clean.

`createPreKeyBundle` accepted an absent one-time pre-key: `if (device.getPreKey() != null)`, and if
it was null the bundle was simply built without one. libsignal permits this — `PreKeyBundle` marks
that parameter `@Nullable` and `NULL_PRE_KEY_ID` is `-1` — because on Signal a server's pre-key pool
can legitimately run dry.

**This app has no server.** `getPreKeyBundle` allocates a one-time pre-key or throws, so a bundle
arriving without one is not a depleted pool; it is a modified invite. The permission was inherited
from a system with a component this app does not have.

The field is covered by neither the signed-pre-key signature nor the Kyber signature, so stripping
it costs a relay one byte: flip the `hasPreKey` marker, drop the following bytes, re-encode. Both
signatures still verify, and the base64 is canonical, so the wire-format guards — which are about
structure — see a structurally perfect bundle.

What acceptance costs is forward secrecy against later device seizure. The PQXDH secret loses its DH
against the one-time key, and everything remaining derives from long-lived material that stays on
the device for up to 32 days. Record the ciphertext, seize the device inside that window, and the
conversation is readable from its first message — which is the entire property the one-time key
provides, and which `OneTimePreKeyIsConsumedTest` already argues for on the sending side. And it is
silent: the session builds, the UI advances exactly as for a good invite, nothing is logged.

The right rule was already written four lines below, for the sibling field: *"silently downgrading
to X3DH is not an option, so fail loudly rather than appear to succeed."* It had not been applied to
the field beside it. Now both refuse.

Four tests, and the controls discriminate: reverting the one-time check fails the strip test and the
"no session results" test while leaving the Kyber test and the precondition green. **The Kyber check
had no test either** — deleting it left the suite green — so it has one now.

## Two arms pin, and the fix asked only one

**The seventh round on the trust surface found that the sixth round's fix was a regression.** The
sixth round moved the post-rejection warning out of a hand-rolled copy and into the shared helper,
placing the call "where the key actually got pinned". There are two places that pin, and it went
into one.

`addContact` warns inside `if (messageEnvelope.getPreKeyResponse() != null)`. Below it sits a second
arm: `if (messageEnvelope.getCiphertextMessage() != null)`, which decrypts — and `decrypt` takes its
pre-key arm on the ciphertext *type* alone, while `isTrustedIdentity` returns true whenever nothing
is pinned. A rejection record does not block trust-on-first-use; it only unpins. So a bundle-less
pre-key message at a rejected address pins a new key through that arm, with no bundle and therefore
none of the warnings above it.

The route in is ordinary: reject a key, then delete the row. `removeContact` deliberately does not
clear `rejectedAddresses`, so the rejection outlives the row while the address stops resolving to a
contact — and the same forged message now opens the add-contact screen instead of the decrypt path.
The user names the contact, and a key lands at an address they explicitly rejected, silently. The
warning the previous unconditional check gave them was lost by a fix whose commit message said it
was consolidating one.

The duplicate-name check is not a backstop: it fires only if the user types a matching name, and the
attacker writes the invite text that suggests the name.

**And the message added in the same round asserted a security event that never happened.**
`INFO_NOTHING_TO_REJECT` replaced "Forgot the stored key for %s" — correct, since nothing was
stored — but kept the closing sentence *"this app has already been given a wrong key for them
once."* That branch is reachable only with no pin and a standing warning, which post-round-five
means the duplicate-name, same-address or storage warnings: in none of them was a key ever offered
at that address, let alone a wrong one. The commit said "its other two sentences stay true". One of
them is false in the branch's own primary state. Telling a user the app was handed a wrong key is a
factual claim about a security event, and asserting one that did not happen is the same defect as
staying silent about one that did.

## A test that could not reach its own branch, and a device test in the wrong state

Two ways this round's tests were measuring nothing, both found by review rather than by running.

**`afailedSessionDoesNotOverwriteAtrueStandingWarning` never reached the branch it names.** Its
sibling clears the contact list; it did not, so setUp's Bob Jones was still present and re-adding
the identical contact threw `DuplicateContactException` inside `createAndAddContactToContacts`.
`addContact` took `abortContactAdding` and returned before `createSessionWithContact` was ever
called. The test passed because "send a fresh one" was never a candidate string.

This was demonstrated rather than argued: with the `!mWarningStanding` guard *deleted* — the exact
defect the test exists to catch — the old test still passed. One line of fixture restored it, and
with the fixture in place the same control fails it.

**The on-device strip test drove the send path with the redirect down, which is the opposite of
production.** The strip redirects typing into its own compose box; the send path's
`setShouldUseOtherIC(false)` is what aims the connection back at the host before committing. The
test placed its message with `setText`, which fires the `TextWatcher` but not the focus listener
that raises the redirect — and an unattached view cannot take focus anyway. So the flag was never
up, the reset was a no-op, and deleting it would have left the test green while, in production, the
ciphertext returns to the compose box, is erased by `clearUserInputString()`, and the user's message
is silently never sent.

The test now raises the redirect before pressing Encrypt, and its listener commits through the
`RichInputConnection` the way `LatinIME` does, so it can assert *where* the ciphertext landed rather
than only what it was. Two smaller findings from the same review are fixed: assertions no longer run
inside `runOnMainSync` — `SyncRunnable` has no `try/finally`, so a failed assertion there reaches
the `Looper` as an uncaught exception and kills the instrumentation process, turning one red test
into a lost run — and `tearDown` now calls `clear()`, releasing the process-wide clipboard listener
the strip's constructor registers.

The javadoc no longer says the message is "typed": it is placed with `setText`, so the strip's own
text-entry path is not exercised there.


## A refused invite that looked exactly like an accepted one

**The one-time pre-key check landed, and the next sweep found the refusal never reached the user.**
`SignalProtocolMain.decrypt` processes an attached bundle and *discards the boolean*. On the
bundle-only arm the strip decided success by inference: no decrypted message came out — which is
what a good re-invite looks like, and equally what a refused one looks like. So the UI advanced
identically either way, with no toast and no log line, and printed "Detected contact: Bob" over a
contact that had no session.

What makes it more than cosmetic is the compound, and the compound is the app's own doing. The
add-contact arm creates the contact row *before* it attempts the session and does not remove it on
failure, and it advises *"ask your contact to send a fresh one."* Following that advice moves the
next attempt onto the arm that says nothing, because the row now exists. A relay stripping the
one-time pre-key from every invite can hold that state open indefinitely: each attempt reports
success, and each send afterwards dies with a generic encryption failure that names nothing.

The strip now asks instead of inferring — `hasSessionWith`, a fact rather than an absence — and says
so when there is no session. It says so as a **standing warning**, for two reasons. The banner is
repainted immediately afterwards by `showChosenContactInMainInfoField`, which is guarded only by a
standing warning, so an ordinary line would have been overwritten with "Chosen contact: Bob" and the
refusal would have been exactly as silent as before — *the first version of this fix was, and its
own test caught it*. And it belongs there on the merits: an invite that does not verify was modified
in transit.

Two tests, and the second is the one that matters: a good re-invite on the same arm must still be
reported as healthy, without which "always warn" would pass the first test while breaking every
normal re-invite. Controls: removing the session check fails the refusal test and leaves the good
one green.

**The third sibling got the same rule.** `getSignedPreKey() != null` still tolerated a null where
the other two now refuse. Not reachable from the wire — `BinaryEnvelope` writes that field
unconditionally and refuses a zero-length key — so this is hardening. It is worth having because
tolerating null hands libsignal a null signed pre-key, and `PreKeyBundle`'s Kotlin null check throws
an *unchecked* exception out of a method declared `throws IOException`; `processPreKeyResponse`
catches `IOException` only, so it would escape a click listener, which is this codebase's named
worst crash mode.

And the Kyber refusal stopped blaming the wrong party. Its message named a "pre-PQXDH sender" as the
cause; there is one decoder and no legacy path, so absent means modified in transit, exactly as for
its siblings. Naming a benign cause for an event only tampering produces is the same untrue-message
defect this document keeps finding on the app's screens — here in a string I wrote while fixing one.

## Three states called two, and a response that cleared the wrong warning

**Round eight found that round seven's fix swapped one false claim for its opposite.** Round seven
corrected `INFO_NOTHING_TO_REJECT`, which had kept `INFO_KEY_REJECTED`'s closing claim that the app
had already been given a wrong key — false where nothing was ever pinned. The replacement said
"none had been stored yet", which is false in the *other* no-pin state: a completed rejection
removes the identity and keeps the row, so the user is told no key was ever stored for a contact
whose key they themselves reported as wrong.

"Nothing is pinned" is not one situation. It is *never stored* and *you already rejected it*, and
they need opposite sentences. The distinguishing fact — `wasKeyRejected` — was three lines away the
whole time. There are three states now, and the existing test could not see the difference: it
asserted only the words "no stored key", which both readings satisfy.

The verify screen had the same hole one line out: in that cell it said "No security number is
available for this contact **yet**. Ask them for a key bundle first" — describing an address nothing
has happened at, on the screen where the user decides whether to reject again. The first attempt at
fixing that reused `INFO_VERIFY_AFTER_REJECTION`, which says *"the number below is the key in use
now"* while the digits are blank: one false claim for another, caught by its own test.

**And a deliberate response about one contact put down a warning about a different one.** Reject and
Verify both called `clearStandingWarning()` unscoped, while `removeContact` had been scoped to the
address deliberately, with the reason in its comment. Verify the genuine Bob row and the
duplicate-name warning about the impostor row is gone — and nothing re-asserts that warning, so it
is gone for good. Now scoped: clear when the warning has no address (the storage and same-address
warnings have no other exit) or when the address matches.

The control for that one is worth recording, because the first version of the test **passed against
the unfixed code**. It read the banner's text — but clearing a standing warning does not repaint the
banner, so the words stay on screen either way. What actually changes is whether the next passive
event may overwrite it, which is what `mayOverwriteInfoBanner` answers. Reading the rendered text to
check a decision is a defect class this document already has a section for; this is the same
mistake, made inside the test for it.


## The refusal that switched off the only substitution detector

**The fourth sweep of Phase 2 found that the fix two commits earlier had handed the attacker
something better than what it took away.**

`buildSession`'s `UntrustedIdentityException` arm is — by its own comment — the only place a
bundle-borne identity change is ever recorded. `createPreKeyBundle` throws *before* `buildSession`
is reached. So once the one-time pre-key check landed, a relay could strip that one unsigned byte
from every re-invite a substituted contact sends and the identity-change warning would never fire
again: the app's entire recovery path from a successful substitution, switched off by the same edit
the refusal was added to catch. Dropping the invite outright would be *worse* for the relay, because
the user would retry. Reporting success is what makes them stop.

Compounding it, the strip's own check was the wrong question. `hasSessionWith` asks "can I send to
this person", which is true for every contact the user already talks to — precisely the case the
attack aims at. So the refusal never fired where it mattered.

Two fixes, and the second is the general one:

**The substitution is recorded before any structural refusal.** It grants an attacker nothing they
did not have — a bundle carrying a different identity already reached that record through
`buildSession` — it removes their ability to *suppress* it.

**The refusal is now a fact rather than an inference.** `decrypt` kept the boolean it had been
discarding. Two inferences had already been wrong in opposite directions: "no message came out" is
what a refused bundle and a good bundle-only re-invite both look like, and "is there a session" is
true for every established contact. The strip asks what actually happened.

That also closed the arm nobody had asked on at all: `processUpdatedPreKeyResponse` runs with
`isSessionCreation = false`, and an honest peer attaches a full bundle to an ordinary message
whenever its signed pre-key rotates — so the routine rotation path announced an update for a bundle
that had been refused.

Three more from the same sweep. The warning is **retracted** by the very thing it asks for
("ask them to send another"), which previously left a working contact sitting under "Nothing has been
set up" with every later notice suppressed — a user acting on that text may reject a good key. It is
**soft**: it is the only standing warning a relay can raise unilaterally, so a hard one would let a
relay raise it about one contact and thereby silence the caution shown when the user adds another —
the one notice that fires *because nothing was noticed*. And **Reject is no longer offered on a
contact the warning is not about**: `rejectContactKey` marks the address whether or not anything was
pinned, so that tap flagged an innocent contact permanently while the real warning survived
untouched.

**A test of mine was vacuous again, and the measurement is what found it.** The first version
asserted that the refused rotation path does *not* say "updated keybundle". It passed against the
unfixed code — because that line is repainted by `showChosenContactInMainInfoField` before the user
ever sees it, so its absence was never observable. Writing the positive counterpart is what proved
it: a *good* rotation does not show that line either. The test now asserts the property that is
actually visible — the refusal stands, and standing suppresses the repaint.

## A record that meant less than four messages claimed

**Round nine.** `rejectContactKey` marks the address unconditionally, pin or no pin — and the
no-fingerprint verify screen deliberately enables Reject as the escape hatch from a standing
warning. So `wasKeyRejected` does not mean "the user compared numbers and they did not match". It
can equally mean "the user pressed the only enabled button on a screen with no numbers on it", in a
state a relay can produce on demand.

Four messages asserted the comparison as fact, two of them added the round before. In that state
each was false, and worse, each *replaced a message that had been true*: the no-fingerprint cell
used to say "no security number is available for this contact yet", which is correct there.

They now claim only what the record supports: *you told this app not to trust keys arriving for X*.
A message that wants to claim a comparison needs a record only a comparison writes, and there is no
such record today — which is a real limitation, recorded rather than papered over.

**And this one has a mechanical guard now**, because the same defect had appeared in three
consecutive rounds by the same step: a sentence written for one state, copied into a neighbouring
one, carrying its factual claim with it. `NoClaimSentenceIsSharedBetweenMessagesTest` refuses to let
a sentence appear in two `INFO_` constants until someone writes down that the sharing is deliberate
— scoped to the exact pair, so a third use fails again. It cannot tell a true sentence from a false
one; it makes the copy impossible to perform *silently*, which is the step all three defects
skipped. Its control is the historical defect itself, reintroduced verbatim: both of its tests fail.


## The warning weakened to protect a message, and the erase that bought

**Rounds ten and five arrived at the same conclusion from opposite sides: the "soft warning" was the
wrong axis, and it handed the messenger an erase.**

The problem it was solving is real. The caution shown when a new contact is created —
*"this key reached you through the messenger and the app cannot tell whose it is"* — is the one
notice that fires *because nothing was noticed*, and it was suppressible by any standing warning. A
relay can raise a standing warning unilaterally, so it could raise one about Bob and then have the
user add an attacker-chosen "Carol" in silence.

Making the refusal warning yield to ordinary notices was the wrong fix, in a way worth recording.
`setInfoUnlessWarned` has six callers, and one of them is the **password-field notice** — which
`LatinIME` raises on every input session from the host field's `inputType`, and the messenger owns
the inputType of every field it presents. So the erase needed no user action at all: focus a
password field, the notice lands and takes the warning with it, focus an ordinary field again, and
the strip reads "Chosen contact: Bob". Nothing re-raises it. That contradicted `mWarningStanding`'s
own javadoc — *"Nothing the messenger can cause clears it"* — and the fixed-defect comment sitting
twenty lines above the notice itself.

And the premise was false anyway. The javadoc I wrote claimed the refusal was *"the one standing
warning a relay can raise unilaterally"*; this file says elsewhere that the identity-change warning
is something *"any messenger can arrange with one forged bundle"*. The special case did not cover
the warning that mattered most.

**Both requirements are real, so the banner shows both.** The caution now appears *beside* a
standing warning rather than instead of it: the warning keeps its flag, its text and its address, so
every deliberate response still clears it, and `warningWithRecipient` still rebuilds from the warning
rather than from what is painted. Promoting the caution to a warning of its own was tried first and
is wrong for a reason already on file — that is "Contact Carol created" landing on top of a security
warning, which `StripWarningErasureTest` exists to forbid. The flag survives only to retract the
refusal when a later invite from the same contact is accepted, which is the remedy its own text asks
for.

**And the refusal said something false wherever a session survived it.** "Nothing has been set up" is
right for a first invite and wrong on the rotation path — where an honest peer attaches a bundle to
an ordinary message, the message decrypts under the existing session, and the user reads that
sentence with the reply in front of them. Two messages now, chosen by asking whether anything
survived rather than assuming: the same mistake, in the same method, as inferring the refusal itself.

Smaller, from the same sweep: `resetForTest` now clears the refusal flag (the singleton outlives a
test class, and that method exists for exactly this), and a `sInstance != null` guard that could
never be false is gone.

**One more vacuous test of mine, caught by its control.** The new test that a refused substitution
also clears the verified badge asserted `isContactKeyTrustworthy` immediately — which answers false
on the strength of the *pending change* alone, so it passed with the clearing deleted. The
consequence lives on the other side of the dismissal: `dismissIdentityChange` is a tap and
deliberately does not restore `Contact.verified`, so an uncleared badge comes back with no fresh
comparison anywhere. The test dismisses first now, and its control fails.


## One invariant, swept instead of exampled

Rounds seven through ten each found a defect **in the previous round's fix**, all in the same place:
something the messenger can cause taking down a standing warning, or a message asserting an event
that did not happen. Ten rounds of point fixes had not converged, so this one added a guard for the
shape rather than the instance.

`MessengerCannotClearAstandingWarningTest` states the property `mWarningStanding`'s own javadoc
already claimed — *"Nothing the messenger can cause clears it"* — and checks it against every event
a relay can trigger: the host field being declared a password field (the round-10 route), a
clipboard change, an unrelated invite arriving, a configuration change rebuilding the strip, moving
between the strip's screens, and selecting a contact.

**The first version of it was worth less than it looked, and its own control said so.** It raised
one generic warning and swept the events past it. Reintroducing the round-10 defect verbatim — the
soft-yield that let a password-field notice erase the refusal — left it **green**, because that
defect lived on one particular warning and the test had constructed a different one. A sweep over
events alone measures only the warnings its author happened to build.

So it is a cross product: every event against every warning the app can leave standing, each raised
through its real path — the generic one, the refused-invite warning, and the identity-change warning
from a substituted bundle. With that, the round-10 control fails, and so does deleting the warning
restore in `adoptState`. The cross product also caught a mistake in its own first draft: it pasted
*Bob's* invite as "an unrelated invite", and a good invite from Bob legitimately retracts a refusal
about Bob — the test being wrong rather than the app.

What it cannot do is prove the event list complete. A genuinely new route into the strip still needs
adding by hand. What it does is make the routes that are known impossible to reopen quietly, which
is the failure mode ten rounds actually had.

**Round eleven, found in parallel with it.** The refusal's two-message split asked "is there a
session" *after* the decrypt — and the decrypt creates one. `decryptMessage`'s PREKEY arm pins by
trust-on-first-use whenever the address holds no key, and a refused attached bundle does not stop
it, because the `PreKeySignalMessage` carries its own identity key. So on the add-contact arm the
strip printed *"what you already had with them is unchanged"* at the exact moment a
messenger-supplied key was pinned — and that arm does not fire the contact-creation caution either,
so the reassurance was the only thing on the banner. The previous wording was also false there, but
it erred toward alarm; the fix had routed the state to the reassuring falsehood.

Three states now, and the predicate is captured before the decrypt rather than after. The third
says a key was set up anyway and sends the user to compare it by voice.


## A corrupted chat log was an amplifier for key substitution

**A fourth place had to survive an unreadable chat log, and `ChatLogUnavailableException`'s own
javadoc says "the three places".** Found by the first Phase 1 sweep in many commits.

`SignalProtocolMain.decrypt` files every incoming message in the log and had no catch, so the throw
travelled up into `E2EEStrip.decryptMessage`'s catch-all — added for an unrelated JNI reason — and
came out as `INFO_MESSAGE_DECRYPTION_FAILED`. **The message had decrypted.** The user was told
decryption failed for a message the app had read perfectly, and the persist below the throw never
ran, so the advanced ratchet and the replaced one-time pre-key stayed in memory.

What makes it more than a wrong sentence: one flipped byte in the sealed log makes it permanent —
GCM fails, no key needed — and this document's own analysis is that the app's generic
decryption-failure advice drives users to delete the contact and re-invite. That is a
key-substitution window. A corrupted chat log became an amplifier for substitution through advice
given about the wrong problem.

The message is delivered now, the session is persisted, and the failure is reported as itself:
*"This message was read, but it could not be added to your saved history… nothing needs to be sent
again — only the record of it is missing."* Deliberately no deletion or re-invite advice.

**Round twelve, in parallel, found two more.** The refusal's predicate asked whether a *session*
appeared while the sentence it chose asserts a *key* was pinned "and this app cannot tell whose it
is". Those come apart on a path the app's own advice produces: `removeContactFromContactListAndProtocol`
deletes the session and deliberately keeps the pin, so after a delete-and-re-invite the peer's next
message builds a session against a key that was already trusted — and the message decrypted
*because* it matched that pin, which the same app treats elsewhere as proof of identity. It asks
about the pin now.

And the contact-creation caution was painted beside a standing warning but stored nowhere, while
every repaint rebuilds the banner from the warning's text alone. Hiding the keyboard dropped it, a
rotation dropped it, and tapping the contact row dropped it — the row the caution asks the user to
go and verify. **The invariant sweep could not see this**, and that is worth recording about the
sweep rather than about the bug: it watches the flag, and this was an erase of text with the warning
left standing.

**The completeness guard added last commit was itself vacuous for three of its eight warnings.** It
resolved constants within ±400 characters of each call site, and missed `INFO_IDENTITY_CHANGED_EXISTING`
by *sixteen characters of comment* and the duplicate-name pair by forty-seven thousand, because those
are chosen inside a helper. Those three entries were inert: deleting them failed nothing, and a new
warning written the way the identity-change warning is written would have passed with no raiser and
no excuse — the most important warning in the file being exactly that shape. Resolution is by method
now, with one level of helper indirection and comments stripped first, and over-collection is handled
by classifying every constant the scan touches rather than by narrowing the scan until it misses one.
The control is the three formerly-inert names: removing them from the swept list now fails.

## Two storage findings that make a recorded residual wrong

**Recorded now, fixed next — and the point of writing them here is that this document currently
states something false.**

`StorageHelper` and this file both describe the account store's laundering residual as: the attacker
"must supply cleartext for every key at once, and the user's contacts visibly vanish. A reduction in
stealth." **That cost does not exist.** The attacker snapshots `protocol.xml` first, replaces it with
a single cleartext row, lets the keyboard raise once (the master key is untouched — `hasEncryptedData`
gates only *minting*), reads the now-sealed row back, and restores the snapshot with that one row
swapped in. The AAD is the same in both states because it binds the key *name*, so it opens. The
account comes up with the attacker's identity key beside the user's genuine contacts and display
tags, with nothing missing and nothing to see.

Second, `MARKER_MIGRATING` does not need to be raced. It is committed as its own durable write before
anything is sealed, and nothing invalidates it except a *successful* migration — so it can be
harvested from a deliberately interrupted run and replayed later to disarm the anti-laundering guard,
achieving the same thing in one app run with no row ever going missing. The class javadoc's claim
that the marker "cannot be faked" is true of forgery and silent about retention.

Both are the same underlying gap: **nothing binds a sealed value to the file or the run it was sealed
in.** Both need the file-access adversary, which is strictly stronger than the messenger — which is
why they are deferred rather than urgent, and why the honest thing to fix first was the description.


## The laundering primitive, closed from outside the file

**Both storage attacks recorded last commit are fixed, and the fix is one line of reasoning: the
guard cannot live in the thing the attacker rewrites.**

Converting cleartext found on disk is a laundering primitive — it takes bytes an attacker wrote and
hands them back sealed under the real master key, at which point they are indistinguishable from the
user's own data. Two guards existed and both sat inside `protocol.xml`. One refuses cleartext beside
decryptable data, so the attacker empties the file first; emptying costs nothing, because
`hasEncryptedData` gates only whether a key is *minted*, not whether it is used. The other trusts
`MARKER_MIGRATING`, which is durable and invalidated only by a *successful* migration, so it can be
harvested from a deliberately interrupted run and replayed later.

The fact now lives in the **Keystore**: a second alias whose key material is never used, and whose
mere existence means the one-time conversion has already happened. An attacker holding the app's
private files cannot edit it. They can destroy it — but that destroys the master key with it, and
the sealed data they wanted to launder into becomes unreadable, which leaves them nothing.

It is also sealed on a **fresh install**, where there is no cleartext to convert. Otherwise the one
device that never needed the conversion would be the one that stayed open to it forever.

Two details worth stating. `legacyMigrationIsSealed()` **fails closed**: an unreadable Keystore
reads as "already converted", because the other answer is the one that seals an attacker's cleartext.
And `sealLegacyMigration()` failing is logged loudly rather than swallowed — the conversion itself
succeeded, so the data is safe; what is lost is protection against a *second* one.

Four tests, and the control is the attack: remove the check and three of them fail, including the
snapshot sequence whose recorded cost — "the user's contact list visibly disappears" — turned out
not to exist, because the attacker puts the file back.

**Round thirteen found the caution fix from the previous commit was scoped to the wrong thing.** It
cleared `mStandingCaution` inside `setWarningMessage`, reasoning that a caution belongs to the
warning it was shown beside. False on a fact: that method does not distinguish *replacing* a warning
from *re-posting the identical one*, and `warnIfIdentityChanged` re-posts on **every decrypt**. So
one more relayed message from the contact the warning was about destroyed the caution about a
different contact — the attacker's — with the warning standing the whole time. Another erase of text
with the flag up, which is the shape the invariant sweep cannot see, introduced by the commit whose
own entry says so.

And the other half was never fixed at all: with **no** warning standing — the common case, and the
one the call site argues matters most — the caution was still written straight to the view and
stored nowhere, so a clipboard post, hiding the keyboard, tapping the contact row or a rotation all
erased it.

The caution is a standing item of its own now, with its own address, cleared only by a deliberate
response about *that* contact, holding the banner against passive repaints the way a warning does,
and carried across a rebuild whether or not a warning accompanies it.


## A fix that was true of the store and false of the app

**The seal landed, its tests passed, and on a fresh install it was never written.**

`StorageHelper` calls `migrateToEncrypted()` only when `needsMigration()` says so, and a fresh
install never needs a migration — so the one place that sealed was the one place those devices never
reached. Every install that had no 0.1.5 store to convert kept exactly one free laundering, which is
the population the previous entry singled out as protected. That claim was false as wired.

**The test asserted the property and passed anyway**, because it called `migrateToEncrypted()`
directly on a store holding `MARKER_COMPLETE` — a call `StorageHelper` cannot make in that state. It
was a true statement about the store and a false one about the app, and nothing in the suite drove
the seal through the real wiring. The replacement drives `StorageHelper` end to end; its control is
the old gating, under which both new tests fail and the old one still passes.

Sealing now happens on **every load** rather than only inside a migration, which also supplies the
retry the seal never had: a process death between the marker and the seal, or one transient Keystore
failure, used to leave a device permanently unsealed because nothing ever tried again.

A caveat on the guarantee, from the same sweep and worth stating rather than leaving implied: the
seal holds against the `run-as`/file-access adversary, and a **root** adversary with direct
keystore-DB access can delete the seal alias while leaving the master key intact. The claim is not
"unforgeable", it is "not reachable by rewriting the app's files".

**Round fourteen found the caution had one repaint path left.** `resetChosenContactAndInfoText`
guarded on `mWarningStanding` alone, and it is the other unconditional banner writer: it runs when
the user taps the banner — a natural response to a notice — and when *any* contact is deleted, so
deleting Alice painted "No contact chosen" over a caution about Carol. That is the cross-contact
erase `clearCautionIfAbout` is scoped to prevent, arriving one line after it.

**The sweep now watches the words, not only the flag.** Both round-13 defects and this one were
erasures of text with the warning still standing, and the sweep asserted only that *something* was
standing — which stayed true throughout. Each raiser supplies a distinctive fragment of what it puts
on screen, and every event must leave that fragment on the banner. The caution is swept as an item
in its own right. The control is the round-13 defect reintroduced: six rows fail where the old sweep
caught none.

Two smaller things from the same round, both mine. A duplicated statement committed at the Verify
listener — a `sed` that matched two indentation variants. And a test seam that re-implemented
`mayOverwriteInfoBanner` minus one term, which had already drifted when written (the real predicate
also refuses over a password field) and which had been inserted between that method and the javadoc
saying re-implementing a body in a test proves only that the copy behaves. It reads the field
directly now.


## FLAG_SECURE was down on the route users actually take

**Found by turning the previous entry's lesson into a sweep of its own.** Rather than review the
code again, a reviewer was asked to find *tests that assert a property through a path production
never takes*, with the fresh-install seal as the template. It found four, and the first is a real
defect rather than a testing one.

`setChosenContact` never told the window. `notifySensitiveVisibility` had three callers — a screen
switch, the compose-box watcher, and `adoptState` — and `setChosenContact` has **nine** call sites,
of which two are followed by a screen switch. So on the commonest route there is, accepting an
invite with the Decrypt button, the strip chose a recipient with no screen change, and the
session-creation arm writes no text so the watcher never fired either: the main view sat there with
the banner naming the contact and `FLAG_SECURE` **down**. That is exactly the disclosure the
chosen-contact term was added to cover.

It failed closed on the way out too. Hiding the keyboard notifies *true* while the contact is still
set, and `forgetChosenRecipient` then nulls it silently — so the last thing the window heard was
"sensitive" and the flag stayed on for the keyboard's life, which this design explicitly rejects.

**Why the existing tests could not see it.** Both tests in `SecureWindowCoversTheChosenContactTest`
drive `selectContact`, and the file's javadoc says so — *"Driven through `selectContact`, which is
what a contact row's click listener calls."* That sentence reads as scoping and is in fact the hole:
`selectContact` is one of the two call sites that happen to be followed by a screen switch. The
dismissal test that would have caught the other half never sets a recipient, so the `false` it
observes comes entirely from the compose-box term.

**And one of my own tests was hollow again, in a way worth recording.** The invariant sweep's
"an incoming key bundle" row drove `processIncomingEnvelopeForTest`, whose javadoc says plainly that
it drives the *plain signal-message* arm — so a bundle-only invite ran the "no contact found" path,
a Toast and a screen switch, and never reached `processPreKeyResponse`, whose first statement is the
banner write the row exists to check. The row could not fail for any of the seven warnings it was
crossed with. It goes through the clipboard and the Decrypt button now.

**Three more from round fifteen, all the same family**, and the fix this time is the rule rather
than the instance. The banner writers each carried their own copy of "do not overwrite a standing
item" as `if (mWarningStanding)`, and when the caution became a second standing item, three
consecutive rounds each found one more copy that had not been told — the reset path, the recipient
repaint, the session-failure line. There is one predicate now,
`aStandingItemHoldsTheBanner()`, and the copies ask it.

Two of those three are invisible to the sweep by construction, which is worth stating rather than
leaving implied: a stale "Sending to: X" is not an erasure, and the password-field notice heals
within the single event the sweep fires. Both have targeted tests instead, each with a control.


## A writer on the right of an &&

**Round sixteen found the previous commit's refactor silenced the warning that matters most, and
the mechanism is worth more than the instance.**

`warnIfIdentityChanged` reads like a predicate and is a **writer**: it posts the Toast and calls
`setWarningMessage`, which is the only thing that raises `mWarningStanding`. It sat on the right of

```java
} else if (!mWarningStanding && !warnIfIdentityChanged(chosenContact)) {
```

and the fix that unified the banner guards widened the left term to `!aStandingItemHoldsTheBanner()`.
That is correct for what the guard was *for* — not painting generic advice over a standing item —
and it short-circuited the call. So with a caution standing, which is the ordinary state straight
after adding any contact, the identity-change warning was never raised at all.

On this arm nothing else raises it: `createSessionWithContact` shows a Toast and no more, and the
ciphertext path that would otherwise post it is not taken by a bundle-only invite. So the app could
detect a key substitution at a pinned address — by its own javadoc *"the highest-signal security
event the protocol produces"* — and put nothing on the only surface that persists, plus lose the
Reject escape hatch, which needs the warning's address.

The path is ordinary at every step: adding a contact leaves a caution standing; deleting a contact
is the app's own advice after a decryption failure, which the messenger can induce at will; and
deleting one deliberately **keeps** the pinned identity, which is exactly what makes the next invite
at that address a detectable substitution rather than a first sighting.

The two things behind that `else if` were never the same thing. The writer is called
unconditionally into a local now, and only the generic advice is guarded — the shape `selectContact`
already used.

**Two findings from the faithfulness sweep, closed.** `discardRecordedMessage` removes a phantom log
entry *and persists*; nothing tested the second half, because the only test called
`Account.removeUnencryptedMessage` directly on an account whose storage helper was null — an
in-memory assertion on a fixture that had never touched disk, while production has already flushed
the entry before the rollback runs. And that removal matched on the address **name** while every
other log operation identifies an entry by the rendered address, so a rollback of a failed send
could take a delivered message belonging to a different contact sharing the name. Both fixed, both
with controls.

Also: `contactFor`, the seam behind `processIncomingEnvelopeForTest`, compared a folded contact
device id against the envelope's raw one, where production folds both. A legacy id — the committed
0.1.5 fixture carries 7296 — resolved to null in the seam and to the real contact in the app: the
seam and the code it stands in for would take opposite arms. It asks production now. The javadoc two
methods above it already said a seam that re-creates the thing under test pins only its own copy,
and that the mistake had been made once in this file. This was the second time.


## The first clean round in eleven, and a guard for the class

**Round seventeen found nothing.** Rounds seven through sixteen each found a defect in the
immediately preceding round's fix, so a clean result here is worth recording as a result rather than
as an absence. It checked the `addContact` restructure for ordering, false firing, wrong-contact
addressing and double-posting, and swept every `&&`/`||` operand in the strip for side effects.

**The class got a guard, and the guard found a second instance before the round reported.**
`NoWriterSitsInAshortCircuitTest` computes the set of side-effecting methods from the source — a
hand-written list would need the same attention that missed the defect — and fails when one is
invoked on the right of `&&` or `||`. Its control is the round-16 defect reintroduced verbatim.

The second instance is `warnIfIdentityChanged(contact) || warnIfKeyWasRejected(contact)` in
`selectContact`, and it is **not** a defect — which is why the guard asks for an argument rather
than forbidding the shape. Both writers concern the same contact and write the same single warning
slot, and the left one returns true only after it has posted, so when the short circuit fires a
warning about that contact is already standing. Nobody is left with nothing, which is exactly what
made round sixteen's case a defect rather than an ordering choice. Which of the two wins is a real
judgement, and it is now made deliberately: a pending identity change is a live event, while the
rejection record describes something the user already did. The reviewer reached the same conclusion
independently.

**One behaviour change worth recording**, from the same round and not a defect: on the failure arm,
`warnIfIdentityChanged` now overwrites a duplicate-name warning set forty-five lines earlier in the
same `addContact` call. Reachable — an attacker gets a key pinned at its own address, the user
deletes that contact (the pin survives), and the attacker re-invites at the same address under a
live contact's name with a different key. The duplicate-name warning is documented as never
re-asserted while the identity-change one re-posts on every decrypt, so the durable warning displaces
the one-shot one. Both are addressed to the same contact and both send the user to compare the
security number, and the banner holds one warning — so this is a consequence, not a defect. It is
here because it is the kind of thing that becomes a surprise later if nobody wrote it down.


## The canonicality check, the fix that was not one, and what is actually true

**Phase 3's third sweep reported that the wire format's strongest guarantee did not hold on the
FairyTale route. I fixed it, shipped the fix, then proved the fix was worthless — and reverted it.**

The report was accurate about the mechanism. `EnvelopeCodec.fromWire` re-encodes and demands the
text be the canonical encoding of its own bytes; which decoder produces that text is chosen by
scanning the pasted message for an invisible character, which the adversary writes. On the FairyTale
route `decode` read only the invisible characters, and the nibble table ignores every code point
outside its sixteen — so prose was stripped before the check saw a byte.

The fix embedded the decoy sentence inside the compressed payload and made `decode` refuse a
mismatch. **It is not a boundary.** Nothing authenticates the payload, so a relay decompresses it,
puts its own sentence in both halves, and recompresses — fifteen lines, all of them readable in this
repository, no secret at any step. The check stopped only an adversary who declined to re-encode,
which is not an adversary. Measured with a test that performs exactly that re-encode; it passed.
A reviewer reached the same conclusion independently and in parallel.

**And it was actively harmful while it was in.** `visibleTextOf` stripped `\p{C}`, which includes
`\n` and `\t`, so a transport that hard-wraps `"add me"` to `"add\nme"` made an honest invite
fail; Java's `\s` is ASCII-only, so an HTML renderer emitting `&nbsp;` did the same. The commit
message claimed whitespace normalisation avoided exactly that denial of service, and the
normalisation ran *after* the strip that caused it. The same `\p{C}` breadth left bidi controls,
soft hyphens and private-use characters free to alter the rendered text while being invisible to
both halves of the comparison.

**What is actually true, and is now pinned by a test rather than argued.** The relay owns the
messenger. It can put any sentence it likes beside any message, in the same conversation, with or
without this encoding — so unauthenticated prose next to an invite is not a property of the codec
and no encoder-side change alters it. What the canonicality check does close is narrower and real:
text that would otherwise ride *inside* the envelope and survive as part of a validated object.
That property was never affected.

`FairyTaleCarrierIsNotAuthenticatedTest` pins the measured behaviour the way `StoreRollbackTest`
pins a rollback's cost — a change in either direction fails. If someone adds a real binding it goes
red and should be replaced; if someone adds another fake one it stays green and says why that is not
enough. The mitigation, if one is wanted, is not in the codec: it is that the app must never present
carrier prose as though the sender wrote it.

**Two trust controls that were writing and returning constants.** `verifyContactInContactList` ended
`storeAll…(); return true;` while its own javadoc defines false as "verification could not be
recorded" — so the one failure that most plausibly means that was the one it could not report, and
the strip already renders false as `INFO_VERIFY_UNAVAILABLE`. `rejectContactKey` discarded the
result entirely, leaving the app to print "Forgot the stored key" over a key still pinned on disk,
to be re-pinned by the next `reloadAccount` — the silent trust-on-first-use `markKeyRejected` exists
to prevent. Both report now, the second through its own channel, because "was there a key to forget"
and "did this land" are different questions.

They shipped with no tests, which a reviewer pointed out and which was fair: the commit's five new
tests were all for the encoder change that got reverted. There are five now, with controls, and they
caught two further defects on the way — the flag held the *previous* rejection's outcome on the
early-return path, and the failed-write message claimed a key had been refused in the state where
none existed.


## Sweeping for the class instead of the bug, and what it found

**The last defect's lesson generalised into a question nobody had asked: which checks on this branch
does an adversary defeat by redoing the work the honest code does?**

The answer for the class itself is clean — no other checksum-over-attacker-data, no other embedded
copy compared with itself, no forgeable marker. `EnvelopeCodec.fromWire`'s re-encode-and-compare was
examined specifically and is *not* an instance: the adversary cannot choose the two sides
independently, because `encode(bytes)` is a function of the bytes the parser produced. It establishes
canonicality, which re-encoding genuinely gives, and never claims authenticity.

But the sweep found the adjacent, weaker half of the class — **claims that outrun their mechanism**
— and one of them was a live defect.

**"Detected contact: Bob" was asserted from an unsigned header.** `processSignalMessage` called the
decrypt, discarded the result, and painted the contact's name on the persistent banner
unconditionally. Both sibling arms gate that line, with comments arguing the gate is required; this
one was the odd one out. A relay copies the address name and device id out of any envelope that
contact ever sent — it carries all of them — pairs them with arbitrary bytes, and *encodes*. No
forgery is involved, so the canonicality check is satisfied by construction. The decrypt then fails,
a transient toast blames the user, and the banner that stays on screen names the contact. The one
thing that ties a message to the key pinned at that address is a **successful decrypt**, and it was
one frame away and unused.

Fixing it also corrected the method's `@return`, which claimed "false only on the session-creation
arm, when no session exists after the attempt". It never asked whether a session existed; it
returned false exactly when a bundle was refused. So `usable` and `bundleAccepted` at the call sites
meant "the bundle was not refused" — the javadoc described a check the code did not perform.

**And a warning asserted a fact with no adversary in sight.** `INFO_RETIRED_CONTACT_NAME` said
"...and this new one has a different address", but its predicate suppresses only when a pin survives
at the recorded address — and `LegacyKeyMigration` deliberately blanks that address for every
pre-upgrade retirement. So on any upgrading install, a deleted contact re-inviting *from the same
address* got a warning whose central clause was false. It now says what the predicate supports: the
app cannot confirm this is the same person coming back.

**Two defects in the previous commit's retained half**, both found by review. A failed verify write
returned false *without rolling back* `setVerified(true)`, which had already gone into the live
contact list — so the toast said "Nothing has been marked as verified" while the row rendered the
verified badge and `isContactKeyTrustworthy` agreed. That errs open, and it was a path this branch
created. And the new pinning test's stated discriminator was backwards: re-landing the reverted fake
binding turns it *red*, and its javadoc then told the reader to replace the test — the one
instruction that would ratify the defect. The re-encode attack is a second test now, which stays
green under a fake binding and red under nothing at all.

**Also closed, from the earlier faithfulness sweep:** `RichInputConnection.setSelection` was `void`
with three silent exits, one of them after committing to the new cursor model, while its own javadoc
still documented a boolean return. Two call sites follow it with "delete backwards over what was
selected". When the move fails the editor still holds the selection and our model says the caret is
collapsed, so the delete eats that many characters *before the real cursor* — text the user never
selected, and in the recapitalise path a buffer that can hold decrypted plaintext. The signal is
restored and both call sites bail.


## A gate that changed the verb and nothing else

**The previous commit gated "Detected contact: X" on the decrypt having produced a message. On the
production route it changed nothing a user would see, and the test that proved it drove a seam that
skipped the line which undid it.**

`processSignalMessage` has one production caller, `decryptMessageInClipboard`, and four statements
later that caller runs `showChosenContactInMainInfoField()` unconditionally — which paints
**"Chosen contact: Bob"** on the same banner, from the same unsigned header. Suppressing one verb
while the other ran regardless is not a fix. And `processIncomingEnvelopeForTest` calls
`processSignalMessage` directly and returns, so the test never reached the repaint: a control that
certified a property the public path undid on the next line. That is the hollow-through-a-seam
failure this document already has a name for, made again in the commit that cited it.

The real fix is that the sender is not adopted at all unless something decrypted.
`setChosenContact` must still run *before* the decrypt — it clears the staged message when the
recipient changes, so adopting afterwards wipes the plaintext just put on screen — so the adoption
is undone by `forgetChosenRecipient()` when nothing comes out. That also takes away the half the gate
never touched: Encrypt no longer stays aimed at a recipient chosen by an envelope that decrypted to
nothing. The test now goes through the clipboard and the Decrypt button, and its control is the
gate-only version, which it fails.

**And the verify rollback restored the cosmetic half of what it retracted.** Verifying also clears a
standing rejection and dismisses a pending identity change, both irreversible in memory. The
rollback added last commit put back `setVerified` and left those applied — so a failed write told
the user "Nothing has been marked as verified" while the live store had forgotten the rejection.
`wasKeyRejected` then answers false, `INFO_PINNED_AFTER_REJECT` does not fire the next time a
relayed message pins a key at that address, and the first successful write from any later operation
persists the cleared record permanently. That is the silent trust-on-first-use `markKeyRejected`
exists to prevent, reached from the other direction — and the same argument that shaped
`rejectContactKey` fifty lines above it. Both retractions are captured before and restored on
failure.

**The typing-path change shipped without a test**, which a reviewer said plainly and which was fair:
`setSelection` regained its boolean and two call sites began bailing on it, with nothing exercising
either. `FailedCursorMoveDoesNotDeleteTest` drives `LatinIME.mInputLogic.onCodeInput` — the real
path, not the method — with a host connection that refuses to move the caret, and carries an
anti-vacuity guard proving the ordinary case still reaches the delete. Its control is the unguarded
delete, which it fails.


## Three informational lines the user never sees

**Measured while writing a test, and it changed what that test could honestly claim.**

`decryptMessageInClipboard` ends with `showChosenContactInMainInfoField()`, unconditionally. All
three of the strip's "Detected contact…" lines are written by `setInfoUnlessWarned` a few statements
earlier, into the same `TextView`. So on the production route every one of them is painted over
before a user could read it: what remains on the banner is "Chosen contact: Bob".

This is why the previous commit's gate was inert, and it is worth stating as a property of the
surface rather than as a fact about one line. Two consequences:

- Gating those lines is a correctness improvement in the code and **not** a user-visible fix. Saying
  otherwise — as the commit that gated the first one did — overstates it.
- A test that asserts one of them is on the banner is testing the repaint order, not the app. The
  rotation test was written that way first, failed, and is now asserting what is observable: that a
  rotation whose bundle was accepted is not reported as *refused*. Refusals are standing warnings,
  which survive the repaint by design.

The three lines are not security claims — a signed-pre-key rotation does not change the identity key
the safety number is derived from — so this is a dead-informational-text finding rather than a
missing warning. Recorded because three separate fixes have now been argued in terms of what those
lines say, and none of them reach a user.

**Two items from the previous review, closed.** `setSelection` committed the expected-selection model
before asking the editor and returned with it already moved, so guarding the callers stopped the
immediate wrong delete and left the desync: the model said "collapsed at the end" while the editor
still held the selection, `hasSelection()` answered false, and the next backspace took the
single-character branch against a selection the model had forgotten. The model is restored on
refusal now, so a false really is "nothing happened" — checked by pressing backspace a second time
after the connection recovers and requiring the whole selection to go. And its javadoc no longer
promises a `false` for a failed cache reload that the method has never produced.

`processUpdatedPreKeyResponse` asked the wrong question: it read a return value that had stopped
meaning "the bundle was accepted" once that method gained a second reason to be false. It asks
`lastAttachedBundleWasRefused()` now, which is the fact it wanted.


## Text crosses into another application, and three writes that could not fail

**The gap named in every report is half closed.** "Nothing moves text through another app" was true
because there was no other app on the device — every instrumentation test attached the keyboard to a
field in the app under test, so the input connection never crossed a package boundary, which is the
boundary the whole threat model is about.

There was a second application available the entire time. The test APK is
`com.amnesica.kryptey.test`: its own package, its own process, installed beside the app. A manifest
and an activity in the `androidTest` source set make it a stand-in messenger, and
`TextCrossesIntoAforeignAppOnDeviceTest` launches it by component — `startActivitySync` refuses an
activity belonging to another application, which is exactly what makes the test worth having — and
reads back out of `dumpsys input_method` that this IME is bound to a window owned by that package.

Stated rather than implied: this establishes the binding crosses the boundary. It is still not a real
messenger — nothing relays, stores or renders the text, and no second device is involved.

**Three writes that reported success they had not earned**, from the sixth Phase 2 sweep.

The verify rollback restored the two *retractions* faithfully and wrote `setVerified(false)` as a
**constant**. Right for a first verification, wrong for a contact that was already verified — and
re-verifying is one tap on the green badge, an ordinary thing to do after any warning. So a failed
write took away a badge the user legitimately held while disk still said verified. Fail-closed, which
is why it would have gone unnoticed. It captures the previous value now — and the first attempt at
that captured it *after* the flag was set, making the restore a no-op, which the existing test caught.

`storeMessageLog` swallowed its exception and the caller returned true on the account batch alone.
So a failed chat-log commit produced exactly the outcome `mLastChatLogWriteFailed` exists to report —
the message delivered and absent from the history — with the notice never firing, because the flag
only ever covered the log being unREADable. Both halves report now.

**And a test of mine that could not fail.** The rotation test shipped last commit was written as
"a key rotation that landed must be announced", and a reviewer showed the old and new
implementations leave a byte-identical banner — because the line it controls is repainted before any
user sees it, which is the finding recorded in the section above. The fix at that line is real and
its observability is nil. The test is renamed for what it actually pins: a rotation whose bundle was
accepted must not be reported as *refused*. Claiming otherwise would have been coverage on paper.

Also documented rather than quietly left: `setSelection` returns true when there is no connection at
all, having never asked an editor. Narrow, self-repairing, and a change to it would alter behaviour
for the pointer callers that discard the result — so it is written into the contract instead of
folded into an unrelated commit.


## Ciphertext crosses the process boundary, measured

**The gap named in every report is closed as far as one device allows.** The strip encrypts a message
in the live IME and commits it into a field owned by `com.amnesica.kryptey.test` — a different
package, a different process — and the other process reports back that what arrived is non-empty and
does **not** contain the plaintext.

The verdict carries no text. The field is in another process, so it cannot be read by reference, and
adding a UI-automation dependency would mean new hashes in the verification metadata. The plaintext
is passed *in* as an extra and the comparison happens over there, so neither half is ever written to
a device-wide log — a test whose subject is "the plaintext must not cross this boundary" has no
business publishing it.

**The first version of both cross-app tests was wrong, in two ways worth recording.**

The binding test asserted `dumpsys input_method` contained the foreign package name and
`mCurMethodId=<this IME>`. Neither establishes a binding. `mCurMethodId` is the *selected* input
method — true from the moment the harness runs `ime set`, before any test starts, which this
document already records elsewhere in as many words — and the package name appears in the dump's
start-input history, so it survives long after the IME has moved on. Two unanchored substrings over
one dump, with nothing tying the served window to that package: it would have passed on residue left
by a sibling test in the same run. The foreign process reports `isActive(field)` about its own field
now.

And the send test failed for a reason the same review predicted: nothing asked the IME to attach to
that field, so the strip committed into a connection that did not exist. **It also broke a test that
had nothing to do with it** — the activity was never finished, so a resumed window from another
package sat on top of the stack with a contact chosen, leaving the IME window `FLAG_SECURE` and
failing the test that asserts the ordinary keyboard is not secure. A test that contaminates the suite
is worse than a missing one, because the failure lands somewhere else. The activity bounds its own
lifetime now and the strip's state is handed back in `tearDown`.

**Three more writes that could not fail.** `mLastMessageLogWriteSucceeded` is only assigned where the
log is written, so on the path where `secureStore()` resolves to nothing — a Keystore key invalidated
by a credential change, a migration that threw — the method returned early having written *nothing*
and the flag kept its initialiser, `true`. A device in that state reported a healthy log write
forever. And the send path never consulted the flag at all: the receive half reported "delivered but
not recorded" and the send half stayed silent, so a message could go to the messenger and vanish from
the history with nothing said. Both closed, the second with its own wording — "this message was read"
is not true of a message the user just sent.


## A guard for the third instance of the discarded answer

**Three defects on this branch were the same shape, and each cost a review round to find:** a method
that answers "did this happen", called as though it answered nothing.

- `processPreKeyResponseMessage`'s refusal was discarded inside `decrypt`, so a bundle the app had
  rejected was reported to the user as an accepted invite.
- `verifyContactInContactList` ended `storeAll…(); return true;` — the one failure that means "this
  verification was not recorded" was the one it could not report.
- `storeMessageLog` swallowed its exception and its caller returned true on the account batch alone,
  so a message could be delivered and silently absent from the history.

They are invisible on reading, because a discarded return looks exactly like a void call. So the
shape has a guard now: a call, as a bare statement, to a method in the same file returning
`boolean`. Its control is the second defect reintroduced verbatim.

**Exemptions are per call SITE, not per name**, and that distinction did real work here. The same
method is consumed where its answer matters and ignored where it does not — `warnIfKeyWasRejected`
is a writer whose boolean is a convenience, and exempting the name would have hidden the site that
mattered. Each entry is an argument about one call, in writing.

**It found one immediately.** Deleting a contact discarded its write result and
`removeContactFromContactListAndProtocol` returned nothing, so a failed write left the row gone, the
messages swept and the session deleted **in memory only** — and the next raise brings the contact and
its plaintext back after the app has said it was deleted. This is the one operation where that is a
promise rather than an inconvenience: the strip already refuses to delete a contact whose chat log
cannot be *read*, on the stated grounds that a row removed while its plaintext stayed behind is the
worse outcome and the one the help text says does not happen. A failed write broke the same promise
from the other side. It reports now.

**Two false-positive shapes had to be handled before the guard was worth anything**, and both were
mine: `if (...) {` matches the same pattern as a method declaration, so the enclosing name came back
as "if"; and the second line of a multi-line assignment — `final boolean x = a(...) || b(...);` — is
textually indistinguishable from a discarded call. A guard that cries wolf on those would have been
turned off within a week.


## The cross-app tests, and what they cost to make honest

**The two tests that cross a package boundary took four device runs to stop being wrong or
contaminating, and the failures are worth recording because they were all mine.**

**The worst was silent.** The ciphertext test omitted `resetForTest()` before `initialize(null)`,
which is the line that nulls the storage helper — and `initializeStorageHelper` returns early on a
null context *without clearing that field*, which `resetForTest`'s own javadoc names as the trap. The
live IME installs a real Keystore-backed helper when `setInputView` runs, so without that line
`initialize(null)` refuses to generate over existing data: both "accounts" are the same on-disk
identity, the session is built with itself, and the click drives the real `encrypt` — committing a
fabricated contact list, a self-pin, and **a chat-log entry holding the plaintext** to the device's
real encrypted store. The test passes either way, because a self-encryption is still not the
plaintext. A test whose thesis is "the plaintext must not leave" was writing it to disk.

**Attribution was theatre.** Both tests read a verdict out of logcat, and `logcat -c` is not enough:
the sibling's activity is designed to outlive its test and logs four times a second, so a marker in
the buffer says nothing about which launch produced it. Worse, `Intent.filterEquals` ignores extras,
so the two launches are filter-identical — the second could be handed the first's instance, which was
started *without* the secret, making `containsSecret=false` unconditional and the headline assertion
unfalsifiable. Each launch carries a nonce now, forces a fresh task, and reports `haveSecret`
separately, because "the secret was absent" and "I was never told the secret" produced the same token.

**And the lifetime was wrong in both directions, on a timer.** Too short and the subject
self-destructs mid-test, so the send commits into a dead connection and the failure reads as
"nothing arrived" — pointing at the app. Too long and it sits resumed on top of the stack holding
focus: **three unrelated tests failed with "something else holds focus"**, which is ambient
contamination this suite already had a name for. A timer was the wrong mechanism. The activity dies
on a broadcast from its own test now, with the timer demoted to a backstop for a test that crashes
first.

**One thing found by a test rather than a review.** Writing a test for the real
`lastMessageLogWriteSucceeded` — both existing readers override it with a constant, so neither had
ever run the field's logic — surfaced an NPE: `secureStore()` builds an `EncryptedKeyValueStore`
around whatever the factory returns, including null, and the seal call added a few commits ago
dereferences it on every load. Production passes a constructor reference so the box is never null
there; this is defence, not a live fix, and it is stated that way. `secureStore` refuses a null box
outright rather than null-guarding each use, because the first use missed would be an unchecked throw
out of the storage path.

**The flaky test, diagnosed.** `AutofillDoesNotReachTheKeyboardTest` failed on two consecutive
device runs and passed on the third with nothing changed. It runs *before* the cross-app tests, so
contamination was ruled out. The cause was in the test: the bind loop twenty lines above retries its
`showSoftInput` on every poll — and this file already records why, "a single best-effort call is why
an earlier version timed out on a freshly booted device and passed on one that had been up for a
while" — while the focus move that actually triggers the fill request was a single best-effort call
followed by a passive wait. The same lesson, in the same file, not applied to the second loop. It
retries now, alternating between the two fields, because re-requesting focus on the view that
already has it produces no new request. Three consecutive green runs since.


## A green test that could not go red

**The test written last commit to cover the log-write flag covered half of what its javadoc claimed,
and a control said so.**

Its "healthy store" case used a fresh account, whose log is loaded and **empty** — so
`storeMessageLog` took its "nothing to say and nothing already said" arm and returned true without
touching the store. The test then asserted `true`, which is also the field's initialiser. Deleting
the write-path assignment outright left it green. It covered the null-store path and nothing else,
while its javadoc said it closed both.

Two changes make it discriminate. The healthy case puts a message in the log and additionally asserts
the log key reached its own file, so the flag is reporting a write rather than a skipped one. And a
third case makes the log write fail **alone**, which is what kills the mutant: asserting `true` on a
healthy store never can, because `true` is the default. The AAD makes that constructible —
`EncryptedKeyValueStore` binds the key *name* into it, so a box can refuse exactly the log's key and
seal everything else.

**Four more from the same review.** `messageStore()` still built a store around a null box after
`secureStore()` stopped doing so — the same rationale, written out at one of the two sites, and the
chat log's version escapes further because the deferred loader carries it past a click listener that
catches three checked types. The finish receiver would throw `SecurityException` on API 33+, in
`onCreate` *before* the backstop timer is posted, so the activity would die silently and both
cross-app tests would fail with a message pointing at the app. The verdict scan kept only the last
matching line, so an implementation that put the plaintext in the foreign field and later replaced it
would satisfy the assertion — every edit is delivered to that process, so every line is asserted now.
And `tearDown` had a throwing call ahead of its contamination-critical work with no `finally`, which
is the ordering that produced the `FLAG_SECURE` cascade in the first place.


## An unreadable history is not an empty one

**Round eighteen, aimed at production code rather than at the test scaffolding the previous rounds
had drifted into, found four defects in what the strip tells the user.**

**Two of them said the history was empty when the app could not see it.** Deleting a contact whose
chat log cannot be read is refused — correctly, because the log probably still holds that contact's
plaintext, and a row removed while its plaintext stayed behind is the outcome the help text promises
does not happen. The message shown was *"There are no saved messages for this contact"*: a claim
about the history, made in the one state where the app cannot read the history, asserting the
opposite of the reason for the refusal. The history screen said the same thing while rendering an
empty list under "Message log with: X", with a comment two lines above stating that the log is still
on disk for a later unlock to recover. A user who believes their plaintext is gone is exactly the
user who stops trying to remove it. Both now say the log exists and could not be opened.

**A relay could suppress the "delivered but not recorded" notice at will.** The refused-bundle branch
delivers the message and did not ask whether the log write landed, while the ordinary branch did.
Staple a bundle to every relayed message and strip its one-time pre-key — one unsigned byte — and
every message takes that branch: a delivered message whose record was lost is filed nowhere and
reported nowhere, under a banner that is reassuring about the key and silent about the record.

**And creation was the last write in the family that could not report a failure.** Rejection,
verification, deletion, a sent message and a received one all thread the result up; creating a
contact discarded it, said "Contact X created", and sent the user to compare a security number for a
contact that exists in memory only — reverted by the next `reloadAccount`, whose timing the host app
controls. The unreadable-storage case was already covered; the gap was account-loaded-but-write-
failed, which is the case that looks healthy.

**One thing attempted and dropped rather than left broken.** The help section added last commit makes
a behavioural claim — the invite goes to whatever app holds the cursor — so it should be pinned. The
claim is true: the invite path calls the same `sendEncryptedMessageToApplication` an ordinary send
does, and the on-device cross-boundary test exercises that path into a foreign process. But a JVM
test driving the invite button produced nothing at all, with no toast and an empty compose box, and
the cause was not found within the time this tick had. The test was deleted rather than committed
red or left asserting less than its name. The claim rests on reading plus the device test, and that
is written here rather than implied by an absent test.

## The round that fixed a sentence by breaking its opposite

**Round nineteen found that the previous round's best fix had inverted the one arm where the old
message was true.** The chat-log screen has two ways of showing nothing, and they mean opposite
things: the history is empty, or the history cannot be opened. The screen looks identical in both —
an empty list under "Message log with: X" — so the sentence beside it is the whole of the
distinction. Round eighteen replaced *"There are no saved messages for this contact"* with the
unreadable-log sentence on **both** arms.

The reasoning was that the exception is called `UnknownContactException`, so it must be a lookup that
failed. It is not. There is exactly one throw site reachable from that call, and reaching it requires
`getUnencryptedMessages()` to have **returned**: the log was opened, read, and held nothing for this
contact. An unreadable log throws `ChatLogUnavailableException` out of the same call and lands in the
arm below; a null account returns null and throws nothing at all.

So the app told every contact with no messages yet — the state **every** contact starts in, and the
state a user is in **immediately after successfully clearing their history** — that their messages
"are still on this device", and to "not assume anything here has been removed". A deletion that
worked, reported as a deletion to distrust. That is the direction the sentence was written to
prevent, arrived at by the fix for it.

Nothing failed, because no test tied either sentence to a state; both arms only had to not crash.
`EachChatLogSentenceMatchesItsStateTest` ties them, asserting the exact text on purpose — the point
is *which of two sentences* a user reads, so a test satisfied by either is not testing the thing that
broke. Restoring the old sentence with the old mutant re-applied fails it.

**A second notice named a cause the user can disprove in one tap.** One flag,
`mLastChatLogWriteFailed`, is raised by two different failures — a log that cannot be READ, and a log
that reads fine but whose WRITE does not land — and the notice named only the first: "because the
stored history cannot be opened". On the write arm the chat-log button opens the very history the
toast says cannot be opened, showing the message it says was not saved, which then vanishes at the
next raise. The cause clause is gone; what is true on both arms stays. This matters past accuracy:
this app's entire trust surface is sentences, and the only thing separating a key substitution from
an ordinary reconnection is a notice the user is asked to believe. One they can catch out about
storage is one they will not believe about a key. `AnoticeMayNotNameAcauseTheScreenDisprovesTest`
drives the write arm and asserts the state, not the wording — it opens the log in the same state the
notice describes and demands the app not contradict itself between the two.

**And the lost-contact-write notice reached one of the three arms that create a contact.** The read
sat nested inside "a bundle arrived" *and* "a session was established", while the flag is set by
`createAndAddContactToContacts` above, whichever of three exits is then taken. The two it missed:

- a **refused** bundle — the row exists in memory, the banner gives the ask-for-a-fresh-invite
  advice, and the lost write is not mentioned. The user asks for another invite, gets one, and the
  contact vanishes again.
- a **ciphertext-only** envelope — the arm whose own comment records that it pins a key by
  trust-on-first-use. A contact is created, a key is pinned, a message is decrypted and shown, and
  nothing is said at all: the account write can fail while the LOG write succeeds, so the message
  notice does not cover it either. The user is sent to compare a security number for a contact that
  is about to disappear.

The check now sits where the row is created, above all three. `EveryArmThatCreatesAcontactReportsAlostWriteTest`
drives each arm with a store whose account write fails and demands the notice from each, with a
fourth test asserting a successful write says nothing — without which the other three would pass on a
notice that always fires. Deleting the hoisted check kills exactly the three, and leaves the fourth
green.

**The invite-button test dropped last round is reinstated, and the cause was a fixture error rather
than a defect.** The strip does not commit the invite itself: it hands the text to
`Listener.onTextInput`, and in production that listener is `LatinIME`, which wraps it in an `Event`
and commits it through `InputLogic`. The deleted test's listener ignored the text, so nothing arrived
and a working button looked broken. `TheInviteGoesToTheHostAppTest` gives the listener the job
`LatinIME` does and asserts what lands in the host field parses back to a key bundle. Two mutants
kill it: not delivering, and delivering something that is not a bundle.

**A sweep over every button, and the hollow first version of it.** The strip carries eighteen click
listeners and two unchecked catches, and this is an input method: an exception out of a listener does
not land in a dialog, it takes the keyboard out of whatever app the user is typing in, and the states
that produce one — an invalidated Keystore key, a corrupt stored value, a store that will not write —
are persistent, so it is crash-on-tap in every app until reinstall. The existing crash test presses
three buttons chosen by hand against one broken state; the choosing is the weakness, because a
nineteenth listener is covered by nobody.

`NoButtonOnTheStripCanKillTheKeyboardTest` walks the inflated hierarchy, finds every view that really
has a listener attached (sixteen, plus the reject button once the verify screen has been loaded — its
listener does not exist before that), and presses all of them in four states: healthy, unreadable
chat log, a store that will not write, and no account at all.

**Its first version was vacuous, and went green.** It pressed the buttons in hierarchy order against
one shared strip, and the first thing in that order is `e2ee_info_text`, whose listener is
`resetChosenContactAndInfoText`. So it cleared the chosen contact and the fifteen buttons after it
took their "no contact chosen" early return. Four passing tests pressing nothing. The proof is that
replacing the chat-log screen's real `catch (ChatLogUnavailableException)` with a catch that cannot
match — a genuine, reachable IME kill — failed none of them. Rebuilding the whole world per button
makes the same mutant fail exactly the unreadable-log test.

That is the ninth distinct hollow control this document records, and the second whose hollowness came
from **the test's own earlier steps changing the state its later steps read**. The rule that keeps
holding: a control that passes is the thing to distrust, and the only way to know is to make the
defect and watch it fail.

Both round-nineteen wording defects are the same shape as the one this document already records
about comments and about its own counts — *a statement that was true when written, kept after the
thing it described moved*. The difference here is that the round-eighteen inversion was not drift.
It was reasoning from a type's name instead of from its throw site, in a file where the type name and
the meaning point in opposite directions, and it shipped because the arm had a control for crashing
and none for lying.

## The inviter's side pinned a key and said nothing

**Round twenty found the most reachable trust defect any round has produced: the arm that handles the
first message from a new peer pinned that peer's key by trust-on-first-use, posted no caution, left
the banner claiming no contact was chosen, and left the two action buttons dark.**

This is not an exotic path. It is the inviter's side of **every conversation this app has ever set
up**, and the help describes it: *"Your chat partner has to add you to their contact list and then
send you an encrypted message… The contact is now automatically selected."* Whoever sent the invite
meets this arm exactly once, at the moment their peer's key is pinned.

What arrives is a message with no bundle beside it. `getMessageType` branches on field presence
alone, so choosing this arm costs a relay one omitted field; `decrypt` then takes its PreKey branch
on the ciphertext **type**, where `isTrustedIdentity` returns true whenever nothing is pinned — a
rejection record does not block trust-on-first-use. So the key is pinned, and it arrived through the
messenger, which is the party this app treats as the adversary.

The sibling arm has posted a caution for this for several rounds, and the comment there argues it is
the most important of the four outcomes on that arm **because it fires when nothing was noticed**,
which is what a successful substitution looks like from inside the app. The same sentence was owed
here and was never said. It is now said from one place rather than two: a second copy of a claim is a
claim that will drift, which this document records happening to comments, to counts, and to the
duplicate post-rejection warning that kept the old behaviour after the helper it copied learned a new
rule.

**Two further consequences fell out of the same silence, and they are what the user actually sees.**
Nothing on that path repainted the banner, so it was left reading "No contact chosen" while a contact
*was* chosen and Encrypt aimed at them — false about the thing that matters most on that screen, who
the next message goes to. And because `disablesActionButtons` matches on that exact sentence, Encrypt
and Decrypt stayed disabled: the user was handed a decrypted message and no way to answer it, on the
flow the help calls "automatically selected". One caution fixes all three, because posting it is what
repaints the banner.

`TheInviterSideIsToldToCompareTheNumberTest` drives it through the real clipboard and the real
Decrypt button rather than calling the arm directly, so the banner and the button states are the ones
the flow produces. It was written failing — three of its four tests red against the shipped code,
with the fourth passing to prove a key really was pinned — and removing the caution turns all three
red again.

**What this says about the previous rounds.** Two rounds in a row moved a check to "where the key
actually got pinned" and each time reached only one of the two places that pin. The lesson recorded
after the first was that a fix here is usually right about the threat and wrong about its blast
radius; this is the third instance, and the pattern is specific enough now to name: *when a defect is
fixed on one arm of `addContact`, the fix is not finished until the other two arms have been walked
by hand.*

## The true thing faded and the false thing stayed

**Round twenty's remaining findings, and they share one shape: the accurate statement was put on the
surface that lasts three and a half seconds, and the inaccurate one on the surface that survives
everything.**

**A contact that did not reach disk.** The lost write was a toast; the caution stored beside it said
*"Contact X created… compare the security number by voice before sending anything private"*, and
that one lives in `mStandingCaution` — it survives every repaint, a screen switch, and a rebuild. So
the user read the false sentence for as long as they looked at the screen and went off to compare a
number for a contact that will not exist after the next raise, whose timing the messenger controls.
The standing item now carries the failure instead. Nothing is lost by replacing rather than
appending: *"do not send them anything until you have added them again successfully"* covers
everything the other sentence covers, plus the reason the contact is about to vanish, which the other
does not mention.

**A deletion that did not reach disk still took the warning down.** Deleting the contact a warning
names is the deliberate response to that warning, so a successful deletion clears it. There are two
ways for the deletion not to happen, and only one was handled: a deletion *refused* because the log
will not read. The other is one performed in memory whose write did not land — and there the row is
gone from the list *right now*, which is exactly why clearing looked right and exactly why it was
wrong. The next `reloadAccount` brings the contact, its pinned key and its messages back, and the
app's only lasting warning about that key does not come back with them.

The trade is a warning standing over a contact temporarily absent from the list. That is the lesser
evil, and the same one the unreadable-log arm beside it settles the same way: a warning with no
visible subject is confusing; a subject that returns with no warning is a silent key-substitution
window.

**And one existing test was asserting the defect.** `SelectingAcontactDoesNotEraseAwarningTest`
checked that deleting a named contact clears its warning — with a fixture whose write does not land,
so it was describing the state the app should not be in, and passing only because the clear was
unconditional. Gating the clear made that visible. Its precondition is now stated rather than
assumed.

**And the reason it happened is a production inconsistency, not a fixture quirk — which is the
correction to what this section first claimed.** Two helpers answer "did the write land", and with no
storage helper they answer opposite ways: `accountWriteSucceeded()` returns **true** (contact
creation, verification and key rejection report through it), while
`storeAllAccountInformationInSharedPreferences()` returns **false**, and `removeContact` returns that
directly. So in one ordinary fixture a created contact reports saved and a deleted one reports lost,
about the same store, in the same test class. That is why three fixtures in two ticks looked healthy
and were not, and it is pinned by `TheTwoWriteReportersDisagreeTest` rather than described.

The true-on-null branch fails **open**: every trust decision reported as saved while nothing is
stored. It is unreachable in production today, because `LatinIME.setInputView` always passes a real
context so the helper always exists — but "unreachable today" is exactly the status this document has
been wrong about before. **Closing it is one line and turns 46 existing tests red**, because that
many fixtures depend on the fail-open. Measured, not estimated. That is a deliberate migration rather
than a drive-by — and it has since been done; see [the section on
it](#the-fail-open-that-made-every-fixture-mean-two-things).

**The help stops promising a deletion it cannot make.** It said *"if you delete the contact, the
message history will be deleted too"*. `removeContact`'s own comment records the exception: an entry
from a version before messages were labelled by address keeps a bare name, `belongsTo` cannot match
one, so deleting every contact can leave that plaintext in storage where no screen reaches it — there
is a test named for it. The help now says what is deleted, that the app reports a failed write, and
the two things it does not promise, including the one no keyboard can promise: nothing here removes
what was already sent through the messenger.

## A claim about an event, made without checking the event

**Round twenty-one found that the previous round's fix bought the messenger a false security claim
for the price of one crafted paste.** The caution added there — *"This key reached you through the
messenger and the app cannot tell whose it is, compare the security number by voice"* — was posted
unconditionally on the arm that handles a message with no bundle. That arm attempts a decrypt and
discards the result, and a decrypt that fails pins nothing.

So: put an envelope on the clipboard with no bundle and arbitrary bytes as its ciphertext. The type
is decided by field presence alone, so it routes here; there is no session, so the decrypt throws;
the contact row was already created. The app then told the user a key had arrived that it could not
attribute, and sent them to compare a security number — on a screen with no fingerprint to render,
whose Reject button is re-armed only for a standing *warning*, which a caution is not. And because a
standing caution makes `mayOverwriteInfoBanner` refuse, the banner was then held for the life of the
strip, suppressing every later clipboard hint. The only way down was deleting the contact.

**It also painted over the truth.** An envelope carrying a bundle *and* a message runs both arms.
With the bundle refused, the first arm writes "Could not set up a session from that invite. Ask your
contact to send a fresh one" as a plain line, and this repaint composes the banner from the standing
items alone — so the one sentence telling the user what to do next was replaced by a claim that a
contact was created and a key arrived. The exact inversion the last three commits exist to remove,
introduced by them.

The gate was already in this file twice: `warnIfKeyWasRejected` requires an actual pin, and
`decryptMessageAndShowMessageInMainInputField` computes `keyPinnedByThisPaste` before choosing its
wording. The new caution was written with neither. It now asks.

**The lost-write notice forbade and offered the same act.** Its repaint runs `refreshActionButtons`,
and `disablesActionButtons` matches with `startsWith` — so a sentence whose first variable part is
the contact's name could not be matched at all, and the same repaint that posted "do not send them
anything until you have added them again successfully" turned Encrypt back on. `encryptAndSend` has
no storage guard of its own, so pressing it hands the messenger ciphertext for a session that exists
only in memory. The notice now opens with a fixed phrase so the buttons can agree with it.

**And two sentences promised the wrong event.** Both said "the next time the keyboard opens", which
is the one event that does *not* reload: `reloadAccount` runs only from `LatinIME.setInputView`,
whose only in-app caller fires on a theme or ui-mode change. Lowering and raising the keyboard
changes nothing. So a contact whose write was lost stays present and usable for as long as the
process lives — the opposite of what the sentence promised, on the surface it had just been moved
onto.

**The help item reworded last commit was wrong, and this is the correction.** It said messages from
an older version "cannot be matched to anyone". `LegacyKeyMigration` re-keys every pre-upgrade entry
whose bare name identifies exactly one contact; only the ambiguous remainder keeps a bare key, which
is what `removeContact`'s own comment says. The rewrite had generalised the remainder to all old
history, telling users their whole past was orphaned when most of it is attributed, shown, and
deleted with the contact. It now says that, and adds the omission that matters more than either:
deletion **keeps the pinned key on purpose**, so deleting is not a way to start over with someone —
which the app needs to say plainly, given how often its own advice mentions deleting and re-inviting.

**Three rounds running have now found the previous round's fix.** Not the same defect each time — a
sentence inverted, a check that reached one arm of three, a claim made without checking its own
subject — but the same *kind*: the fix was right about the threat and wrong about the conditions
under which it fires. The rule earned here is narrower than "test the fix": **a notice that asserts
an event must be gated on that event having happened, and the gate belongs beside the notice, not in
the caller that usually happens to be right.**

## The fail-open that made every fixture mean two things

**Done rather than deferred, because three consecutive rounds each found the previous round's fix in
the same few methods, and this was underneath all of them.**

Two helpers answered "did the write land", and in the one state where the answer is unambiguous — no
storage helper at all, so nothing can possibly have been stored — they disagreed.
`accountWriteSucceeded()` returned **true**; `storeAllAccountInformationInSharedPreferences()`
returned **false**, and `removeContact` returns that directly. So the app said a created contact was
saved and a deleted one was lost, about the same store, in the same breath.

The true-on-null branch fails **open**: every trust decision reported as saved while nothing is
stored. Unreachable in production — `LatinIME.setInputView` always passes a real context — but that
is not where the damage was. **Every test fixture inherited the split without saying so.** A fixture
that created a contact was on the success path; the same fixture deleting one was on the failure
path; nothing anywhere said which. Three tests were caught asserting state that held only because a
write had been reported one way rather than the other, and one of them had been green for rounds
while describing a deletion that never reached disk.

Closing it turned **48 tests red across 17 classes**. Each now states whether it depends on a write
landing, through one shared helper whose whole purpose is to make that a decision rather than an
inheritance. Two classes needed the call in a different place than the rest, and the difference is
instructive: one rebuilds its whole world per test in a helper that re-initialises
`SignalProtocolMain` — which drops the storage helper with it — and one runs its entire flow inside
`setUp`, so a helper installed at the end of `setUp` arrived after the write it was meant to
describe. Both would have looked like the migration failing rather than the fixture being wrong.

**The migration was not only bookkeeping.** Fixtures that delete a contact had been exercising a
*failed* deletion by accident, and now exercise a real one — including the ten cases in
`MessengerCannotClearAstandingWarningTest`, whose subject is which events may take a warning down.
Those were testing the event against a deletion that never happened.

`TheTwoWriteReportersAgreeTest` asserts both directions, so restoring the fail-open fails there
rather than quietly changing what a dozen fixtures mean. It is the same file that pinned the
disagreement one commit earlier, rewritten rather than deleted, because the disagreement is the
reason the agreement is worth asserting.

## The refusal that locked the keyboard

**Round twenty-two found six defects, four of them introduced by the two rounds before it. The worst
was a state the user could not get out of.**

**The lock.** The lost-write notice disabled Encrypt *and* Decrypt. The notice says "do not send them
anything until you have added them again successfully" — and adding them again means pasting their
invite, which needs Decrypt. Deleting them first does not help: a deletion whose write also fails is
correctly not treated as done, so the caution stays up while the row leaves the list, taking that
contact's verify screen and therefore the only unconditional clear with it. The banner then held a
caution nothing could clear, with **both buttons dark for every contact**, including ones whose keys
are fine on disk, until the input-method process was killed. A rotation did not help: the caution is
carried across a rebuild on purpose. The user could not read a message from anybody.

Encrypt alone carries the refusal now. Refusing to *send* is the whole of what the sentence asks
for; reading is how the user gets out.

**The gate that was defeated exactly when it mattered.** Button state was derived by matching the
start of the banner. The banner is composed **warning-first**, so any standing warning — a
post-rejection pin, a duplicate name, both reachable in the same `addContact` — pushed the notice
into the middle of the string and the match missed. Encrypt came back on precisely when a security
warning was already on screen. The refusal is a fact now, `mChosenContactReachedDisk`, recorded where
it is known instead of inferred from what the screen happens to say. Prose is not a data structure.

**The gate that asked the wrong question.** `hasPinnedKey` answers "is a key pinned now", and
`removeContact` keeps the pin on purpose. So after deleting a contact, any envelope at that address
made the app announce that a key had just reached the user through the messenger — weeks after it
had. It fired on the honest re-add too, where the message decrypts *because* it matched the key
already trusted, which this file elsewhere spends a paragraph explaining is the opposite of an
unattributable new key. The caller now passes what was pinned before it acted, which is the
`keyPinnedByThisPaste` shape the decrypt path had all along.

**And the two facts were folded into one.** "A key was pinned" and "the contact row reached disk"
have nothing to do with each other, but the lost-write notice had been put inside the pin caution —
so a refused invite whose write also failed said so for three and a half seconds and then looked
ordinary. They are separate again, and the row's notice is posted last, because when both are true
the row is the one to say: its advice contains the other's.

**Reject was the last member of the write family clearing unconditionally.** It put the standing
warning and caution down *before* writing. When the write failed, the toast said so briefly while the
persistent surface had already been wiped and nothing restored it — and on the next `reloadAccount`
the rejected key is pinned again with `rejectedAddresses` empty, which is the silent
trust-on-first-use `markKeyRejected` exists to prevent. `verifyContact` learned this first,
`removeContact` next, this last.

**A control of mine was hollow again, in the way already written down here.** Two of the three tests
for the reject fix read the banner text. `clearStandingWarning` lowers the flag and does not repaint,
so those assertions passed whether or not the warning was cleared. They assert the flag now, with the
clipboard test beside them proving the screen follows it. That lesson had been recorded twice before
and still had to be learned a third time on first draft — which is the honest reason it is being
written again rather than a new one.

## Stating the rule instead of fixing the case

**Four consecutive rounds each found the previous round's fix in one decision: which button is live,
and what the banner says while it is.** Not the same defect each time — a gate matched against the
start of a string that a warning could push it off; a refusal that disabled the very button needed to
escape it; a claim gated on the wrong question — but the same decision, four times. Case-by-case
tests kept passing because each was written for the case that had just been fixed.

So the two things that kept breaking are now stated as properties over the cross product of states a
hostile messenger can arrange, rather than as cases:

**One — the app never offers what it forbids.** If Encrypt is live, nothing on the lasting surface is
telling the user not to send. `encryptAndSendInputFieldContent` has no storage guard of its own, so
an Encrypt that is live under a "do not send them anything" banner hands the messenger ciphertext for
a session that may exist only in memory.

**Two — there is always a way back in.** Decrypt may be dark for reasons about the whole app (an
unreadable store, a password field, no contact chosen), never because one contact's row failed to
save. That distinction is the difference between a refusal and a trap: the notice tells the user to
add the contact again, adding means pasting, pasting needs Decrypt, and deleting first does not help
because a deletion whose write also fails is not treated as done.

Sixteen combinations, each built by driving real flows — a store that writes or does not, a real
invite, the real password-field entry point — so a passing combination is one the app can be in. The
two defects the previous rounds found are each caught by the invariant they violate: reinstating the
prefix gate fails the first, disabling Decrypt on a per-contact reason fails the second. A third test
asserts the sweep reaches both answers, because an invariant satisfied by never offering anything is
satisfied vacuously.

What it does not claim is that these are the only invariants worth having, or that every reachable
state appears in the sweep. It fixes the two that four rounds of case-by-case work kept breaking, and
that is the point: **when the same decision breaks repeatedly in different ways, the next test should
be about the decision rather than about the newest way.**

## The refusal the adversary could switch off

**Round twenty-three found seven defects, and the first of them defeated the invariant suite written
one commit earlier — because that suite enumerated states and the messenger does not need to reach a
bad state. It needs to move the app out of a good one.**

The send-refusal was a flag about "the chosen contact", reset on every recipient change. Hiding the
keyboard is a recipient change: `onKeyboardHidden` calls `forgetChosenRecipient`, and any app may
hide the keyboard whenever it likes. So could tapping the banner — a natural gesture on a notice just
read — or relaying a message from anybody else. The user then re-selects the contact and Encrypt is
live again, under a banner still saying "do not send them anything until you have added them again
successfully". The refusal survived exactly as long as the adversary allowed.

It is a property of the standing caution now, set and cleared with it, and scoped to that caution's
address. One fact, one lifetime, taken down by exactly the deliberate responses that take the caution
down. The sweep now runs each of its sixteen states through four adversary transitions — nothing,
hide-and-raise, tap-the-banner, clipboard traffic — and re-selects the contact afterwards, because
that is what a user does. **The lesson is about the shape of the test, not the fix: enumerating
states is not enough when the adversary owns the transitions.**

**The session write was the last one whose result went nowhere.** `buildSession` called
`storeAllAccountInformationInSharedPreferences` and discarded it, then returned true. Creation,
deletion, rejection, verification, the chat log and both message directions all thread theirs up.
The state that made it matter: the contact row lands, so no lost-write notice fires, and then the
session and the freshly pinned identity key do not — "Session with X created", a prompt to compare a
security number, and a session that exists in memory only until the next reload. Reported separately
from the return value on purpose: that boolean answers "was the bundle acceptable", and a storage
failure dressed as a refused invite would send the user off to ask for a fresh bundle over a full
disk.

Its flag then needed clearing in **`decrypt` as well as** in the writer, which is the sharper half of
the same lesson: a decrypt that builds no session never enters `processPreKeyResponse`, so the flag
went on describing whichever bundle was processed last — in one fixture, one belonging to a different
account. *A per-operation flag has to be cleared by every operation that reads it, not only by the
one that writes it.*

**The banner repaint reached one arm of two, again.** Gating the pin caution removed the only banner
write on the bundle-only arm. Reach it by following the app's own recovery advice — delete the
contact, accept their fresh invite — and every repaint is skipped: the duplicate-name warning is
deliberately suppressed for a re-add at the same address while the pin survives, the pin caution is
gated because the pin survived the deletion, and an invite carries no ciphertext. The banner keeps
"No contact chosen", and since `disablesActionButtons` matches that sentence, **both buttons go dark
on a contact just set up successfully.** The fallback repaint now covers every arm — and fires only
when nothing else wrote, because the first version of it was unconditional and destroyed the
refused-invite sentence, which is a plain line stored nowhere.

**And `FLAG_SECURE` came down while the warning was still on screen.** The predicate asked
`chosenContact` alone, on the stated premise that every main-view banner naming a contact is written
on a path where it is set. Two host-forceable events break that premise and leave the banner exactly
where it was: hiding the keyboard, which clears the recipient and deliberately does not touch the
warning, and a configuration change, where `adoptState` restores the warning and deliberately does
not restore the recipient. In both, the window became capturable while displaying who the user talks
to and the tag distinguishing them from a second contact of the same name — the exact pair the
predicate's own javadoc gives as its reason for existing. It asks the model for both halves now.

## A deletion that undid itself everywhere but on disk

**The three findings round twenty-three left owed, and the first of them was a dead end reached from
the one direction that removes the way out.**

A deletion whose write did not land was performed in memory and nowhere else: the row left the list
while disk still held it, its pinned key and its messages. The user was correctly told it had not
been saved — and then had no way to try again, because every route to a contact goes through the
contact list. Its verify screen was gone, so Reject and Verify were unreachable; a second delete had
nothing to delete. Any standing item about that contact had no deliberate response left, and a
caution holds the banner for the life of the process.

The row is restored now, the way `verifyContact` rolls back its badge. **What comes back is the row,
not everything** — the swept messages and the deleted session are still on disk, because that is the
write that failed, so the next reload brings them; the honest description of the interim state is
"the contact is here and their session is not until the keyboard restarts". Worse than a clean undo,
much better than a contact the user can neither see nor retry. It is also what the app already said
happened: the notice reads "they and their saved messages will come back", which until now was true
only after a reload the user cannot trigger.

**The escape hatch counted warnings and not cautions.** The verify screen re-enables Reject when a
warning stands with no fingerprint to show, precisely so a standing item always leaves one deliberate
response. A caution holds the banner exactly as a warning does, and one can stand with nothing pinned
— a bundle whose signature fails pins nothing, and if the contact write also fails the lost-write
caution goes up about a contact with no key. Verify dark for want of a number, Reject dark for want
of a warning, deletion refused by the same storage trouble that raised the caution. Cautions count
now.

**And the storage caution was erasing the refused-invite instruction.** They are independent facts —
a bundle can be refused whether or not the write landed — and the refusal line is a plain banner
write stored nowhere, so the repaint that posts the caution destroyed it. The user was left with a
storage notice and nothing saying the invite had failed, while the caution's own advice, "add them
again successfully", is not actionable with an invite that will never work. The two are carried
together.

**Three more fixtures turned out not to write**, found by the rollback rather than by inspection:
they delete a contact and assert the deletion happened, which had been true only because a failed
write left the in-memory pruning in place. That is the migration continuing to pay out — every one of
these was a test whose subject was a successful deletion and whose fixture performed a failed one.

## Four of my own fixes, undone

**Round twenty-four reviewed the working tree, uncommitted changes and all, and most of what it found
had been introduced within the previous few hours. Four of its findings were reversals of fixes made
the same day.**

**The partial rollback was worse than none.** A deletion whose write fails now restores the contact
row — but it restored only the row, leaving the swept messages and the deleted session gone. The
account batch writes the *whole* in-memory account, so the first later successful write — sending to
somebody else, receiving anything — persisted that half-state permanently. The user was told the
deletion had not happened and the messages would come back; what they got was a contact with no
session and no history, for good, whose every incoming message then fails to decrypt — and this
app's standard advice for a failed decrypt is delete-and-re-invite, the key-substitution window.
The rollback now restores the messages and the session record too. The retired display name is
deliberately left retired, because that asymmetry errs toward warning.

**The FLAG_SECURE fix disabled screenshots for the whole device.** Counting *every* standing item as
sensitive covered the reported gap and cost far more than it was worth: a caution goes up after every
successful contact add and comes down only when the user verifies, rejects or deletes that contact.
So from the first contact onward the flag was up whenever the keyboard was — and a FLAG_SECURE window
blanks the entire system screenshot, silently breaking screenshots in every app during ordinary
typing, which is the opposite of the decision the predicate's own javadoc records. Scoped to
warnings, which are the rarer, sharper case and the one the gap was actually found in. **The residue
is stated rather than hidden**: a caution naming a contact is capturable once the recipient has been
forgotten.

**The escape hatch was a false affordance with a permanent destructive side effect.** Offering Reject
when a *storage* caution stands is not offering a deliberate response — `rejectContactKey` marks the
address whether or not anything was pinned, and that record is deliberately permanent. Pressing it
either brands an address the user never complained about, so the contact's next genuine invite raises
a key warning that is simply untrue, or — if the write fails, which is the state the caution reports
— does that in memory and does not even clear the caution, because the clear is gated on the
rejection landing. Withdrawn. The dead end it was meant to open has been closed where it actually
was: a failed deletion now leaves the contact and its verify screen reachable.

**And "the last member of the write family" was only half of one.** `buildSession` has two arms that
write, and only the success arm was fixed. The failure arm is the one place a bundle-borne key
substitution is ever recorded — if its write is lost, the recorded change and the cleared badge live
in memory only and the next host-forced rebuild restores the pre-attack state. That arm records its
result now. The flag also had exactly one reader, inside `addContact`, so a rotation from an
*existing* contact was written down and never reported; that path reads it too.

**What this round costs to admit.** Two of the four were fixes for real defects that created worse
ones, and both were mine, made the same day, each with a test that passed. The tests were not wrong
about what they asserted — they asserted the reported defect was gone. What neither asked was *what
else changed*, and in both cases the answer was reachable in one step: a caution stands after every
add; the account batch writes everything. **A fix whose blast radius is not measured is a fix that
has not been finished** — this document has said that twice before, and this is the round that had to
pay for it in the same session.

## Keeping a fact where nothing else owns it

**The send-refusal has now been moved twice, and both moves were the same mistake in mirror image:
the fact was kept somewhere that something else owns.**

First it was a flag about "the chosen contact", cleared on every recipient change — and the messenger
forces recipient changes at will by hiding the keyboard. Moving it onto the standing caution fixed
that and created the reflection: there is one caution slot, so a successful add of any *other*
contact overwrote the caution and cancelled the refusal with it. The app stopped refusing to send to
Bob because the user had added Carol, which is not a fact about whether Bob's row is on disk.

It is now a map from address to the write count at the moment the failure was seen. Nothing else
owns that. It is not reset by choosing somebody else, not overwritten by another contact's caution,
carried across a rebuild, and it expires for the right reason: any later successful account write
puts the whole contact list on disk, so the refusal is dropped rather than left standing as a stale
claim — refusing to send to somebody who *is* saved is the same class of defect as the reverse, and
it is the one that teaches users to ignore the notice.

**And a suppression protected nothing while costing a sentence.** `sessionCreationFailed` was
recorded inside the guard deciding whether to *paint* the refusal line — a guard that fires when a
standing item already holds the banner, which is the common case right after adding anyone. So the
fact was lost exactly when the guard fired, and the lost-write caution then composed itself without
the refusal while overwriting the standing item the guard existed to protect. The user was told to
"add them again successfully" with an invite that will never work, and never told it had been
refused. Recording the fact and painting the line are now separate decisions, which is what they
always were.

**Three fixes, three mutants, three different tests caught them** — including the round-23 defect,
which the extended sweep still catches now that the refusal lives somewhere else entirely. That is
the useful property of moving a fact rather than patching its symptoms: the old tests keep working
because they were about the behaviour, not about where it was stored.

## Two mutants that survived, and what they were hiding

**Round twenty-five found the send-refusal broken in two more ways, and the tests written to fix them
both passed against the defect. The mutants are how that was discovered, and they are the reason this
section is not a list of four confident fixes.**

**The refusal expired while the sentence justifying it stayed.** The map entry was dropped when a
later write landed; the caution saying "do not send them anything" is cleared only by verify, reject
or a landed delete. Two halves of one fact, two lifetimes — the same mistake this refusal has now
made in three shapes. They expire together now.

**Every carried refusal was dead on arrival.** On a configuration change the order is: reload from
disk, **write back**, build the new strip, restore its state. The write-back moved the counter, so
every restored entry satisfied "a later write has landed" on its first read. The refusal was
destroyed by exactly the event it was carried across. The write-back is excluded from the counter,
and that is a correctness point rather than bookkeeping: it stores what it has just *read*, so it
cannot contain the row an earlier failure lost.

**The button was never the control.** Button state is recomputed from the banner's TextWatcher, so it
moves when the banner *text* changes — and the events that matter need not change it: re-selecting a
contact under a standing item repaints the same string, and a later successful write elsewhere
changes nothing on screen. The refusal is now asked at the moment of the act, inside
`encryptAndSendInputFieldContent`, which had no storage guard of its own at all. A dark button is a
hint; the guard is the control.

**And the trust-on-first-use arm was still silent.** A ciphertext-only envelope never reaches
`buildSession`, so the write inside `decrypt` is the *only* place the freshly pinned key and its
session are stored — and its result was discarded. That is the inviter's side of every conversation
this app sets up: the row lands, no notice fires, the user is sent to compare a security number, and
the pin exists in memory only until the next messenger-forced reload unpins it.

**The two mutants.** Both first-draft tests passed with the defect reinstated. The trust-on-first-use
test failed *every* write, so the caution fired from the row half and the discarded line was never
observed — the fix needed a store that fails only the second write. And the reload exclusion cannot
be tested behaviourally at all under Robolectric: there is no Keystore, so every write fails and any
such test is vacuous whether or not the exclusion exists. That one is a source-level guard instead,
which says out loud that it is asserting a shape rather than a behaviour, and asserts alongside it
that the counter has exactly one increment so the exclusion cannot be sidestepped.

**Two of this project's own mechanical guards fired during the work** — the short-circuit guard
caught a predicate I had made mutate while it sat on the right of an `&&`, and the
discarded-result guard caught the reload's write. Both were correct, and both caught the defect
before a review round did. That is the first time in this log the guards have got there first.

## The file the deletion did not check

**The store is two files with two commits, and the deletion's success was read from one of them.**

`storeAllInformationInSharedPreferences` writes `protocol_messages` first and `protocol` second, and
returns only the second. So a log commit that fails while the account commit succeeds — two
independent commits on very differently sized files, which is ordinary on a nearly full disk —
produced a deletion reported as **complete**: no notice, the standing warning about that contact
cleared, the row gone from disk, and that contact's plaintext still sitting in the log file, owned by
no row. `belongsTo` compares the full rendered address, so nothing matches it afterwards. It is
beyond the one erasure action the user has, permanently.

That is the outcome the app's own deletion refusal calls the worse of the two — *a contact row
removed while its plaintext stayed behind* — reached through the one file the return value did not
cover. It is now reported as its own fact, on the banner, worded as what it is: the contact went, the
messages did not, and nothing on this device can reach them now.

**A failed deletion also had no durable surface.** It was the last member of this family whose only
notice was a toast — three and a half seconds, and then a screen that looks like an ordinary success
with the row, the session, the pinned key and the messages all restored. A user who deleted a contact
because they suspected a key substitution, which is this app's own standard advice, had no way to
learn the deletion had not happened. It gets the standing caution and the send-refusal the failed
*add* has had for several rounds.

**Ordering, not scoping, is what makes that caution stand.** The leftover-plaintext caution names no
contact — the contact is gone, so nothing about them could ever take it down, and a caution nobody
can clear is the dead end this file keeps closing. But a null address is exactly what
`clearCautionIfAbout` treats as "about anyone", so posting it where the other notices go had the very
next line erase it. It is posted after the clears.

**And the rotation notice reached one of three arms the messenger chooses between.**
`getMessageType` dispatches on field presence alone, so appending arbitrary bytes as a ciphertext
moves a bundle from the bundle-only arm to the combined one. The reader lived on the bundle-only arm:
**one appended field and the notice disappeared** — and the combined arm is the *ordinary* shape for
a signed-pre-key rotation, so the arm with no reader was the common one. Both arms call one helper
now.

That is the fourth time a control in this file reached one of two or three places that needed it. The
pattern is specific enough to state as a rule: **when the messenger picks which branch handles its
input, a control on one branch is not a control.**

## A decision, rather than a third flip

**`FLAG_SECURE` has been widened once and narrowed once, and round twenty-five was right that the
narrowing left an inconsistency: the reason given for excluding cautions — device-wide screenshot
loss — applies to warnings too, and a warning is something a relay can raise on command by stripping
one unsigned byte from an invite.** So this is settled as a decision with its cost written down,
rather than moved again.

The line is drawn on what the state **means**, not on how sensitive the text is.

A caution is the app's report of *ordinary successful use*: one goes up after every contact added,
and comes down only when the user verifies, rejects or deletes that contact. Counting those puts the
flag up whenever the keyboard is, from the first contact onward — and a `FLAG_SECURE` window blanks
the whole system screenshot, so screenshots break in every app during normal typing, for as long as
the app is used normally. That is not a state anyone is being asked to leave.

A warning means something is wrong and the user is being asked to act. The flag is up while that is
true and comes down when they act.

**The accepted cost, stated rather than hidden:** a relay can raise a warning unilaterally, so it can
force screenshots off until the user responds. Weighed against it — the messenger this app treats as
the adversary *cannot capture the screen at all*, so the disclosure being defended here is against a
screen recorder, and what it would capture is a security warning naming a contact. The nuisance is
inflicted by an app already telling the user something about their keys is wrong, the user can end
it, and dismissing the keyboard restores screenshots meanwhile.

**The residue is real and recorded:** a caution naming a contact is capturable once the recipient has
been forgotten.

**And the refusal map no longer keeps entries for contacts that do not exist.** When the failure was
the row's own write the row was never on disk, so a later reload drops it from memory too — the
contact becomes unselectable and undeletable, and both other removal routes run only for a contact
the user can reach. It could not produce a false refusal, which is why it was a leak rather than a
hazard; it is closed because reasoning about it a second time cost more than the four lines. Both
directions are pinned: not sweeping fails one test, sweeping everything fails three.

## Comparing the arms instead of remembering them

**Four times a control has been added to one of the three arms an envelope can take, and four times a
review round found it. `getMessageType` dispatches on field presence alone, so the sender picks the
arm — appending arbitrary bytes as a ciphertext moves an envelope from one to another at no cost and
with nothing to forge. A control on one arm is not a control.**

`EveryEnvelopeArmCarriesTheSameControlsTest` compares the three arms to each other rather than to a
list somebody maintains: every notice helper on this surface is named `warnIf…`, `reportIf…` or
`cautionThat…`, so a control present on some arms and absent from others fails and names the ones
that lack it. Anything genuinely arm-specific goes in an exemption list with the argument, and a
second test stops that list naming arms or controls that no longer exist. The honest limit is stated
in the file: a helper that does not follow the convention is invisible to it.

**Writing it immediately turned up a fifth instance**, which is the point of it existing. The plain
signal-message arm had no rotation report, because that arm carries no bundle and so did not look
like a rotation. It writes the same session state: `decrypt` writes at the end of every successful
decryption — the advanced ratchet, and on a PreKey message the key it has just pinned by
trust-on-first-use. Losing that write means the message is delivered and the session state is not, so
the peer's next message fails to decrypt — and this app's standard advice for a failed decrypt is
delete-and-re-invite, the key-substitution window. This is the first defect in this document found by
a guard rather than by a review round or by a reader.

**And the short-circuit guard caught the fix.** `delivered && reportIfTheRotationWasNotSaved()` puts
a writer where Java may skip it, which is exactly how the identity-change warning stopped being
raised several rounds ago. It is called unconditionally into a local now — safe, because `decrypt`
sets the flag true at entry and only a write can make it false, so a decrypt that failed before
writing reports nothing. That is the second time in two ticks a mechanical guard has corrected the
work in progress rather than a review round correcting it afterwards.

## One sentence doing four jobs

**Round twenty-six caught a string being reused for four different failures, true of one of them —
and the previous commit had just put it on the busiest path in the app.**

`INFO_CONTACT_NOT_SAVED` says the contact *"was set up here"*, that they *"will be gone once this
keyboard restarts"*, and to *"add them again successfully"*. On a failed **deletion** every clause is
inverted: the row, messages and session are rolled back and disk was never touched, so the contact is
not gone and will not be gone — they are still there, which is the thing the user needs to know. On a
lost **session** write the contact exists and is on disk; what was lost is the ratchet, or a key just
pinned — and "add them again" is the delete-and-re-invite advice this file elsewhere identifies as a
key-substitution window, handed out for a storage fault. Adding the rotation reader to the third arm
last commit moved that wrong text onto **every incoming message**.

Each failure has its own sentence now, and each says what is actually true of it.

**The refusal machinery was inverted for deletions too, and it cost a send.** A failed deletion
recorded the contact as "not on disk" — false; the row is there, its *removal* is what failed. The
expiry rule built on that says a later landed write settles the refusal, which is right for a failed
add (the write supplies the missing row) and exactly backwards for a failed delete (the write
persists the *restored* row). And because expiry runs at the moment of the act rather than on the
repaint, the button stayed dark on the stale refusal — so one tap on it expired the entry, sent the
message, and wiped the notice in the same call. A failed deletion no longer touches that machinery:
nothing about it makes sending unsafe, because the contact, their key and their session are exactly
as they were before the user asked.

**And one unreadable value cost the user every contact they had.** The load substituted an empty list
for a contact list it could not read; the next raise wrote that empty list back over the stored
ciphertext. Every row and every verified badge, destroyed permanently and silently, even where the
bytes were recoverable. Values are sealed per key, so one can fail while the identity key, address
and protocol store all read fine — `storageState()` still reports READABLE and the strip shows "No
contact chosen", **byte-identical to a fresh install**, which the opening message's own javadoc calls
the worst possible reading of the situation because the obvious response to it is to re-invite
everybody. The migration beside it already guards against this exact shape and says so in its own
comment; the contact list had no such guard.

The whole write is refused now, not just the contacts key: skipping one key would protect the old
rows and silently fail to save new ones, which is the same defect in a smaller coat. Refusing is
reported, because every operation that needs a write already threads its result up — so the app
becomes read-only and *says so*, rather than quietly eating the contact list.

**A dead statement, and what it was hiding.** An expiry call sat one line outside the lambda it was
written for: it ran once at construction against an empty map and never on a tap, while its comment
described what it would do if it were one line further in. Removing it is trivial; noticing it
mattered, because it is why the expiry is concentrated entirely on the Encrypt tap, which is what
made the defect above a single-tap sequence rather than a slow drift.

## A row with no key, and the arm that filled it

**The caution that exists for exactly one event — a key arriving through the messenger that the app
cannot attribute — had both of its callers inside `addContact`. So it announced pins made while
adding a contact, and no others.**

A contact row with no pinned key is not an exotic state; the attacker makes one. Strip the one-time
pre-key from a genuine invite — one unsigned byte, covered by neither signature — and the bundle is
refused while the row, created before the bundle is processed, survives. From then on that address is
a **known contact**, so a later message from it routes to the message arms rather than to the add
screen. `isTrustedIdentity` returns true whenever nothing is pinned, and `decrypt` takes its PreKey
arm on the ciphertext type alone, so a bundle-less `PreKeySignalMessage` pins its own identity key by
trust-on-first-use. The user sees a decrypted message under "Detected contact".

Neither step needs a forged signature. **Omitting one optional field is what moved the envelope from
the arm that cautions to the arm that said nothing** — the fifth instance of that shape, and the
first found by a review round after the mechanical guard for it was written, because that guard
compares the three arms to each other and this control was on none of them.

The check now sits where every arm's pin passes through, on the decrypt path, next to the
before-value that was already being computed there and used for one sentence only.

**Two existing tests had to change, and they were right to.** Both asserted that nothing stands over
the banner after a healthy re-invite. A compare-the-number caution now does, and it is correct rather
than residue: that paste pinned a key at an address that held none, which is exactly the event the
caution describes — it had simply never been said on that path. What those tests were actually
guarding, that "always warn" must not pass them, is asked of **warnings** now, which is what the
guard was for.

## Two facts, one slot, again

**The notice that a deletion left plaintext behind was put in the caution slot, and the caution slot
holds one thing.** So the routine "Contact X created. Compare the security number" posted on the very
next add destroyed it — and a messenger can time that: relay an invite straight after the failed
deletion, the user adds the contact because adding contacts is what this app is for, and the notice
is gone. It also names no contact, which `clearCautionIfAbout` reads as "about anyone", so verifying
or deleting *anybody* cleared it too. Nothing re-asserts it, and it is the user's only chance to
learn of a condition no screen can otherwise show and their one erasure action cannot reach.

It has a slot of its own now. The previous commit's claim that ordering alone made it well-behaved
was wrong in both directions at once: too easy to lose in the ordinary case, and unclearable in the
case that produces it.

**And it has a real resolution rather than none.** The orphaned entries were already removed from the
in-memory log by the deletion; only the write failed. So the next message-log write that *does* land
persists the pruned log and the plaintext is gone — which is why the notice is tied to a
message-log write counter rather than to anything the user does to a contact. That was the tell: a
notice that no user action can resolve does not belong in a slot cleared by user actions.

**Four checks said "warning or caution" and had to learn about a third kind.** `mayOverwriteInfoBanner`,
the rebuild repaint, the reset-on-delete and the chosen-contact repaint were each written as an
explicit pair, so adding a third standing item meant finding all four — and the one that was missed
first is the one the test caught: the plain "Chosen contact: X" line painted straight over the
notice. They ask one shared question now, which is what should have been there before a third kind
existed.

## A bound that counted what the app does not distinguish

**The retired-name list is capped at a hundred entries and de-duplicated so the cap counts distinct
names rather than repeats. The writer compared raw strings; the reader folds.**
`hasRetiredDisplayName` goes through `displayNamesMatch`, which NFKC-folds, strips invisible
characters and maps confusables — so "Bob Jones", "Bob Jones " and a Cyrillic-B "Вob Jones" occupied
three slots and answered one query.

That is worth fixing rather than tidying because **a messenger can drive deletions**: replaying a
message makes the decrypt fail, and this app's own advice for a failed decrypt is
delete-and-re-invite. Varying the name each cycle meant the dedup never collapsed, so a hundred
*variants* could press a real entry out of the bound where a hundred repeats could not — and the
entry pressed out is what stops a later contact reusing a deleted person's name at a new address from
being accepted in silence. Writer and reader fold identically now, which is the only way a bound on
"distinct names" means anything. The address half is deliberately still compared exactly: it is what
makes a re-add at the same address unwarned, and folding it would suppress the warning at an address
the user never deleted from.

**And the canonical-encoding check was claiming a property it cannot have.** Its comment said that
without it "the prose has to sit outside the envelope where the user can see it is just text". That
holds on the RAW route, which is what the check defends. It does not hold in general, and cannot: the
receiver does not choose the encoding — the router sends anything containing an invisible character
to the FairyTale decoder — and that decoder accumulates bits from sixteen mapped code points and
ignores everything else, so visible prose around an invisible payload is the carrier working exactly
as designed. The encoder is public and keyless, so anyone can re-encode a genuine envelope inside
prose of their choosing.

The comment now says what the check defends and names the test that already pins the other half.
This is the same class as every wording defect in this document, applied to a comment rather than to
a notice: **a control that describes itself more broadly than it works is a control someone will
later rely on for the part that does not exist.**

## A sentence with no caller

**`INFO_NO_SAVED_MESSAGES` once became unreachable, and nothing noticed.** Both arms of the chat-log
screen were pointed at the unreadable-log sentence, so the constant sat in the file looking like a
feature while the arm that should have used it told users their deleted history was still on the
device. It took a review round to find, and the fix is recorded several sections above.

The mechanical half of that is now a test: every `INFO_` constant on the strip must be referenced
somewhere other than its own declaration. Recreating the original defect — pointing the empty-history
arm at the unreadable-log constant — fails it.

**What it does not claim** is stated in the file. A reference inside dead code satisfies it, so this
is not reachability; and it says nothing about whether the sentence is *true* where it is used, which
is what the tests around it are for. It checks the one thing that is mechanical: that a sentence
still has a caller at all. Notices are this app's entire trust surface and the branches beneath them
are edited every round, so a sentence losing its last caller is a live failure mode rather than a
tidiness question.

Fifty-two notices, all of them called. The exemption list is empty on purpose: a notice with no
caller should be deleted, not parked — and a second test stops the list naming constants that no
longer exist, which is the same rot every classification list here is guarded against.

## The advice that must never be given for a storage error

**This document's most-repeated finding is that "delete the contact and ask for a new invite" is a
key-substitution window, and that a messenger can provoke it at will by replaying a message or
flipping a bit.** Storage failures look the same from the user's side — something did not work — and
are completely different underneath: nothing about the keys is wrong, and the remedy is free space or
an unlock. A storage notice that reaches for that advice hands the attacker a second route to the
exchange, out of a condition they do not even have to cause.

It has been reached for twice. The receive-side lost-write notice was written on top of the generic
decryption-failure message; the lost-session notice reused the failed-add sentence, whose instruction
is "add them again successfully". Both were corrected in earlier sections. A scan over the seven
storage notices now fails the build if a third one does it, and its limit is stated in the file: it
matches the phrases this app actually uses and cannot catch advice worded in a way nobody has used
yet.

**And the help now explains the storage failures at all**, which it did not. The app grew several of
them — a contact that will not save, a session write lost, a deletion that did not happen, plaintext
the log could not drop, and a read-only mode when the contact list cannot be read — and the help had
one sentence about any of it. A user who has just been told something could not be saved goes looking
for what to do, so the notices deliberately not carrying the explanation only works if the
explanation exists somewhere. It says what the condition is, that reading still works, that a failed
deletion deleted nothing, why the app stops saving rather than replacing what it cannot read, and —
in as many words — not to swap keys over it. A second test pins that last sentence, because it is the
one that matters and it is the one that would be dropped as verbose.

## A method with no callers, and an invite dead on arrival

**The store notice's retirement had zero call sites.** It was wired in, then lost when a file was
restored from a snapshot taken before it — twice over, because the send-refusal's expiry went the
same way in the same restore. Nothing failed either time: a private method with no callers compiles,
and a notice that is never retired looks exactly like a notice. That one holds the banner, so its
absence suppressed every informational line in the app for the life of the process, permanently,
while its own advice ("try deleting another contact once the device has free space") actually worked
and left the sentence standing and false.

Both are wired again, and both now have a test that fails when the call site goes. That is the second
lesson of the section: **restoring a file from a snapshot is an edit**, and the ones it silently
reverts are the lines added since — which are exactly the lines nothing tests yet.

**The counter that retirement waits on was on the wrong object.** `initializeStorageHelper` builds a
*new* `StorageHelper` on every `reloadAccount`, so a count held there went back to zero on every
configuration change and "a later write has landed" could never be true again. The account counter
beside it was already on the singleton, which is why only one of the two had the problem: two
counters, one lifetime rule, and one of them followed it. It also counted no-op writes —
`storeMessageLog` returns true for "nothing to write", and a reload defers the log — so the reload's
own write-back reported a landed log write having touched nothing, at the exact moment the reload had
re-read the un-pruned log from disk.

**A phrase match had rotted into a wrong answer.** The caution retirement asked whether the standing
sentence contained "could not be saved". `INFO_CONTACT_NOT_SAVED` does; `INFO_SESSION_NOT_SAVED`,
written later and posted by the same code path, does not. So after a lost session write the refusal
expired while the sentence justifying it stayed — clearable only by verifying, rejecting or deleting
that contact, which is a security action taken for a storage reason. It asks a flag set beside the
caution now. *Searching a sentence for a phrase is a match on how something was worded, and wording
is the thing this project changes most.*

**And the invite was dead on arrival.** The export path writes the private halves of the keys whose
public halves are about to leave the device, and its own comment explains why that write cannot be
best-effort: the invite is carried to a messenger by hand, so a reload always intervenes before the
reply. The result was discarded. An invite exported while nothing can be written therefore produced a
reply this device could not decrypt — and the app's advice for an unreadable message is delete the
contact and ask for a new invite, which is the key-substitution window, reached out of a storage
fault nobody mentioned. The invite is refused now rather than handed over, and the send path reports
its own lost key state, which was the last write in the family with no reader at all.

## One warning slot, and what fits in it

**The duplicate-name warning was raised in exactly one place and never re-asserted, while the warning
slot holds one thing. That is an eviction an attacker can buy for one forged bundle.**

The warning fires when a second contact's rendered name folds onto an existing one — the file calls
it "the only control covering the case the pin cannot", because two rows the user cannot tell apart
is the cheapest bypass of the whole trust model. The attack: get a second "Bob Jones" added at an
address you control, then post one forged bundle for the genuine Bob. That second warning costs
nothing and calls `setWarningMessage` unconditionally, replacing the duplicate-name text. The user
then does exactly what the app tells them — compares the number for the genuine Bob, it matches
because the pin never moved, presses Verify — and the clear is scoped to the contact they just
verified. Both warnings are gone, the impostor row is indistinguishable from a healthy contact, and
nothing mentions it again.

It is recomputed now rather than remembered. Two rows sharing a folded name is a fact about the
contact list, so it can be asked at selection time like a pending identity change or a rejection
record — and **a warning that can be asked again cannot be evicted for good.**

**Three writers now share that slot, and the order they run in is the fix's other half.** They are
writers, so the last one to fire holds the banner. Chained with `||` the later ones were skipped
whenever an earlier one fired — the short-circuit guard caught that immediately, as it has every time
this file has done it. Calling them in severity order would have been worse: the least serious would
land last and hold the slot. So they run in *reverse* severity — shared name, then rejection, then
identity change — and a detected key substitution is written last.

**And the invite-refusal fact was being lost with its sentence.** Suppressing the refusal's banner
text while a more serious warning stands is right. Dropping the *fact* meant an attacker who first
raises any cheap warning could then strip the one-time pre-key — one unsigned byte, covered by
neither signature — from every subsequent invite, and "it does not verify, which means it was changed
on the way here" was never shown at all. That claim is strictly stronger than anything the pin
caution says. It is recorded per attempt now and said on a channel that cannot displace the warning,
which is a poor surface for it and strictly better than the nothing that was there.

That is the third time this exact separation has been needed — record the fact, decide separately
whether to paint it — and each time the suppression was correct and taking the fact with it was not.

## A sentence that travelled further than its meaning

**Moving the pin caution onto the decrypt path was right, and it took its wording somewhere the
wording was false.** The sentence began "Contact X created", because it was written for the two
`addContact` arms, where a row had just been made. On the decrypt path nothing is created — the pin
simply lands at a row that already exists.

The clearest reachable case is the app's own recovery flow. The user compares safety numbers, they do
not match, they press Reject; the app tells them *"nothing can be sent to them until they send a new
invite"*. The peer sends one, the pin transitions from absent to present, and the banner then reads
the post-rejection warning above **"Contact X created"** — two sentences contradicting each other
about the same event, on the screen whose entire job is to be believed. It now says "A key for X has
been stored" where nothing was created, with the same instruction after it.

**And the caution was being destroyed on the same paste that posted it.** Both facts are true of one
paste and there is one caution slot: the rotation reader replaced the pin caution with the
lost-session notice, whose advice is *"nothing here needs deleting or re-inviting, wait until the
device has free space"* — which does not contain "compare the security number". So in the state where
a messenger-supplied key had just been pinned by trust-on-first-use **and** the write failed, the only
sentence saying a key had arrived was the one removed.

`addContact` met this collision and settled it the other way, arguing the storage sentence should win
*because its advice contains the other's*. That argument was sound there and does not transfer: this
storage sentence says nothing about comparing a number. The pin caution is kept and the storage one
appended. **The reasoning, not the outcome, is what had to be carried across** — copying the
conclusion would have lost the notice that fires because nothing was noticed.

**A test broke for the right reason and was fixed the right way.** The extractor that pulls this
sentence out of the source anchored on the literal `setCautionBesideAnyWarning("Contact "`, so it
failed the moment the wording became a choice of two. Loudly, which is correct. It anchors on the
method now, and reads the whole body — so both wordings are covered, and a later edit that gave one
of them its own instruction would still be checked.

## An invariant that was written down and false

**`requireExhausted` says its job is that "refusing keeps a hostile envelope from smuggling data past
the parser". About five kilobytes could ride past it.**

The bundle carries a device count as one byte, so up to 255 entries were parsed and retained — while
`createPreKeyBundle` consumes `getDevices().get(0)` and nothing else. Entries 1..254 were validated
against nothing: about 300 bytes each, up to 255 unchecked bytes apiece in the signature field, and
filler entries free to set both optional flags to zero and reuse one genuine key. Exactly one device
is ever encoded and exactly one is ever read, so the parser now refuses any other count. Nothing
renders those bytes, so this was a broken stated invariant rather than the staple-prose attack the
canonical check defends — but **an invariant that is written down and false is worse than one never
claimed**, because the next person to rely on it has a sentence saying they may.

**And the presence flags had 255 spellings each.** They were read as `!= 0` while the encoder writes
0 or 1, so every envelope carrying an optional field had 254 alternative wire texts that decoded to
an identical object and passed both the canonical-encoding check and `requireExhausted`. Canonicality
is a property of the whole wire text; a byte with 255 accepted values is a hole in it.

That one is pinned by a property rather than a case: take a canonical envelope, change one byte, and
check that the result cannot decode back to the same envelope — sameness decided by re-encoding, so
two distinct wire texts meaning one message is exactly what fails. It is honest about its limit in
the file: one byte at a time, from a small set of values, so a malleability needing two simultaneous
changes is outside it. Loosening the flag fails it immediately.

**`registrationId` accepted 31 bits where the encoder can write 14.** libsignal's ids are 14-bit;
the parser checked only the sign. A test had been using 287 million as a "distinct id", which is how
long the gap had been visible without being seen.

**One check was tried and reverted, and the reason is worth as much as the fixes.** Requiring the
top-level device id to equal the bundle's own is superficially the same kind of tightening — the
encoder cannot produce a disagreement. But it buys nothing, because the top-level *name* is equally
sender-chosen, so an unpinned address is already free and an attacker simply writes both ids the
same. And it costs something: a disagreement is what a lazy splice produces, and refusing it at the
parser turns a substitution attempt the trust layer would have **warned** about into an unexplained
"not a valid encoded envelope". Refusing input is usually the safe direction; here it removes a
signal and blocks nobody. Twelve tests failing was the evidence, not the reason.

**And one deserializer was the exception to its own file's discipline.** Every sibling converts
unchecked failures to `IOException`; this one dereferenced two nodes and cast one. Not wire-reachable
— that path no longer goes through a general-purpose deserializer — which is why it is discipline
rather than a defect, and why it is worth keeping: discipline is what stops the next caller being the
one that makes it reachable.

## A warning nobody can answer

**Making the duplicate-name warning re-assertable closed a real eviction hole and opened a worse one:
a warning shown on every single send, for the life of the install, that no action ends.**

The predicate asks *live or retired*, and the retired half is suppressed only for a re-add at the
same address. This file says elsewhere, repeatedly, that a genuine reinstall **mints a fresh
address** — so the ordinary honest flow (delete a contact, they reinstall, add them again) matches
the retired half forever and the suppression never fires. Nothing prunes the retired list, so no
verification, deletion or comparison ends it: there is no second row to delete.

The cost is not just noise. `mWarningStanding` was then true on every selection, and selection
precedes every send — so every routine notice was suppressed for the life of the install and
`FLAG_SECURE` was pinned on. And a warning shown on every send carries no information, which is the
habituation this control's own javadoc is written to avoid. A messenger can drive the deletion loop
(replay a message, the decrypt fails, the app's advice is delete-and-re-invite), so it can put
contacts into that state deliberately and then run the real same-name substitution against a user
trained that this exact sentence means nothing.

**The rule that separates the two halves: a warning may be re-asserted only if the user can resolve
it.** Two live rows sharing a folded name is resolvable — delete one, which is what the warning asks
for and what its address scoping exists to support. A retired name is not. So the live half is
recomputed and the retired half stays a one-shot at add time, where it is news.

**And the composed caution was growing once per incoming message.** Appending the storage sentence to
a standing pin caution left the result containing "compare the security number", so the next paste
appended it again — and the messenger decides how many messages arrive. The banner has no maximum
line count, so repetition alone pushes the warning composed above it and the recipient line composed
below it off the screen. It also re-scoped the caution's address to whoever was chosen, which is the
cross-contact erase that field exists to prevent, and stored it with the storage flag false, which
disabled its own retirement.

All three are fixed by storing the composition **as a storage notice, about the contact it is
already about**. That the flag is what bounds the growth is not obvious, and is worth recording: a
separate "have I already said this" guard was written, and the mutant proved it dead — the flag alone
does the work, because a composed caution marked as a storage notice is no longer something the next
paste appends to.

## Three call sites, three answers to one question

**The same collision — a storage caution arriving while a pin caution stands — was reached from three
places and solved three different ways: one appended, one replaced, and one replaced while storing a
null address.**

Only the first was right, and the reasoning behind it is what the other two were missing: no storage
sentence contains "compare the security number", so replacing loses the notice that fires *because
nothing was noticed*, which is what a successful key substitution looks like from inside the app. The
null-addressed one was worse again — `clearCautionIfAbout` reads a null address as "about anyone", so
verifying or deleting any contact cleared it.

There is one composer now. It appends only when the standing caution is a pin caution **about this
contact**, so composing cannot re-scope somebody else's caution onto this address, and it always
stores the result as a storage notice — which is both true and what bounds it, since a composed
caution marked that way is not something the next paste appends to again.

**And `addContact` was reporting a lost session write with the contact-row sentence.** In the state
where the row landed and only the session write failed, that sentence says the contact "will be gone
once this keyboard restarts" — false, the row is on disk — and instructs the user to "add them again
successfully", which is the delete-and-re-invite exchange this project spent a commit removing from
storage notices precisely because a messenger can provoke it. The correct sentence existed twenty
lines away and says "nothing here needs deleting or re-inviting". The comment justifying the reuse —
*"same sentence for both, because it is true of both"* — was written before the second sentence
existed and was wrong the moment it did.

**The guard I built for this could not catch it.** `NostorageFailureAdvisesReInvitingTest` checks that
no storage notice *contains* re-invite advice. Here the notice was fine and the **state that posted
it** was wrong — the same sentence, true in one state and false in another. A scan over strings
cannot see that, and saying so is more useful than widening it until it appears to.

**A completeness guard caught the consequence immediately.** Adding a second sentence to `addContact`
made `INFO_SESSION_NOT_SAVED` reachable from a method that raises warnings, and the sweep that
requires every such constant to be swept or excused failed on it within one run — which is the third
time a mechanical guard here has reported a change's side effect before a review round did.

## The failure the app had no words for

**One storage failure was invisible to everything the app uses to describe storage failures, and it
is the one that lasts.**

Values are sealed per key, so the CONTACTS value can fail to open while the identity key, the address
and the protocol store all read fine — and `storageState()` trial-decrypts only the protocol store,
so it reports READABLE. The strip then showed an **empty contact list** under the ordinary "invite
someone" line: byte-identical to a fresh install, which is exactly the reading the storage warning
exists to prevent, because the obvious response to an apparently empty app is to re-invite everyone
and replace every key already compared.

Meanwhile every write is refused — correctly, since writing would replace the unreadable list with an
empty one — so the invite the user is invited to send is refused too, **every time**, with advice to
free up space that can never work. The app was silently permanently broken while saying nothing was
wrong.

It says it now, as a warning rather than a line, because what it has to head off is the same thing
the storage warning heads off. It says the contacts are still there, that nothing is being saved on
purpose, not to re-invite anyone, and that free space will not help — which is the clause that
matters, because it is the instruction the previous message gave and could not deliver.

**A fact with no consumer is a variable, not a separation.** `mLastInviteWasRefused` was added to
record the invite-refusal "separately from whether the sentence is painted", and nothing ever read
it — the toast beside it was the only surface either way. The separation that mattered was making
that toast unconditional; the field was speculative state on the surface where the last several
rounds of defects started, and it is gone. Its test now asserts the surface the user actually gets.

**And one fix was reverted for a better reason than it was made.** The decoder refuses any device
count but one; the encoder still accepts up to 255, and that asymmetry was reported as another
written-and-false invariant. Tightening the encoder was wrong twice: an attacker does not use it —
the decoder is the boundary and it is the side that changed — and it is what the tests use to build
the hostile multi-device wire texts that prove the decoder refuses them. Tightening removed the
ability to construct the adversarial input while protecting nothing. **The claim is corrected instead
of enforced**: what is true is that the app's own callers build exactly one device, which is why
refusing more on the way in cannot reject anything legitimate.

## A displaced warning is one that comes back

**The banner holds one warning, so warnings displace each other. The obvious fix made things worse,
and the test that proved it was one written three ticks earlier for a different attack.**

Three writers run on every selection in reverse severity, so the most serious wins the slot *for the
contact being tapped*. That orders them against each other and not across subjects: tapping a contact
whose name is shared displaces an identity-change warning about a **different** contact, or the
address-less storage warning. The obvious fix — make the least severe one yield to any standing
warning — was tried, and `AforgedBundleCannotEvictTheDuplicateWarningTest` failed within one run: if
this warning yields, an attacker raises one cheap warning about somebody else and the duplicate-name
warning never returns. **Closing a displacement by reopening an eviction is strictly worse**, and the
older test knew it.

So the property worth having is not "no displacement" but **displacement that undoes itself**. Every
warning on this surface is now recomputed rather than remembered: the identity change, the rejection
and the shared name are re-derived on selection, and the storage warnings are re-raised on every
`setInputView`. Looking at a warning's own subject brings it back. That is now pinned for the two
ends of the range — the most frequent contact-scoped one and the app-wide one — and both mutants
kill: stop re-deriving either and the test says so.

**And the retired-name bound was still counting what the reader does not distinguish.** Folding the
name closed the attack that varied the *name*; the address half was still compared exactly, and the
address is derived from a peer-chosen value the attacker mints fresh for nothing. So the same
eviction ran through the field that had not been fixed: a hundred delete-and-re-add cycles at
different addresses fill a hundred slots under one name. It counts names now, which is what the
reader matches on. What is given up is in the safe direction — the stored address only suppresses the
warning for a re-add at that same address, so collapsing to the most recent one costs a warning the
user did not need rather than one they did.

## Twenty-eight seams and no way in

**The seams that let tests drive this surface have grown to twenty-eight, and several of them
fabricate standing items.**

Most are inert if they leak — `showContactListForTest` shows a screen the user could reach anyway,
which is why the existing guard covers only `testIsRunning`, the one whose leak changes protocol
behaviour. But `setWarningMessageForTest`, `setCautionForTest` and `setStoreNoticeForTest` write
directly into the warning, caution and store-notice slots, and those slots are the app's entire trust
surface. A production path into one of them could put a security sentence on screen that describes
nothing — or, worse, hold the banner with a fake so a real warning cannot be written, which is the
wedge several sections of this document are about.

Nothing enforced that they had no production caller. They do not: twenty-eight seams, one
seam-to-seam call, and no other. That is now checked rather than true by luck, and wiring any of them
into a production method fails the build.

Its limits are in the file, as they should be: it matches the naming convention, so a seam not called
`…ForTest` is invisible to it, and it cannot see reflection. What it does check is the shape that has
gone wrong elsewhere in this project more than once — **a thing that exists only for tests acquiring a
caller that is not a test.**

## Nine Errors nobody could catch

**Found by asking which production classes no test names at all — three, and one of them answered a
corrupt record with `throw new AssertionError(e)`. So did eight of its neighbours.**

It is libsignal's own idiom, and it encodes an assumption this project has already disproved: that
bytes the app wrote itself cannot come back corrupt. They can — the store is sealed per value, one
flipped byte makes GCM refuse, and the chat log has had an unreadable-path for exactly that for many
rounds.

**The type is what made it dangerous.** `AssertionError` is an `Error`, and every guard on the paths
that reach these stores catches `RuntimeException` or `Exception`. This file already says so, in as
many words, about `OutOfMemoryError`: *"an Error that neither this method's catch (RuntimeException)
nor the clipboard listener's catch (Exception) stops"*. The stores are called by libsignal from
inside `decrypt`, which runs from a click listener — so a corrupt record killed the input method in
whatever app the user was typing in, and the condition is persistent, which makes it crash-on-tap
until reinstall. The same sentence that identified the hazard sat nine files away from nine instances
of it.

They throw an unchecked exception the existing guards catch, so a corrupt record becomes a decryption
failure the user is told about rather than a keyboard that disappears.

**The test asserts the type family, not our class.** The first version required our own exception and
failed on the session store, which fails inside libsignal with its own unchecked type — caught by the
same guards, and therefore fine. Requiring our class would have been asserting the implementation
rather than the property that keeps the keyboard alive. A source scan sits beside the four
behavioural cases, because what is being forbidden is the idiom rather than any one instance.

## One slot, two facts, four defects

**A review round was asked whether this surface is converging, and its answer was the most useful
thing it produced: yes, and five of its seven findings are the same defect.** The caution slot held
two independent facts about one contact — a key was pinned that the app cannot attribute, and a write
did not land — in one string with one flag. Every writer therefore hand-rolled the merge, and every
hand-rolled merge lost something:

- the composer could not compose onto its own output, so a **second** storage failure for the same
  contact dropped the pin half;
- the refusal retirement cleared the whole composed string, justified by a comment saying the pin
  caution would be re-posted by the next paste that pins — it never is, because that method returns
  immediately once a key is pinned, and after the pin it always is. So any later write anywhere
  deleted the sentence saying a messenger-supplied key had been pinned, and the messenger chooses
  when that happens by relaying any message at all;
- one call site never reached the composer and simply overwrote;
- and an address-less storage notice re-scoped the whole caution onto whoever was chosen.

**Two fields make all of that unnecessary rather than careful.** There is no merge, no flag, and no
call site that has to remember which half it is holding.

**And splitting them exposed a distinction neither behaviour had.** The old replacement was justified
by containment — "do not send them anything until you have added them again successfully" subsuming
"compare the number before sending anything private". That argument is sound when the contact is
about to disappear and false when it is not, and both cases were treated the same. When the **row**
write fails the contact will not survive the restart, so asking the user to compare a security number
is asking for work about to be thrown away; when only the **session** write fails the row is on disk,
the contact stays, and the key just pinned by trust-on-first-use is precisely what should be
compared. The code asks which write was lost now.

**The review's negative note is recorded because it is fair**: each recent fix has shipped with a
justifying comment that is wrong on a fact the code beside it settles — "the pin caution is
re-posted", the containment argument above, "every warning is re-derived on selection". The reasoning
was being written faster than it was checked against the body. Three of that round's seven findings
were exactly that, and they are why this section exists.

## A warning raised from a condition has to come down with it

**Two warnings here describe a state rather than an event, and nothing ever lowered them.** When the
condition went away, the method's remaining branch treated a standing warning as "leave the banner
alone", so the sentence stayed. Every clause was then false — including the one describing its own
exit, *"this clears when the device can read its own storage again"* — and it held the warning flag,
which suppressed every informational line for the life of the process. The only way out was pressing
Verify or Reject on some contact: a security gesture performed for a cosmetic reason, which this file
calls a false affordance everywhere else.

Only those two are lowered, and only when they are the text standing. **Every other warning is about
an event, and an event does not stop having happened** — a key substitution is not undone by the
store becoming readable. Both halves are pinned: the condition warning comes down, the event warning
does not.

**And the retired-name dedup went back to keying on name AND address, one round after being changed
to name alone.** The case for name-only was that the reader matches on the name, so a bound meant to
count names should not be fillable by varying the address. That attack is real and it is *expensive*:
retired entries are created only when the **user** deletes a contact, so it needs a hundred
add-and-delete cycles the user performs, and an attacker cannot mint entries under a name the user
never types.

Name-only bought a far cheaper attack in exchange. The user deletes the genuine "Bob" at one address;
an impostor invites as "Bob" from another, which correctly warns; the user heeds the warning and
deletes the impostor — and that deletion, under the same folded name, **evicts the genuine entry**.
Deletion deliberately keeps the pin, so the impostor's next invite at that same address is suppressed
and arrives with no warning at all. One cycle, using the name it is impersonating as the eviction
key, turning a firing warning into silence.

A hundred user-driven cycles to crowd out an entry is worse than nothing; one attacker-driven cycle
to delete the exact entry that would have warned is worse than that. **Both readings were defensible
from the code; only counting who pays for each cycle decides between them.**

**A mistake worth recording:** the edit that changed this back deleted the de-duplication line
entirely — a comment replacement swallowed it — and three tests failed at once saying a hundred
entries had accumulated where two were expected. That is what those tests are for, and it is the
second time this session that a mechanical guard has caught a bad edit rather than a bad design.

## The warning that needed a way to be answered

**The retired-name warning could be raised and never re-derived, and both fixes tried for it were half
right.** Re-asserting it on every selection put a sentence on every send for the life of the install,
because nothing prunes the retired list — habituation, which is what this control's own javadoc is
written against. Not re-asserting it meant an attacker displaced it with any cheap warning, the user
resolved that one, and the impostor row was indistinguishable from a healthy contact from then on.

What was missing was not a policy but **a resolution**. The warning's own text says the app "cannot
confirm that this is the same person coming back" — and comparing the safety number by voice is
exactly how the user confirms it, and the only way anyone can. So a contact whose number has been
compared is no longer warned about, the question is asked in full on every selection, and the warning
ends the moment the user does the thing it is asking for.

The retired entry itself stays. The suppression is scoped to the one address whose number was
compared, so a **later** contact reusing that name is still warned about — which is the whole reason
the entry exists.

**And the same "raise but never lower" defect was here too.** Recomputing the answer is only useful
if both answers are acted on: verifying made the condition false and nothing took the sentence down,
so the first attempt at this fix passed its first assertion and failed its second. That is now the
third instance of this shape in the file — the storage warnings, the contacts-unreadable warning, and
this — and all three are lowered by the same rule: **a warning raised from a condition is lowered
when the condition goes; a warning raised from an event is not, because the event still happened.**

That closes the last finding from the last general sweep. The round that produced it judged the
surface to be converging and recommended one more pass scoped to the caution slot, then stopping;
that pass is done, and its four symptoms turned out to be the one missing abstraction it predicted.

## Conditions and events, decided once

**The same rule was discovered three times before it was written down: a warning raised from a
CONDITION must be lowered when the condition goes, and one raised from an EVENT must not, because the
event still happened.**

Each discovery cost a defect. The storage warning kept asserting an unreadable store after it
recovered. The contacts-unreadable warning kept asserting it after an unlock — including the clause
describing its own exit. The shared-name warning kept asserting a clash after the user had resolved
it by comparing the number. In every case the raise was right and nothing took it down, and in every
case the sentence held `mWarningStanding`, which suppresses every other notice for the life of the
process.

Both halves of the rule matter and they fail in opposite directions. A condition warning that cannot
be lowered is a permanent banner and therefore a permanent silence about everything else — in an app
whose entire trust surface is sentences. An event warning that *can* be lowered by a passing refresh
is worse: a detected key substitution is not undone by a disk emptying, and a warning a refresh can
forget is one the messenger can arrange to have forgotten.

So every method that raises a warning is classified, a condition raiser must contain its own lowering
path, and an event raiser must contain none. Both mutants kill: remove the lowering from a condition
raiser and one test fails; add one to an event raiser and the other does. **The classification is the
point rather than the list** — a new raiser fails the build until somebody decides which kind it is,
which is the question that was skipped three times.

## A fix that could not run, and a test that could not tell

**The lowering path added for condition warnings was unreachable in production, and the test pinning
it described something `LatinIME` does not do.**

`refreshOpeningMessage` has one production caller, and the view it is given is always freshly
inflated — so `mWarningStanding` is always false there and the lowering never ran. Worse, the real
order is `refreshOpeningMessage` and *then* `adoptState`, and `adoptState` re-posted the very warning
the lowering was meant to remove. The test called `refreshOpeningMessage` twice on one strip and
called that "what LatinIME does on every setInputView". It does not, and the control was hollow —
green while the defect stood.

The cost of that was three permanent harms after storage recovered: a banner asserting something
false about the store including the clause naming its own exit; `mWarningStanding` held, so every
other notice suppressed for the life of the process; and **Encrypt and Decrypt left dark on an
install whose storage works**, with no user action that clears it.

Condition warnings are now **re-derived** across a rebuild rather than replayed — asked again, with
the answer deciding — while event warnings are still replayed, because the event still happened. The
test drives the real order: surrender, inflate a new strip, refresh, adopt.

**And a landed write was erasing a notice about a different failure.** The failed-delete arm
deliberately records no send-refusal; but the expiry keyed on the *address*, so a refusal left by an
earlier failure at that address retired the deletion notice anyway — and a failed deletion rolls the
row back, so the entry survives the sweep for contacts that no longer exist. Two storage failures in
sequence and the user was left believing a deletion succeeded while the contact, its pinned key and
its plaintext were all still there, on a screen byte-identical to a healthy one. A refusal means
"this row is not on disk" and a later write settles it; a failed deletion is the opposite claim about
the same address and no write settles it. They are told apart now.

**Two process failures worth recording.** A `git add -A` swept a review agent's temporary probe file
into a commit — it is removed, and the lesson is that a broad add is not safe while anything else is
writing to the tree. And the first version of the test above recorded its refusal at the wrong
address, so the mutant survived: the test passed with the defect reinstated. **The mutant is the
only reason either was noticed.**

## Two questions, one list

**The retired-name list was keyed twice, each way bought its own attack, and neither was a keying
problem.**

The list remembers the names of deleted contacts so that a name coming back can be warned about. It
was keyed by name and address for one round and by name alone for the next, and the argument
reversed each time, because two different questions are being asked of one list:

- the **bound** asks *how many names do we remember*, and the reader matches on the name — so an
  entry per address let a messenger that drives deletions mint entries for free by varying the
  address, pressing a real name out of a hundred-entry list without the user ever typing the
  impostor's name;
- the **suppression** asks *was this exact address one the name was deleted from*, because deletion
  keeps the pin and a re-add there is provably the same identity — so collapsing to the name alone
  made deleting an impostor "Bob" **evict** the genuine "Bob", and the impostor's next invite
  arrived silent. One attacker-driven cycle, using the impersonated name as the eviction key.

They are two facts about one name. One entry per folded name, carrying its addresses as a **set**,
answers both: the bound counts names, so varying the address merges instead of accumulating, and the
suppression still needs an exact address, so deleting the impostor **adds** its address rather than
removing the genuine one.

**The merge had to not widen the suppression, and the obvious reading would have.** With an entry
per address, a second retirement of the same name elsewhere left a record matching the name and not
the excluded address, so the reader warned. Under a set, "the set contains this address" suppresses
instead — and that case is the one the control exists for: a genuine Bob deleted at one address, an
impostor Bob deleted at another, the impostor coming back. So the rule is **every** recorded address
must be the excluded one. That reproduces the old answer for every input, and an attacker cannot
shrink a set back to one.

Two bounds now, and the second one's direction is the argument for allowing it at all: a name's
address set is capped, and dropping the oldest loses **silence, not a warning** — an address no
longer listed is one the suppression no longer applies to.

**And the loader had to be changed with it.** It read a fixed three elements. Reading the set back as
its first address would have discarded the rest on the next `setInputView` — which is every time the
keyboard is raised — so the set would have been an in-memory nicety while the suppression quietly
went back to answering for one address. That is the same failure this file already records once, and
it is the reason the reload test exists rather than being assumed.

Four mutants were run against this: the reader taking "contains" instead of "every", the loader
keeping one address, and both earlier keyings. Each is killed by the test that claims that property,
and by no other.

## The clause the argument rested on, and did not mention

**The set-of-addresses shape held. The written argument for it was wrong in a way that would have
taken the next maintainer straight into the attack.**

A review round could not reach silence against the code as committed, and said so. What it found
instead: the property making the shape safe is **one un-asserted clause**, and every one of the five
new tests passed with that clause deleted.

The clause is the de-duplication inside the merge. Without it, the sequence is cheap: the messenger
drives eight delete-and-re-invite cycles at its **own** address — one replayed message each, the
app's own advice supplies the rest — the set fills with eight copies of that one address, the
genuine address falls off the far end, and every address in the set is then the impostor's, so the
impostor is suppressed **permanently** while the genuine contact warns forever. The test that came
closest repeated a single address five times and asserted the set stayed quiet — which it does
either way, because five identical addresses are still all-equal. **The mutant the whole change was
about was the one mutant not run.**

**And three comments stated the safety argument in a form that is false.** They said suppression
"needs a set of exactly one address", which is true only of *distinct* addresses — a set of eight
equal ones suppresses, and the trim produces sets of eight. A fourth, worse: the bound's javadoc said
dropping an address "loses silence, not a warning". The reader settles it the other way. Every
address in a set is a reason **not** to suppress, so removing one can only make suppression easier.
The conclusion happened to be right; the reason given was inverted, and a maintainer who trusted it
and lowered the bound toward one would have walked into the paragraph above.

What actually makes the trim safe is the **size** it leaves, and that is now what the comment says.

**Three smaller things, all in the same direction — the shape changed and its neighbours did not.**
The legacy migration blanked element two of an entry that now holds a set, so it would have migrated
most of the thing it exists to refuse; unreachable today, fixed and tested anyway, because a
migration written to a shape the record no longer has comes back wrong the next time the shape
changes. The loader folded nothing, so a store written by the previous build kept one entry per
address and the hundred-name bound went on counting addresses until the user deleted that name
again. And the reader indexed elements the writer guards against, which would have turned a shape
neither can produce into a crash on a click listener.

Four more mutants, each now killed by the test that claims it: the merge without de-duplication, the
trim reversed, the loader without the fold, and the migration blanking one element.

## Once per process is not "every time the keyboard comes up"

**The lowering path for the two condition warnings was still unreachable after the round that was
written to make it reachable.**

Last round moved the re-derivation into the rebuild. A reviewer then established the real call graph
rather than reading the comments: `refreshOpeningMessage` has exactly two callers, `setInputView` on
a freshly inflated strip and `adoptState` guarded on the same flag — and `setInputView` runs **once
per process** unless the theme changes, because `KeyboardSwitcher.updateKeyboardTheme` is the only
in-app caller and it returns early when the theme has not changed. So both callers guarantee no
warning is standing, and the branch that lowers one could not execute at all.

The sequence that costs something is ordinary: the keyboard starts while the device is locked, or
while the contact list is sealed. The banner says so — including the clause naming its own exit,
"this clears when the device can read its own storage again, usually after an unlock". The user
unlocks. Nothing re-asks. The sentence stays for the life of the process, holds `mWarningStanding`
so **every other notice is suppressed**, and leaves Encrypt and Decrypt dark on an install whose
storage works. On the contacts arm there is no exit at all: the suggested Verify or Reject acts on
the contact list, which is exactly what cannot be read.

`onStartInputViewInternal` **does** run every time the keyboard comes up, and already reaches into
the strip. It now asks whether a condition warning is standing and, only then, re-reads the store
and re-derives. Gated for two reasons, both load-bearing: an ungated refresh repaints the opening
banner, wiping whatever the strip is currently saying on every raise; and re-asking means a store
read, which is worth paying for exactly when the answer on file is "it could not be read".

**The guard for this had to change shape.** The existing one asserted that a condition raiser's body
*contains* a lowering path — which certifies dead code just as happily, and did. The new one asserts
**reachability**: the per-raise entry point must ask the question, re-read the store, and re-derive.
That is the check that would have caught this a round earlier.

Two smaller corrections in the same commit. The re-derivation inside `adoptState` is redundant in the
order `LatinIME` uses today — reaching it already means the fresh strip's refresh raised nothing —
and its comment claimed a mechanism that was not running; it is kept, and now says why: it makes the
method independent of an ordering it does not control, and dropping a carried condition warning
without asking is the fail-open direction. And the new guard reads `LatinIME.java`, which tripped
the rule that every repo file a test reads must be a declared task input — caught by that guard, not
by a run.

**One instrumentation run out of two aborted natively**, deep in the framework's view-tree draw with
no application frame in the stack, during the foreign-app ciphertext test; the re-run was 30/30. It
is recorded rather than explained: this file already notes that multi-core TCG crashed the guest's
system server, and an unexplained abort in the same environment is worth a line even when the next
run is green.

## A control on one branch is not a control

**Two reviews, six defects, and the largest one is the rule this file keeps re-learning.**

**The duplicate-name warning was re-derived where the user taps a row, and not where the messenger
chooses one.** `warnIfNameIsShared` had exactly one caller: `selectContact`, the contact list being
tapped. The three decrypt arms move the recipient through `setChosenContact` and re-derived the
other two warnings — the identity change and the rejection — but not this one. Which path runs is
not the user's choice: the messenger decides what arrives and the user only presses Decrypt.

So the eviction this control was rewritten to survive still worked, one branch over. Add an impostor
row under a name the user already has (which warns, correctly), displace that warning with any
cheaper one about somebody else, let the user resolve *that* — and from then on every relayed item
from the impostor selects it in silence, banner reading like a healthy contact, Encrypt aimed at the
row the messenger picked. The re-derivation now lives in the setter, where no future path can
forget it.

**The same shape twice more, in the same commit.** The plain-message arm gives the recipient back
when nothing decrypted, with a comment explaining that the address which moved it is an unsigned
header the relay copies from any envelope. The two bundle arms did not — and which arm handles an
envelope is decided by field presence alone, so the undo cost **one appended field** to escape.
Both arms undo it now, each asking about the halves it actually carries.

**And "the attached bundle was refused" had three outcomes read as two.** The flag was set only on a
false return. Bundle processing can also *throw* — a stored session record that cannot be read back
raises from inside libsignal's own store callback, which needs one flipped byte and no attacker at
all — and a throw left the flag exactly as cleared. The strip then retracts a standing "that invite
was changed on the way here" warning and says "Detected contact" for a bundle nobody looked at. It
is set in a `finally` now: *threw* maps to refused, not to accepted.

**The fix from last tick had two defects of its own, both found before shipping anything else.**
The lowering path ends by painting the opening line, and it wrote that line **raw** — the only
banner writer in the file that never asked whether a caution or a store notice held the banner. That
was invisible while its only callers were a fresh strip and `adoptState`; a keyboard raise reaching
it on a live strip is what made it matter, and a live strip in that state is precisely one holding
cautions. Their fields stay set when the paint is lost, so nothing would ever have written them
again — the fix for one erasure performing another. Over a password field it wiped that notice too.

**And the re-read itself was too blunt for the cadence it now runs at.** On the contacts-unreadable
arm every other value reads fine, so a plain reload builds a whole replacement account — contact
list substituted empty — and installs it. Every write has been refused since the fault began, so
whatever the session did exists in memory only and is destroyed: on a rebuild that happened once per
forced configuration change; on every raise it happens whenever the messenger cares to lower and
raise the keyboard. The re-read now adopts only when it actually recovers something. What it costs
when it *does* recover — the session's memory-only rows — is deliberate and written down: keeping
them would mean the next successful write replaces the user's real contacts with a list that never
held them.

**Two things recorded rather than fixed.** The per-raise call is unconditional today only because
`onStartInputViewInternal`'s own suppressor is dead code — it fires under a flag with no assignment
to `true` anywhere in the file. Restoring that setter, an ordinary merge outcome, would give back a
window per rotation in which a raise skips the re-derivation. And the reachability guard now demands
the recovery-gated re-read by name and demands it *before* the method's first early return, because
asserting on the substring `reloadAccount` alone did not catch swapping it back — the safe call
contains it.

Seven mutants this round, each killed by the test that claims it.

## A return value that meant something else

**The recipient undo added last round read a boolean as "nothing decrypted". It does not mean that,
and the cost was the user's message.**

`decryptMessageAndShowMessageInMainInputField` answers "was this envelope wholly good". Its refusal
branch **displays the plaintext and then returns false**, deliberately — the message is authenticated
by the existing session and has nothing to do with a bundle stapled to it. So on the combined arm,
"the bundle was refused" collapsed the return value to false whether or not a message had been shown,
and the undo fired: `setChosenContact(null)` empties the compose box, so the message the user had
just been handed was wiped in the same call.

That is not exotic. The combined arm is the **ordinary** shape for a signed-pre-key rotation, and
stripping the one-time pre-key — one unsigned byte a relay removes — makes the bundle refused while
the message beside it still decrypts. Staple that to every relayed message and the app's main
function is suppressed per message, with no crypto and nothing on screen to explain it.

Two changes, and the tests had to be built so that **each is load-bearing on its own**. The strip now
records whether it displayed a message rather than inferring it. And the undo gives the recipient
back **to what it was** rather than to nobody: nulling is a further change, not an undo, and it
empties the compose box on the way past. The first pair of tests killed neither mutant, because with
a recipient already chosen the restore alone is enough, and with the fact recorded the restore is
never reached — each fix masked the other. Two more cases split them: a raise with no recipient
chosen, where the undo really moves something, and an envelope where neither half is any good.

**All three arms now call one undo**, and the arm-parity scan can see it. It could not before —
the scan matches controls by naming convention, so it reported parity it did not have, which is how
the one-arm-only version survived three rounds. That was the reviewer's point, and it applied to the
scan's own blindness as much as to the code.

**Two more from the same round.** Moving the shared-name re-derivation into the recipient setter had
a consequence I did not trace: the invite-refusal warning is posted only when nothing is already
standing, so for any contact whose name folds onto another, "that invite was changed on the way
here" became a three-second toast — silence bought for one unsigned byte, handed back by a fix for
something else. The refusal is about *this envelope* and now outranks a warning derived from the
contact list. And the lowering's final paint still wrote "No contact chosen" over a strip that has
one, which `disablesActionButtons` matches by prefix — so it darkened Encrypt and Decrypt on an
install whose storage had just recovered. It goes through the chooser that knows about the recipient.

**One reported defect that was not one.** The reachability guard was said to be satisfied by the
comment naming the safe call. It is not — the scan strips comments — and the mutant says so:
deleting the call while keeping its comment fails. The dependency is real even though the defect was
not, so it is written down rather than left to be re-derived. What the same finding got right is
smaller and was fixed: the ordering check excused a missing landmark, so an ordinary rewording of a
log line would have made it pass vacuously.

## Two attacks that would work if the library were built differently

**The key-material lifecycle had never been reviewed. It was, and the two most serious findings do
not reproduce — which is a result worth writing down as carefully as a defect would be.**

The reasoning behind both was sound. The Kyber replay guard records the sender's base key inside
`markKyberPreKeyUsed`; libsignal calls that during session derivation; derivation happens before the
inner message is authenticated. If those three held together, **one flipped byte would burn the base
key while the message carrying it failed** — and since every message a peer sends before hearing
back carries the same base key, every genuine copy afterwards would be refused as a replay. A
permanent, deniable, zero-crypto denial of session establishment, repeatable before every delivery
attempt, landing the user on the app's own delete-and-re-invite advice. The sibling claim was that
`loadPreKey` is a mutating read reached on the same pre-authentication path.

Measured against libsignal 0.86.5, on two different corruptions — a flipped byte in the body, and a
flipped byte in the trailing MAC, which parses cleanly and fails authentication — **the store is
untouched**: no base key recorded, no one-time pre-key marked used, and the genuine copy opens
normally afterwards. The callbacks are not reached until the message verifies.

That is a property of **the library version**, not of this code, and it is exactly the kind of thing
that changes under an upgrade without anyone noticing. So it is a test now, with the replay refusal
asserted alongside it: without that floor the pair would pass just as happily against a build where
the guard had been deleted outright.

**The review round that produced these was asked to report on ground nobody had audited, and its
value is not diminished by two of four findings being wrong.** It described precisely what evidence
would settle them — "one test away" — and that test took ten minutes. Two findings from the same
round are still open and are real: bundle fields can be spliced between two genuine invites from the
same identity, because the one-time pre-key is covered by no signature and the ids are unsigned; and
`pruneUsedPreKeys` drops the lowest used **id** while the allocator hands out the lowest free id, so
a recycled id can make the newest invite the first one pruned. Both are next.

## Newest first, when it meant oldest first

**Retention kept the fifty most recently used one-time pre-keys by dropping the lowest ids. The
allocator hands out the lowest free id, so the record it dropped first was often the newest.**

Used records are kept rather than deleted, because a peer's first message arrives long after the
invite was handed over and the key must still be there to open it. Fifty are kept, since the whole
store is rewritten on every message. Which fifty was decided by sorting ids — a proxy for age that
`findUnusedPreKeyId` breaks: it returns the **lowest free** id, and when a peer's first message
finally arrives libsignal removes that id and the app regenerates it in place, unused. The next
invite therefore carries a low id, and the invite after that prunes it.

Measured on the real invite path before the fix: fifty-five invites, the oldest retained id answered,
and **the invite handed that id was destroyed by the very next invite** while fifty older keys were
kept. The peer holding it could never be decrypted — their messages simply stopped working, with
nothing on either screen to say why. The relay does not have to wait for any of it: an invite it
discards still spent an id, so making invites fail makes the user press Invite again, which is what
mints and prunes.

Records now carry **when** they were consumed, and retention drops the earliest consumption. The
sequence is derived from the store rather than persisted beside it, because a counter that restarts
at zero after a reload would make everything consumed afterwards look older than everything before —
the same defect through a different door. A record written by an older build carries zero, which
sorts it oldest: correct, since it was consumed before the field existed.

Two mutants: pruning by id again, and a sequence that never advances. Both are killed by the
measured case, and the bound itself is pinned separately so the fix cannot quietly become "keep
everything".

## Fields that were never issued together

**A relay holding an earlier genuine invite from the same person can mix its fields into the current
one, and the result is accepted. Measured, reproduced, and being fixed on a branch rather than
half-landed here.**

libsignal signs the signed pre-key and the Kyber pre-key individually and signs **nothing that ties
a bundle's fields to each other**. Everything else is checked for presence alone. So the relay keeps
the current bundle, swaps in the one-time pre-key from an invite the same person issued last week,
and both libsignal signatures still verify. Measured: accepted.

What that buys is not impersonation — the identity key is unchanged and the pin still refuses a
substitution. It is **one-time pre-key reuse**. Two peers negotiate against the same one-time key, so
a single record covers both their opening messages and one device seizure inside its lifetime reads
both; and whichever peer's first message arrives second can never be decrypted, because the record
is consumed and regenerated. Splicing the previous rotation's signed or Kyber key is the same move
against forward secrecy, the post-quantum half included.

The fix is an **issuing signature**: one signature over the canonical encoding of the whole bundle,
made with the identity key the bundle already carries, verified before the bundle is processed and
after an identity change has been recorded — so a bundle refused here is still not a way to stop a
substitution being written down. It authenticates nothing about *who* the identity is; a bundle from
an attacker's own identity verifies perfectly. It answers the narrower question the format could
not: were these fields issued together. Wire version 2, because an optional signature is one an
attacker omits.

**It went out on a branch, and the reason is the interesting part.** The production change is small
and works. Forty-seven tests then failed, all of them fixtures that build bundles by hand — and each
one had to declare which adversary it models. A **relay edit** now carries the issuer's untouched
signature over content it no longer covers, which is a more faithful model than these fixtures could
express before: a relay can copy an invite and edit a field, and cannot sign the result. A
**field-level check** — no Kyber key, a second device, a stripped one-time key — is defence against
the *issuer*, the only party that can sign what it emits, so those sign as the issuer and keep
testing exactly what they claimed. Codec tests get bytes of the right shape from a fixture named so
that nobody mistakes them for a valid signature, because the codec carries the signature and does
not verify it.

Three things were updated deliberately rather than absorbed. The **golden vector**'s version byte,
written out by hand — that is what a golden vector is for, and it is the test that cannot be updated
by accident. The **bundle size**, re-derived at 2572 characters against the 4096-character invite
threshold, leaving a margin of 1524: the assertion demands re-derivation rather than adjustment, and
the threshold does not need moving. And the **fuzz corpus**, raised from two thousand mutations to
two and a half thousand, because a longer envelope reached the parser ninety-nine times against a
floor of a hundred — lowering the floor would have been the same test proving less.

Doing that quickly is how a suite gets quietly weakened, which is the failure this file records more
often than any other. So it was done on a branch, one file at a time, with the main line green
throughout, and merged when the whole suite and the device suite were both back to green.

## What the signature does not close

**Two reviews of the bundle signature, and the most useful thing either said was that the claim
written around it was too wide.**

The splice is the *expensive* way to get one-time pre-key reuse. The cheap way needs no edit at all:
the relay withholds the current invite and delivers an earlier, entirely genuine one that somebody
else already holds. Every signature verifies, because nothing was touched — and two people then
negotiate against the same one-time key, which is the whole harm the splice was reported for. The
issuing signature binds a bundle's fields together; it says nothing about whether that bundle has
been handed out before. REVIVAL.md already defers bundle replay as a known hazard and measures it for
one recipient; the two-recipient consequence is the half that was not measured, and it is the half
that matters. It is a test now.

**A second measured hole, in the mechanism itself.** Verification re-encodes what the decoder parsed
and checks the signature over *that*, which is sound only if one wire spelling decodes to one
envelope. libsignal's key deserialisers read from the front of the array and ignore what follows —
measured: a 33-byte EC key with eight bytes appended is accepted and re-serialises to the same 33
bytes. So a padded key field re-encoded canonically and **the signature still verified**: a relay
could pad a genuine invite and have it accepted. Nothing renders those bytes, so it is a malleability
primitive rather than a live attack — and it made a written invariant false, which this file treats
as a defect of its own. Each key field must now contain exactly the key it decodes to, checked by
comparison rather than against a hard-coded length, so a libsignal upgrade that changes a
serialisation does not turn every invite into a refusal.

**One test was found by both reviews independently.** `astrippedBundleDoesNotProduceAsession` was
left unsigned when the other thirty-six fixtures were converted, so it was refused at the signature
gate and never reached the missing-field check it is named for — a genuine bundle in the same
unsigned envelope would have passed it identically. Delete the field check and it stayed green. It
now signs as the issuer, per the rule written twelve lines above it in its own file, and the mutant
kills it.

**Three more that had quietly stopped measuring their subject**, all collateral from the version
bump rather than from the signature: a hand-built frame in the boundary tests, and one in the encoder
guards that was *already* vacuous before the bump — its identity-key length was zero, so parsing
threw before the device count it was written for was ever read, and a bare `assertThrows(IOException)`
cannot tell those apart. Both are fixed to build the frame the test claims to be building.

**And two claims of mine were simply wrong.** A wrong-length signature does not throw — measured,
every length from 1 to 255 returns false — so the crash path that would have taken the keyboard down
does not exist; it is pinned as a test because it is a property of the library, not of this code. And
the splice case at a fresh address is refused by libsignal before the new check is reached: the
mutant says so, deleting the verification leaves that test green. The case stays as defence in depth
with its sentence corrected, because the one the signature is genuinely alone on is its sibling.

## Eighty-four characters, and where they landed

**The bundle signature made the wire envelope longer, and one of the numbers this project argues
from was measured before it.**

`E2EEStrip.encode` refuses a message that would arrive undecodable, and its reasoning is written out
in figures: a 500-byte message is 3068 wire characters, and 5500 when a signed pre-key rotation falls
due and a full PQXDH bundle rides along; FairyTale expands that by about 1.5x; the recipient refuses
past 8192. Those figures predate the signature by one commit, and nothing re-derives them — the test
beside them asserts the *property* (anything sent is decodable, anything else is refused), which
stays green whichever way the numbers move.

Re-measured: **5584**, eighty-four more, and a 200-byte rotation-due message now encodes to 7904
against the same 8192 cap. So the headroom on that path is about 288 characters rather than about
370, and **the refusal boundary moved down by roughly sixty bytes of plaintext**. Nobody is sent an
undecodable message by it — that property still holds and is still tested — but a message that used
to just fit is now refused. That is a cost of the change, and the point of writing it down is that
it was not visible from anything the suite says.

**Three smaller things from the same round.** The claim that the decoder "refuses version one
outright rather than accepting an unsigned bundle from an older build" had no test: the
malformed-version case uses `0x7F`, which any unknown value satisfies, while the value that matters
is specifically **one** — the version whose envelopes carry no signature at all, and the one a relay
would actually try. It is pinned now, and the mutant that accepts version 1 alongside version 2 kills
it.

The fuzz floor was an absolute count against a corpus the test itself chooses, so raising the corpus
was always available as a way to quiet it — which is exactly what was done to it last tick, for a
defensible reason, but the availability is the problem. It is a rate now: four percent of whatever
corpus is used, which cannot be bought by enlarging the corpus.

And two comments explaining a refusal by libsignal's signed-pre-key check now describe a refusal that
happens earlier and for a different reason. Corrected rather than deleted, because what those tests
are about — that the refusal lands *after* the warning has been posted — is unchanged by which check
gets there first.

## The half of the deletion that did happen

**"Nothing was deleted — they, their key and their saved messages are all still here" was false about
the messages, in the one case the report cannot see.**

A save writes the message log to its own file **first** and the account batch second, and reports
only the batch. That ordering is deliberate and load-bearing: the batch carries the migration marker,
and batch-first would let a kill seal that marker over a log still holding pre-upgrade keys, which is
unrecoverable. But it leaves one pair of outcomes outside the report — the log commit lands and the
account batch fails, which this file already argues is ordinary on a nearly full disk, having
reasoned about it only in the other direction.

`removeContact` then sees "the deletion did not reach disk", rolls the contact, the session and the
messages back **in memory**, and the strip says nothing was deleted. **Measured**: the reload came
back with the contact row present and the log **empty**. The one half of the deletion that had
actually happened was the destructive half, and the app asserted the opposite — durably, in a notice
and in the help text. In memory the messages survive until the next keyboard raise, which on an
input method is imminent, and then they are simply gone.

The repair uses the same ordering that caused it: **the rollback writes again**, the log goes out
first, and the restored messages reach disk even when the account batch fails a second time. Nothing
about it can make the state worse — the batch is the half that was already failing — and the return
value is unchanged, because whether the *deletion* landed is still false.

Two shapes were rejected before that one. Writing the batch first is the thing that must not be done,
for the reason above. Restoring the log file explicitly introduces a restore that can itself fail,
and a failed restore leaves the identical state while now claiming a rollback happened — worse than
saying nothing. What made the third option findable was asking which write had *already* succeeded,
rather than which one to add.

The test pins both directions: a deletion that did not land leaves the messages on disk, and one that
did still takes them. Without the second, the first passes against a build that never prunes at all —
which breaks the same promise from the other side, for a user deleting a contact precisely to be rid
of the conversation.

## Asked once, and the answer written down

**The legacy migration was not idempotent in content, and the state that exposes it is one the write
order deliberately produces.**

Re-keying the 0.1.5 chat log asks, per entry, "which single contact bears this address name?" That
question must be asked **once**, at the first load after the upgrade — the moment the answer is one
the messenger has not had time to arrange. "Once" was enforced by the schema marker. But the marker
travels in the account batch, and the log is committed first, so **log re-keyed, marker missing** is
exactly the state the ordering was chosen to produce; three separate comments call re-running safe.

It is not. On a second pass the key being examined is a **rendered** one — and a pre-upgrade address
name can be byte-for-byte a rendered key, which is the whole subject of the smuggling test already in
this file. **Measured**: pass one placed the entry with the genuine contact; pass two moved it into
the attacker's row, and `belongsTo(Bob)` went false. The user's pre-upgrade history, re-filed into an
impostor's conversation, under their name and tag.

Neither obvious fix was available. Deciding "already re-keyed" from the key's **shape** is the hole
`LegacySeparatorSmugglingTest` exists to keep shut, and it lands the same substitution on pass one
with no interruption needed. Making the **marker** durable means writing it into the message file,
which widens the permitted-key set that closed the laundering oracle.

So the answer is recorded **on the entry**, in the same file and the same commit as the re-keying it
describes: if the log write lands, the flags land with it; if it does not, neither does the re-keying.
The flag is set even when the entry could not be attributed, because *"I could not tell"* is an
answer too, and asking again later means asking against a contact list the messenger has had time to
arrange — which closes the second half of the same finding.

**A message this build creates is already correctly keyed**, so the ordinary constructor records the
question as answered, and only a store written before the field existed reads as unasked. That is
what makes the fixtures interesting: six legacy fixtures had to say so explicitly, and they read
better for it — a legacy entry is now visibly a legacy entry rather than an ordinary one that happens
to be keyed oddly. The new field is excluded from `equals` with its reason: it is bookkeeping, not
identity, and including it would stop a migrated entry matching the copy a deletion rollback took
before it.

## Cannot, and cannot right now

**The key ladder read a temporary answer as a permanent one, and degraded for the life of the
install.**

Two of the four rungs bind the storage key to the screen lock. Each candidate is certified by a
**real seal/open** — deliberately, so a key that cannot actually be used is never accepted — and a
seal/open is precisely what a lock-bound key refuses **while the device is locked**. So a first key
generated on a locked device failed both lock-bound rungs, and the ladder stepped down to one with no
lock binding at all. The ladder is walked once, at generation, and never revisited: a device that
fully supports lock-bound storage would spend the rest of the install with storage readable whenever
the app runs, and the only trace was a log line from months earlier.

The suite could not catch it, and not by oversight: `afailedSelfTestFallsThroughToTheNextRung`
**asserts** the step-down. There was no mutant to run, because the degradation was the recorded
intent — which is what a review round is for.

The ladder now asks whether the device is locked, and defers instead of degrading. **Refusing is safe
here and nowhere else**: this loop runs only when the alias is absent and there is no existing data,
so deferring costs a keyboard raise, and the app already renders that state and says it clears after
an unlock. Repairing a weak key later would mean deleting it — the destructive direction, with no way
back, and the reason this fix had to be generation-time only. An unknown answer reads as locked, so
the failure direction is a deferral rather than a silent downgrade.

The floor matters as much as the fix: an unlocked device that genuinely cannot honour a rung must
still step down, or a phone without StrongBox ends up with **no** storage rather than weaker storage.
Two mutants — degrade while locked, and defer while unlocked — die on those two tests respectively.

**Five comments were corrected alongside it**, all of the kind this file counts as defects. The
migration's class javadoc still said ambiguous entries are *deleted* while its body, forty-five lines
below, says "kept, not deleted" and explains that deleting them was a destruction primitive. Two more
justified the marker with a skip that does not exist. One said per-operation key resolution exists so
a subclass can "invalidate and re-resolve", when no re-resolve exists anywhere. And one enumerated
two bare-name comparison arms when only one is left — its conclusion still holds, but a reader
deciding whether the separator must stay non-printable should be counting the real arms.

**One of the two open questions was then asked of a device rather than argued.** Whether an
unlocked-device-required key survives the screen lock being *removed* decides between two very
different apps: if it does not, then removing a PIN silently turns the identity key, every session
and the whole history into unreadable ciphertext, on a build with no RESET path. A comment answered
it by assumption, listing "a Keystore key invalidated by a credential change" among the causes of an
unresolvable store.

**Measured at API 28: it survives.** The test sets a PIN, generates the key, seals, clears the PIN
and opens — and the ciphertext opens normally. The flag binds *use* to the device being unlocked, not
to the credential existing, and the two options that do carry invalidation semantics are not used
here. The comment is corrected, the measurement is in `REVIEW-SETTLED.md` with its API level beside
it, and the test asserts the survival — so a platform that changes this fails loudly instead of
losing a user's history quietly.

That leaves one thing genuinely open on this surface: **which rung a given install actually took is
recorded nowhere a user or a test can see.** Where to put it is a product question — a diagnostic
screen, a one-time notice, nothing at all — and not one to invent while fixing something else.

## What the shipped thing actually contains

**Two identifiers were reaching logcat in release builds, and the guard written to stop exactly that
had been green for the life of the branch.**

`Log.d(TAG, "chosenContact = " + chosenContact)` interpolates a whole `Contact`, whose `toString`
concatenates first name, last name, device id, protocol UUID and the verified flag. It ran on the
add-contact path, in release — this build does not minify and nothing strips `Log` calls. And a
refused bundle logged the peer's protocol address at ERROR, on a path **the messenger triggers at
will** by flipping one byte in a bundle it relays. Neither is message plaintext; both are the durable
identity of somebody the user talks to, which is the thing this whole design spends its effort
protecting.

`NoSensitiveLoggingTest` could see neither. Its contact pattern required the literal text
`.toString()`, and the one call that existed used implicit concatenation. That is the same shape as
every hollow control this file records: the guard matched a *spelling* rather than the thing being
refused. It now refuses a contact or an address concatenated into a log line however it is written,
and both mutants — putting either line back — kill it.

Deleting rather than reducing was deliberate in both cases. The tempting repair for the first is to
log the keyed display tag, which is not an off-device identifier and *is* a stable correlator tying
every line about that contact together, while looking obviously safe in a diff. For the second it is
to name the person instead of the UUID, which is strictly worse and is what two already-fixed sites
had to be talked out of.

**And the guards for the shipped artifact were reading a different artifact.** Three release guards
exist and all three are source or build-script scans; the one that opens "the shipped manifest is a
security artefact" hands Robolectric the **debug** merged manifest — which carries the recording
autofill service, the editable-field activity and `android:debuggable="true"`. Its permission and
export assertions were being made about a variant nobody ships. A release-only manifest contribution
— a `releaseImplementation` dependency declaring `INTERNET`, say — was caught by nothing.

So the artifact is now asserted against itself, in the gradle task that already opens the release APK
and already fails the build: no permission but `VIBRATE`, no scaffolding, not debuggable,
`allowBackup` false, and no testing-flavour entry in the APK. A unit test could not do this job —
it would be skipped whenever no APK had been built and would pass on a stale one otherwise. The
mutant is an added `INTERNET` permission, and it fails the build.

That last check found something on its first run: the release APK was shipping libsignal's
**testing**-flavour acknowledgments file, 44.7 KB describing the binary the packaging block
deliberately excludes. Removed by exact path rather than by an `acknowledgments` pattern, because
these are third-party attribution for an AGPL dependency distributed through F-Droid and dropping the
wrong one trades 44 KB for a licence problem. 781 entries to 780.

**Every commit hash in this file pointed at nothing.** Twelve anchors, all unresolvable — the cause
is ordinary and mine: amending a commit message rewrites that commit and every descendant, and this
branch has been amended repeatedly to correct test counts. The claims themselves were fine; their
addresses had rotted. They now cite what was measured rather than where, because a record that dates
itself by hash on a branch that gets amended is a record that decays silently. The cold-verification
entry was the one that mattered: it is the only evidence the pinned dependency set is complete, and
it was pointing at a commit that does not exist.

## Three fields that were offering their text away

**Half of what a keyboard normally leaks does not exist in this fork, and the review that went
looking said so first.** There is no personalization package, no user-history dictionary, no
suggestion strip, no emoji palette, no clipboard-history manager, and no file write anywhere outside
`SharedPreferences`. So "typed text reaches a learner" has no mechanism here. What is left is what
the framework provides for free, and that had not been swept.

**Every editable field this app owns was suggestion-enabled.** `TextView` opens a spell-checker
session for any editable field whose suggestions are on, and such a session ships the field's text
over binder to whatever app the user has set as their spell checker — a third-party process,
commonly from the same vendor as the stock keyboard, commonly one that keeps what it is given. The
compose box holds decrypted messages and the user's draft; the two contact fields hold a
correspondent's name. This is neither autofill nor accessibility, so neither the autofill device test
nor `FLAG_SECURE` covers it.

Whether the platform actually starts a session for a view inside a non-focusable input-method window
was **not** established, and the flag is set anyway rather than investigated: it costs one attribute,
and the alternative is depending on a framework detail nobody here has measured. Not
`textPassword` — that would hide the message from the person meant to read it. The guard asserts the
**inflated view's** input type, because a source scan would stay green against a later
`setInputType` call and would miss a field skinned by one of the two themes.

**And the two contact fields lowered the typing redirect on focus loss** — the exact shape the
compose box's own comment calls the app's central promise broken, twelve lines above them. Focus loss
is not the user asking for their typing to go to the messenger; blurring a name field handed the rest
of a correspondent's name to the messenger's own field. No path was found that drives it, so this
pins the invariant rather than replaying an exploit — which is the argument the compose-box case was
written on too.

**The second half is what makes the first safe.** Copying that asymmetry without a lowering path
leaves the redirect up pointing at a field that is no longer on screen, and every keystroke on the
main view then vanishes into it — a total functional break rather than a leak. Leaving the
add-contact screen is the choke point, so that is where it is handed back.

**The first version of that test was hollow, and the mutant said so.** It blurred the field with
`clearFocus()` and passed against a build that still lowered on blur. Measured: `clearFocus()` on the
only focusable view in a container hands focus straight back, so no blur ever happened. The test now
drives the production listener with the argument the framework delivers, and says why in the place
someone would otherwise re-introduce a `clearFocus()`.

## A line that was doing security work without saying so

**Nothing in the strip refuses the selection toolbar. What refuses it is the scroll behaviour, and
nobody had written that down.**

A long-press on an editable field offers Copy, Share and `ACTION_PROCESS_TEXT` — the clipboard the
messenger reads, and an `Intent` extra to any installed app. There is no
`setCustomSelectionActionModeCallback` and no `textIsSelectable="false"` anywhere in this file. What
stops it is that `TextView` builds a selection controller only when its movement method can select
arbitrarily, and `ScrollingMovementMethod` cannot.

That line was written to scroll a tall message. Swapping it for `ArrowKeyMovementMethod` — the
ordinary fix for "tapping cannot place the cursor" — would reopen both paths with nothing to notice,
which is what makes an accidental property worth pinning rather than admiring. The mutant is exactly
that swap, and it now fails two tests: the property, and the floor that says the box must still
scroll — because a *null* movement method also cannot select arbitrarily, and would satisfy the
first while making a long message unreadable.

Deliberately not fixed with a selection-ActionMode callback. That suppresses the selection toolbar
without touching the *insertion* one, so it would be easy to add, believe the field is closed, and
then feel free to change the movement method.

**And two exception messages carried identity into a sink neither logging guard inspects.** One named
the contact, one named the address, and every catch of both ends in `printStackTrace` — which on
Android reaches logcat, while both guards match `Log.*` call sites. Neither is reachable today, which
is why the fix is the message rather than a new control: the caller already knows which contact it
passed, and a log reader does not need to.

One existing test had pinned the old sentence *including the name*. It now asserts the property —
that the refusal says what happened and does not carry the name — which is what it was always for.

## A hole that was not there

**A review round reported that Robolectric's `android-all` jars run unverified in the job that builds
the release APK. The reasoning was sound, the mechanism is real for the versions it describes, and it
does not happen here — measured.**

Dependency verification pins 386 artifacts. Older Robolectric fetches a ~100 MB `android-all` jar at
test time into `~/.m2`, outside Gradle's resolution and therefore outside verification, and that jar
executes arbitrary code in the same job that produces the release artifact. The reviewer did not
invent the mechanism: **this repository's own CI comment asserted it**, which is where the finding
came from, and I repeated the assertion in a note last tick without checking it.

Measured against Robolectric 4.16.1 as pinned: the suite runs to completion with `--rerun-tasks` in
a container started with `--network none`. No `android-all` jar exists anywhere in the image before
or after, and no `~/.m2` is created at all. The Android runtime comes from `nativeruntime` and
`shadows-framework`, both of which are pinned and verified like everything else.

So the correction is to the **comment**, in both places it appears, and the cache step stays — a
cache that hits nothing costs nothing, and a future Robolectric could reintroduce the fetch. What
the comment now carries is what to re-measure if it ever starts hitting.

Worth recording as a pattern rather than an incident: this is the third time a false comment has
produced a finding. A stale note does not merely misinform a reader — it is *read as evidence* by the
next person to look, including a reviewer whose whole job is to be suspicious. The cost is not the
minute it takes to write; it is a round spent confirming something the codebase asserted about
itself.

## The instruction that vanished when it was needed

**Every warning this app raises ends by telling the user to compare the safety number. The screen
that comparison happens on stopped saying how — in exactly the states an attacker produces.**

The instruction carries three things: compare **by voice**, do **not** send the numbers through the
messenger you are chatting in, and the reason — *anything that could change your keys could change
those numbers to match*. Two state notices **replace** it: one when a key change is pending, one when
a rejection stands. A pending change costs an attacker one forged bundle. In that state the screen
said "if it still matches what they read out" and named no channel and no reason at all — and a user
satisfies that sentence by pasting the digits into the chat they are already in, which is the single
thing the instruction exists to prevent.

The clause is its own constant now, appended rather than embedded, because **a sentence that must
survive every branch cannot live inside one of them**.

**Two things this turned up that the report did not.** There are two base writes of the verify text,
in different methods, and only one is on the live path — so my first fix went to the dead one and the
test caught it. That is its own small hazard: a sentence added to one and not the other is a screen
that says different things depending on how it was opened.

And appending unconditionally would have been wrong. After a bare rejection the pin is gone, so there
is **no number at all** and the screen says so — telling the user to read digits out by voice there
is telling them to compare nothing. The obvious fix is the one that breaks it, so that state is now
the floor: the screen with no number must **not** ask for a comparison. Both mutants die — dropping
the clause from the pending-change branch, and appending it where there is nothing to read.

**The guard that was supposed to hold this read the constant's source text**, which cannot tell
whether the sentence reaches the screen — the same shape as three other hollow guards this file
records. It still exists, doing the cheap half; the durable half asserts the rendered view in all
three states.

**And the chat-log cap now has numbers rather than adjectives**, measured on the path where the cost
actually lands — the save that follows every decrypt once the log is loaded. 500 messages: 65 ms.
2,000: 87 ms. 10,000: 222 ms. 20,000: 207 ms. 50,000: 412 ms, 15 MB stored. The decision is still
yours; what changed is that it can now be made against evidence, and the harness that produced these
is written down beside the two it sits with.

## Nothing was testing the number itself

**Everything this app does about trust reduces to one instruction — compare these digits by voice —
and nothing tested the digits.**

Two mutants were run and both survived the whole suite:

- **Render every one of the twelve groups as the first group.** Both honest sides render the same
  degraded value, so they still match. The comparison silently stops being a check while looking
  exactly as it did, and the number falls from about two hundred bits to about seventeen — roughly
  2^17 key generations to make the shown digits agree.
- **Change the iteration count from 5200.** Two installs on different builds then show different
  numbers for the same pair of keys. The app tells both users that a mismatch means an attack, and
  its advice for a mismatch is a rejection that permanently discards a correct pin.

Neither shows up as a broken screen. Both are agreements that quietly stop meaning anything. The
existing fingerprint tests could not see either: every assertion compares two values that are both
derived from production, or — in the one place an independent generator is constructed — compares
that generator's output against *itself*.

So the assertions added are about **relationships**, not values. The twelve views must equal what the
protocol computed, and what the protocol computed must equal what an independently constructed
generator at Signal's iteration count produces. Pinning literal digits as a golden vector would break
on any legitimate libsignal change, and the reflex fix for a red golden test is to update the
expected value — which is the one change that must never be made quietly. The iteration count is now
stated once in a test, so changing it in production is a visible decision rather than a silent one.

Both mutants now die, each on the assertion that names its failure.

## The tag fell off the bottom of the list

**A row could show a whole, legible name and no address tag — which is the row reading as the plain,
unambiguous original, and the one state the tag exists to prevent.**

Two contacts the user has given the same name are told apart by their tags; the pin cannot cover that
case, which is why the tag is ungated and shown on every row. The tag is also the **bottom-most**
element of a row, and `ListView` deliberately draws a partial last child when its height is bounded
and not a whole number of rows. So the cut falls on the tag while the name above it survives intact,
and with `divider="@null"` there is no rule and no ellipsis — nothing on screen says the row was cut.

**Measured**: a 322-pixel screen budget against 92-pixel rows produced a list 261 pixels tall, with
the third row's bottom at 276. Reproduced before the fix, gone after it.

The list now refuses to draw a partial row. Not by putting the tag back beside the name — that
reinstates the horizontal clipping a previous round fixed, where at large font scales both name views
measured narrower than the ellipsis glyph and drew nothing. Not by a hard-coded height either, which
reintroduces the negative-column class at large font scales.

**Two mistakes of mine on the way, both caught by tests I wrote against my own fix.** The first
snapped against the height the list *wanted* rather than the height it was *given* — the screen's
budget is shared with the info line and the button strip — so it dropped a row that would have fitted.
The second was worse in kind: a "minimum one row" floor that was **dead code**. It computed a larger
height and then skipped the assignment, because the assignment is conditional on shrinking. It looked
like a floor, and the test I wrote for it asserted an outcome that never happened. Both are gone, and
what replaced the floor is a test asserting what the code actually does.

That leaves one state this cannot fix, written down rather than papered over: with room for less than
a single row there is no whole row to show, so the list keeps what it was given. Growing past its
allowance would only hand the parent a view it has to clip anyway.

## The one thing a list actually does

**Every test of the contact adapter handed it a fresh row. `ListView` never does that.**

A list reuses row views: the row that scrolled off the top is handed back as `convertView` for the
row scrolling in at the bottom. Not one call in the suite passed a used row, so the single thing a
list does to these views was untested — on the icon the user is told to act on.

The failure direction is the bad one. The two badges are overlapping siblings and the verified one is
declared second, so it draws on top: a row recycled from a verified contact shows a **green tick over
an unverified one**. Measured — a mutant that sets the badges only when the row is newly inflated
passes every other test in the file.

**The reviewer's named mutant was wrong, and checking it is what found the right one.** They proposed
deleting the line that hides the unverified badge in the verified branch, and said the suite would
stay green. It does not: an existing test catches that in both directions on a fresh row. What is
uncovered is narrower and more interesting — the *recycled* path specifically, which no assertion in
the repository reaches. A finding that is wrong in its mechanism can still be right about the gap,
and the only way to tell is to run it.

The recycled view handed to the new test is one **the adapter itself returned**, not a hand-built
row. A hand-built one would also pass against an adapter that ignored `convertView` entirely, which
is a different bug — and the test would then be proving nothing about recycling while appearing to.

## Which kind of hollow

**Swept the suite for guards that could pass without measuring anything, expecting to find work.
There is none of that kind left, and the result is worth more than the fix would have been.**

Thirty-five tests read source or walk files at run time. **All thirty-five carry an anti-vacuity
floor** — "found only N", "no seams were found at all", "the naming convention this scan relies on
has changed", or an outright count. Five state it in prose rather than in a comparison, which is why
two passes of pattern-matching under-counted them; they are floors all the same.

So the discipline that produces floors is complete, and adding more would be motion rather than work.
That matters because the hollowness this project keeps actually finding is a **different species**,
and mistaking one for the other wastes a round:

- **A guard matching a spelling rather than a property.** The contact-logging regex required the
  literal text `.toString()` and missed the one call that existed, which used implicit
  concatenation. The verify-screen guard read the *source of a constant* and could not tell whether
  the sentence reached the screen. A bare `assertThrows(IOException)` cannot tell which refusal it
  caught, and two hand-built frames were being refused for their version byte rather than for the
  thing under test.
- **A guard that never exercises the path.** Nothing ever handed the contact adapter a recycled row —
  the only thing a list actually does to those views. Nothing measured the list with a bounded
  height, which is the only condition under which a row can be clipped.
- **A floor that is dead code.** The one I wrote myself: a minimum-row guard that computed a larger
  height and then skipped the assignment, because the assignment only ever shrinks.

A floor answers "did this scan see anything?". None of the above is a failure to see; each is seeing
the wrong thing, or seeing it in the wrong state. The remedy is not another count — it is to assert
against the rendered view, the parsed object, the recycled row: **the thing the user or the attacker
actually meets**, rather than the text that produces it.
