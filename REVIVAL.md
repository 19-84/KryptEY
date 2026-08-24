# KryptEY revival — state of the work

Branch `revival`, six commits on top of 0.1.5. This documents what changed, what is verified, and
what is deliberately still open, because the diff is large and several decisions in it are not
obvious from the code alone.

Baseline: KryptEY 0.1.5 (May 2023) — libsignal 0.21.1, cleartext key storage, `jcenter()` build.

**PQXDH is asserted, not assumed.** libsignal records the handshake in the session version - 3 is
X3DH, 4 is PQXDH - and until late in the revival nothing checked it. Every test asserted that a
session could be built and that messages round-tripped, all of which is equally true of X3DH. If the
Kyber pre-key were dropped, ignored, or silently unusable, sessions would still establish and the
suite would stay green while the post-quantum property the upgrade exists for was absent. The
session version is asserted now, on both sides and on the out-of-band path.

**Tests: 31 → 561, all passing.** Debug and release both assemble; dependency verification pins 387
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
text and `importOutOfBandKeyBundle()` consumes it, with the result recorded on the contact as
`KeyOrigin.OUT_OF_BAND`. That provenance is the point — trust-on-first-use cannot detect a hostile
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

**Mutation-sweep ledger.** Two sweeps have been run, both in an isolated worktree. Recording the
outcomes here because a previous commit claimed "all 21 survivors killed or documented" with nothing
in the tree to audit that against.

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

## Verified vs. reasoned

**Verified by execution:**

- 561 JVM tests, including an end-to-end conversation across all four phases and a real MITM
  identity substitution driven through libsignal
- A golden wire vector, re-checked against the three mutants that previously survived
- Robolectric tests against real SharedPreferences
- Negative controls on the highest-stakes regressions (legacy-peer crash, one-time pre-key
  overwrite, Jackson fixture corruption)

**Reasoned but NOT verified:**

- `AndroidKeystoreCryptoBox`'s Keystore CALLS are executed by zero JVM tests; its key-resolution
  DECISIONS now are (`KeyResolutionTest`, 10 tests, via a `KeystoreOps` seam). 11 instrumentation tests are written and
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

1. **UI for out-of-band exchange.** `exportOwnKeyBundle` / `importOutOfBandKeyBundle` exist and are
   tested; nothing in the keyboard invokes them. QR would be a dependency decision (ZXing); string
   transfer needs none. *Not* on this list any more: UI for accepting an identity change. That is
   now a decision rather than a gap — the exit is discard via deletion, and adopt-in-place stays
   unavailable on purpose.
2. **A screen showing the offered safety number beside the pinned one.** `getPendingIdentity`
   supplies it. Until it exists, no warning text should ask the user to check "their new number" —
   they cannot see it. The strings were corrected accordingly.
3. ~~**`SignalProtocolMain` has never been mutation-tested.**~~ Done. Every class in
   `signalprotocol/` has now been swept, which closes the last coverage gap reachable in this
   environment. The `SignalProtocolMain` sweep was 151 mutants and 44 survivors, and almost all of
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

So a build claim needs a run from empty caches, not just a `clean` task. `scratchpad/build/verify-cold`
does that: fresh volume, no warm `~/.gradle`, discarded afterwards. It takes a few minutes, which is
the only reason to reach for the warm path at all.

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

## Known-deferred defects

- **Store rollback.** Restoring an old `protocol.xml` presents envelopes that verify perfectly,
  rewinding the ratchet and resetting `trustedKeys`. Needs a monotonic counter the attacker cannot
  rewind; not solvable at the storage layer.
- **Bundle replay.** No freshness check, so replaying a captured envelope forces a session rebuild.
  Halved (the bundle was being processed twice per message) but not eliminated.
- **No user-visible signal when the Keystore key is gone** — it currently looks identical to
  "no data". `destroyMasterKey()` has no production caller (only instrumentation tests), so there is
  no reset path.
- ~~**Non-atomic account write** — 7 independent `commit()`s per save, on the IME main thread.~~
  Resolved. `KeyValueStore` gained a `putAll`; `SharedPreferencesKeyValueStore` overrides it with one
  editor and one commit, and `EncryptedKeyValueStore.putAll` seals the whole batch before handing
  the delegate anything. A save is now one durable write instead of eight, and a failure to seal
  any value writes none of them. The torn state it prevented was the dangerous kind: every value
  individually well-formed, so reload could not detect that a new protocol store sat beside an old
  contact list.
- **At 320dp with fontScale 2.0 the contact row shows no name at all** — both name views measure
  narrower than the ellipsis glyph, so nothing is drawn: no characters, no `…`. The rows stay
  distinguishable because the address tag is `wrap_content` and is never truncated
  (`theTagIsNeverTruncatedAtAnyWidthOrFontScale`), which is what the tag exists for. So this is safe
  under the stated property and still poor. Fixing it properly means wrapping the tag onto a second
  line when the column is too narrow, which is a layout restructure rather than an attribute change.
  This is not the only such cell: 360dp at fontScale 2.0 renders the last name as nothing for a
  CJK name (`龘龘龘龘龘龘龘龘龘龘`), because CJK glyphs are about twice the advance of Latin ones. An
  earlier version of this paragraph claimed every other cell in the
  {320,360,411,480}dp × {1.0,1.15,1.3,1.5,2.0} grid showed at least one character of each name; that
  was measured only over ASCII names and is false. What holds across the whole grid is the weaker
  and actually-asserted property — the rows stay distinguishable, by tag where not by name.
- ~~`E2EEStripView` enables the *encrypt* button on detecting an encrypted message.~~ Resolved: it
  was a copy-paste slip, and inert — `setInfoTextViewMessage` fires a `TextWatcher` that enables
  both buttons for any info text other than `INFO_NO_CONTACT_CHOSEN`, so all three sibling
  `changeImageButtonState` calls are dead. Corrected rather than deleted, so the branch reads as
  what it means.
