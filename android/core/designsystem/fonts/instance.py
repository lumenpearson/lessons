#!/usr/bin/env python3
"""Freeze the variation axes this app never moves, at build time.

`google_sans_flex.ttf` beside this script is the typeface as it was
downloaded: six variation axes, 3.81 MB, of which 3.41 MB is `gvar` — one set
of outline deltas per axis per glyph — against 31 KB of outlines. The app
moves two of the six, so the other four are four fifths of the download for a
shape nothing ever asks for.

Freezing an axis is not dropping a feature: the glyphs are re-drawn at the
value the axis is frozen at, and every glyph is kept. What is lost is the
ability to ask for a different value later, which is why the build takes the
list of axes to keep rather than deciding for itself.

Run by `core/designsystem/build.gradle.kts` on every build. It is a script
rather than a line in that file because the axes to freeze have to be read out
of the font's own `fvar` table: the build knows which axes to keep, not which
axes exist.
"""

from __future__ import annotations

import argparse
import shutil
import sys

KEEP_EVERYTHING = "all"


def parse_arguments(argv: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", required=True, help="the font as it was downloaded")
    parser.add_argument("--output", required=True, help="where the instanced copy goes")
    parser.add_argument(
        "--keep",
        required=True,
        metavar="TAG,TAG",
        help=f"axis tags to leave variable, or {KEEP_EVERYTHING!r} to copy the file as it is",
    )
    parser.add_argument(
        "--pin",
        action="append",
        default=[],
        metavar="TAG=VALUE",
        help=(
            "freeze this axis at VALUE rather than at the font's default, because that "
            "is the value the app sets it to. Ignored for an axis --keep keeps."
        ),
    )
    return parser.parse_args(argv)


def main(argv: list[str]) -> int:
    arguments = parse_arguments(argv)

    if arguments.keep == KEEP_EVERYTHING:
        shutil.copyfile(arguments.source, arguments.output)
        return 0

    # Imported here so that `--keep all` needs nothing but Python: that value
    # exists precisely for a machine where fonttools cannot be installed.
    from fontTools.ttLib import TTFont
    from fontTools.varLib import instancer

    # `recalcTimestamp=False` so that two builds of the same source produce the
    # same bytes; the modification date in `head` is otherwise `now`.
    font = TTFont(arguments.source, recalcTimestamp=False)
    if "fvar" not in font:
        # A static font has nothing to freeze, and `font["fvar"]` below would
        # be a KeyError rather than a sentence. It is a legitimate thing to
        # replace the file with, and then `--keep all` is the correct setting —
        # but saying so has to be a decision, because every axis the app asks
        # for is silently ignored from that point on.
        print(
            f"{arguments.source} declares no variation axes at all, so there is nothing to "
            f"freeze. If the typeface was replaced with a static one, build with "
            f"`-Plessons.font.axes={KEEP_EVERYTHING}` and stop asking for axes in Type.kt.",
            file=sys.stderr,
        )
        return 1
    declared = {axis.axisTag: axis.defaultValue for axis in font["fvar"].axes}

    kept = [tag for tag in arguments.keep.split(",") if tag]
    unknown = [tag for tag in kept if tag not in declared]
    if unknown:
        print(
            f"{arguments.source} declares no axis {', '.join(unknown)} — it has "
            f"{', '.join(declared)}. Asking to keep an axis the file does not have is "
            "how an update to the typeface goes unnoticed, so this refuses rather than "
            "quietly keeping the rest.",
            file=sys.stderr,
        )
        return 1

    pinned = dict(pin.split("=", 1) for pin in arguments.pin)
    stale = [tag for tag in pinned if tag not in declared]
    if stale:
        # Silently ignoring one would be worse than it looks: the build would
        # succeed, and the test that checks a frozen axis against the value the
        # app asks for would still find the tag in the build file's map and
        # pass, while nothing had been frozen at all.
        print(
            f"{arguments.source} declares no axis {', '.join(stale)}, so freezing it at a "
            "value does nothing. The typeface was probably replaced; take the tag out of "
            "`fontAxisPins`, and out of Type.kt if the app still asks for it.",
            file=sys.stderr,
        )
        return 1

    frozen = {
        tag: float(pinned.get(tag, default))
        for tag, default in declared.items()
        if tag not in kept
    }

    instancer.instantiateVariableFont(font, frozen, inplace=True, updateFontNames=False)
    font.save(arguments.output)
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
