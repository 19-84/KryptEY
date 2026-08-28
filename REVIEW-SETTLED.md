# Claims already investigated

Read this before reporting. Every entry is a claim a review round made that was **investigated and
settled**, with the evidence that settled it. Re-reporting one of these without new evidence is the
churn this file exists to stop; refuting one *with* new evidence is welcome and is exactly how an
entry should be overturned.

Settled does not mean unimportant. Two of these describe attacks that would work against a slightly
different build, which is why each has a test holding the property in place.

---

## Refuted — the mechanism is real, the consequence does not follow

**A corrupted first message poisons every genuine copy, via the Kyber replay guard.**
The guard records the sender's base key in `markKyberPreKeyUsed`; libsignal calls that during session
derivation; derivation precedes authentication. If all three held, one flipped byte would burn the
base key while the message carrying it failed, and every later copy — all carrying the same base key
— would be refused as a replay.
*Measured against libsignal 0.86.5, two corruptions (a byte in the body; a byte in the trailing MAC,
which parses and fails authentication): no base key recorded, no pre-key marked used, genuine copy
opens.* The store callbacks are not reached until the message verifies.
Held by `AcorruptedFirstMessageDoesNotPoisonTheGenuineOneTest`, including a replay-refusal floor so
the test cannot pass against a build with the guard deleted. **A libsignal upgrade could overturn
this** — that is what the test is for.

**`loadPreKey` marks a one-time pre-key used on a decrypt that then fails.**
Same path, same measurement: the used flags are identical before and after a failed decrypt.

**A wrong-length bundle signature throws out of the decrypt click listener and kills the IME.**
The decoder accepts a signature field of 1..255 bytes and the verifier checks only that it is
non-empty, so an attacker-chosen length reaches `ECPublicKey.verifySignature` with only `IOException`
caught on that path. The reasoning is right about the reach; the library does not throw.
*Measured against libsignal 0.86.5: signature lengths 1, 63, 65, 100 and 255 all return `false`.*
Held by `AwrongLengthSignatureIsRefusedRatherThanThrownTest`.

## Refuted — the code already does what the finding asks

**The per-raise reachability guard is satisfied by the comment naming the safe call.**
`AconditionWarningIsLoweredWithItsConditionTest` reads `LatinIME` through `sourceOf`, which strips
block and line comments before scanning. Mutant: delete the call, keep its comment — the test fails.
The *smaller* half of that finding was right and was fixed: the ordering check excused a missing
landmark, so rewording a log line would have made it pass vacuously.

---

## How to overturn an entry

State the version or condition under which the measurement no longer holds, and the experiment that
shows it. "The reasoning still looks right to me" is not new evidence — the reasoning is recorded
above precisely because it looks right.
