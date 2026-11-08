---
name: large-refactor
description: Carry a change across many files at once — splitting a god class, moving code between modules, renaming a concept everywhere, ratcheting a lint or a type rule — as a per-file pipeline with a gate after every step, the way Airbnb runs its LLM-assisted migrations. Use before any change that touches more than about ten files with the same transformation.
---

# A change across many files

Airbnb moved 3.5 thousand React test files from Enzyme to React Testing Library in six weeks
with an LLM doing the edits, and the part worth copying is not the model — it is the
pipeline around it (`docs/engineering.md`, «Large changes are a pipeline»). Each file goes
through fixed steps, each step ends at a gate a machine checks, the model is asked only when
a gate fails and is handed that gate's errors, every file carries the step it reached, and
the run is tuned on a small sample of the failures before it is swept across the rest. What
decided the success rate was which related files went into the context, not the wording of
the request. This repository's decompositions
(`docs/specs/2026-10-11-android-decomposition-a-plan.md`, #625) are exactly that shape, and
so is every baseline burn-down.

## Before the first file

1. **Write down the transformation as a rule a reviewer could apply by hand**, in the
   design or plan under `docs/specs/`. "Split `GithubRepositoryImpl` by collaborator: device
   flow, pull requests, issues, permissions; the public interface does not change" is a
   rule. "Clean up the repository" is not.
2. **Name the gate that proves one file done.** For Kotlin: the module compiles, its tests
   pass, `./gradlew :<module>:detekt` holds. For Python: `ruff check`, `python -m mypy`,
   the tests that import the module. A step without a gate is a step nobody can tell is
   finished.
3. **List the files**, with `rg` or a Gradle task, into the plan as a checklist. That list is
   the progress bar; nothing else is.
4. **Do three files by hand first** — the simplest, a typical one and the worst. The worst
   one is where the rule turns out to be wrong, and finding that out on file three costs
   nothing; on file sixty it costs a revert.

## The loop, per file

```
read the file, every caller (rg the symbol) and its tests  →  apply the rule
  →  run the file's gate
       green  →  commit (one sentence: what the change makes the project do)
       red    →  read the failure, fix the cause, run the gate again
                 (three rounds at most — then stop and write down why)
```

- **Give the step the right files.** The file, its callers, its tests and one file already
  done by the rule are the context; a request with the rule and nothing else is how the same
  wrong edit is made forty times.
- **Feed the failure back, not the wish.** The gate's output is the context the next attempt
  needs: the compiler's line, the test's assertion, detekt's rule id. "Try again" without
  it is how a loop repeats its own mistake.
- **A file that will not go green in three rounds is not forced.** Leave it as it was, note
  it in the plan's checklist with the failure, and go on. Airbnb's automated loop allowed
  about ten attempts and still finished on a manual tail of three per cent; an agent here
  reads each failure itself, so three rounds is where a fourth stops being information.
- **Sample, tune, sweep.** When several files fail the same way, stop the sweep, take five of
  them, fix the rule or the context until those pass, and only then go on — the sample is
  where the success rate is raised, not the retries.
- **Move a baseline entry, never add one.** When code moves between files, detekt's baseline
  names the old file; `./gradlew detektBaseline` after the move rewrites it, and the diff of
  the baseline must show entries moving or disappearing, never new ones (#624).
- **One rule per pull request.** A split and a rename in the same diff cannot be reviewed as
  either.

## Measuring it

Keep the checklist in the plan current: done, left by hand (with why), not started. Say the
numbers in the pull request — «41 of 44 files, three left by hand: …» — and the test and
baseline counts before and after. A large change reported as «done» with no count is a
claim nobody can check.

## Gates

The `gates` skill, for every half the change touched, after the last file and before the
push — the per-file gates are narrower and do not replace it.
