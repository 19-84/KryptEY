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

## From round 11 (first pass under the highs-and-criticals rule)

Round 11 reported **no HIGH or CRITICAL**. Its angle: can the host app kill the keyboard through the
previously unswept half (`keyboard/`, `latin/utils/`, `latin/common/`, `compat/`), plus a mutation
sweep over the files neither swept nor named by any test (`Account`, `chat/`, `ProtocolAddresses`,
`JsonUtil`, `E2EEStrip`, both list adapters, `WholeRowsListView`).

### Three candidates that died at harm construction — do not re-walk

**`KeyboardSwitcher.java:358` — NPE from a keyboard-less view.** A real latent defect:
`getKeyboardSwitchState()` treats `mKeyboardLayoutSet != null` as proof `mKeyboardView` holds a
Keyboard, but the layout set is never reset while `onCreateInputView` replaces the view wholesale.
Not HIGH: every route goes through `isImeSuppressedByHardwareKeyboard()`, whose
`!onEvaluateInputViewShown() &&` short-circuits unless a hardware keyboard is attached and visible.
No adversary-chosen trigger — the other input is a `uiMode`/dynamic-colour change, a system event
the host cannot force (rotation does not change `uiMode`).

**`KeyboardSwitcher.java:163` — NPE on a null `mKeyboardLayoutSet`.** The dangerous half of a pair;
its sibling `requestUpdatingShiftState` is safe only by accident, because `updateAlphabetShiftState`
opens with the exact inverse guard. Not HIGH: needs `mKeyboardLayoutSet == null` while
`mKeyboardView != null`, i.e. the first `onStartInputView` bailed early. `InputMethodService` creates
the input view in `initialize()` before dispatching `onStartInputView`, so the state could not be
built and the host does not choose it.

**`clearInviteRefusalIfAbout` — a replayed genuine bundle drops the refusal record.**
`E2EEStripView.java:5133`. Same shape as the confirmed badge-drop finding, and a re-delivered copy of
a genuinely issued bundle satisfies every check (no freshness term anywhere). Not HIGH: it is the
designed exit — the app's own advice is "ask them to send another" — the method is scoped twice
over, the identity-change record and any rejection survive, and `warnIfIdentityChanged` runs earlier
in the same pass and is re-derived from the store, so the stronger warning stays on screen. Only the
weaker, lower-ranked line goes.

### Survivors with no constructed harm — LOW

- `Account.java:550` `messages == null` in `removeAllUnencryptedMessages`. Unreachable:
  `getUnencryptedMessages()` throws rather than returning null on a failed load, and the only
  `setUnencryptedMessages` caller is guarded.
- `JsonUtil.java:197` stored-address validation throw. Its own comment records that the JSON is
  app-private and sealed per value.
- `ListAdapterContacts.java:46`, `WholeRowsListView.java:48` and `:52` — view-layer null/zero guards.

### MEDIUM

- **`ListAdapterMessages.getView` has no else-arm.** A `StorageMessage` whose sender and recipient
  both differ from the account name leaves the recycled row's text and visibility untouched, so it
  repaints the previous message on the previous side. Every stored message carries the account name
  in one of the two fields, so nothing reaches it today.
- **`E2EEStripView` arm asymmetries**, from a systematic arm x action table: the add-contact
  refused-bundle arm is the only bundle arm asking neither `warnIfKeyWasRejected` nor
  `warnIfThisKeyIsPinnedElsewhere`; the message-only arm is the only decrypt arm whose calls to those
  two are conditional on `PREKEY_TYPE`; the add-contact ciphertext arm posts the pin caution twice
  into one slot with two wordings. In each case the user still receives a warning on that arm, and
  the condition cannot newly become true where the question is skipped.

### LOW

- `changeHeightOfMessageListView` sets `params.height = 700` in raw pixels, overriding the layout's
  `250dp`, so the list height varies about fourfold across densities. Rendering only.
- `ListAdapterContacts.getItem`'s LinkedHashMap fallback unboxes `(Integer) get("deviceId")` and
  `(Boolean) get("verified")` inside a `catch (ClassCastException)` where the outer `catch (Exception)`
  cannot see the result. Nothing produces a non-`Contact` element today.
- `KeyboardId.boundedActionLabel` truncates with `substring(0, 128)`, which can split a surrogate
  pair. `drawText` and `codePointCount` both tolerate it.
