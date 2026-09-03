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

## Do the scanners actually fire?

`EveryFileATestReadsIsATaskInputTest` turned out to have a completeness gap that hid three inputs -
it looked for a fixed list of file extensions, and `.pro`, `.properties` and extensionless
`tools/` scripts were not in it. **A guard that closes a class of defect is itself subject to that
class**, so every source scanner in the suite was then driven with a defect it is supposed to catch.

| scanner | defect introduced | result |
|---|---|---|
| `NoWriteResultIsDiscardedTest` | a bare `storeAllAccountInformationInSharedPreferences();` | fires |
| `AnaddressIsRenderedOneWayPerRealmTest` | `ProtocolAddresses.key(...)` used inside the view | fires |
| `NostatementIsWrittenTwiceInArowTest` | the same call twice in succession | fires |
| `InstrumentationTestsCleanUpTheKeystoreTest` | an `@After` that stops resetting the alias | fires |
| `DebugLoggingStaysOffTest` | `DEBUG_PREVIOUS_TEXT = true` | fires, two cases |
| `EveryFileATestReadsIsATaskInputTest` | an undeclared `.pro` read | fires, after the fix |

All six discriminate. The one gap found was the one already fixed.

**One note on method, because it nearly produced a false pass.** The first attempt at the debug-switch
control searched for `public static final boolean DEBUG = false` and matched nothing - the switches
are named `DEBUG_EVENT`, `DEBUG_LISTENER`, `DEBUG_PREVIOUS_TEXT`, `DEBUG_ENABLED`. The run that
followed touched unmodified source and passed, which reads exactly like a scanner confirming its
own correctness. A control that does not apply is not a control, and it looks identical to a good
result unless the applying step prints something.

## Does the device suite discriminate?

The JVM guards were all driven with a defect they should catch. The device suite had not been: 41/41
had been reported several times while the only device tests ever watched failing were the five seal
ones, which were controlled when they were written.

**Controlled now, on the property that exists only on device.** `FLAG_SECURE` is what REVIVAL calls
"the change that most needs a device before it is trusted" - window flags behave differently across
vendors and nothing in this environment can run the keyboard. Removing `addFlags(FLAG_SECURE)` from
`LatinIME` makes `FlagSecureReachesTheWindowOnDeviceTest.asensitiveScreenMakesTheImeWindowSecure`
fail with its own assertion and the real `dumpsys` block:

    the chat log is on screen and the IME window is not marked secure ... every screen holding
    decrypted plaintext is screenshottable

So that test notices. The other 35 have not been watched failing, and that is a different state
from being wrong - recorded here so the next person knows which is which.

**They are not uniform, and two categories need no control.** `AutofillDoesNotReachTheKeyboardTest`
asserts a PLATFORM property - whether the framework reaches views inside an IME's own window - and
its own javadoc records that nothing in the layout or the code sets `importantForAutofill`, because
the branch chose to measure rather than add a guard it could not show a need for. There is no
app-side line to mutate, and the test is self-controlled by design: it establishes that autofill
fired, that the structure identifies fields by id and contains the host's own field, and that the
keyboard was bound, before believing the absence.

More generally **13 of the 16 device classes carry an explicit setup check** - an assertion whose
message begins "precondition", "fixture", "premise" or "control" - which is the shape that stops an
absence being believed for the wrong reason. The three that do not are
`ProtocolRoundTripOnRealHardware`, `StripRoundTripOnDevice` and `AndroidKeystoreCryptoBox`, and all
three assert positive round-trip outcomes rather than absences, which is the case where a setup
check earns least.

*The first version of this count said five classes, because it searched for the word
"precondition" alone and `AcontactSwitchDoesNotRepaintThePreviousNumber` writes "fixture:". That is
the same literal-matching defect this file documents seven times over, committed in the measurement
used to describe it.*

**Two false results on the way, both of which looked like answers**, and they are written into
`tools/README.md` because they are properties of the harness rather than of this branch: an install
failure produces no test result and is not covered by the script's retry, and a wrong package in
`KRYPTEY_TEST_CLASS` produces `Tests run: 1, Failures: 1` where the failure is `initializationError`.
The second is the dangerous one - every other false result this session was green, and that one is
red, which is the outcome a control is hoping for and therefore the one least likely to be inspected.

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

## Raising targetSdk, and the two defects it found

`targetSdk` went 33 -> 35 to match `compileSdk`. The point was not the number; it was that a stale
target opts the app out of platform behaviour changes and nothing anywhere notices. Two real
defects fell out of it, and both had been live the whole time.

