#!/bin/sh
# Fires on Stop, and says nothing unless HANDOVER.md is genuinely behind the work.
#
# The condition is narrow on purpose: a merge commit on HEAD that is newer than the last
# commit touching HANDOVER.md. During a batch there is no such merge — it appears only after
# a pull request has gone into main and dev has been fast-forwarded onto it, which is exactly
# the window where the close-out is the remaining work. It goes quiet again the moment the
# file is committed, so it cannot become background noise.
#
# Twice the owner has had to ask for this by hand. That is what this replaces.

cd "${CLAUDE_PROJECT_DIR:-.}" 2>/dev/null || exit 0
git rev-parse --git-dir >/dev/null 2>&1 || exit 0

merge=$(git log -1 --merges --format=%ct HEAD 2>/dev/null) || exit 0
[ -n "$merge" ] || exit 0
doc=$(git log -1 --format=%ct -- HANDOVER.md 2>/dev/null)
[ -n "$doc" ] || doc=0

[ "$merge" -gt "$doc" ] || exit 0

subject=$(git log -1 --merges --format=%s HEAD 2>/dev/null)
printf '{"systemMessage":"HANDOVER.md has not been touched since %s. A batch whose pull request has merged is not finished until that file describes it — see the handover skill for what goes stale."}\n' \
  "$(printf '%s' "$subject" | sed 's/[\\"]/\\&/g')"