- `LocaleUtils.sLocaleCache` is an unbounded static `HashMap`; keys come from the app's own subtypes.
- `KeyboardSwitcher.isShowingMoreKeysPanel()` is the one accessor in that class not null-checking
  `mKeyboardView`, while seven siblings do. Its single caller is already guarded on the same field.

## From round 12 (second clean pass — confidentiality and composition)

Round 12 reported **no HIGH or CRITICAL**. Deliberately independent of round 11: it went at what
crosses between host apps, what reaches disk, and authenticity under composition, and it swept call
sites rather than guards.

**Two sweeps, both largely enforced.** Every statement in the cross-app clearing paths —
`onKeyboardHidden`, `clearDecryptedContent`, `clearComposeFieldAndCaches`, `forgetAbandonedInvite`,
and both arms of `setHostFieldIsPassword` — was deleted individually: **15 of 15 killed.** There is
no unenforced statement left in the dismissal or password-guard paths. Of the 16 trust-warning raiser
call sites, **13 were killed**; the three survivors are all on `addContact` and are listed below.

Read and closed without a mutant, so the next round can skip them: `allowBackup=false` plus
`dataExtractionRules`; `SettingsActivity` is exported but `FragmentUtils.isValidFragment` allow-lists
seven fragments, so `EXTRA_SHOW_FRAGMENT` injection is shut; no dictionary or personalisation
machinery exists in this fork, so there is no plaintext learning path; the only `setPrimaryClip`
writes an empty clip; `createFingerprint` binds to the two identity keys and reads the pin, never the
session or the pending key; `removeContact` keeps the pin and `rejectedAddresses` outlives
`removeIdentity`; no `String.format` takes an attacker-derived format string.

### MEDIUM

- **`E2EEStripView.java:1825` — `warnIfThisKeyIsPinnedElsewhere(chosenContact)` on `addContact`'s
  ciphertext arm is driven by nothing.** Deleting it leaves the full suite green. Not an equivalent
  mutant: this is the inviter's side, where a bundle-less `PreKeySignalMessage` pins by
  trust-on-first-use, and it is one of the five arrival sites of the one warning REVIEW-SETTLED
  records as re-derived by nothing.
- **`E2EEStripView.java:1704` — `warnIfIdentityChanged(chosenContact)` on the accepted-bundle arm is
  pinned only by bookkeeping.** Killed by exactly one test, `NoWriteResultIsDiscardedTest >
  everyExemptionStillNamesAsiteThatDiscardsAwrite` — an exemption list that names the site, not a
  behavioural assertion. Its premise is reachable (a forged bundle records a pending change;
  `removeContact` clears neither the pin nor `pendingIdentities`; the peer's genuine re-invite then
  lands on this arm), and its own comment says `warnIfKeyWasRejected` defers to it, so the pair can
  go silent together. Worth a real test even at MEDIUM.
- **`E2EEStripView.java:1853` — `cautionThatAkeyWasPinned` on the ciphertext arm survives.** Close to
  equivalent: the shared caution at `:5023` already fires on this path, so deleting it only
  downgrades the wording. Its comment's second justification ("it also repaints the banner") is
  stale — `showChosenContactInMainInfoField()` repaints unconditionally.

### LOW

- `SenderKeyStoreImpl.java:44` is the only store method interpolating a whole record object into a
  `Log.d` in a non-minified release build. Unreachable today (no group send).
- Toasts carry `labelFor(contact)` — display name plus keyed address tag — in a separate window that
  the IME window's `FLAG_SECURE` does not cover, so `notifySensitiveVisibility` cannot reach them.
  Same residue class the banner path already accepts.
- `IdentityKeyStoreImpl.saveIdentity`'s `REPLACED_EXISTING` arm does not clear `outOfBandAddresses`
  while `acceptIdentityChange` and `removeIdentity` both do. Inert, but the three persisted
  collections are otherwise kept in step.
- `E2EEStripView.java:1313` renders the safety number with `String.format(Locale.getDefault(),
  "%05d", ...)`, so under a locale whose default numbering system is non-Latin the digits render in
  Eastern-Arabic or Devanagari while the peer reads Latin. Display only; the comparison is spoken.

## Unexamined

- The **correctness** half of `keyboard/` (rendering, geometry) and `latin/utils/` beyond logging.
  Three sweeps answered the security question there; nothing has answered the behaviour question.
- **Lint**: 502 filtered errors in the baseline.
