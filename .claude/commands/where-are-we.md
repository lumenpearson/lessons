---
description: Show where this project's work stands right now — branch, uncommitted work, recent commits, and the state HANDOVER.md claims — then say what is actually next.
allowed-tools: Bash(git status *) Bash(git log *) Bash(git branch *) Bash(head *)
---

## Working tree

!`git branch --show-current`

!`git status --short`

!`git log --oneline -8`

## What HANDOVER.md claims

!`head -60 HANDOVER.md`

## Instructions

`HANDOVER.md` is stale the moment it stops being updated, and another agent may be working
in this tree — so treat the text above as a claim, not as fact.

Tell the user, in three or four lines:

1. Which branch this is and whether anything is uncommitted that you did not write.
2. Where the last batch actually got to, according to the commits — not according to the
   document.
3. What the document says is still open, and which of those items only the owner can close
   (the external cron, the bot's `/start`, a Vercel environment variable, an APK on a real
   phone).
4. The one thing you would do next, and why.

Do not start work. This command answers a question.
