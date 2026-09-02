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

## From round 13 (a review of my own unreviewed changes)

Two clean rounds had left the backlog empty, and I then made ten production changes to close it -
each with a test, but author and test-writer were the same party. Round 13 reviewed exactly that
diff. **No HIGH, no CRITICAL.** Six of the changes were confirmed genuinely pinned by reverting each
and watching its test go red with no sibling case failing beside it, and `saveIdentity`'s
`REPLACED_EXISTING` arm was traced rather than taken on trust: dead through every production path,
and the added line only removes a trust claim, which is the fail-safe direction.

Both MEDIUMs it found were mine, and both are fixed:

**`blankRow` disarmed Delete permanently.** It hides the button; the ordinary path set a listener on
it and never set visibility back, unlike the verified/unverified pair where both arms set both. Once
a row had been blanked, every later contact bound into that recycled `View` had no Delete button. My
test drove real -> blanked; the review drove blanked -> real. A one-directional test of a recycling
path tests half of it.

**`setKeyboard`'s null-view guard could never fire.** `setMainKeyboardFrame`, called the line above,
dereferences `mKeyboardView` first, so the NPE the guard named had already been thrown - and its log
line described a state it could not be reached in. That is the same "handler that looks like it
covers the code above it" defect fixed in `ListAdapterContacts.getItem` five commits earlier, then
committed again here. The check now lives at the first dereference.

Three careless fixes it warned off, worth keeping: dropping `setVisibility(INVISIBLE)` from
`blankRow` puts a Delete button on a nameless row; `GONE` instead of `INVISIBLE` reflows the row,
because the last-name view is `layout_toStartOf` that button; and hoisting the keyboard guard above
`setMainKeyboardFrame` skips the frame-visibility update on the hardware-keyboard path.

### LOW, from the same round

- `boundedActionLabel` still ends in a lone high surrogate if units 126 AND 127 are both high
  surrogates - already-malformed host input, same tolerated consumers, no change in outcome.
- `ListAdapterContacts.getItem` no longer catches `IndexOutOfBoundsException` from
  `mContacts.get(position)`; old and new both end in an exception out of list layout, so the
  diagnosis changes and the outcome does not.
- `setKeyboard`'s silent return leaves `KeyboardState` believing a transition succeeded while the
  view keeps its old keyboard. Only reachable with no layout set, and the alternative was an NPE.
- `isShowingMoreKeysPanel`'s guard is inert: its one caller is already inside
  `if (visibleKeyboardView.isShown())`. Consistency with seven siblings, as its javadoc says.
- Four changes are pinned by no test and verified inert rather than merely untested: both
  `super.onBindDialogView` calls (neither dialog layout contains any `@android:id`, so the base
  implementation binds nothing), `toUpperCase(Locale.ROOT)` on a `0-9a-f` string (an equivalent
  mutant), and the `SenderKeyStoreImpl` log line.

## Open

**Toast text is outside the IME window's `FLAG_SECURE`, and this is accepted.** Toasts carry
`labelFor(contact)` - a display name the user chose plus its address tag - in their own window, so
`notifySensitiveVisibility` cannot reach them and a screen capture during one includes the label.
Not changed, for two reasons. The content is a name the messenger already knows, since it is their
contact; and the alternative is toasts that no longer say which contact they are about, on a surface
whose whole value is telling the user which contact an action applied to. The banner path accepts
the same residue. Revisit if a toast ever carries message content, which none does today.

**Two `KeyboardSwitcher` NPE guards were added without tests** (`isShowingKeyboardId` not checking
`getKeyboard()`, `setKeyboard` not checking its layout set). The states are exactly the ones round 11
tried and could not construct, so a test would assert against a state nothing produces - the vacuous
shape this branch keeps rejecting. The guards are cheap, the reasoning is in the code, and if either
state ever becomes reachable the guard is already there.

Amended after round 13: this entry originally said `setKeyboard` checks "its view or layout set",
which was true of the code and wrong about what the code could do. The view half could never fire -
`setMainKeyboardFrame`, called the line above, dereferences `mKeyboardView` first - so that check
now lives at the first dereference and the guard here is the layout set only.

## The claims audit, and what it left

Once the listed backlog was empty, the remaining work turned out not to be in the list. Both
`REVIVAL.md` and `REVIEW-SETTLED.md` carry findings marked settled on the strength of somebody
having read the code - and a claim verified by reading has nothing that notices it becoming false.
Every absolute claim in both documents was enumerated and checked.

