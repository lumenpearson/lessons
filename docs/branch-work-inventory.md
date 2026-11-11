# Branch work retained during the master/dev transition

This is the disposition of work found on 10 November 2026 for #724. A branch name
is not evidence that its feature is missing or finished. The cut starts at PR722's
verified merge55b4c799. A complete verified Git bundle and a per-ref/per-worktree
inventory were made before any cleanup. No worktree directory or local WIP is
deleted; one modified Claude settings file is preserved byte-for-byte.

## Already released work

Sixty-five local/remote refs in the initial inventory were ancestors of55b4c799.
They cover the Android calendar/decomposition, v2 contract, stale-write and parent
retention batches, gate/count/navigation checks and #377/#723. Their original
commits remain in release history. Deleting a redundant ref after verification
does not remove its feature. Exact branch/ref SHAs are retained in the local
inventory; completion of ref cleanup is recorded in the release proof.

## Contained feature refs

The contained feature refs below were checked individually with merge-base,
not classified solely from their names. The last column is the actual tip commit
subject, which identifies retained work even where a session later fast-forwarded
its branch to a newer release. A contained tip proves its committed changes are
in the release; feature completeness and deferred follow-ups remain the claims
in HANDOVER/history and their issues. Local and remote copies share a row only
when the feature name agrees. The remote HEAD alias is not a branch to delete.

| Contained feature ref | Copies found | Retained tip(s) | Actual tip commit |
| --- | --- | --- | --- |
| `agents/airbnb-practices` | remote | `51d0296d` | Restore the mounted v2 state after tests patch its flags |
| `android/calendar-period-panel` | local | `6758fd3d` | Put «Календарь»'s period between the arrows that step it, in a panel that grows |
| `android/decomposition-b` | local | `2ba842e2` | Say what the history does about attribution: no trailer since 6 October, and none in a new commit |
| `server/access-member-parent-702` | local + remote | `a1827794` | Keep class administration writes and audits in one guarded transaction |
| `server/access-request-parent-697` | local + remote | `08a312f7`, `537f8e8c` | Merge pull request #704 from lumenpearson/server/access-request-parent-697 |
| `server/approve-held-role-368` | local | `94f6e539` | Answer an approval that changes no role with «Роль не изменилась», not a near twin of the refusal |
| `server/bell-delete-rechecks-678` | local + remote | `ab18fca5`, `f102d8f1` | Recheck bell schedule references after acquiring the deletion lock |
| `server/bell-parent-races-662` | local + remote | `49988cee`, `7170be0b` | Merge pull request #680 from lumenpearson/server/bell-parent-races-662 |
| `server/class-audit-races-662` | local + remote | `22936695` | Check class updates before staging their audit entries |
| `server/connect-no-store-357` | local | `907f37a5` | Keep the member lists and every diary answer out of caches too, and name the table's members in a test |
| `server/detekt-budget-635` | local + remote | `92d84023`, `e36ebb8a` | Keep detekt baseline rewrites within reviewed budgets. |
| `server/device-page-names-352` | local | `057b6eb4` | Cut the device page's two names by their escaped length, not their raw one |
| `server/device-parent-races-654` | local + remote | `641f543e`, `bd459249` | Merge pull request #701 from lumenpearson/server/device-parent-races-654 |
| `server/diary-deprecations-391` | local + remote | `46f40dee`, `e3e5e249` | Merge pull request #683 from lumenpearson/server/diary-deprecations-391 |
| `server/editor-parent-707` | local + remote | `5d8dea13`, `8d66c4d4` | Keep timetable editor writes and their audit within retained parents |
| `server/gate-lists-659` | local + remote | `55b4c799`, `6f5a05ee` | Merge pull request #722 from lumenpearson/server/notice-lifecycle-377 |
| `server/homework-parent-races-662` | local + remote | `43adbae4`, `529bd36c` | Merge pull request #687 from lumenpearson/server/homework-parent-races-662 |
| `server/host-keepalive-400` | local | `8ab43362` | Say where the work stands after the night's five bug fixes, and bring CLAUDE.md level with them |
| `server/instruction-references-661` | local + remote | `6c92e226`, `f62699e0` | Merge pull request #720 from lumenpearson/server/instruction-references-661 |
| `server/join-parent-705` | local + remote | `663fc271`, `9d060cca` | Retain the class before device joins consume their code |
| `server/notice-lifecycle-377` | local + remote | `55b4c799`, `7cf8fc31` | Merge pull request #722 from lumenpearson/server/notice-lifecycle-377 |
| `server/phone-link-parent-708` | local + remote | `55b4c799`, `9d060cca` | Merge pull request #722 from lumenpearson/server/notice-lifecycle-377 |
| `server/special-day-parent-races-662` | local + remote | `47f43716`, `dc2964b0` | Merge pull request #685 from lumenpearson/server/special-day-parent-races-662 |
| `server/stale-bot-writes-654` | local + remote | `806bb16e` | Record the stale write regressions passing on PostgreSQL |
| `server/stale-write-not-found-384` | local | `2de5a077` | Answer «📆 Период» too when a day's mark is taken off mid-write, and keep the docs to what is covered |
| `server/stale-writes-api-654` | local | `edf8e03e` | Answer a diary correction cleared mid-save with a 409, which the app does not read as a missing child |
| `server/subject-collection-delete-654` | local + remote | `408f9c34` | Make subject collection and deletion retain their class |
| `server/subject-create-parent-654` | local + remote | `0dfe89d2`, `498b1439` | Merge pull request #694 from lumenpearson/server/subject-create-parent-654 |
| `server/subject-detail-races-654` | local + remote | `02125954`, `0dfe89d2` | Make subject detail retries retain their row and class |
| `server/task-homework-parent-710` | local + remote | `55b4c799`, `8d66c4d4` | Merge pull request #722 from lumenpearson/server/notice-lifecycle-377 |
| `server/task-parent-699` | local + remote | `5371d2c9` | Keep new personal tasks inside their Class transaction |
| `server/term-stale-audits-654` | local + remote | `432b6e78` | Make term writes retain their class and check unchanged rows |
| `server/test-counts-660` | local + remote | `03e36409`, `1d332804` | Refuse incomplete reports and support measured module updates |
| `server/unchanged-audit-races-654` | local + remote | `20a0c0c4`, `48c98bc8` | Make audited retries refuse rows deleted after lookup |
| `server/watch-contract-401` | local | `f82712d4` | Say in the contract that a newer stream ends the older cleanly, and that a clean end is no reason to reopen |

