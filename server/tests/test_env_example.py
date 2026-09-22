"""`.env.example` against the settings the code actually reads.

The file had drifted: three of the eleven variables a Vercel deployment needs
— `WEBHOOK_SECRET`, `CRON_SECRET` and `BOT_USERNAME` — were in `docs/deploy.md`
and in `config.py` and in nobody's `.env`, because the file everyone copies had
never heard of them. Two of those three are ones a deployment refuses to start
without, so the cost was a deploy each to find out.

Nothing else can catch this. A missing variable is not a missing field: every
setting here has a default, which is the whole reason the deployment check in
`config.py` exists, so the server starts perfectly well against an example file
that is half a year behind.
"""

from __future__ import annotations

import re
from pathlib import Path

from app.config import Settings

ROOT = Path(__file__).resolve().parent.parent.parent
EXAMPLE = ROOT / "server" / ".env.example"
WORKFLOWS = ROOT / ".github" / "workflows"
BUILD_DOC = ROOT / "docs" / "build.md"

# The one setting that is never written by hand. Vercel sets it about itself,
# and it is what switches on the refusal-to-start check — so a line for it in
# the file people copy would be an invitation to make a laptop refuse to start.
# The file says so in prose, which this test checks for rather than against.
PLATFORM_SET = {"VERCEL"}


def env_names() -> set[str]:
    """Every environment variable `Settings` reads, as it is spelled there."""
    return {
        (field.alias or name).upper()
        for name, field in Settings.model_fields.items()
    }


def example_names(text: str) -> set[str]:
    # Commented-out lines count: an optional variable is documented by being
    # shown switched off, which is the file's own convention for every one of
    # them that is empty by design.
    return set(re.findall(r"^#?\s*([A-Z][A-Z0-9_]*)=", text, re.MULTILINE))


def test_every_setting_the_code_reads_is_in_the_example() -> None:
    missing = env_names() - example_names(EXAMPLE.read_text("utf-8")) - PLATFORM_SET
    assert not missing, (
        "server/.env.example does not mention: "
        + ", ".join(sorted(missing))
        + " — add each with a comment saying what it is for and what empty means"
    )


def test_the_example_invents_nothing() -> None:
    # The other direction, and it is the one that rots quietly: a variable
    # removed from `Settings` leaves a line in the example that people go on
    # setting, and `extra="ignore"` means nothing ever says it does nothing.
    invented = example_names(EXAMPLE.read_text("utf-8")) - env_names() - PLATFORM_SET
    assert not invented, (
        "server/.env.example sets variables the code does not read: "
        + ", ".join(sorted(invented))
    )


def test_the_platform_variable_is_explained_rather_than_listed() -> None:
    # It is deliberately absent, and «absent» and «forgotten» look identical in
    # a file — which is how the three missing ones survived. The prose is what
    # tells them apart, so the prose is what is pinned.
    text = EXAMPLE.read_text("utf-8")
    assert "VERCEL" in text, "the example no longer says why VERCEL is not in it"
    assert not re.search(r"^#?\s*VERCEL=", text, re.MULTILINE), (
        "VERCEL is given as a variable to set; it is the platform's own, and "
        "setting it by hand makes a machine refuse to start"
    )


def test_every_actions_secret_is_written_down() -> None:
    """The other place this project's configuration lives.

    Eight repository secrets drive the two workflows that sign an APK and call
    the deployed tick, and none of them is in any `.env`: a reader looking for
    «what do I have to set» finds `server/.env.example` and would never learn
    that half the answer is in GitHub's settings. `docs/build.md` lists them.

    What this pins is that a secret a workflow reads is **named somewhere** in
    that document — a ninth one added to a workflow fails here until somebody
    writes it down. It cannot tell whether what is written is still true, and
    it does not try: a name is checkable and a sentence is not.

    Two of the eight have already cost something by being undocumented:
    `LESSONS_GITHUB_CLIENT_ID` and `LESSONS_CONTACT_EMAIL` were not passed to
    the build for the whole life of the workflow, so every APK it ever produced
    hid «Войти через GitHub» and «Отправить письмом», and nothing said so.
    """
    used: set[str] = set()
    for workflow in sorted(WORKFLOWS.glob("*.yml")):
        used |= set(
            re.findall(r"secrets\.([A-Z][A-Z0-9_]*)", workflow.read_text("utf-8"))
        )
    # Handed to every workflow by GitHub itself; nobody sets it, so nobody has
    # to be told about it.
    used -= {"GITHUB_TOKEN"}

    documented = BUILD_DOC.read_text("utf-8")
    missing = sorted(name for name in used if f"`{name}`" not in documented)
    assert not missing, (
        "docs/build.md does not mention the Actions secrets: "
        + ", ".join(missing)
        + " — say what reads each one and what happens without it"
    )