**The compose affordances tracked focus, not the redirect.** `composeInsideTheKeyboard` raises the
redirect - `setShouldUseOtherIC(true)`, so typing lands in the encrypted compose box - and then
left the clear button and the encoding indicator beside it to be shown as a *side effect* of
`requestFocus()` succeeding. Its own javadoc says `requestFocus()` "returns false silently whenever
the view cannot take focus", and the `adoptState` call site's comment says relying on the focus
listener firing is "the mechanism that method was written to distrust" - and the user-visible half
relied on it anyway. Then the blur half hid them again while the redirect stayed up, which the
focus listener's own comment argues at length that blur must not lower.

Consequence, with no adversary: after any rebuild carrying a draft - a rotation, a dark-mode switch
- the strip shows the state that means "typing goes to the messenger" while typing goes into the
encrypted box, the user has no clear button for a draft they wanted rid of, and no indication of
which encoding a send would use. Not a plaintext disclosure; the app misreporting its own state on
the one surface that reports it.

Why nothing saw it: **Robolectric runs at whatever `targetSdk` says**, which was 33 for the whole
revival. Measured across an SDK matrix, the focus lands at 31 and 33 and does NOT land at 26, 28,
30, 34 or 35. So the JVM suite was green at the one level that happened to work, and the device
suite runs API 28 - where it was broken - but does not run these tests. Fixed by tying the
affordances to `isUsingOtherIC()`; `StripCarriedStateRound4Test.p5` and
`StripCarriedStateRound5Test.r4` are now pinned across `sdk = {26, 28, 30, 31, 33, 34, 35}`, and
they fail at five of those seven levels against the old code.

**The settings-app narrowing had been inert since Android 11.**
`SettingsActivity.aimAtTheSystemSettingsIfWeCan` calls `queryIntentActivities` to point the
first-run "go and enable a keyboard" intent at the real settings app rather than at a chooser a
lookalike can sit in. From `targetSdk` 30 that call is filtered by package visibility and the
manifest declared no `<queries>` - so it returned nothing, the method concluded there was no system
handler, and fell back to the implicit intent, which its own comment calls "not a fix". A filtered
query and a device with genuinely no handler are indistinguishable from inside that method, which
is why it never looked wrong. Lint had been saying `QueryPermissionsNeeded` about it as a warning
outside the baseline, where nothing read it. Fixed with a `<queries>` element naming that one
action; `OnlyTheSettingsIntentIsQueriedTest` pins it and enumerates every visibility-filtered
PackageManager call in the app so a second one cannot arrive undeclared.

**Also from this: the test JVM's heap was never chosen.** Gradle's default for a `Test` task is
512MB. Pinning two tests across an SDK matrix - each `(SDK, config)` pair is a Robolectric sandbox
holding a classloader over a full `android-all` jar - exhausted it, and it did not fail in those
tests. It failed in whatever ran next, as an `OutOfMemoryError` surfacing three classes away as
`Error inflating class <unknown>`. `maxHeapSize` is now 3g with the reason written down.

**Still open from the same lint pass**, both warnings, neither in the baseline, neither with
behaviour behind it: `UselessParent` on a `ScrollView` in `e2ee_verify_contact_view.xml` (a
redundant layout wrapper) and `ClickableViewAccessibility` on an `onTouch` lambda in
`E2EEStripView` that does not call `performClick` - the same accessibility shape as two entries
already baselined for `MainKeyboardView` and `MoreKeysKeyboardView`. LOW.

## What an API 35 device run actually did

The image builds and the guest boots - 369 seconds under TCG, faster than API 28, which is the
assumption that had kept this closed. The APKs install, the IME is selectable, the autofill service
registers. Then the suite does not finish, and the reason is worth writing down because it looks
exactly like an app defect and is not one.

**First attempt (4096MB, no settle step): the instrumentation process died before any test ran.**
`dumpsys window` showed `mCurrentFocus=Window{... Application Not Responding: com.android.systemui}`
and `mBoundToMethod=false`. Read naively that says the keyboard cannot bind on Android 15. What it
says is that an ANR dialog held window focus, which is the failure this harness's own header
records having already chased once on the google_apis image at 28 - and it produced a nearly
published claim then too.

**After hardening the harness** - 6144MB at API 34+, `hide_error_dialogs` re-asserted after the
reboot, and a wait for no ANR window to hold focus plus a settle period - **20 of 41 tests passed**
before the run ended on `keyDispatchingTimedOut`: "Waited 5087ms for FocusEvent". Input dispatch has
a five-second deadline and a software-emulated guest at API 35 misses it. The device state around it
is unambiguous about why: `100% TOTAL: 62% user + 33% kernel`, `system_app_anr`, "Long monitor
contention" across AppOpsService and ActivityManager, "Skipped 43 frames".

