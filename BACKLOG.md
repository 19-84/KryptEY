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

## Closed

Every MEDIUM and every actionable LOW raised by rounds 10-12 has been fixed, each verified in both
directions - green with the fix, red with the old behaviour restored - with the measured output
quoted in its commit. In commit order:

| What | Commit |
|---|---|
| `warnIfIdentityChanged` on `addContact`'s accepted-bundle arm, pinned only by an exemption list | `b151b1e` |
| `warnIfThisKeyIsPinnedElsewhere` on the ciphertext arm - the inviter's own path | `92d837d` |
| The malleability sweep could not see the Kyber field every real invite carries | `b6a0f7e` |
| `exactlyItsOwnEncoding` at both fields `ec()` reads | `d49c597` |
| A caution justified by a repaint the next line now does | `c01bc96` |
| `ListAdapterMessages.getView`'s missing third arm | `59c8715` |
| Safety-number digits rendered in the device locale; `saveIdentity` kept out-of-band provenance | `e1f394d` |
| A 128-unit bound that cut a surrogate pair in half; a missing null check | `6747af1` |
| A fallback that threw from inside the `catch` meant to cover it | `9e791cb` |
| An unbounded static locale cache; a record object in a release log line | `973639d` |

**Three were not defects and are recorded as such rather than "fixed".** The Kyber
`exactlyItsOwnEncoding` call never fires - `KEMPublicKey` rejects an over-long array before it is
reached, which is why no harm could be constructed - and it is kept as a hedge against a libsignal
upgrade, with the test saying out loud that a future failure reading "trailing bytes" means that
upgrade happened. `saveIdentity`'s provenance drop and `SenderKeyStoreImpl`'s log line are both on
paths nothing reaches; both commits say so instead of implying a live defect.

## Open

**Toast text is outside the IME window's `FLAG_SECURE`, and this is accepted.** Toasts carry
`labelFor(contact)` - a display name the user chose plus its address tag - in their own window, so
`notifySensitiveVisibility` cannot reach them and a screen capture during one includes the label.
Not changed, for two reasons. The content is a name the messenger already knows, since it is their
contact; and the alternative is toasts that no longer say which contact they are about, on a surface
whose whole value is telling the user which contact an action applied to. The banner path accepts
the same residue. Revisit if a toast ever carries message content, which none does today.

**Two `KeyboardSwitcher` NPE guards were added without tests** (`isShowingKeyboardId` not checking
`getKeyboard()`, `setKeyboard` not checking its view or layout set). The states are exactly the ones
round 11 tried and could not construct, so a test would assert against a state nothing produces -
the vacuous shape this branch keeps rejecting. The guards are cheap, the reasoning is in the code,
and if either state ever becomes reachable the guard is already there.

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

## The lint baseline, read rather than counted

602 filtered issues, 23 distinct ids - and the count on its own said nothing, because **427 of them
are `UnusedResources`** inherited from the AOSP fork. The distribution is the useful artefact:

| count | id | disposition |
|---|---|---|
| 427 | UnusedResources | AOSP inheritance, no behaviour |
| 75 | StringFormatMatches | translation-vs-format mismatches in inherited strings |
| 23 | MissingTranslation | inherited |
| 21 | ContentDescription | accessibility, inherited layouts |
| 15 | ObsoleteSdkInt | inherited |
| 26 | everything else | see below |

Four had behaviour or security consequences and were read individually. Three are fixed
(`ColorDialogPreference`'s `toUpperCase` and both `onBindDialogView` overrides); the fourth is a
non-issue and is recorded here so nobody re-opens it:

**`DataExtractionRules` is moot, not unfixed.** Lint says `android:dataExtractionRules` applies only
to Android 12+ and advises also setting `android:fullBackupContent` for `minSdkVersion` 26. This app
sets `android:allowBackup="false"`, so no backup mechanism runs on any API level and the suggested
attribute would do nothing. Adding it to silence lint would be adding a line that has no effect.

**`StaticFieldLeak` on `KeyboardSwitcher` is inherent to the class.** It is a process-scoped
singleton in an input method, holding the view it manages; the "leak" lasts exactly as long as the
process that needs it. Changing it means changing how the IME holds its keyboard, which is not a
lint fix.

**`StringFormatMatches` (75) is the one worth a session of its own.** These are format strings whose
arguments do not match their use, which is an `IllegalFormatException` at runtime rather than a
style complaint. All 75 are in inherited AOSP strings and translations, and none is on a path this
fork added - but "inherited" is not "harmless", and nobody has checked which of them a user can
actually reach. Unexamined, deliberately listed rather than left inside a number.

## Unexamined

- The **correctness** half of `keyboard/` (rendering, geometry) and `latin/utils/` beyond logging.
  Three sweeps answered the security question there; nothing has answered the behaviour question.
- **Lint**: 502 filtered errors in the baseline.