## Recovered designs

The unique commit3c606128 on pending/recovered-designs holds three documents absent
from the release tree. They return under docs/specs/pending with original content:
provider-side diary sign-in, languages of Russia, and a morning brief. Their
presence preserves design work; it does not approve or implement those designs.
The branch's original commit is merged, not rewritten.

## Historical bot-editor branch

The local server/stale-writes-bot-654 has four unique commits ending e805dad0.
It predates the safer shared-service work released by PR675 and later parent-lock
batches. A trial merge reports six overlapping conflicts. Wholesale restoration
would replace newer locking, rollback and atomic-audit behavior with older code.

The fifteen added historical test functions were extracted without their old
production code. Their complete source is retained in ignored evidence. Old
late-delete hooks can deadlock on SQLite after the retained-parent check has
already taken the file's write lock. Current regressions deliberately stage those
deletions before that guard, and cover the same refusal at a reachable boundary.
An interrupted historical diagnostic is not counted as a successful test run.

| Historical scenario | Current disposition and executable evidence |
| --- | --- |
| Homework disappears before save | Existing test_stale_writes.py homework_deleted_mid_write case; shared homework service and gone.committed retain rollback and form clearing. |
| Subject rename disappears before cascades | Existing subject_rename_deleted_before_cascade case seeds matching homework and verifies its original name/text from a fresh session, with no audit. |
| Subject short name/teacher, colour button and typed colour | Existing subject_detail_deleted_mid_write matrix holds all four inputs and form/audit cleanup. |
| A term is replaced during an edit | Existing term_replaced_by_scheme_mid_write case stages before SQLite retention. |
| Class disappears during scheme change | Current terms_scheme calls terms_manage.change_scheme inside gone.committed. The recovered_scheme_press control commits the class deletion and holds state clearing, class-gone answer and usable session. |
| Task completion and reminder race a deletion | Existing task_deleted_mid_write matrix holds both presses, no list redraw and a usable session. |
| Typed lesson disappears during edit | Existing lesson_deleted_after_editor_read case uses a new typed subject and verifies form clearing, no orphan dictionary row, unchanged remaining timetable and no audit. |
| Picked subject enters the same failed lesson write | Current editor_pick_subject delegates to the same _write_lesson. The recovered_picked_subject control deletes the actual lesson before parent retention and checks state, acknowledgment, retained dictionary and exact remaining count. |
| Split/merge loses its class or slot | Current parity write runs inside gone.committed. Recovered_parity_press controls delete the real already-read survivor before SQLite parent retention, then compare all remaining row IDs/parities/names from a fresh session; no new split half survives and the merge sibling remains, with no audit/redraw. Existing parent-loss tests remain separate evidence. |
| Canteen loses its bells | Existing canteen_changes_refuse_a_parent_deleted_after_bells_were_read matrix covers class/bells and setting/clearing. |
| Diary session is signed out while selecting a pupil | A genuine missing guard, tracked as #725. Recovered real-deletion regression fails before the fix and passes with rollback and the existing signed-out answer. |

