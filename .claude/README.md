# `.claude/`

Agent-facing configuration. `CLAUDE.md` at the repository root is still the single source of
truth about this project; nothing in here retells it, the same way no document in `docs/`
retells a neighbour. What is here is the *shape* — which agent owns what, which checks are
real, and which commands are never run from a session.

```
settings.json      permissions, two hooks, the marketplace this project knows about
agents/            eighteen area agents, one per part of the tree that has its own traps
skills/            nine procedures, each one a thing that has gone wrong here before
commands/          two slash commands: /where-are-we, /pre-push
hooks/             the one hook long enough to need a file of its own
```

## Why eighteen agents and not a hundred

Each file in `agents/` exists because that area has produced a defect this project had to fix,
and the file carries the fact that would have prevented it. An agent that only repeats
`CLAUDE.md` would not survive the review standard this repository applies to comments, and a
stub agent is worse than no agent: it answers confidently from nothing. The nine-way split in
`skills/audit/SKILL.md` is the one that actually found thirty-one defects in a pass; the
other nine agents are the areas that split further once they had traps of their own.

Add an agent when an area grows a trap that is not written down anywhere else. Delete one
when its trap stops being true.

## The two hooks

Both say one sentence and only when it applies; neither blocks anything. The first is inline
in `settings.json` and fires after a write to a `values/strings*.xml`, because the Russian
file is the source and its English twin is a separate edit nothing else would remind anybody
about until `ResourceTranslationTest` failed.

The second is `hooks/handover-behind.sh`, on `Stop`. It speaks only when a **merge commit on
`HEAD` neither carried the close-out nor predates it** — which during a batch is never true,
because that merge appears only after the pull request has gone into `main` and `dev` has
been fast-forwarded onto it. It asks first whether the merge's own diff touched
`HANDOVER.md`, because a close-out written inside its own pull request is landed *by* the
merge and is therefore always older than it; a timestamp alone called that missing, and said
so the first time the rule was followed properly. That is precisely the window in which the close-out is the
work that is left, and the hook goes quiet again the moment the file is committed, so it
cannot turn into background noise. It exists because the owner had to ask for that update by
hand twice.

## `settings.json`

- **`permissions.allow`** lists the commands this project genuinely runs: the Gradle wrapper,
  ruff, mypy, pytest, and the read-only half of git.
- **`permissions.deny`** covers the two things a session must not do here — apply a migration
  by hand (they go through the Neon connector, see `skills/migration/`) and seed the demo
  class against a real database — plus force-pushing and reading anything that holds a
  credential.

  Deny rules match the **start** of the command string, so
  `DATABASE_URL=… .venv/bin/alembic upgrade head` slips past the `alembic upgrade *` rule.
  The rules are a guard rail, not a fence. The reason not to run it is in
  `skills/migration/SKILL.md`.
- **`permissions.ask`** holds `.github/workflows/**`, because the workflows work and the
  reasons they are shaped the way they are cost real money to learn (`docs/build.md`,
  "Actions minutes").
- **The one hook** fires on a write to any module's `values/strings*.xml` and says that the
  name needs its `values-en/` twin. `values/` is the one place in this repository where
  Russian is the source rather than a quotation. It only prints; it blocks nothing. A hook committed here
  runs on every teammate's machine, which is why there is exactly one and why it cannot fail.
- **`extraKnownMarketplaces`** registers `anthropics/skills` so `/plugin` can offer it. No
  plugin is enabled by default: enabling one is a decision for everyone who clones this
  repository, not for the session that happened to add the file.

## What is deliberately not here

- **No `.mcp.json`.** This project's Neon and Vercel connections need credentials, and
  secrets never enter this repository. The Neon project is the one **named `lessons`** — the
  account has two — and that is all that can safely be written down; the rest is in
  `skills/migration/SKILL.md`.
- **No `settings.local.json`.** It is gitignored, and it is where personal overrides belong.
- **No model or `defaultMode` pin.** Both are the reader's choice, not the repository's.
