# Backlog

Findings and known gaps that are **not** HIGH or CRITICAL. Review passes address highs and
criticals only and run until none remain; everything else lands here with enough detail that
picking it up later does not mean re-deriving it.

## Severity rubric

Used by every review pass on this branch. Written down because "high" is otherwise decided
per-reviewer, and this project has had findings argued at three different severities in one round.

- **CRITICAL** — silent loss of confidentiality or authenticity. Plaintext or key material reaching
  a party that should not have it; a key substitution the user cannot see; a verified badge that
  was not earned. The user cannot detect it and cannot recover.
- **HIGH** — a security control the user relies on is defeated, or the adversary can cause a crash
  or irrecoverable data loss. The keyboard process dying in the host app; the user's history made
  permanently unreadable; a warning that cannot be cleared; a badge droppable at will by the
  messenger. Visible or recoverable, but the adversary chooses when.
- **MEDIUM** — a real defect with no constructed path to either of the above. Wire malleability with
  no downstream effect; a refusal that moves rather than disappears; a guard whose deletion changes
  a diagnosis but not an outcome.
- **LOW** — dead code, unreachable branches, equivalent mutants, cosmetic drift.

A finding with no constructed harm is at most MEDIUM, whatever its shape suggests. "I mutated it and
the suite stayed green" establishes that a guard is unenforced, not that it matters.

---

## Open — MEDIUM

**`requireFlag(hasKyber)` is driven by nothing.** `BinaryEnvelope.java:396`; the twin at `:389`
(`hasPreKey`) is killed by `NoSecondWireTextDecodesTheSameTest`. Cause is the fixture, not the
guard: that test defines its own local `bundleEnvelope()` passing `null` for the Kyber pre-key,
while the shared `AwireBundleFixture.bundleEnvelope()` in the same package carries one — so the
`hasKyber` byte is always `0` and every mutation of it truncates instead of exercising the flag.
Every bundle this app emits carries a Kyber key (PQXDH is mandatory), so with the guard gone each
real invite has 254 alternative wire texts that decode identically and satisfy both the
canonical-encoding check and `requireExhausted`. MEDIUM: malleability, no key substitution, the
issuing signature still covers the content. Fix is a sweep over the Kyber-carrying fixture; verified
red on the mutant when round 10 probed it.

**`exactlyItsOwnEncoding` at two of its three call sites.** `BinaryEnvelope.java:404` (Kyber public
key) and `:425` (`ec()`, used for both the signed and the one-time pre-key). The tested arm is
`:325` (identity key), via `AkeyFieldCarriesOnlyItsKeyTest.apaddedKeyFieldIsRefused`, which only
ever calls `padTheIdentityKeyField`. The byte-flip test cannot reach these: padding needs two
simultaneous changes (length prefix plus inserted bytes), which its own javadoc excludes.
MEDIUM and INFERRED — mutated, no user-visible harm constructed. Worth noting the capacity is
asymmetric the wrong way: the tested field has a u8 length (≤222 spare bytes), these have u16, so
roughly 4 KB of attacker bytes ride inside an envelope the parser then declares exhausted, and the
signature re-encodes the padding away so a padded genuine invite still verifies.

**`requireEncryptedOnly`'s undecryptable-envelope arm.** `EncryptedKeyValueStore.java:202`. Deleting
it moves where the refusal happens, not whether it happens: with a single permitted key, `decode`
throws at read time and the user meets the same `ChatLogUnavailableException`. Reasoned from the
code path, **not measured** — that distinction is the entry. It would start to matter if the
permitted set grew past one key, or if any caller wrote to that store before reading it.

**`migrateToEncryptedInternal`'s undecryptable-marker refusal.** `EncryptedKeyValueStore.java:211`.
Defence in depth behind two guards that are enforced. The window is a store holding an undecryptable
marker, only cleartext payloads, on a device that never sealed — an interrupted 0.1.5 upgrade whose
master key changed mid-flight. Real, narrow, not attacker-driven. The natural test for it also
disarms the two guards that make the window narrow.

**Chat-log move read-back check.** `StorageHelper.java:490`. Still survives at HEAD; re-confirmed by
sweep 4 rather than newly found. Four vacuous fixture shapes are recorded in REVIEW-SETTLED — the
next attempt must first assert the migration RAN (the copy present in the message file) before
asserting anything about the original.

## Open — LOW

**`SharedPreferencesKeyValueStore.remove`'s commit check.** Dead code: `KeyValueStore.remove` is
declared, implemented, and called by nothing in `app/src/main`. Delete the method or leave it, but
it is not an unenforced control.

**`SessionStoreImpl.loadExistingSessions`' null-record throw.** The code's own comment calls it
unreachable — libsignal's multi-session entry point, and this app performs no group or
multi-recipient send. The sweep confirms nothing executes it.

**`mergeEntriesSharingAname`'s two guards.** The loaded retired-name list can exceed its per-name
bound in memory on an upgrading store; `Account.retireDisplayName`'s own trim — which is pinned —
re-bounds it at the next retirement. The length check fires only for a `String[]` of length < 2,
which the store's JSON shape does not produce.

**`BinaryEnvelope:365`'s `registrationId > 16380` range check.** libsignal accepts 2,000,000 without
throwing. The harm is the 17-bit covert channel the comment claims, nothing more.

**`bundleSignature.length == 0` and its twin in `requireTheBundleWasIssuedAsOneUnit`.** Two guards
for one fact, neither observable alone; `verifySignature(msg, new byte[0])` returns false, so
removing both is a refusal rather than a crash. `AwrongLengthSignatureIsRefusedRatherThanThrownTest`
sweeps lengths 1–255 and skips 0.

**A null-address `TrustedKey` would NPE out of the trust store.** `IdentityKeyStoreImpl:348` and both
`trustedKeys.removeIf(...)` lambdas call `getSignalProtocolAddress().equals(...)` with no null check,
while `:333/:335` — the defended member of the same family — does check. No route to one was
constructed.

## Deferred by decision, not by severity

These are recorded in full in REVIVAL.md; listed here so the backlog is one place.

- **Store rollback.** Restoring an old `protocol.xml` rewinds the ratchet and resets `trustedKeys`.
  Needs a monotonic counter the attacker cannot rewind; not solvable at the storage layer. Requires
  write access to the app's private directory — strictly more than the messenger has. `StoreRollbackTest`
  prices it: a rollback takes the badge away and does not clear the pin.
- **The chat log grows forever and is peer-paced.** Performance half is done (own file, lazy load,
  omitted from the save batch; 294 ms → 199 ms at 20,000 messages). Cap-versus-keep is an open
  product decision — capping deletes the user's history.
- **`FLAG_SECURE` verified on one platform.** Measured on the emulator with a negative control.
  Vendor variance across IME window implementations is narrowed, not answered.
- **Adopt-in-place for an identity change**, and the safety-number comparison screen downstream of
  it. Coupled, and deliberately not built: the screen would ask a question the app then ignores.
- **QR for out-of-band exchange.** String transfer works both ways and the help text is written and
  tested. QR is a ZXing dependency decision.

## Unexamined

- The **correctness** half of `keyboard/` (rendering, geometry) and `latin/utils/` beyond logging.
  Three sweeps answered the security question there; nothing has answered the behaviour question.
- **Lint**: 502 filtered errors in the baseline.
