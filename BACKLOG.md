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

**`StringFormatMatches` (75): examined, and not a crash. The entry above this one said it was, and
that was wrong.**

The previous version of this section called these "an `IllegalFormatException` at runtime rather
than a style complaint" and set them aside as the next real piece of work. Reading them says
otherwise, and the correction is left visible because the claim was made in a commit message too.

All 75 are **two** string resources - `abbreviation_unit_milliseconds` (74, once per translation)
and `abbreviation_unit_percent` (1). Both declare `%s`; all three call sites pass an `int`:

```
res.getString(R.string.abbreviation_unit_milliseconds, value)   // value is an int
```

`%s` accepts any `Object`, so the autoboxed `Integer` formats through `toString()` and no exception
is possible. Lint flags it because passing an `int` to `%s` is usually a sign the author meant
`%d` - a reasonable suspicion, and false here. Every translation checked uses `%s`, so there is no
mismatched-translation case either, which is the shape that WOULD throw.

Worth noting the one behavioural difference, since it argues for leaving it alone: `%s` renders the
number through `Integer.toString`, while `%d` would render it in the formatting locale's digits.
These are millisecond and percentage values in a settings screen, and nothing compares them across
devices - unlike the safety number, where the same distinction was a real defect and was fixed the
other way.

So the lint baseline contains **no unexamined category with behaviour behind it**. That is the
result; it is less interesting than a finding, and it is the honest one.

## Unexamined

- `latin/utils/` beyond its logging. Three sweeps answered the security question across
  `keyboard/` and `latin/utils/`; the behaviour question there is still open.
- The **rendering** half of `keyboard/` - what is painted, and how it looks. The **geometry** half
  is now answered: `EveryKeyIsWhereItIsDrawnTest` asserts, over every key of a real inflated
  keyboard, that a key contains its own drawn centre, that no two keys are painted over each other,
  and that the proximity grid offers the key under the touch. Those hold. Note what that test does
  NOT claim: it compares drawn rectangles for overlap, never hitboxes, because `mHitbox` extends to
  halfway towards each adjacent key by design so no touch lands in a dead gap - a test forbidding
  hitboxes to meet would assert the opposite of the design.
- **Lint**: 502 filtered errors in the baseline.