So the honest statement is: **API 35 device coverage is partial - 20 tests - and the remainder is
blocked by emulator capacity rather than by the app.** No test failed on its assertions. Nothing
here licenses a claim that the app works on Android 15; it licenses "20 device tests pass at 35 and
the rest could not be run here".

**Narrowing gets a real answer out of 35.** `KRYPTEY_TEST_CLASS` pointed at the classes that need no
window system runs the highest-value device claims without the load that saturates the guest. Two
runs, both `PASSED`:

| Classes | At API 35 |
|---|---|
| `AndroidKeystoreCryptoBoxTest` | `OK (11 tests)` |
| `TheMigrationSealIsRealOnDeviceTest`, `ChatLogSplitAgainstARealKeystoreTest`, `ProtocolRoundTripOnRealHardwareTest` | `OK (11 tests)` |

So **22 device tests pass on an Android 15 runtime**, and they are the ones that matter most: the
whole key-resolution ladder, the anti-laundering seal's five cases including
`destroyingTheMasterKeyLeavesTheDeviceSealed` and
`cleartextPlantedAfterTheDeviceHasConvertedIsRefused`, the chat-log split against a real keystore,
and `thesessionBuiltOnThisDeviceIsPqxdh`. The class list was read out of the run's own output rather
than inferred from the count - both runs happen to report 11, which is exactly the coincidence that
would have let a filter matching the wrong thing pass unnoticed.

What is still unanswered at 35 is the window-system half - `FLAG_SECURE`, autofill traversal, input
dispatch, IME binding. Those are the claims a real Android 15 is most needed for and the ones this
environment is least able to give, because their load is what makes the guest miss the five-second
input-dispatch deadline. They need hardware.

## Two people who cannot see each other

`EndToEndConversationTest` and `ProtocolRoundTripOnRealHardwareTest` both put Alice and Bob in one
JVM and produce a conversation by swapping which `Account` is active. Both say so in their own
javadoc, so this was known rather than hidden - but what it costs was not written down anywhere.

What a single process shares: one heap, so either identity's private key is reachable from the
other; one `SignalProtocolMain` singleton and one set of static test seams; one libsignal load; one
source of randomness. **The defect that shape cannot detect is the worst one this protocol can
have** - a key, a session or a plaintext read out of the sender's own store rather than out of what
arrived over the wire. In one process that read succeeds and the test goes green.

`TwoUsersInSeparateProcessesTest` gives each identity its own JVM. The only thing that crosses
between them is text on a pipe, which is the discipline a real user is under when they paste an
invite into a messenger. It asserts, in eight cases:

- the peers really are separate JVMs, and neither is the test's own process - checked with a token
  minted once per JVM and asked for twice, so a per-call random fails the premise rather than
  satisfying it
- they really are two identities, and neither knows the other before anything is exchanged
- an invite crosses as pasteable text, a session builds from it, and a message comes back
- the conversation runs in the direction it was not established from
- both sides independently read the **same 60-digit safety number**
- the plaintext is absent from the ciphertext **bytes** - not from the base64 wire text, where a
  string containing a space could never appear anyway, which is a mistake this suite made once
- a third party holding the invite and the ciphertext recovers nothing
- one character changed in transit is refused

Driven with four defects, each failing exactly the case that should catch it: a per-call JVM token
(`theyReallyAreTwoProcesses`), `SEND` returning the plaintext as its ciphertext
(`theyExchangeKeysAndThenMessages`), `RECV` inventing a plaintext instead of failing
(`athirdPersonHoldingTheTranscriptLearnsNothing` and `amessageAlteredOnTheWireIsRefused`), and
`SAFETY` computing over the peer's own address (`bothSidesReadTheSameSafetyNumber`).

**What it does not claim.** Two processes on one machine are not two devices. Both run the desktop
libsignal, both pass a null `Context`, so neither has an Android Keystore or persists anything - the
keystore half is covered separately and on a real Android 15 runtime. And no real messenger carries
anything: the wire here is a pipe.

**The next step, and its cost.** Genuinely two installs means two Android users on one emulator
(`pm create-user`, `am instrument --user 10`), which gives separate data directories *and* separate
Keystore entries, with the invite and ciphertext shuttled between them by the harness. That is the
first arrangement in this project that would deserve the phrase "two devices". It is roughly a day's
work against a guest that already cannot finish the existing suite at API 35, which is why it is
written down here rather than attempted.

