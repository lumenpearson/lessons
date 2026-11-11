# Three branches with preserved work (#724)

The owner requests master for production, dev for tested integration, and one
temporary working branch merged into dev and then deleted. Apply this on GitHub
and locally. Claude Code branches are part of the work to reconcile, not foreign
features to discard. Keep worktree directories and all uncommitted/private files.
The owner delegates execution and review/merge without additional approval.

## Evidence and preservation

Base55b4c799 is PR722's verified release. Read-only inventory found65 local/remote
refs already contained in that release, eight open Dependabot heads, three pending
designs absent from the release, and four unmerged bot-editor commits containing
15 test functions absent by name. One old Android worktree has modified local
agent settings. These names alone do not establish whether a feature is missing:
compare the old regression's behavior with current shared-service rules.

A complete Git bundle and JSON inventory live outside tracked files in the
original checkout's work directory. Preserve before/after hashes of local WIP.
No reset, force-push, worktree-directory removal or unreviewed feature discard.
Only remove a ref after its work has a documented disposition and retained history.

## Ordered delivery

1. Restore the pending design documents and reconcile the bot branch's regression
   scenarios with the current implementation. Keep newer parent locks, atomic
   audits, rollback rules and shared services. Preserve original commit ancestry
   where practical; otherwise retain the verified bundle and explicit feature map.
2. Make CI run on dev and master as well as PRs. Keep the existing complete gates
   and aggregate required CI job. Only master/tags may use production signing.
   Production monitoring reads the repository's default branch, so GitHub's rename
   does not leave a hard-coded main endpoint. Current docs explain the promotion.
3. Deliver the reviewed working branch into dev; require exact-head dev CI. Rename
   main to master with GitHub's rename operation, preserving redirect/default/PR
   behavior. Verify repository rules and Vercel production-branch settings. Promote
   dev to master through another pinned green PR, then verify production.
4. Resolve all eight dependency PRs as a separate reviewed integration batch. Merge
   original dependency commits, preserve requirement mirrors/locks, install the
   declared versions, run complete gates and a pinned Android Studio Emulator smoke
   check. Do not call an unbuilt or rejected dependency update integrated.
5. Delete merged redundant remote/local refs under the owner's explicit instruction;
   retain master, dev and only an active working branch. Detach preserved old
   worktrees without changing their files before removing their old refs. Enable
   working-branch cleanup after dev merges. Review Dependabot configuration so
   maintenance does not silently recreate an uncontrolled branch queue.

The original next-bug request remains queued after this explicit branch-flow work;
select against the refreshed HANDOVER/live issues once the foundation is released.

## Compatibility and verification

No schema rewrite is implied. Existing APKs retain all v1 contracts. Installed
legal links use HEAD, but old guide and translation refs name main. New guide
reads follow HEAD and this project's translation PRs target persistent dev;
configured forks may resolve their own default only after a missing dev ref,
never on authentication/network failure. Old APKs keep their
bundled guide fallback and need an update to submit translations after cutover.
No production write or live Telegram send is needed. Existing pytest/JVM test
families hold the changes, followed by Ruff, mypy, complete bare pytest with actual
Git Bash and all four offline/JDK21 Android gates, serialized with heavy server
work. Facts come only from complete successful reports. Independent whole-range
review precedes each release; fix major findings with observed RED/GREEN.

UI APIs stay paused until an explicit coordinated grant. Pin APK SHA256 and commit,
record the emulator API/AVD and tested screens, retain screenshots locally without
credentials. A successful warmup is deployment evidence, not emulator coverage.
HANDOVER changes ride inside each open PR; the next batch records the actual prior
merge. Public reports never contain tokens, private hosts or deployment IDs.