The current tests, rather than the obsolete historical function names, hold the
retained behavior. No fresh live diary or Telegram proof follows from these
synthetic upstream/bot tests. The original commits and test source remain
recoverable even where newer shared rules supersede their implementation.

## Dependency heads, a separate integration batch

The eight original PR heads are folded into the dependency batch as merge
parents: wrapper713, pyvoy712, AGP711, network668, DataStore667,
pydantic-settings666, FastAPI665 and Sentry664. Requirement mirrors and the
Linux/universal locks are reconciled before release; Windows/Linux loop markers
are held by the existing host-image tests. Real host checks also exposed728,
whose pyvoy receive/disconnect correction is covered before shipping the bumps.

Their final GitHub dispositions and exact production/ref-retirement readback are
recorded on #724 and the linked integration/promotion PRs. Retire a ref only when
its exact current tip is in verified production and in a restorable all-ref and
reflog bundle. An advanced or unknown tip requires analysis; a branch count is
never a reason to discard it. Keep local WIP/index/files, detaching an old
worktree only at its same retained commit. Expected-tip local transactions and
the atomic remote transaction exclude master/dev and refuse stale values.

Future version bumps use one weekly multi-ecosystem cohort targeting dev/M7 with
the work prefix. GitHub security updates retain their separate default-branch
queue; they are not disabled to enforce a permanent global branch-count cap.
Idle development has two persistent refs; an actual temporary working branch is
the third and is deleted after its checked merge into dev.

## Final branch contract

master is the production/default branch; dev is tested integration. Work is done
on one temporary branch, reviewed and merged into dev, then that branch is
deleted. dev-to-master promotion has its own exact-head CI and production check.
Both persistent branches must survive automatic cleanup. Old worktree directories
can stay detached at their preserved commits without keeping redundant branch refs.
The final live/local ref inventory, dependency dispositions and UI coverage belong
in the release proof, not an assumption in this document.

The Android branch audit also found two embedded main refs. New guide requests
follow HEAD and this project's new translation corrections use dev as their cut
and PR base. A configured fork whose dev ref is genuinely absent resolves its own
default branch once; authorization/network failures do not trigger that fallback.
The existing guide refresh and repository URL tests hold default-branch reads;
fake HTTP-status/body tests hold fork owner/reader payloads and encoded custom refs;
no live translation PR is sent by a verification run. Old APKs keep their embedded
refs, their bundled guide fallback and the compatible server API. They require
an update to submit corrections after the rename; retaining a compatibility main
branch would contradict the owner's requested branch set.
