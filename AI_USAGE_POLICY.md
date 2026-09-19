# AI usage policy for writing code

## Where we stand

We, the contributors and maintainers of this project, treat artificial-intelligence tools
as an ordinary part of the working process. Using them needs no justification and makes a
contribution no less valuable. A significant part of this repository was written that way,
and the commit history does not hide it.

At the same time we hold to a simple principle: **the person who submits a contribution is
responsible for it, however that contribution was produced.** A tool can propose a
solution, but the choice, the verification and the consequences of that choice belong to
the author of the pull request.

This policy describes what follows from that in practice. It does not restrict the choice
of tools.

## What a contributor affirms by contributing

By submitting a change you affirm the following — exactly as you would for code written by
hand:

- the terms of the tool you used place no restriction on its output that is incompatible
  with the project's licence (MIT), with the project's position on intellectual property,
  or with the [Open Source Definition](https://opensource.org/osd);
- if the output contains borrowed material protected by copyright, you have the rights
  holder's permission to use it, change it and pass it to the project;
- together with such a contribution you state the third-party rights involved and the
  licence terms that apply.

The last one is not an abstraction in this project. The design system and some of the
components were carried over from
[sameerasw/essentials](https://github.com/sameerasw/essentials) (MIT), and
[docs/design.md](docs/design.md) says what exactly was taken and what was done differently.
The typeface arrived the same way, but its licence is its own — SIL Open Font License 1.1,
© 2015 Google LLC — and that is precisely the case the clause above is written for: a
repository's licence covers what its author is entitled to license, not everything that
sits inside it. Check with the file itself: a typeface declares its licence in its own
`name` table. The work with the Petersburg electronic diary is arranged the same way: its
addresses and parameters were established from open clients and **used as a map, not
copied**. A new piece of borrowing is described the same way — otherwise in six months
nobody will be able to say where it came from.

## What a contributor checks before submitting

- **Read what you are submitting.** Code you have not gone through line by line cannot be
  called verified — and it does not matter who wrote it.
- **Read the definition, not only the call sites.** In this project, a conclusion drawn
  "from the callers" has already produced a wrong answer, and `docs/design.md` keeps the
  analysis of the confirmed defects, half of which looked correct from any other file.
- **Check a dependency's version in its own repository.** The first CI run here failed on
  four versions that exist in no repository; all four looked plausible. The header of
  `android/gradle/libs.versions.toml` was written after that.
- **Check that a string is translated.** A Russian string with no English twin does not
  break the build; `ResourceTranslationTest` catches it — and better it than a user.
- **Bring evidence, not assertions.** Paste the real output of `pytest`, `ruff` and
  `./gradlew test` into the pull request. "Everything passes" is not evidence.
- **Do not describe as passed what was never run.** The README keeps an "Honest status"
  section precisely because compilation says nothing about what happens on the screen.
- **State separately what the contribution does not cover.** Silence harms the project more
  than incompleteness does.

These requirements are the same for every contributor. They are listed here because AI
tools produce plausible text faster than a person can check it — and that is the one
particularity worth mentioning separately.

## Data that may not be given to a tool

Extending the "What is particular about this project" section of the
[Code of Conduct](CODE_OF_CONDUCT.md): do not paste into a conversation with an external
service anything that Code forbids publishing.

- the bot token, `WEBHOOK_SECRET`, `CRON_SECRET`, the database connection string, a device
  token and the contents of `server/.env`;
- the password and session token of an electronic diary — yours or anybody else's;
- the keystore that signs the APK, its passwords and the contents of
  `~/.gradle/gradle.properties`;
- a real timetable with teachers' surnames, a class code, a dump of a school's database.

Sending data to an external service is equivalent to publishing it: it may be stored or
indexed even after the conversation is deleted. For debugging, the demo class from
`scripts/seed_demo.py` will do — that is what it was written for.

## Attribution in commits

A commit has one author: the person who answers for the change. Everything else in the
message serves one purpose — that a year from now the reason for the edit can still be
worked out.

The convention this repository grew into is to **keep a `Co-Authored-By` trailer naming the
tool** at the end of the message when one took part: the overwhelming majority of commits
do it, and there is no reason to break the consistency of the history for a new rule. The
trailer changes nothing about where responsibility sits: it names a tool, while the person
who submitted the change is the one who answers for it.

What a message must not contain is promotional phrasing, or a description of the process in
place of the result. The subject says what the change lets the project do, the body says
why; "generated with" answers neither question.

## Automated contributors

The project assumes that part of the work is done by agents. The information about the
repository that is loaded into every session lives in [`CLAUDE.md`](CLAUDE.md): the
commands, the module boundaries, and the list of what will bite anybody who does not know
them. The agent configuration around it is in [`.claude/`](.claude/README.md) — agents for
each area of the tree, the procedures they follow, and the permissions a session runs
under. The rules themselves are the same as for people, and they live in this file and in
[CONTRIBUTING.md](CONTRIBUTING.md).

One rule exists only because of agents: **somebody else may be working in this tree at the
same time.** Look at `git status` before you touch a file you did not open, and do not
revert somebody else's uncommitted work.

An agent acts on behalf of the person who started it. Every obligation in this policy stays
with that person.

## What is not forbidden

To head off an over-cautious reading, here it is plainly. This policy does not forbid or
restrict:

- using any AI tool at any stage of the work;
- generating code, tests, documentation, commit messages and pull request descriptions;
- refactoring, hunting bugs, explaining somebody else's code and reviewing changes with
  their help;
- working through agents, including ones that run a task for a long time.

There is exactly one requirement: the contribution must be verified and understood by the
person submitting it.

## Enforcement

Failing to follow this policy is treated the same way as failing to follow any other
requirement for a contribution described in [CONTRIBUTING.md](CONTRIBUTING.md): the change
goes back for rework with the reason stated.

Systematically submitting unverified changes, and passing the data listed above to external
services, are handled in the manner described in the [Code of Conduct](CODE_OF_CONDUCT.md).

## Attribution

The section "What a contributor affirms by contributing" draws on the Linux Foundation's
[Generative AI Policy](https://www.linuxfoundation.org/legal/generative-ai).

The tone and the order of the sections follow this project's
[Code of Conduct](CODE_OF_CONDUCT.md), adapted from the
[Contributor Covenant](https://www.contributor-covenant.org), version 2.1.
