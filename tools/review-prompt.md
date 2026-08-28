# Standing requirements for adversarial review rounds

The scope changes every round; these requirements do not. Paste them into the reviewer's prompt
under the scope. They exist because specific things went wrong, and each rule names which.

## Before reporting

1. **Grep `REVIVAL.md`.** It is the running record of what has been found, fixed and deliberately
   deferred. A finding it already settles is not a finding.
2. **Read `REVIEW-SETTLED.md`.** Those claims were investigated and refuted with evidence. Report one
   again only with *new* evidence, and say what the new evidence is.
3. **Ignore the deferred product decisions** REVIVAL.md lists (chat-log cap, invite retention bound,
   RESET-when-the-Keystore-key-is-gone, QR versus string, invite threshold) and store rollback by
   direct file access, which is outside the threat model.

## Per finding, required fields

- **Claim** — one line.
- **Sites** — `file:line` for every element the claim rests on.
- **Reachability**, exactly one of:
  - `CONFIRMED` — you traced it to a production caller and can name the caller at each step;
  - `INFERRED` — you read the mechanism but did not trace a caller; say which link is unverified;
  - `SHAPE` — you could not reach it; report it as a shape the code no longer matches, not as an attack.
  A finding dressed one level above what you verified costs a whole round to unpick.
- **What settles it** — the single experiment that would confirm or refute the finding, and roughly
  what it costs. This is the most valuable field in the report: a recent round's largest finding was
  refuted in ten minutes because it said "one test away", and two of that round's four findings were
  wrong. Being wrong is fine; being unfalsifiable is not.
- **Cost to the user** — what they see, lose or wrongly believe. If the answer is "nothing, but the
  comment is false", say that — this project treats a comment contradicted by the code beside it as a
  defect, and labelling it as comment-only is what keeps it cheap to act on.
- **How your fix could make it worse.** Every fix on this surface has a way of doing that, and four
  of them have. Name the most likely one.

## Claiming a test is hollow

Say the exact mutant — the production edit that reinstates the defect — and confirm you followed
every helper the test calls before concluding it cannot see it. A guard that reads source through a
helper which strips comments looks blind and is not.

## Shape of the report

- **One message.** Reports have arrived split, with the severe half missing.
- Most severe first. No style or consistency findings unless behaviour changes.
- End with **what you did not check**, explicitly. A gap you name is worth more than a finding you
  padded, and it is what the next round's scope is built from.
- If you found nothing real, say so plainly and list what you attacked. On unaudited ground that is a
  result, not a failure.
