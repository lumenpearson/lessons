---
name: audit
description: Sweep the project for defects the way the nine-area audit did — by area, with every finding re-verified by hand and closed by a test that fails without the fix. Use when asked to check for bugs across modules rather than to fix a known one.
---

# How an audit is run here

This protocol found thirty-one defects in one pass. It works because of the two rules at the
bottom, not because of the fan-out.

## Split by area, not by file count

Nine areas, one pass each:

1. `api/` — endpoints, both bearer tokens, throttles
2. `bot/` — routers, renderers, keyboards, callback parsing
3. `services/` + `schedule.py` + `models.py` — the rules
4. `providers/` + `config.py` + `crypto.py` + `migrations/` — the edges
5. `:core:model` + `:core:data` — domain and sync on the phone
6. `:core:designsystem` + `:widget` — what is drawn
7. `:app` — screens, navigation, ViewModels
8. whatever feature is newest, on its own
9. build, CI, scripts, and the integrity of the test suite itself

The matching agents are in `.claude/agents/`; each carries the traps its area has already
produced.

## What to look for, by shape

- A renderer that grows with the data and has no budget (Telegram refuses at 4096 characters
  **after entity parsing**, and refuses the whole message).
- A string from outside that reaches a message unescaped.
- A bare conversion on callback data.
- A response type that changes whether an exception is thrown (`Response<T>` vs a body type
  in Retrofit).
- A cache key that outlives the data it describes.
- `LocalDateTime.now()` or `ZoneId.systemDefault()` on the Android side.
- A `server_default` spelled `.value` on an enum column.
- A list page whose cap differs from the keyboard's.
- A "view" dataclass that nothing constructs.

## The two rules

1. **Re-verify every finding by hand before fixing it.** Read the code and re-measure.
   Findings drawn from call sites without reading the callee have been wrong in this project
   before, and `docs/design.md` records that.
2. **Close every finding with a test that fails on the code without the fix.** Revert,
   watch it go red, put it back. Three of this project's own tests turned out to assert the
   defect as correct behaviour; when a fix makes a test fail, decide which of the two is
   wrong before touching either.

## Reporting

One sentence per finding, saying what the user would have seen. Counts before and after.
Say what you did **not** cover.
