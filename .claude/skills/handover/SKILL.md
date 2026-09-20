---
name: handover
description: Read or update HANDOVER.md at the repository root — where the work stands, what was left alone, what nothing has verified. Use at the start of a batch to find out what is already true, and at the end to record what changed.
---

# HANDOVER.md

Root of the repository. **Working state, not part of `docs/`.**

## At the start of a batch

Read it, then re-check it. It is stale the moment it stops being updated, so before you act
on any sentence in it:

- `git status`, `git log --oneline -5`, and which branch you are on — **another agent may be
  working in this tree**; do not revert someone else's uncommitted work
- the open PR and its CI state
- `GET /api/v1/warmup` for the database head

## When — without being asked

**A batch is not finished until this file says so.** The trigger is the merge: the moment a
pull request goes into `main`, updating this file is the remaining work of that batch, not a
favour somebody has to request. Two sessions in a row had to be told «обнови HANDOVER» after
the merge, which is two batches that were reported as done while the one document a new
session starts from still described the batch before.

If the batch's own pull request has already merged, the update is its own commit on a `dev`
restarted from `main`, and its own pull request — a merged pull request accepts no new
commits.

## At the end of a batch

Update it — in English, like everything else written about this project — with four
things:

1. **What was done** — one sentence per item, naming what it makes the project do, the same
   voice the commit messages use.
2. **What was deliberately left alone**, and why. A decision not to do something is state.
3. **What nothing has verified.** "Written, never run" is legitimate, in the README's
   words; a claim of verification that did not happen is not.
4. **What only the owner can do** — the external cron, the bot's `/start`, a Vercel
   environment variable, an APK installed on a real phone.

Name branches, PR numbers, test counts and the schema head. Numbers are what the next session
can check cheaply.

## What goes stale mechanically

These drift every time and none of them is a judgement call, so walk them rather than
remembering them:

- **The opening paragraph** — which pull requests merged, the SHA `main` is at, what is open
  (and «nothing is open» is an answer), whether `dev` is level, the schema head, and whether
  `EXPECTED_REVISION` moved.
- **The chain of batch sections.** The new one goes on top; the one that was «What the last
  session added» becomes «What the session before it added», and so on down. Two sections
  both claiming to be the last is the commonest mess here.
- **The milestone table**, whose last row grows with every pull request the milestone takes.
- **The test counts, which live in three places** — this file's cheat-sheet, the README's
  «Honest status» table and `docs/architecture.md`. Take them from a real run, not from the
  last document that mentioned them: `docs/architecture.md` once carried a corrected total
  over a per-module breakdown that still summed to the old one, and the total hid it.
- **Section 5, what nobody has verified**, which grows with every path that shipped without
  a test able to reach it.
- **Section 7, what is left to the owner**, which shrinks when they do one of them.

A number written against a named commit is a record, not a claim, and is not rewritten: this
file reports the gates «at `d330d68`», and at that commit the count really was what it says.