**Eight guards came out of it**, each verified in both directions:

| claim | guard |
|---|---|
| the shipped layouts add no capability | `NolayoutAddsAcapabilityTest` |
| nothing anywhere logs typed text | `NothingLogsWhatTheUserTypedTest` |
| `latin/settings/` persists no text | `SettingsPersistNoTextTest` |
| only this app's fragments can be launched | `OnlyThisAppsOwnFragmentsCanBeLaunchedTest` |
| every serialised class is kept by ProGuard | `EverySerialisedClassIsKeptTest` |
| nothing runs off the IME main thread | `NothingRunsOffTheImeMainThreadTest` |
| BACKLOG's lint figures match the baseline | `DocsDoNotContradictTheAppTest` |
| the geometry a touch uses is the geometry drawn | `EveryKeyIsWhereItIsDrawnTest` |

**Three claims were wrong**, and checking is the only reason anyone knows: the layout count had no
denominator anyone could reproduce; the action-code list named eight of twelve; and the lint summary
said 502 when the section below it said 602.

**Claims found already guarded, so nobody need re-check them:** the decoy corpus carries no
character from the payload alphabet (`FairyTaleInitTest`); `EnvelopeCodec.fromWire` never throws
unchecked (`ClipboardFuzzTest` runs the real listener sequence and fails on anything that is not an
`IOException`); the debug switches are compile-time false (`DebugLoggingStaysOffTest`);
`deSimplifyJsonKeys` (`EncodeHelperTest`); the inflate loop's budget (`DecompressionBudgetTest`).

**Left unguarded on purpose.** `MSG_PENDING_IMS_CALLBACK` is recorded as dead code, and a test that
dead code stays dead is a test of nothing. The ProGuard rules themselves cannot be exercised without
turning `minifyEnabled` on, which is a build change rather than a test - so what is guarded is the
pairing that rots, not the rules.

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

- ~~`latin/utils/` beyond its logging.~~ **Closed.** That package had no tests at all;
  its riskiest class now has five properties (`RecapitalizeRewritesOnlyTheCaseTest`), chosen because
  it is the buffer `performRecapitalization` deletes and re-commits and which that method's own
  comment says can hold decrypted plaintext. `CapsModeUtils` (which decides auto-capitalisation, so
  which character the user gets) and `ResourceUtils`' device-override matcher (which picks per-device
  keyboard dimensions) now have their contracts asserted too, as has `SubtypeLocaleUtils` - where
  the useful check is over DATA rather than a function: every locale the app claims to support must
  actually produce a subtype, which ties together a hand-maintained locale array, separate resource
  arrays and a set of layout-name constants that nothing else relates. Still unexamined:
  `XmlParseUtils`, which is thin - and was closed by measurement rather than by that reasoning. It
  is reached only from keyboard inflation, so the obvious conclusion is that inflating a real
  keyboard covers it. Measured: deleting `checkEndTag`'s refusal leaves both inflation tests green,
  because the 196 shipped layouts are well-formed and a guard that fires only on bad input is not
  exercised by good input. `AmalformedLayoutIsRefusedLoudlyTest` now pins it.
- The **rendering** half of `keyboard/` - what is painted, and how it looks. The **geometry** half
  is now answered: `EveryKeyIsWhereItIsDrawnTest` asserts, over every key of a real inflated
  keyboard, that a key contains its own drawn centre, that no two keys are painted over each other,
  and that the proximity grid offers the key under the touch. Those hold. Note what that test does
  NOT claim: it compares drawn rectangles for overlap, never hitboxes, because `mHitbox` extends to
  halfway towards each adjacent key by design so no touch lands in a dead gap - a test forbidding
  hitboxes to meet would assert the opposite of the design.
- ~~**Lint**: 502 filtered errors in the baseline.~~ **Stale, and it survived in this file for six
  commits after the section above corrected it.** The real figure is 602 across 23 ids, 427 of them
  inherited `UnusedResources`; all four entries with behaviour behind them were read, three fixed
  and one shown moot; the 75 `StringFormatMatches` are `%s` receiving an `int`, which cannot throw.
  Left visible rather than deleted, because a one-line summary contradicting a section of the same
  document is exactly what a reader skimming for open work would act on.
