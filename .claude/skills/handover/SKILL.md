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

## At the end of a batch

Update it, in Russian, with four things:

1. **What was done** — one sentence per item, naming what it makes the project do, the same
   voice the commit messages use.
2. **What was deliberately left alone**, and why. A decision not to do something is state.
3. **What nothing has verified.** «Написано, не запускалось» is legitimate; a claim of
   verification that did not happen is not.
4. **What only the owner can do** — the external cron, the bot's `/start`, a Vercel
   environment variable, an APK installed on a real phone.

Name branches, PR numbers, test counts and the schema head. Numbers are what the next session
can check cheaply.