## Release readiness, and what the rating rested on

Asked to rate how release-ready this is, the answer was 4/10 - fine for a personal build, not for
handing to anyone relying on it against a hostile messenger. The rating is worth less than its
inputs, so the inputs are here. Every one of these is a gap in *evidence*, not a known defect: none
of them is a bug report, and that is exactly why they are easy to leave un-listed.

Ordered by how much of the app's security claim rests on the missing evidence.

**The API-level matrix.** `minSdk` 26, `compileSdk`/`targetSdk` 35, and the device suite runs one
image: `system-images;android-28;default;x86_64`. So 26, 27 and 29 through 35 have never executed a
line of this app. That is not a rounding error for an IME - `InputMethodService` window handling,
inset dispatch, autofill traversal and `FLAG_SECURE` propagation all changed across that span, and
the `FLAG_SECURE` result this branch is proudest of was measured at 28 only. The `targetSdk` raise
to 35 makes this sharper rather than softer: every behaviour change it opts into is inert at API 28,
so the code answering those changes (`RECEIVER_NOT_EXPORTED`, the edge-to-edge insets) is checked by
`TargetSdkIsNotStaleTest` reading source text and by nothing running it. Adding a second AVD is the
cheapest real fix available here and is the one thing on this list that does not need hardware.

**Partly done.** `tools/test-on-emulator` now takes `KRYPTEY_EMU_API`, builds a per-level image and
asserts after boot that the guest is the level that was asked for. An API 35 AOSP x86_64 guest
boots under TCG in about six minutes - faster than 28, which was the assumption that had kept this
closed. The JVM suite also moved with the target: Robolectric emulates `targetSdk`, so 1573 tests
that ran at 33 now run at 35, and two of them are pinned across a seven-level matrix. What is still
one level is the *device* suite's routine run, and what is still zero is hardware.

**No physical device, ever.** The emulator's Keystore is a software implementation. `minSdk` 26
means `setIsStrongBoxBacked` and hardware attestation are reachable on real devices and absent here,
and the whole key-resolution ladder in `AndroidKeystoreCryptoBox` - lock-bound rungs, the step-down
on an insecure device, `applyApi28Protections` - has only ever been exercised against that software
stub. A TEE fails differently: `KeyPermanentlyInvalidatedException` on biometric enrolment, vendor
quirks in `setUserAuthenticationParameters`, StrongBox rate-limiting. The anti-laundering seal
depends on Keystore aliases surviving a file-level rollback, which is a property of the *real*
keystore that the emulator can only imitate.

**It has never run inside a real messenger.** The threat model is "the host app is hostile" and the
host has always been a test activity. Every assumption about `inputType`, cursor reporting,
`commitText` handling, autofill structure and clipboard behaviour is asserted against Robolectric
shims and one emulator.

*Amended.* This paragraph also said "two-device interop over an actual wire has not happened either:
every end-to-end test puts both parties in one process". The second clause was true and is not now -
see "Two people who cannot see each other" below. The first clause stands: two processes on one
machine is not two devices, and no real messenger has ever carried anything.

**The release APK is unsigned and the publish path is unverified.** `assembleRelease` produces
`app-arm64-v8a-release-unsigned.apk`; there is no signing config, so signing, upload and F-Droid
reproducible-build verification have never been exercised. `versionCode` 24 / `versionName` 0.1.5
are inherited and have not been reconciled with anything this branch changed.

**`minifyEnabled false`, so `proguard-rules.pro` has never been applied by an ordinary build.**
`EverySerialisedClassIsKeptTest` checks the rules say the right thing; nothing checks that a
minified build works. The rules are therefore correct as text and untested as behaviour - and the
serialised classes they protect are the ones whose loss corrupts stored identities.

**No external review.** Thirteen adversarial rounds ran, the last three clean at the
highs-and-criticals bar. Every one of them was me or an agent I briefed, and this session
repeatedly produced the defect classes it had just finished documenting - four guard holes were
written *after* the pattern was written up twice. Self-review converging is weaker evidence than it
feels like, and three clean rounds sit against a background rate that was still yielding HIGH
findings a day earlier.

**Performance is a desktop-JVM number.** Nothing has measured keystroke latency, key-derivation
cost or cold-start on a phone. Under TCG the emulator is too slow for any of it to mean anything.

None of the above is scheduled. They are recorded so that a later "it passed everything" has
something to be checked against.

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
