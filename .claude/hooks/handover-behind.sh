#!/bin/sh
# Fires on Stop, and says nothing unless HANDOVER.md is genuinely behind the work.
#
# The condition is narrow on purpose: a merge commit on HEAD that the close-out
# did not arrive with and is not newer than. During a batch there is no such
# merge — it appears only after a pull request has gone into main and dev has
# been fast-forwarded onto it, which is exactly the window where the close-out
# is the remaining work. It goes quiet again the moment the file is committed,
# so it cannot become background noise.
#
# Twice the owner had to ask for this by hand. That is what this replaces.
#
# The first check is the one this got wrong. A close-out written inside its own
# pull request — which is what the rule asks for while that pull request is
# still open — is landed *by* the merge commit, so it is always older than the
# merge and a timestamp alone reports it as missing. It fired that way the first
# time the rule was followed properly. Asking whether the merge carried the file
# is the question that was meant all along; the timestamp is the fallback for a
# close-out committed after the merge instead.

cd "${CLAUDE_PROJECT_DIR:-.}" 2>/dev/null || exit 0
git rev-parse --git-dir >/dev/null 2>&1 || exit 0

merge=$(git log -1 --merges --format=%H HEAD 2>/dev/null) || exit 0
[ -n "$merge" ] || exit 0

# Landed by the merge itself: the close-out rode in with its own pull request.
if git diff --name-only "$merge^1" "$merge" 2>/dev/null | grep -qx 'HANDOVER.md'; then
  exit 0
fi

merged_at=$(git log -1 --format=%ct "$merge" 2>/dev/null)
[ -n "$merged_at" ] || exit 0
doc=$(git log -1 --format=%ct -- HANDOVER.md 2>/dev/null)
[ -n "$doc" ] || doc=0

[ "$merged_at" -gt "$doc" ] || exit 0

subject=$(git log -1 --format=%s "$merge" 2>/dev/null)
printf '{"systemMessage":"HANDOVER.md has not been touched since %s. A batch whose pull request has merged is not finished until that file describes it — see the handover skill for what goes stale."}\n' \
  "$(printf '%s' "$subject" | sed 's/[\\"]/\\&/g')"
