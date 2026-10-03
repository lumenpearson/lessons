# Server decomposition (sub-project 1) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Split `handlers/start.py`, `handlers/content.py`, the homework ticks in `handlers/tasks.py`, `render.py`, `keyboards.py`, `manage_render.py`, `manage_keyboards.py` and the corrections half of `services/diary.py` into feature modules inside the existing layers, without changing anything a user sees. Before any of that, the plan makes the path-keyed tests follow symbols and puts in the two guards #271 and #272 ask for.

**Architecture:** The new packages sit inside the existing layers, the way `handlers/manage/` already does:
- `handlers/start/` and `handlers/content/`: each `__init__` includes one router per flow, in a written-down order that reproduces today's handler order.
- One `*_keyboard.py` and one `*_render.py` per feature, beside `keyboards.py` and `render.py`.
- `manage_render/` and `manage_keyboards/`, mirroring `handlers/manage/`.
- `services/diary_corrections.py`, beside `services/diary_overrides.py`.

Code moves verbatim, and a syntax-tree comparison checks that. Importers are updated rather than served by re-exports, so a stale monkeypatch raises instead of silently missing. `render.py` is the one module that still re-exports, because it keeps re-exporting `app.wording`.

**Tech Stack:** Python 3.12, aiogram 3, FastAPI, SQLAlchemy 2, pytest, ruff, mypy
**Spec:** docs/specs/2026-10-03-one-contract-design.md (section 5)

## Global Constraints

- Nothing under `services/` may import `app.bot`, directly or through another `app/` module. `tests/test_service_layering.py` holds this.
- aiogram stays off the API's cold path. `tests/test_cold_start.py` (Task 2) holds this.
- The work is behaviour-preserving: no user-visible change and no rule change. Every move is verbatim. The only definitions allowed to differ are the ones each task names.
- Comments explain why, not what, and match the density of the code around them. A moved block keeps the comments above it.
- English in code, comments and commits. Russian only in user-facing strings, and quoted in guillemets anywhere else.
- Commit messages are English sentences that say what the change makes the project do. No Conventional Commits prefix. The body explains the reasoning and says what is left uncovered. Trailer: `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
- Gates before every commit (the `gates` skill):
  - `python -m ruff check app tests scripts migrations` → `All checks passed!`
  - `python -m mypy` → `Success: no issues found in N source files`. N is given per task.
  - the touched test files, one at a time, with `-p no:xdist`.
- Full suite (`python -m pytest -q -n auto`) only at the end of a task, and never while a Gradle build runs on this machine (faulty RAM). Check with `tasklist | grep -i java`. If Gradle is running, skip the full run there and rely on the touched files and CI.
- Environment (verified 3 October 2026):
  - `python` in every command means `/c/Users/lumen/StudioProjects/lessons/server/.venv/Scripts/python.exe`. That is the main checkout's venv; a worktree has none.
  - Run every command from the worktree's `server/` directory.
  - Use `python -m pytest`, never bare `pytest`, in a worktree. The venv's editable install maps `app` to `C:\Users\lumen\StudioProjects\lessons\server\app`. With the current directory off `sys.path`, `import app` resolves there: I checked this. Bare `pytest` would therefore test the main checkout, not your edits. The `-m` form puts `server/` first. The import that form used to hide is now refused by `tests/test_test_imports.py`.
- Issues: the PR closes them with `Closes #271`, `Closes #272` and `Closes #275`, each on its own line, plus `Refs #276` and `Refs #273`. Milestone 11.
  - `#275` (fixed in Task 2) and `#276` (left open, Task 8) were found while this plan was written and filed on 3 October 2026, before it was saved; nothing here files them again.
- Read a file before editing it. Check `git status` before touching a file you did not open, because another agent may be working in this tree.

## Review Focus

These are five ways this work can go wrong that no task's ordinary tests would catch, each with what pins it.

1. **A press or message routed to a different handler after a router split.**
   - `test_of_two_handlers_for_one_update_the_right_one_is_asked_first` (Task 3, `tests/test_bot_commands.py`) pins the three pairs that only registration order tells apart:
     - `/start link_…` before `/start`;
     - a shared contact before the wizard's three text steps;
     - the wizard's `TimezonePick` before the class card's.
   - Tasks 7, 8 and 9 diff the dispatcher's full handler order (all 177 handlers) before and after, against an expected diff written into each task.
   - Task 8 adds `test_a_tick_still_reaches_the_homework_flow`, which goes through the real dispatcher.
   - Task 9 swaps two sub-routers on purpose to show the pin fails.
2. **A monkeypatch that silently stops applying.**
   - No package `__init__` re-exports a name a test patches (`_today`, `datetime`, `_save_override`). A patch left on the package therefore raises `AttributeError`, because monkeypatch uses `raising=True` by default. It cannot quietly patch a name nothing reads.
   - Each retargeted patch names the module whose function looks the name up at call time.
   - Tasks 7 and 9 each put one patch back on the package on purpose and watch it fail.
3. **A `CallbackData` class dropping out of the prefix-collision check.**
   - Task 1 makes the census walk `app.bot` and adds a floor of thirty payloads plus the three missed prefixes.
   - Tasks 5 and 11, which create new payload modules, re-run it and must still find exactly the same 30 prefixes.
4. **aiogram pulled onto the API's cold path by an `__init__` or a top-level import.**
   - `tests/test_cold_start.py` (Task 2) imports `app.main` in a fresh interpreter in both deployment configurations and names the `app` module that holds anything of aiogram's.
   - It runs in every task's full suite and explicitly after Task 4, the services move.
5. **A document naming a file that no longer exists.**
   - Each task edits the documents its move makes wrong.
   - Task 12 runs a `git grep` for the old paths and expects no output.

Also checked in every move task: that moved code was not edited along the way. `/tmp/decompose/moved.py` (Task 0) compares every top-level definition's syntax tree, and `git diff --color-moved` shows anything that is not a move.

## What was verified while writing this plan (3 October 2026, worktree `spec-one-contract`)

- **Importing `app.main` with the suite's settings leaves aiogram unloaded.** That is `BOT_TOKEN=""`, `RUN_BOT=false`; the webhook is unmounted. `aiogram in sys.modules` was `False` and no `app.bot*` module was loaded.
- **#272 is a live defect in production.** With Vercel's settings (`VERCEL=1`, `BOT_TOKEN` and `WEBHOOK_SECRET` set, which `get_settings` makes mandatory there), importing `app.main` loads **736 aiogram modules**.
  - The only holder is `app.api.telegram`, which imports aiogram at the top: lines 12–13, `from aiogram import Bot, Dispatcher` and `from aiogram.types import Update`.
  - With that module stubbed, aiogram is not loaded.
  - Measured on this machine: `app.main` takes 1.05 s without aiogram, and aiogram plus `aiogram.types` add 2.67 s.
  - So every Vercel cold start pays for aiogram today. Task 2 files this as `#275` and fixes it first.
- **The callback census:** walking `app.bot` finds 30 `CallbackData` classes with 30 distinct prefixes. The current three-module scan finds 27, and the three it misses are exactly `dry`, `ted` and `tes`.
- **The announcement walker**, resolved to objects, finds exactly the 12 entries `ANNOUNCED_HERE` lists, and all 12 are module-level functions.
- **The current dispatch order** is 177 handlers: 59 on `message` and 118 on `callback_query`.
- **aiogram's `Router._propagate_event`** (`aiogram/dispatcher/router.py`, lines 175–204) asks a router's own handlers first, in registration order, then each sub-router in include order, depth first.
- **No router in `app/bot` has router-level filters or middlewares.**
- **aiogram's `Command` accepts `/homework@`** (empty mention; `aiogram/filters/command.py`, lines 127–156). `CommandBreakoutMiddleware._COMMAND` rejects it. That gap is why Task 8 keeps the ticks' place in the order (see `#276`).
- **Collected test counts per file today:**

  | Test file | Tests |
  |---|---|
  | test_bot_manage | 180 |
  | test_bot_commands | 80 |
  | test_bot_handlers | 77 |
  | test_bot_views | 96 |
  | test_announcements | 6 |
  | test_directory | 23 |
  | test_bot_calendar | 23 |
  | test_bot_message_limits | 29 |
  | test_bot_schools | 19 |
  | test_hardening | 42 |
  | test_bot_button_style | 6 |
  | test_holidays | 18 |
  | test_diary_corrections_per_child | 15 |
  | test_corrections_per_child_revision | 8 |
  | test_diary_api | 52 |
  | test_diary_session | 54 |
  | test_service_layering | 3 |
  | test_webhook | 9 |
  | test_api_docs | 2 |
  | test_vercel_entry | 3 |
  | test_startup | 4 |
  | test_roles | 33 |

- **`app/` holds 153 `.py` files**, which is the "153 modules" mypy reports.

---

### Task 0: Tools for checking a move (not committed)

**Files:**
- Create, outside the repository: `/tmp/decompose/moved.py`, `/tmp/decompose/handler_order.py`

**Interfaces:**
- Produces two scripts used by Tasks 4–11:
  - `python /tmp/decompose/moved.py <old path> <new path>... [--allow a,b]`
  - `python /tmp/decompose/handler_order.py`

- [ ] **Step 1: Check which `app` the interpreter imports**
  ```bash
  python -c "import app; print(app.__file__)"
  ```
  Expected: a path under the worktree's `server/app/`.

- [ ] **Step 2: Write the move checker**
  ```bash
  mkdir -p /tmp/decompose
  cat > /tmp/decompose/moved.py <<'EOF'
  """Did a move change anything but the location?

  usage (from server/): python /tmp/decompose/moved.py <old path> <new path>... [--allow a,b]

  Reads <old path> as it is at HEAD and the new files as they are on disk, and
  compares every top-level definition by its syntax tree - decorators, docstring
  and body, not line numbers or comments. Prints MISSING for a definition no new
  file holds and CHANGED for one that differs from every copy; exits 1 if either
  is printed for a name not in --allow.
  """
  import ast
  import subprocess
  import sys
  from pathlib import Path


  def definitions(source):
      found = {}
      for node in ast.parse(source).body:
          if isinstance(node, ast.FunctionDef | ast.AsyncFunctionDef | ast.ClassDef):
              found[node.name] = ast.dump(node)
          elif isinstance(node, ast.Assign | ast.AnnAssign):
              targets = node.targets if isinstance(node, ast.Assign) else [node.target]
              for target in targets:
                  if isinstance(target, ast.Name):
                      found[target.id] = ast.dump(node)
      return found


  args = sys.argv[1:]
  allow = set()
  if "--allow" in args:
      at = args.index("--allow")
      allow = set(args[at + 1].split(","))
      del args[at : at + 2]
  old, *new = args
  before = definitions(
      subprocess.run(
          ["git", "show", f"HEAD:./{old}"], capture_output=True, check=True, encoding="utf-8"
      ).stdout
  )
  after = {}
  for path in new:
      for name, dump in definitions(Path(path).read_text(encoding="utf-8")).items():
          after.setdefault(name, []).append(dump)

  bad = False
  for name, dump in before.items():
      if name not in after:
          print(f"MISSING {name}")
      elif dump not in after[name]:
          print(f"CHANGED {name}")
      else:
          continue
      bad = bad or name not in allow
  sys.exit(1 if bad else 0)
  EOF
  ```

- [ ] **Step 3: Write the handler-order dumper**
  ```bash
  cat > /tmp/decompose/handler_order.py <<'EOF'
  """Print the order the bot offers each update to its handlers, one per line.

  Run from server/. The area printed is the first module under app.bot.handlers,
  so a handler that moves into a package of the same name (start.py ->
  start/menu.py) prints the same line, and a diff of two runs shows only real
  reorders and real moves between areas.
  """
  import os
  import sys

  # The venv's editable install maps `app` to the main checkout: the current
  # directory goes first, so a worktree's own code is what gets measured.
  sys.path.insert(0, os.getcwd())
  os.environ.pop("VERCEL", None)
  os.environ.update(
      DATABASE_URL="sqlite+aiosqlite:///:memory:",
      RUN_BOT="false",
      BOT_TOKEN="",
      OWNER_IDS="1000",
      TIMEZONE="Europe/Moscow",
  )

  from app.bot.handlers import build_router  # noqa: E402


  def walk(router, observer):
      for handler in router.observers[observer].handlers:
          callback = handler.callback
          area = callback.__module__.split(".")[3]
          yield f"{observer} {area}:{callback.__qualname__}"
      for child in router.sub_routers:
          yield from walk(child, observer)


  root = build_router()
  for observer in ("message", "callback_query"):
      for line in walk(root, observer):
          print(line)
  EOF
  ```

- [ ] **Step 4: Self-test both, and keep the baseline**
  ```bash
  python /tmp/decompose/moved.py app/bot/render.py app/bot/render.py; echo "exit $?"
  python /tmp/decompose/handler_order.py > /tmp/decompose/order-main.txt
  wc -l < /tmp/decompose/order-main.txt
  head -3 /tmp/decompose/order-main.txt
  ```
  Expected:
  - `exit 0` with nothing printed before it;
  - `177`;
  - then `message start:cmd_start_link`, `message start:cmd_start`, `message start:on_contact`.

---

### Task 1: Count every callback payload in the bot (#271)

**Files:**
- Modify: `tests/test_bot_manage.py`, lines 274–358. These are the «Callback prefixes» section: `test_every_button_on_the_class_menu_is_one_its_payload_class_packed` (279–307), `_unpacks` (310–315) and `test_management_callbacks_do_not_collide_with_the_everyday_ones` (318–358).
- Test: `tests/test_bot_manage.py`

**Interfaces:**
- Produces `_bot_callback_payloads()` in `tests/test_bot_manage.py`. It returns every `CallbackData` subclass defined under `app.bot`, each once. Every later task that moves a payload relies on it.

- [ ] **Step 1: Read the two existing tests** (lines 279–358) and confirm the line ranges above.

- [ ] **Step 2: Introduce the helper as today's three-module scan, and add the failing test**

  Insert this directly above `def test_every_button_on_the_class_menu_is_one_its_payload_class_packed` (line 279):
  ```python
  def _bot_callback_payloads() -> list[type]:
      from aiogram.filters.callback_data import CallbackData

      from app.bot import calendar_keyboard, keyboards, manage_keyboards

      found: list[type] = []
      for module in (keyboards, manage_keyboards, calendar_keyboard):
          found.extend(
              value
              for value in vars(module).values()
              if isinstance(value, type)
              and issubclass(value, CallbackData)
              and value is not CallbackData
              and value.__module__ == module.__name__
          )
      return found


  def test_the_prefix_census_reaches_every_keyboard_module():
      """The checks below are only as good as what they walk. These three
      prefixes are ``editor_keyboard``'s and ``diary_keyboard``'s, which the
      list of three module names never opened (#271)."""
      prefixes = {payload.__prefix__ for payload in _bot_callback_payloads()}

      assert {"ted", "tes", "dry"} <= prefixes
      # Thirty on 3 October 2026. A new payload raises it; a module the walk
      # stopped reaching would lower it, which is the failure this line is for.
      assert len(prefixes) >= 30
  ```

- [ ] **Step 3: Run it and watch it fail**
  ```bash
  python -m pytest -q -p no:xdist tests/test_bot_manage.py -k "census or payload_class or collide"
  ```
  Expected: `1 failed, 2 passed, 178 deselected`. The failure is `assert {'dry', 'ted', 'tes'} <= {...}`.

- [ ] **Step 4: Replace the helper with the walk, and fold both tests onto it**

  Replace `_bot_callback_payloads` with:
  ```python
  def _bot_callback_payloads() -> list[type]:
      """Every ``CallbackData`` class the bot defines, found by walking ``app.bot``.

      Discovered rather than listed. The two checks below used to scan three
      modules by name — ``keyboards``, ``manage_keyboards`` and
      ``calendar_keyboard`` — so ``editor_keyboard``'s two payloads and
      ``diary_keyboard``'s one were never asked whether their prefixes collided
      with anything (#271), and a payload moved into a module of its own would
      have dropped out the same way. Each class is counted once, in the module
      that defines it: ``manage_keyboards`` importing ``Menu`` from
      ``keyboards`` is one payload, not two.
      """
      import importlib
      import pkgutil

      from aiogram.filters.callback_data import CallbackData

      import app.bot

      def unreadable(name: str) -> None:
          # pkgutil's default swallows an ImportError in a subpackage, which here
          # would leave that package's every payload quietly out of the count.
          raise ImportError(f"{name} could not be imported, so its payloads would go uncounted")

      found: list[type] = []
      for info in pkgutil.walk_packages(app.bot.__path__, prefix="app.bot.", onerror=unreadable):
          module = importlib.import_module(info.name)
          found.extend(
              value
              for value in vars(module).values()
              if isinstance(value, type)
              and issubclass(value, CallbackData)
              and value is not CallbackData
              and value.__module__ == module.__name__
          )
      return found
  ```

  In `test_every_button_on_the_class_menu_is_one_its_payload_class_packed`, replace lines 286–298 with the block below. The `CallbackData` and module imports and the `payloads = [...]` comprehension go; the docstring and the loop over `menu.inline_keyboard` are unchanged.
  ```python
      from app.bot.manage_keyboards import class_menu

      payloads = _bot_callback_payloads()
      menu = class_menu(is_owner=True, many_classes=True, pending=2, diary_bound=True)
  ```

  Replace the body of `test_management_callbacks_do_not_collide_with_the_everyday_ones`, from line 319 up to and excluding the comment `# And every payload packs:` at line 346, with:
  ```python
      """A duplicate prefix would not fail a build - it would route one
      feature's button into another feature's handler. Every payload in the
      bot, not only the two families the name still mentions."""
      prefixes: dict[str, str] = {}
      for payload in _bot_callback_payloads():
          name = f"{payload.__module__}.{payload.__name__}"
          assert payload.__prefix__ not in prefixes, (
              f"{name} reuses the prefix {payload.__prefix__!r} of {prefixes[payload.__prefix__]}"
          )
          prefixes[payload.__prefix__] = name
  ```
  Lines 346–358 (the "every payload packs" half) stay as they are.

- [ ] **Step 5: Run the three, then the file**
  ```bash
  python -m pytest -q -p no:xdist tests/test_bot_manage.py -k "census or payload_class or collide"
  python -m pytest -q -p no:xdist tests/test_bot_manage.py
  ```
  Expected: `3 passed, 178 deselected`, then `181 passed`.

- [ ] **Step 6: Gates.** `python -m ruff check app tests scripts migrations` → `All checks passed!`; `python -m mypy` → `Success: no issues found in 153 source files`. Then the full suite, under the Gradle rule above.

- [ ] **Step 7: Commit**
  ```
  Count every callback payload in the bot when checking for a shared prefix

  The check that no two payloads share a prefix, and the one that every button
  on «⚙️ Класс» unpacks into some payload, both scanned three modules by name:
  `keyboards`, `manage_keyboards` and `calendar_keyboard`. `editor_keyboard`'s
  `EditorAction` («ted») and `EditorSubject` («tes») and `diary_keyboard`'s
  `DiaryAction` («dry») were never asked (#271), and the splits that follow move
  payloads into modules of their own, which a list of names would drop the same
  way.

  Both checks now take their payloads from one walk of the `app.bot` package:
  every module is imported, every `CallbackData` subclass is counted once, in
  the module that defines it, and a subpackage that fails to import fails the
  walk instead of shrinking it. A new test holds the walk itself — the three
  prefixes it used to miss, and a floor of thirty payloads, so a module the walk
  stops reaching shows as a failure rather than as a shorter list.

  Not covered: a payload written by hand rather than packed — «🔁 Сменить код»
  on the class menu is still the literal "cls:rotate_code:" — is checked only
  by the class-menu test, which unpacks every button that menu draws.

  Refs #271

  Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
  ```

---

### Task 2: Keep aiogram off the API's cold start, and fail when it returns (#272, #275)

**Files:**
- Create: `tests/test_cold_start.py`
- Modify:
  - `app/api/telegram.py`, lines 10–13 (imports) and 60–72 (`handle_update`);
  - `app/main.py`, lines 172–179 (the comment above the webhook import).
- Test: `tests/test_cold_start.py`, `tests/test_webhook.py`, `tests/test_api_docs.py`, `tests/test_vercel_entry.py`, `tests/test_startup.py`

**Interfaces:**
- Produces `tests/test_cold_start.py`, which every later task's full run includes.
- Produces an `app/api/telegram.py` that imports no aiogram at module level.

- [ ] **Step 1: Read #275 before fixing it.** The defect is already filed (3 October 2026, milestone 11, `type:bug`, `area:server`, `status:now`) with the measurement that confirmed it: 736 aiogram modules and 3.07 s for `import app.main` under Vercel's settings. This task fixes it and #272's guard holds it.

- [ ] **Step 2: Write the guard**

  Create `tests/test_cold_start.py`:
  ```python
  """The API's cold start does not import aiogram (#272).

  A serverless deployment imports `app.main` on every cold start, before the
  first response, whatever the request. aiogram costs about two and a half
  seconds of that (`app/di.py` measured it), so `app/main.py`, `app/di.py`,
  `app/api/cron.py` and `app/api/telegram.py` each keep the bot's import out of
  their top level. Nothing held that: one top-level `from app.bot…` in an
  endpoint, or a service reaching the bot through a neutral module, would put
  the seconds back, and every other test would still pass, because this suite's
  own process imports aiogram for the bot tests anyway.

  So it is asked of a fresh interpreter — the only place the answer means
  anything — in both configurations a deployment can be in: the webhook
  unmounted, as this suite and a local `RUN_BOT=false` run; and mounted, as
  every Vercel deployment is, because `get_settings` refuses to start one
  without `BOT_TOKEN` and `WEBHOOK_SECRET`. A failure names the `app` modules
  holding something of aiogram's, which is where to look.
  """

  from __future__ import annotations

  import json
  import os
  import subprocess
  import sys
  from pathlib import Path

  import pytest

  SERVER = Path(__file__).resolve().parents[1]

  #: Imports one module and prints, as JSON, every aiogram module left loaded
  #: and every `app` module holding an aiogram object or module.
  _PROBE = """
  import json, sys, types
  import MODULE


  def from_aiogram(value):
      try:
          name = value.__name__ if isinstance(value, types.ModuleType) else value.__module__
      except Exception:
          return False
      return isinstance(name, str) and name.partition(".")[0] == "aiogram"


  loaded = sorted(name for name in sys.modules if name.partition(".")[0] == "aiogram")
  holders = sorted(
      name
      for name, module in list(sys.modules.items())
      if module is not None
      and name.partition(".")[0] == "app"
      and any(from_aiogram(value) for value in vars(module).values())
  )
  print(json.dumps({"aiogram": loaded, "holders": holders}))
  """

  #: The suite's own settings (`conftest.py`): no token, so no webhook and no bot.
  _LOCAL = {"BOT_TOKEN": "", "WEBHOOK_SECRET": "", "RUN_BOT": "false"}

  #: What a Vercel deployment is started with — the set `tests/test_api_docs.py`
  #: uses, written out again because a test module may not import another
  #: (`test_test_imports.py`). Nothing connects: the engine is lazy.
  _VERCEL = {
      "VERCEL": "1",
      "DATABASE_URL": "postgresql+asyncpg://user:secret@db.invalid:5432/lessons",
      "BOT_TOKEN": "123456:not-a-real-token",
      "WEBHOOK_SECRET": "not-a-real-secret",
      "RUN_BOT": "false",
      "OWNER_IDS": "1000",
      "TIMEZONE": "Europe/Moscow",
  }


  def _import_in_a_fresh_interpreter(module: str, settings: dict[str, str]) -> dict[str, list[str]]:
      # `VERCEL` dropped first, so the local case is local even in a shell that
      # happens to carry it; the Vercel case sets it again.
      env = {name: value for name, value in os.environ.items() if name != "VERCEL"}
      result = subprocess.run(
          [sys.executable, "-c", _PROBE.replace("MODULE", module)],
          cwd=str(SERVER),
          env={**env, **settings},
          capture_output=True,
          text=True,
          timeout=120,
      )
      assert result.returncode == 0, result.stderr
      return json.loads(result.stdout.strip().splitlines()[-1])


  @pytest.mark.parametrize("settings", [_LOCAL, _VERCEL], ids=["webhook-unmounted", "vercel"])
  def test_importing_the_api_leaves_aiogram_out(settings):
      found = _import_in_a_fresh_interpreter("app.main", settings)

      assert found["aiogram"] == [], (
          f"importing app.main loaded {len(found['aiogram'])} aiogram modules; "
          f"held by {found['holders']}"
      )


  def test_the_probe_sees_aiogram_where_it_is():
      """Held here rather than trusted: a probe that could not see aiogram would
      pass the test above for ever. `app.bot.keyboards` imports it at the top."""
      found = _import_in_a_fresh_interpreter("app.bot.keyboards", _LOCAL)

      assert "aiogram" in found["aiogram"]
      assert "app.bot.keyboards" in found["holders"]
  ```

- [ ] **Step 3: Run it; the Vercel case fails today**
  ```bash
  python -m pytest -q -p no:xdist tests/test_cold_start.py
  ```
  Expected: `1 failed, 2 passed`. The failure reads `importing app.main loaded 736 aiogram modules; held by ['app.api.telegram']`. The count can differ with another aiogram version; this venv has 3.31.0.

- [ ] **Step 4: Fix `app/api/telegram.py`**

  Replace lines 10–16 (from `import logging` through `from app.config import get_settings`) with:
  ```python
  import logging
  from typing import TYPE_CHECKING

  from fastapi import APIRouter, Header, HTTPException, Request, status

  from app.config import get_settings

  if TYPE_CHECKING:
      # Names for the annotations below, and nothing more. `app.main` imports
      # this module on every cold start of a deployment that mounts the
      # webhook — every Vercel one — and aiogram is seconds of import for a
      # request that is usually not an update, so it is imported where an
      # update is handled.
      from aiogram import Bot, Dispatcher
  ```

  In `handle_update` (lines 60–72), between `authorise(secret_header)` and `bot, dispatcher = _instances()`, insert:
  ```python
      # Here, after the secret, rather than at the top: see the imports.
      from aiogram.types import Update

  ```
  The module-level annotations `_bot: Bot | None` and `_dispatcher: Dispatcher | None` (lines 24–25) and `-> tuple[Bot, Dispatcher]` stay as they are. Under `from __future__ import annotations` they are strings and never evaluated. This relies on PEP 563 and was not run here; Step 5 checks it with mypy and the webhook tests.

- [ ] **Step 5: Run the guard and everything that touches the webhook**
  ```bash
  python -m pytest -q -p no:xdist tests/test_cold_start.py
  python -m pytest -q -p no:xdist tests/test_webhook.py
  python -m pytest -q -p no:xdist tests/test_api_docs.py
  python -m pytest -q -p no:xdist tests/test_vercel_entry.py
  python -m pytest -q -p no:xdist tests/test_startup.py
  ```
  Expected: `3 passed`, `9 passed`, `2 passed`, `3 passed`, `4 passed`.

- [ ] **Step 6: Prove the guard bites**
  - Temporarily add `import aiogram  # noqa: F401` as the first line after `from __future__ import annotations` in `app/api/cron.py`.
  - Run `python -m pytest -q -p no:xdist tests/test_cold_start.py`. Expected: `2 failed, 1 passed`, both failures naming `held by ['app.api.cron']`.
  - Revert with `git checkout -- app/api/cron.py` and re-run. Expected: `3 passed`.

- [ ] **Step 7: Correct the comment in `app/main.py`**

  Lines 172–179 put four seconds of aiogram on importing the webhook module, which is no longer true. Replace them with:
  ```python
  # Imported here, where it is mounted. The module itself costs nothing to
  # import: it keeps aiogram out of its top level and builds the dispatcher on
  # the first update, so a cold start that serves the phone pays nothing for the
  # bot even where the webhook is mounted — which on Vercel is every deployment.
  # `tests/test_cold_start.py` holds that in both configurations.
  ```
  Lines 169–171 stay: "Only mounted when a webhook secret is configured…" and the `#` line.

- [ ] **Step 8: Gates.** ruff → `All checks passed!`; mypy → `Success: no issues found in 153 source files`; then the full suite.

- [ ] **Step 9: Commit**
  ```
  Keep aiogram out of the API's cold start, and fail the day it comes back

  Nothing checked that importing `app.main` leaves aiogram unloaded (#272), and
  the suite could not notice on its own: its process imports aiogram for the
  bot tests anyway. `tests/test_cold_start.py` asks a fresh interpreter, in
  both configurations a deployment can be in — the webhook unmounted, as the
  suite and a local `RUN_BOT=false` run, and mounted, as every Vercel
  deployment is because `get_settings` refuses one without `BOT_TOKEN` and
  `WEBHOOK_SECRET` — and names the `app` modules holding anything of aiogram's
  when it fails. A third test proves the probe can see aiogram at all, so the
  first two cannot pass for want of looking.

  Written, the mounted case failed (#275): `app/api/telegram.py` imported
  `Bot`, `Dispatcher` and `Update` at module level, so every cold start on
  Vercel loaded 736 aiogram modules — 2.7 s on the maintainer's machine,
  against 1.05 s for the rest of `app.main` — for requests that were never
  updates. The two classes are now names for annotations only, and `Update` is
  imported in `handle_update` once the secret has been checked; the dispatcher
  was already built on the first update. The comment in `app/main.py` that put
  four seconds on importing the webhook module says what is true now.

  Not covered: what the import costs on Vercel itself — measured here, on
  Windows, not there.

  Refs #272, #275

  Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
  ```

---

### Task 3: Make the path-keyed tests follow symbols, and pin the handler pairs a split could reorder

**Files:**
- Modify:
  - `tests/test_announcements.py`: imports at lines 33–57, `ANNOUNCED_HERE` at 338–358, `_announcing_call_sites` at 361–399, the test at 402–421.
  - `tests/test_directory.py`: imports at 23–37, `_throttles_built_in_app` at 500–513, the test at 516–548.
  - `tests/test_bot_commands.py`: imports at 13–39, plus a new section at the end of the file.
  - `CLAUDE.md`, lines 647–653 («Two screens can match one press»).
- Test: the three test files.

**Interfaces:**
- Produces `ANNOUNCED_HERE` keyed by function objects. Task 7 changes only its imports.
- Produces `_FIRST_WINS` and `_offered()` in `tests/test_bot_commands.py`, with the start handlers imported from `app.bot.handlers.start`. Task 9 retargets those imports.

- [ ] **Step 1: `test_announcements.py`: key the list by the functions themselves**
  - Add `import importlib` to the stdlib imports (after `import ast`, line 33).
  - Replace lines 338–358 with:
    ```python
    #: Every place in `app/` that pushes to the class, and what bounds the part of
    #: it that a person typed.
    #:
    #: Written down with a reason each, because the reason is what a reviewer has
    #: to agree with; the list itself is derived from the source by the test below
    #: and fails naming anything that is not here. Adding a ninth announcement is
    #: then a decision — measure it, or say in one line what already bounds it.
    #: Keyed by the function rather than by its file, so that moving one to
    #: another module changes the import above and nothing here.
    ANNOUNCED_HERE: dict[object, str] = {
        edit._tell: "the wrapper the endpoints below announce through; no text of its own",
        edit.homework_put: "measured below — `HomeworkIn.text` accepts 4000",
        edit.homework_delete: "a subject name, `max_length=120`",
        edit.override_put: "subject/room/teacher 120 each and `note` 500, all schema-capped",
        edit.event_put: "`title` 200 and `location` 120, both schema-capped",
        edit.event_delete: "a stored title, `max_length=200`",
        edit.day_put: "`DayIn.note`, `max_length=500`",
        homework_text: "measured below — free text, only `shorten`",
        override_subject: "measured below — a subject cut to 120 going in",
        event_title: "measured below — a title cut to 200 on the way in",
        override_cancel: "pressed below — a lesson number and a date",
        override_clear: "pressed below — a lesson number and a date",
    }
    ```
  - Replace `_announcing_call_sites` (lines 361–399) with:
    ```python
    def _announcing_call_sites() -> dict[object, str]:
        """Every function in `app/` that pushes to the class, directly or through a
        wrapper in its own file — as the object the module hands out, with a
        `module:name` label for the failure message.

        One file's worth of indirection, and no more: `api/edit.py` announces
        through its own ``_tell``, so a walk that only looked for
        ``notify_subscribers`` would name the wrapper and miss all seven endpoints
        behind it. Resolving names across files instead would start matching any
        function that happens to share a name with a wrapper somewhere else.
        """
        found: dict[object, str] = {}
        for path in sorted(APP_ROOT.rglob("*.py")):
            tree = ast.parse(path.read_text(encoding="utf-8"), str(path))

            calls: dict[str, set[str]] = {}
            for node in ast.walk(tree):
                if not isinstance(node, ast.FunctionDef | ast.AsyncFunctionDef):
                    continue
                named = set()
                for inner in ast.walk(node):
                    if isinstance(inner, ast.Call):
                        func = inner.func
                        named.add(
                            func.attr if isinstance(func, ast.Attribute) else getattr(func, "id", "")
                        )
                calls[node.name] = named

            announcing = {"notify_subscribers"}
            while True:
                grown = {name for name, named in calls.items() if named & announcing}
                if grown <= announcing:
                    break
                announcing |= grown

            # Its own definition is not a call site; every caller of it is.
            callers = (announcing & set(calls)) - {"notify_subscribers"}
            if not callers:
                continue
            parts = path.relative_to(APP_ROOT.parent).with_suffix("").parts
            module_name = ".".join(parts[:-1] if parts[-1] == "__init__" else parts)
            module = importlib.import_module(module_name)
            for name in callers:
                label = f"{module_name}:{name}"
                # A name the module does not hand out — a function nested in
                # another — stays a label, so it fails below naming itself.
                found[getattr(module, name, label)] = label
        return found
    ```
  - In `test_every_place_that_pushes_to_the_class_is_read_in_this_file`, replace lines 410–421 (from `real = …` to the end) with:
    ```python
        real = _announcing_call_sites()

        assert real, "found no notify_subscribers call at all — the AST walk is broken"
        unread = sorted(label for found, label in real.items() if found not in ANNOUNCED_HERE)
        assert not unread, (
            "these push to the class and nothing here measures them or says what bounds them: "
            + ", ".join(unread)
        )
        gone = sorted(
            f"{function.__module__}:{function.__name__}"
            for function in ANNOUNCED_HERE
            if function not in real
        )
        assert not gone, "ANNOUNCED_HERE names call sites that no longer exist: " + ", ".join(gone)
    ```

- [ ] **Step 2: Run it, then prove it bites**
  - `python -m pytest -q -p no:xdist tests/test_announcements.py` → `6 passed`.
  - Temporarily delete the `override_clear: …` line from `ANNOUNCED_HERE` and re-run. Expected: `1 failed, 5 passed`, naming `app.bot.handlers.content:override_clear`.
  - Restore the line and re-run → `6 passed`.

- [ ] **Step 3: `test_directory.py`: name the throttles by object**
  - Add `import importlib` after `import ast` (line 23).
  - Replace lines 500–548 (`_throttles_built_in_app` and `test_every_throttle_on_the_attempts_table_uses_one_window`) with:
    ```python
    def _throttles_built_in_app() -> list[tuple[object, ast.Call]]:
        """Every ``JoinThrottle(...)`` written anywhere under ``app/``, as the
        object it built.

        Resolved to the object rather than named by file, so that a limiter moving
        to another module — `api/public.py`'s helpers are due to move with the v2
        layer — is still the same limiter here. A call that builds no module-level
        name (one inside a function, say) stays a `path:line` label and fails
        below as one.
        """
        found: list[tuple[object, ast.Call]] = []
        for path in sorted(APP.rglob("*.py")):
            tree = ast.parse(path.read_text(encoding="utf-8"))
            named = {
                id(node.value): node.targets[0].id
                for node in tree.body
                if isinstance(node, ast.Assign)
                and len(node.targets) == 1
                and isinstance(node.targets[0], ast.Name)
                and isinstance(node.value, ast.Call)
            }
            parts = path.relative_to(APP.parent).with_suffix("").parts
            module_name = ".".join(parts[:-1] if parts[-1] == "__init__" else parts)
            for node in ast.walk(tree):
                if not isinstance(node, ast.Call):
                    continue
                name = getattr(node.func, "id", None) or getattr(node.func, "attr", None)
                if name != "JoinThrottle":
                    continue
                target = named.get(id(node))
                if target is None:
                    # as_posix: on Windows str() of a relative path has
                    # backslashes (#178).
                    found.append((f"{path.relative_to(APP).as_posix()}:{node.lineno}", node))
                else:
                    found.append((getattr(importlib.import_module(module_name), target), node))
        return found


    def test_every_throttle_on_the_attempts_table_uses_one_window():
        """Each recorded attempt prunes the **whole** table to its own limiter's
        window, and the cron sweeps it to an hour. A limiter with a longer window
        than the others would have its history silently cut to theirs; one with a
        shorter window would cut theirs. So there is one window, and it is here.

        Found by reading the source rather than by listing the known limiters, so
        a fifth cannot arrive without being asked this.
        """
        built = _throttles_built_in_app()
        live = [
            public.join_limiter,
            diary.diary_login_limiter,
            diary.diary_open_limiter,
            directory.directory_limiter,
        ]
        unknown = [limiter for limiter, _ in built if not any(limiter is known for known in live)]
        assert unknown == [], unknown
        assert len(built) == len(live)

        for limiter, call in built:
            window = next((k.value for k in call.keywords if k.arg == "window"), None)
            if window is None and len(call.args) >= 2:
                window = call.args[1]
            assert isinstance(window, ast.Constant), limiter
            assert float(window.value) == 900.0, limiter

        assert {limiter.window for limiter in live} == {900.0}
        assert cron.JOIN_ATTEMPT_TTL.total_seconds() >= 900.0
        assert directory.directory_limiter.limit == 20
    ```

- [ ] **Step 4: Run it, then prove it bites**
  - `python -m pytest -q -p no:xdist tests/test_directory.py` → `23 passed`.
  - Temporarily add `extra_limiter = JoinThrottle(limit=1, window=900.0)` below line 48 of `app/api/directory.py` and re-run. Expected: `1 failed, 22 passed`, listing the extra limiter.
  - Revert with `git checkout -- app/api/directory.py`.

- [ ] **Step 5: `test_bot_commands.py`: pin the pairs that only registration order tells apart**
  - Change line 15 to `from collections.abc import AsyncGenerator, Iterator`.
  - After line 32 (`from app.bot.handlers.unknown import …`), add:
    ```python
    from app.bot.handlers.start import (
        change_timezone_apply,
        cmd_start,
        cmd_start_link,
        create_class_letter,
        create_class_school_search,
        create_class_school_typed,
        create_class_timezone,
        on_contact,
    )
    ```
    Put it before the `unknown` line if `ruff --fix` sorts it there.
  - Append at the end of the file:
    ```python
    # --------------------------------------------------------------------------
    # Which of two handlers an update reaches
    # --------------------------------------------------------------------------


    def _offered(observer: str) -> list[Any]:
        """Every handler on ``observer``, in the order the dispatcher offers an
        update to them.

        A router asks its own handlers first, in registration order, then each
        router included under it, in include order — depth first (aiogram's
        ``Router._propagate_event``). A module split into a package of routers
        keeps every update where it went only while this order holds.
        """

        def walk(router: Any) -> Iterator[Any]:
            yield from (handler.callback for handler in router.observers[observer].handlers)
            for child in router.sub_routers:
                yield from walk(child)

        return list(walk(dispatcher()))


    #: Handlers that can both match one update, the one that must win first.
    #: Nothing but registration order tells each pair apart — the shape «Two
    #: screens can match one press» in CLAUDE.md warns against — so they are held
    #: here, where splitting their module into several routers would otherwise
    #: reorder them without a word.
    _FIRST_WINS = [
        # «/start link_…» is a «/start» too: the bare handler would open the menu
        # instead of linking the phone.
        ("message", cmd_start_link, cmd_start),
        # A shared contact while the wizard waits for text: its steps are
        # filtered by state alone and would take the contact as the answer.
        ("message", on_contact, create_class_letter),
        ("message", on_contact, create_class_school_search),
        ("message", on_contact, create_class_school_typed),
        # Both take a TimezonePick, and only the wizard's state tells them apart:
        # the class card's handler would answer the wizard's last question as a
        # change to an existing class — a refusal, for somebody who has none yet.
        ("callback_query", create_class_timezone, change_timezone_apply),
    ]


    @pytest.mark.parametrize(
        ("observer", "first", "then"),
        _FIRST_WINS,
        ids=[f"{first.__name__}-before-{then.__name__}" for _, first, then in _FIRST_WINS],
    )
    def test_of_two_handlers_for_one_update_the_right_one_is_asked_first(observer, first, then):
        offered = _offered(observer)

        assert offered.index(first) < offered.index(then), (
            f"{then.__module__}.{then.__name__} is now offered {observer} updates before "
            f"{first.__module__}.{first.__name__}"
        )
    ```

- [ ] **Step 6: Run it, then prove it bites**
  - `python -m pytest -q -p no:xdist tests/test_bot_commands.py` → `85 passed`.
  - Temporarily swap `create_class_timezone` and `change_timezone_apply` in the last tuple and re-run. Expected: `1 failed, 84 passed`.
  - Restore the tuple.

- [ ] **Step 7: Say so in `CLAUDE.md`.** At the end of the «Two screens can match one press» note (line 653, after "…holds it."), add:
  ```
  The pairs that predate the rule — `/start link_…` and `/start`, a shared
  contact and the create-a-class wizard's text steps, the two `TimezonePick`
  handlers — are held in their order by `test_bot_commands.py`, so a router
  split cannot reorder them quietly.
  ```

- [ ] **Step 8: Gates.** ruff → `All checks passed!`; mypy → `Success: no issues found in 153 source files`; then the full suite.

- [ ] **Step 9: Commit**
  ```
  Name what these tests guard by object rather than by file, and pin the handlers only their order tells apart

  `test_announcements.py` keyed the places that push to the class by
  "bot/handlers/content.py:<function>", and `test_directory.py` listed the
  files that build a `JoinThrottle`. Moving a function or a limiter to another
  module — which the server decomposition is about to do to `content.py`, and
  the v2 layer to `api/public.py`'s helpers — would have failed both for no
  defect at all. Both still find their subjects by reading the source, and now
  resolve each to the object its module hands out and compare objects: the
  announcement list is keyed by the functions themselves, and the throttle
  census by the four limiters the routes use. Something the source builds that
  no module hands out stays a label and fails as one.

  `test_bot_commands.py` gains the pairs of handlers that can both match one
  update and are told apart by registration order alone — `/start link_…` and
  `/start`, a shared contact and the wizard's text steps, the two
  `TimezonePick` handlers — read off the real dispatcher, so the split of
  `handlers/start.py` that follows cannot reorder them without failing here.

  Refs #273

  Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
  ```

---

### Task 4: Keep a family's diary corrections beside the rules for applying them

**Files:**
- Create: `app/services/diary_corrections.py`
- Modify:
  - `app/services/diary.py`: docstring lines 1–20; imports at 29, 34, 39 and 52; delete 326–540; one new import.
  - `app/api/diary.py`: line 77 (add an import) and lines 674, 798, 824, 878, 914, 932.
  - `tests/test_diary_corrections_per_child.py`, line 37.
  - `tests/test_corrections_per_child_revision.py`, lines 36 and 42.
  - Comments: `app/models.py` 825 and 999; `app/providers/netschool/mapper.py` 77; `app/providers/petersburg/mapper.py` 285; `app/schemas/diary_session.py` 52; `CLAUDE.md` 452.
- Test: `tests/test_diary_corrections_per_child.py`, `tests/test_corrections_per_child_revision.py`, `tests/test_diary_api.py`, `tests/test_diary_session.py`, `tests/test_service_layering.py`, `tests/test_cold_start.py`

**Interfaces:**
- Produces `app.services.diary_corrections` with: `SCOPE_PREFIX`, `UnknownDiaryServer`, `child_scope`, `_of_child`, `load_corrections`, `list_overrides`, `put_override`, `drop_override`, `drop_overrides`.
- `app.services.diary` imports `UnknownDiaryServer` and `child_scope` from it for `DiaryService.scope_of`. It is not a re-export for anybody else.

**Decision:** update the importers rather than re-export. There are two tests (one import line each, since they use `service.` only for moved names) and five calls in `app/api/diary.py`. No monkeypatch targets a moved name. The `sign_in`, `DiaryService` and `ADOPT_UPSTREAM_BUDGET_SECONDS` patches all target names that stay in `services/diary.py`.

- [ ] **Step 1: Create `app/services/diary_corrections.py`**
  ```python
  """Corrections laid over what came down: getting the rows in and out.

  The rules for applying them live in ``services/diary_overrides.py``, which is
  free of SQLAlchemy so that they can be tested without a database. This is the
  other half. The rows are filed under the child — the diary, and the pupil's id
  on it — rather than under the session or the login: a session ends every few
  days and a correction must not, and the login is whatever the phone typed,
  which nothing upstream vouches for (#165). So they are shared by everyone whose
  own diary lists the child. Which children a session reaches is decided in the
  routes, by `_student`: the session's own diary is asked for its pupils on
  every call, and an id it does not list is a 404 before any row here is read or
  written.

  Beside ``diary_overrides`` rather than inside ``services/diary.py``, which is
  the sessions: the two halves of one feature sit next to each other, and the
  session module no longer carries a second subject in its middle.
  """

  from __future__ import annotations

  from urllib.parse import urlsplit

  from sqlalchemy import ColumnElement, select
  from sqlalchemy.exc import IntegrityError
  from sqlalchemy.ext.asyncio import AsyncSession

  from app.models import DiaryOverride
  from app.providers.diary.registry import NETSCHOOL, PETERSBURG
  ```
  Then append, verbatim and in this order, from `app/services/diary.py`:

  | Lines | Name |
  |---|---|
  | 341–342 | `SCOPE_PREFIX`, with its comment |
  | 345–349 | `UnknownDiaryServer` |
  | 352–408 | `child_scope` |
  | 411–420 | `_of_child` |
  | 423–436 | `load_corrections` |
  | 439–448 | `list_overrides` |
  | 451–506 | `put_override` |
  | 509–524 | `drop_override` |
  | 527–539 | `drop_overrides` |

  The section comment at lines 326–339 becomes the docstring above.

- [ ] **Step 2: Shrink `app/services/diary.py`**
  - Delete lines 326–540, including the blank lines before `_tell_upstream_goodbye`.
  - Remove the now-unused imports:
    - `from urllib.parse import urlsplit` (line 29);
    - `from sqlalchemy.exc import IntegrityError` (line 34);
    - `DiaryOverride` from line 39, which becomes `from app.models import DiarySession, SchoolClass`;
    - `NETSCHOOL` from line 52, which becomes `from app.providers.diary.registry import PETERSBURG, Binding, provider_for`.
  - Add after line 54 (`from app.security import …`):
    ```python
    from app.services.diary_corrections import UnknownDiaryServer, child_scope
    ```
  - Append to the module docstring, before its closing `"""`:
    ```
    The corrections a family lays over what came down are filed per child, in
    :mod:`app.services.diary_corrections`, beside the rules for applying them in
    :mod:`app.services.diary_overrides`.
    ```
  - `adopt`'s docstring (`:func:`child_scope``, line 205) stays as it is: the name is still bound in this module.

- [ ] **Step 3: Update the importers**
  - `app/api/diary.py`: after line 77 (`from app.services import diary as service`), add `from app.services import diary_corrections`.
  - In the same file:
    - line 674: `service.load_corrections` → `diary_corrections.load_corrections`;
    - line 824: `service.list_overrides` → `diary_corrections.list_overrides`;
    - line 878: `service.put_override` → `diary_corrections.put_override`;
    - line 914: `service.drop_override` → `diary_corrections.drop_override`;
    - line 932: `service.drop_overrides` → `diary_corrections.drop_overrides`;
    - comment, line 798: `` `services/diary.child_scope` `` → `` `services/diary_corrections.child_scope` ``.
  - `tests/test_diary_corrections_per_child.py`, line 37: `from app.services import diary as service` → `from app.services import diary_corrections as service`. Every `service.` use in that file is a moved name: `child_scope` ×12, `UnknownDiaryServer`, `list_overrides` ×2, `load_corrections`, `drop_overrides`.
  - `tests/test_corrections_per_child_revision.py`, line 36: the same one-line change. Line 42's comment: `` `services/diary.child_scope` `` → `` `services/diary_corrections.child_scope` ``.

- [ ] **Step 4: Point the comments at the new home.** Replace `services/diary.py:child_scope` and `services/diary.child_scope` with `services/diary_corrections.child_scope` in:
  - `app/models.py`, lines 825 and 999;
  - `app/providers/netschool/mapper.py`, line 77;
  - `app/providers/petersburg/mapper.py`, line 285;
  - `app/schemas/diary_session.py`, line 52;
  - `CLAUDE.md`, line 452.

  Leave `migrations/versions/0017_corrections_per_child.py` (lines 14 and 132) as written: a revision's text is the record of what was applied.

- [ ] **Step 5: Check that the move is verbatim**
  ```bash
  python /tmp/decompose/moved.py app/services/diary.py app/services/diary.py app/services/diary_corrections.py; echo "exit $?"
  git add -N app/services/diary_corrections.py
  git diff HEAD --color-moved=dimmed-zebra --color-moved-ws=allow-indentation-change -- app/services/diary.py app/services/diary_corrections.py
  ```
  Expected:
  - `exit 0` with nothing printed;
  - in the diff, every moved block dimmed. The only bright lines should be the new docstring, the imports, the docstring paragraph added to `diary.py`, and the deleted section comment.

- [ ] **Step 6: Tests**
  ```bash
  python -m pytest -q -p no:xdist tests/test_diary_corrections_per_child.py
  python -m pytest -q -p no:xdist tests/test_corrections_per_child_revision.py
  python -m pytest -q -p no:xdist tests/test_diary_api.py
  python -m pytest -q -p no:xdist tests/test_diary_session.py
  python -m pytest -q -p no:xdist tests/test_service_layering.py
  python -m pytest -q -p no:xdist tests/test_cold_start.py
  ```
  Expected: `15 passed`, `8 passed`, `52 passed`, `54 passed`, `3 passed`, `3 passed`.

- [ ] **Step 7: Gates.** ruff; mypy → `Success: no issues found in 154 source files`; then the full suite.

- [ ] **Step 8: Commit**
  ```
  Keep a family's diary corrections beside the rules for applying them

  `services/diary.py` is the diary's sessions — signing in, adopting a phone's
  session, refreshing, signing out — and carried a second subject in its
  middle: the rows of corrections a family lays over what came down, filed per
  child. They now live in `services/diary_corrections.py`, next to
  `services/diary_overrides.py`, which holds the rules for applying them; the
  section's own comment, which already called itself the other half of that
  module, is the new module's docstring.

  Moved verbatim — `SCOPE_PREFIX`, `UnknownDiaryServer`, `child_scope`,
  `_of_child`, `load_corrections`, `list_overrides`, `put_override`,
  `drop_override`, `drop_overrides` — and compared by syntax tree against the
  old file: nothing missing, nothing altered. `DiaryService.scope_of` imports
  the two names it needs; nothing else is re-exported, so `api/diary.py` and
  the two tests that used them name the module they live in. The comments that
  pointed at `services/diary.child_scope` follow it, except in revision `0017`,
  whose text is the record of what was applied.

  Refs #273

  Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
  ```

---

### Task 5: Give each bot feature its own keyboard module

**Files:**
- Create: `app/bot/access_keyboard.py`, `app/bot/content_keyboard.py`, `app/bot/start_keyboard.py`, `app/bot/week_keyboard.py`, `app/bot/tasks_keyboard.py`, `app/bot/reminders_keyboard.py`
- Modify:
  - `app/bot/keyboards.py`: docstring at line 1, imports at 16–19, and the moved ranges listed below.
  - Importers in `app/`:
    - `app/bot/calendar_keyboard.py`, lines 25–26;
    - `app/bot/handlers/access.py`, 21–28;
    - `app/bot/handlers/content.py`, 26–35;
    - `app/bot/handlers/reminders.py`, 20;
    - `app/bot/handlers/start.py`, 19–36;
    - `app/bot/handlers/tasks.py`, 28–39;
    - `app/bot/handlers/week.py`, 22.
  - Importers in tests:
    - `tests/test_bot_button_style.py`, 15–20;
    - `tests/test_bot_calendar.py`, 27–28;
    - `tests/test_bot_commands.py`, 33;
    - `tests/test_bot_handlers.py`, 49–56;
    - `tests/test_bot_views.py`, 49.
  - `docs/bot.md`, line 11.
- Test: `test_bot_manage`, `test_bot_button_style`, `test_bot_calendar`, `test_bot_commands`, `test_bot_handlers`, `test_bot_views`, `test_bot_schools`, `test_hardening`, `test_bot_message_limits`

**Interfaces:**
- Produces:
  - `app.bot.access_keyboard`: `AccessAction`, `RolePick`, `role_picker`.
  - `app.bot.content_keyboard`: `HomeworkAction`, `OverrideAction`, `EventAction`, `HomeworkTick`.
  - `app.bot.start_keyboard`: `TimezonePick`, `GradePick`, `SchoolPick`, `grade_picker`, `school_picker`, `school_fallback`, `timezone_picker`.
  - `app.bot.week_keyboard`: `WeekNav`, `week_nav`, `next_keyboard`.
  - `app.bot.tasks_keyboard`: `TaskAction`, `task_list_keyboard`, `task_delete_picker`, `task_delete_confirm`, `task_remind_keyboard`.
  - `app.bot.reminders_keyboard`: `ReminderAction`, `reminder_keyboard`.
- `app.bot.keyboards` keeps: `WEEKDAY_NAMES`, `WEEKDAY_FULL`, `Menu`, `DayNav`, `shift_days`, `shift_weeks`, `TimetableAction`, `DiarySchoolPick` (until Task 11), `ClassAction`, `main_menu`, `back_to_menu`, `day_nav`, `request_contact`, `cancel_keyboard`, `weekday_picker`, `cut`.
  - Shared names stay here because the feature modules import `Menu` and `cut` from `keyboards`. `DayNav` must stay because `main_menu` packs it: moving it would make `keyboards` and its feature module import each other.

**Decision:** update the importers rather than re-export. There are seven app modules and five tests, all with named imports.

- [ ] **Step 1: Create the six modules.** Each starts with its docstring and `from __future__ import annotations`. Then come the imports shown, then the moved blocks from `keyboards.py`, verbatim, in source order.

  `access_keyboard.py`:
  ```python
  """Payloads and the role picker of «👥 Доступ» (``handlers/access``).

  Both role pickers send a ``RolePick``, and only ``target`` tells them apart —
  see «Two screens can match one press» in CLAUDE.md.
  """

  from __future__ import annotations

  from aiogram.filters.callback_data import CallbackData
  from aiogram.types import InlineKeyboardButton, InlineKeyboardMarkup

  from app.bot.button_style import DANGER
  from app.bot.keyboards import Menu
  from app.models import Role
  ```
  Moves: 75–77 `AccessAction`, 80–82 `RolePick`, 293–309 `role_picker`.

  `content_keyboard.py`:
  ```python
  """Callback payloads of the day-to-day editing flows (``handlers/content``) and
  of the «сделал» ticks.

  Payloads only: the flows build their buttons inline, one question at a time,
  and the month grid they share is ``calendar_keyboard``'s, which packs these
  same classes for its «на какой день?».
  """

  from __future__ import annotations

  from aiogram.filters.callback_data import CallbackData
  ```
  Moves: 70–72 `HomeworkAction`, 90–92 `OverrideAction`, 95–97 `EventAction`, 147–149 `HomeworkTick`.

  `start_keyboard.py`:
  ```python
  """Buttons for creating a class — grade, school, time zone — whose time-zone
  picker «🕒 Часовой пояс» on an existing class reuses (``handlers/start``)."""

  from __future__ import annotations

  from aiogram.filters.callback_data import CallbackData
  from aiogram.types import InlineKeyboardButton, InlineKeyboardMarkup

  from app.providers.dadata.models import SchoolPage
  ```
  Moves: 100–101 `TimezonePick`, 104–105 `GradePick`, 108–113 `SchoolPick`, 330–349 `grade_picker`, 352–412 `school_picker`, 415–435 `school_fallback`, 438–455 `timezone_picker`.

  `week_keyboard.py`:
  ```python
  """Buttons under «🗓 Неделя» and «⏭ Что дальше» (``handlers/week``)."""

  from __future__ import annotations

  from aiogram.filters.callback_data import CallbackData
  from aiogram.types import InlineKeyboardButton, InlineKeyboardMarkup

  from app.bot.button_style import SUCCESS
  from app.bot.keyboards import Menu
  ```
  Moves: 135–136 `WeekNav`, 463–481 `week_nav`, 484–490 `next_keyboard`.

  `tasks_keyboard.py`:
  ```python
  """Buttons for «✅ Мои задачи» (``handlers/tasks``).

  ``TASK_BUTTONS_MAX`` is the renderer's, not this module's: what is drawn and
  what can be pressed read one number.
  """

  from __future__ import annotations

  from aiogram.filters.callback_data import CallbackData
  from aiogram.types import InlineKeyboardButton, InlineKeyboardMarkup

  from app.bot.button_style import DANGER, PRIMARY, SUCCESS
  from app.bot.keyboards import Menu, cut
  from app.bot.render import TASK_BUTTONS_MAX
  ```
  Moves: 139–144 `TaskAction`, 499–550 `task_list_keyboard`, 553–574 `task_delete_picker`, 577–599 `task_delete_confirm`, 602–644 `task_remind_keyboard`.

  `reminders_keyboard.py`:
  ```python
  """Buttons for «🔔 Напоминания» (``handlers/reminders``)."""

  from __future__ import annotations

  from aiogram.filters.callback_data import CallbackData
  from aiogram.types import InlineKeyboardButton, InlineKeyboardMarkup

  from app.bot.button_style import DANGER
  from app.bot.keyboards import Menu
  ```
  Moves: 152–154 `ReminderAction`, 647–694 `reminder_keyboard`.

- [ ] **Step 2: Shrink `keyboards.py`**
  - Delete every moved range above and the section header at 458–460.
  - Delete `from app.bot.render import TASK_BUTTONS_MAX` (line 17) and `from app.providers.dadata.models import SchoolPage` (line 19).
  - Replace the docstring at line 1 with:
    ```python
    """The bot's shared vocabulary: the main menu and its payloads, the day pager,
    the payloads two features both pack, and the small keyboards every screen
    ends with.

    A feature's own payloads and buttons live beside this file, one module each
    — ``start_keyboard``, ``content_keyboard``, ``tasks_keyboard``,
    ``week_keyboard``, ``reminders_keyboard``, ``access_keyboard`` — as
    ``editor_keyboard``, ``calendar_keyboard`` and ``diary_keyboard`` already
    did. They import ``Menu`` and ``cut`` from here, never the other way round,
    which is why ``DayNav`` stays: ``main_menu`` packs it.
    """
    ```

- [ ] **Step 3: Update the importers**

  `app/bot/calendar_keyboard.py`, lines 25–26:
  ```python
  from app.bot.content_keyboard import EventAction, HomeworkAction
  from app.bot.content_keyboard import OverrideAction as OverrideCB
  from app.bot.keyboards import Menu, back_to_menu
  ```

  `app/bot/handlers/access.py`, lines 21–28:
  ```python
  from app.bot.access_keyboard import AccessAction, RolePick, role_picker
  from app.bot.keyboards import Menu, back_to_menu, cancel_keyboard
  ```

  `app/bot/handlers/content.py`, lines 26–35:
  ```python
  from app.bot.content_keyboard import EventAction, HomeworkAction
  from app.bot.content_keyboard import OverrideAction as OverrideCB
  from app.bot.keyboards import Menu, back_to_menu, cancel_keyboard
  ```

  `app/bot/handlers/reminders.py`, line 20:
  ```python
  from app.bot.keyboards import Menu, cancel_keyboard
  from app.bot.reminders_keyboard import ReminderAction, reminder_keyboard
  ```

  `app/bot/handlers/start.py`, lines 19–36:
  ```python
  from app.bot.keyboards import (
      ClassAction,
      DayNav,
      Menu,
      back_to_menu,
      cancel_keyboard,
      day_nav,
      main_menu,
      request_contact,
      shift_days,
  )
  from app.bot.start_keyboard import (
      GradePick,
      SchoolPick,
      TimezonePick,
      grade_picker,
      school_fallback,
      school_picker,
      timezone_picker,
  )
  ```

  `app/bot/handlers/tasks.py`, lines 28–39. `cut` stays from `keyboards`: it is the button-label `cut`, not `app.wording.cut`.
  ```python
  from app.bot.content_keyboard import HomeworkAction, HomeworkTick
  from app.bot.keyboards import Menu, cancel_keyboard, cut
  from app.bot.tasks_keyboard import (
      TaskAction,
      task_delete_confirm,
      task_delete_picker,
      task_list_keyboard,
      task_remind_keyboard,
  )
  ```

  `app/bot/handlers/week.py`, line 22:
  ```python
  from app.bot.keyboards import Menu, shift_weeks
  from app.bot.week_keyboard import WeekNav, next_keyboard, week_nav
  ```

  Tests:
  - `tests/test_bot_button_style.py`, lines 15–20 →
    ```python
    from app.bot.keyboards import main_menu
    from app.bot.reminders_keyboard import reminder_keyboard
    from app.bot.tasks_keyboard import task_delete_confirm, task_list_keyboard
    ```
  - `tests/test_bot_calendar.py`, lines 27–28 →
    ```python
    from app.bot.content_keyboard import EventAction, HomeworkAction
    from app.bot.content_keyboard import OverrideAction as OverrideCB
    ```
  - `tests/test_bot_commands.py`, line 33 → `from app.bot.week_keyboard import WeekNav`
  - `tests/test_bot_handlers.py`, lines 49–56 →
    ```python
    from app.bot.access_keyboard import AccessAction, RolePick
    from app.bot.content_keyboard import EventAction, HomeworkAction
    from app.bot.keyboards import shift_days, shift_weeks
    ```
  - `tests/test_bot_views.py`, line 49 →
    ```python
    from app.bot.keyboards import main_menu
    from app.bot.tasks_keyboard import task_list_keyboard
    ```

- [ ] **Step 4: `docs/bot.md`, line 11.** Replace "the buttons in `keyboards.py`, `manage_keyboards.py` and `calendar_keyboard.py`." with:
  ```
  the buttons in `keyboards.py` (the menu, and what every screen ends with), one
  `*_keyboard.py` per feature beside it, and `manage_keyboards.py`.
  ```

- [ ] **Step 5: Format, then check that the move is verbatim**
  ```bash
  python -m ruff check --fix app/bot/keyboards.py app/bot/*_keyboard.py app/bot/handlers tests
  python /tmp/decompose/moved.py app/bot/keyboards.py app/bot/keyboards.py app/bot/access_keyboard.py app/bot/content_keyboard.py app/bot/start_keyboard.py app/bot/week_keyboard.py app/bot/tasks_keyboard.py app/bot/reminders_keyboard.py; echo "exit $?"
  ```
  Expected: `exit 0` with nothing printed.

- [ ] **Step 6: Tests, including the census**
  ```bash
  for f in test_bot_manage test_bot_button_style test_bot_calendar test_bot_commands test_bot_handlers test_bot_views test_bot_schools test_hardening test_bot_message_limits; do python -m pytest -q -p no:xdist tests/$f.py; done
  ```
  Expected, in order: `181 passed`, `6 passed`, `23 passed`, `85 passed`, `77 passed`, `96 passed`, `19 passed`, `42 passed`, `29 passed`.

  The census must still count exactly 30 distinct prefixes:
  ```bash
  python -c "import os; os.environ.update(DATABASE_URL='sqlite+aiosqlite:///:memory:', RUN_BOT='false', BOT_TOKEN='', OWNER_IDS='1000', TIMEZONE='Europe/Moscow'); import importlib, pkgutil, app.bot; from aiogram.filters.callback_data import CallbackData; found=[v for i in pkgutil.walk_packages(app.bot.__path__, 'app.bot.') for m in [importlib.import_module(i.name)] for v in vars(m).values() if isinstance(v, type) and issubclass(v, CallbackData) and v is not CallbackData and v.__module__ == m.__name__]; print(len(found), len({c.__prefix__ for c in found}))"
  ```
  Expected: `30 30`.

- [ ] **Step 7: Gates.** ruff; mypy → `Success: no issues found in 160 source files`; then the full suite.

- [ ] **Step 8: Commit**
  ```
  Give each bot feature its own keyboard module, as the editor, the calendar and the diary already have

  `keyboards.py` held the payloads and buttons of six features beside the main
  menu. Each feature's now sits in a module of its own beside it —
  `start_keyboard`, `content_keyboard`, `tasks_keyboard`, `week_keyboard`,
  `reminders_keyboard`, `access_keyboard` — and `keyboards.py` keeps what
  more than one of them needs: the menu and its payloads, the day pager,
  `ClassAction`, `TimetableAction`, `cancel_keyboard`, `back_to_menu`,
  `weekday_picker` and the button-label `cut`. The feature modules import from
  it and never the reverse, which is why `DayNav` stays: `main_menu` packs it.

  Every definition moved verbatim (compared by syntax tree against the old
  file), every importer names the new module, and nothing is re-exported. No
  prefix changed; the census walks the package now, so the six new modules are
  in it without being named, and it still counts thirty payloads.

  `DiarySchoolPick` stays here until the management keyboards are split by
  screen, where it belongs.

  Refs #273

  Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
  ```

---

### Task 6: Give each bot screen its own renderer, and keep `render` the one door to `app.wording`

**Files:**
- Create: `app/bot/access_render.py`, `app/bot/week_render.py`, `app/bot/homework_render.py`, `app/bot/tasks_render.py`, `app/bot/reminders_render.py`
- Modify:
  - `app/bot/render.py`: rewritten to its docstring, the re-exports, `render_role_help` (101–110) and `silenced_lessons` (130–149).
  - Importers in `app/`:
    - `app/bot/handlers/access.py`, 31–38;
    - `app/bot/handlers/reminders.py`, 21;
    - `app/bot/handlers/tasks.py`, 40–47, and 458 (docstring);
    - `app/bot/handlers/week.py`, 23;
    - `app/bot/tasks_keyboard.py` (its `TASK_BUTTONS_MAX` import).
  - Importers in tests:
    - `tests/test_bot_views.py`, 50–67;
    - `tests/test_bot_message_limits.py`, lines 32, 133, 152, 153, 154, 173, 376, 403 and 619.
  - Docs: `CLAUDE.md` 617–618, `.claude/skills/bot-message/SKILL.md` 36–37, `docs/bot.md` 10.
- Test: `test_bot_views`, `test_bot_message_limits`, `test_service_layering`, `test_bot_handlers`, `test_bot_manage`, `test_bot_button_style`

**Interfaces:**
- Produces:
  - `app.bot.access_render`: `ACCESS_MEMBERS_MAX`, `ACCESS_INVITES_MAX`, `ACCESS_LABEL_MAX`, `ACCESS_REQUEST_NOTE_MAX`, `render_access_list`.
  - `app.bot.week_render`: `WEEK_TEXT_LIMIT`, `INVISIBLE`, `duration`, `render_week`, `render_next`, and private helpers.
  - `app.bot.homework_render`: `HOMEWORK_ITEMS_MAX`, `HOMEWORK_TEXT_MAX`, `HOMEWORK_DIGEST_LIMIT`, `render_homework_digest`, `homework_digest_keys`.
  - `app.bot.tasks_render`: `PRIORITY_ICONS`, `TASK_LINES_MAX`, `TASK_BUTTONS_MAX`, `TASKS_UNREACHABLE`, `TASK_TITLE_MAX`, `TASK_GROUPS`, `task_group`, `task_line`, `render_task_list`, `render_task_saved`.
  - `app.bot.reminders_render`: `render_reminder_card`.
- `app.bot.render` keeps `render_role_help`, `silenced_lessons`, and all fifteen `app.wording` re-exports that `test_service_layering.py:183–200` asserts.

**Decision:** update the importers. There are five app modules and two tests, plus about nine `render.X` attribute uses in `test_bot_message_limits.py`. The only re-exports kept are the wording ones, which the spec and the layering test require.

- [ ] **Step 1: Create the five modules.** Each starts with its docstring and `from __future__ import annotations`. They import from `app.wording` directly, where these names live, as `render.py` itself did.

  `access_render.py`:
  ```python
  """The member list on «👥 Доступ», and the caps ``handlers/access`` reads with it."""

  from __future__ import annotations

  from html import escape

  from app.wording import clamp, cut, more_line
  ```
  Moves: 43–65 (the four `ACCESS_*` constants with their comments) and 68–98 `render_access_list`.

  `week_render.py`:
  ```python
  """«🗓 Неделя» and «⏭ Что дальше»: a week in one message, and where the class
  is right now (``handlers/week``)."""

  from __future__ import annotations

  import math
  from datetime import date as Date
  from datetime import datetime
  from datetime import time as Time
  from html import escape

  from app.models import WeekParity
  from app.schedule import ResolvedDay, ResolvedLesson, week_parity
  from app.wording import DAY_KIND_LABELS, EVENT_ICONS, MONTHS_GENITIVE, WEEKDAYS_SHORT, clamp, plural
  ```
  Moves: 119–122 `WEEK_TEXT_LIMIT`, 124–127 `INVISIBLE`, 152–160 `duration`, 163–171 `_minutes_until`, 174–264 («Week view» header, `_week_lesson_line`, `_render_week_at`, `render_week`), 267–378 («Что дальше» header, `_lesson_ref`, `_next_day_line`, `_weekday_accusative`, `render_next`).

  `homework_render.py`:
  ```python
  """The homework digest, and the rows its «сделал» buttons are built from."""

  from __future__ import annotations

  from datetime import date as Date
  from html import escape

  from app.schedule import ResolvedDay
  from app.wording import human_date, plural
  ```
  Moves: 386–490 (`HOMEWORK_ITEMS_MAX`, `HOMEWORK_TEXT_MAX`, `HOMEWORK_DIGEST_LIMIT`, `_homework_digest`, `render_homework_digest`, `homework_digest_keys`). The section header at 381–383 is dropped, since the module name says it. In the comment above `HOMEWORK_ITEMS_MAX`, line 389 "``handlers/tasks`` reads" stays true until Task 8.

  `tasks_render.py`:
  ```python
  """«✅ Мои задачи»: one person's list, and what a saved task says back."""

  from __future__ import annotations

  from datetime import date as Date
  from html import escape

  from app.models import PersonalTask
  from app.wording import clamp, cut, human_date
  ```
  Moves: 117 `PRIORITY_ICONS`, 497–616 (`TASK_LINES_MAX`, `TASK_BUTTONS_MAX`, `TASKS_UNREACHABLE`, `TASK_TITLE_MAX`, `TASK_GROUPS`, `task_group`, `task_line`, `render_task_list`, `render_task_saved`). In the comment above `TASK_BUTTONS_MAX` (line 507), "Declared here rather than in `keyboards`" → "Declared here rather than in `tasks_keyboard`".

  `reminders_render.py`:
  ```python
  """The «🔔 Напоминания» card (``handlers/reminders``)."""

  from __future__ import annotations

  from datetime import time as Time

  from app.models import ReminderSettings
  ```
  Moves: 624–643 `render_reminder_card`.

- [ ] **Step 2: Rewrite `render.py`** to exactly this, then append `render_role_help` (lines 101–110) and `silenced_lessons` (lines 130–149) verbatim:
  ```python
  """What the bot says about the class rather than on one screen, and the door to
  the wording both shells share.

  Each screen's renderer lives beside this file — ``week_render``,
  ``homework_render``, ``tasks_render``, ``access_render``, ``reminders_render``,
  as ``diary_render`` and ``editor_render`` already did — kept apart from the
  handlers so the wording can be changed without touching any control flow.
  What is left here is the sentence under the menu that says what a role may do,
  and the warning both bells editors print.

  The calendar names, the message budget, ``plural`` and the day card are not
  here: the digests in ``services/reminders`` and the paste grammar in
  ``services/timetable_io`` need them too, and a service may not import the bot
  (#205), so they live in :mod:`app.wording`. Every one of them is imported back
  below, so ``from app.bot.render import …`` in a handler reads the same object it
  always did. Nothing left in this file draws with them, so each is imported as
  ``X as X``, which is how the linter tells a re-export from an unused import.
  """

  from __future__ import annotations

  from app.models import Role
  from app.wording import DAY_HOMEWORK_TEXT_MAX as DAY_HOMEWORK_TEXT_MAX
  from app.wording import DAY_KIND_LABELS as DAY_KIND_LABELS
  from app.wording import EVENT_ICONS as EVENT_ICONS
  from app.wording import MESSAGE_LIMIT as MESSAGE_LIMIT
  from app.wording import MONTHS_GENITIVE as MONTHS_GENITIVE
  from app.wording import MONTHS_NOMINATIVE as MONTHS_NOMINATIVE
  from app.wording import WEEKDAYS as WEEKDAYS
  from app.wording import WEEKDAYS_SHORT as WEEKDAYS_SHORT
  from app.wording import clamp as clamp
  from app.wording import cut as cut
  from app.wording import human_date as human_date
  from app.wording import more_line as more_line
  from app.wording import plural as plural
  from app.wording import relative_day_name as relative_day_name
  from app.wording import render_day as render_day
  ```

- [ ] **Step 3: Update the importers**
  - `app/bot/handlers/access.py`, 31–38:
    ```python
    from app.bot.access_render import (
        ACCESS_MEMBERS_MAX,
        ACCESS_REQUEST_NOTE_MAX,
        render_access_list,
    )
    from app.bot.render import MESSAGE_LIMIT, clamp, cut
    ```
  - `app/bot/handlers/reminders.py`, 21 → `from app.bot.reminders_render import render_reminder_card`.
  - `app/bot/handlers/tasks.py`, 40–47:
    ```python
    from app.bot.homework_render import homework_digest_keys, render_homework_digest
    from app.bot.render import WEEKDAYS_SHORT, human_date
    from app.bot.tasks_render import render_task_list, render_task_saved
    ```
    In `homework_tick_keyboard`'s docstring (line 458), "Built from ``render.homework_digest_keys``" → "Built from ``homework_render.homework_digest_keys``".
  - `app/bot/handlers/week.py`, 23 → `from app.bot.week_render import INVISIBLE, render_next, render_week`.
  - `app/bot/tasks_keyboard.py`: `from app.bot.render import TASK_BUTTONS_MAX` → `from app.bot.tasks_render import TASK_BUTTONS_MAX`.
  - `tests/test_bot_views.py`, 50–67:
    ```python
    from app.bot.access_render import render_access_list
    from app.bot.homework_render import render_homework_digest
    from app.bot.reminders_render import render_reminder_card
    from app.bot.render import MESSAGE_LIMIT, plural, render_day, render_role_help
    from app.bot.tasks_render import (
        TASK_BUTTONS_MAX,
        TASKS_UNREACHABLE,
        render_task_list,
        render_task_saved,
    )
    from app.bot.week_render import INVISIBLE, WEEK_TEXT_LIMIT, duration, render_next, render_week
    ```
  - `tests/test_bot_message_limits.py`:
    - line 32 → `from app.bot import access_render, diary_render, editor_render, manage_render, render, tasks_render, week_render`;
    - lines 133 and 152: `render.render_access_list(` → `access_render.render_access_list(`. The `render.MESSAGE_LIMIT` argument stays.
    - lines 153–154: `render.ACCESS_MEMBERS_MAX` → `access_render.ACCESS_MEMBERS_MAX`;
    - line 173: `render.render_task_list` → `tasks_render.render_task_list`;
    - lines 376 and 403: `render.render_week` → `week_render.render_week`. **Not line 234**, which is `diary_render.render_week`.
    - line 619: `render.ACCESS_REQUEST_NOTE_MAX` → `access_render.ACCESS_REQUEST_NOTE_MAX`.

- [ ] **Step 4: Documents**
  - `CLAUDE.md` 617–618 and `.claude/skills/bot-message/SKILL.md` 36–37: "`render.py` always did this; `diary_render.py` and `editor_render.py` never did" becomes "`render.py`, and the per-screen renderers split out of it (`week_render`, `homework_render`, `tasks_render`, `access_render`), always did this; `diary_render.py` and `editor_render.py` never did". SKILL.md keeps its own punctuation.
  - `docs/bot.md` line 10: "the wording lives in `render.py` and `manage_render.py`;" → "the wording lives in one `*_render.py` per screen, in `render.py` (what is said about the class, and the door to `app/wording.py`) and in `manage_render.py`;".

- [ ] **Step 5: Format, check the move, run the tests**
  ```bash
  python -m ruff check --fix app/bot/render.py app/bot/*_render.py app/bot/tasks_keyboard.py app/bot/handlers tests
  python /tmp/decompose/moved.py app/bot/render.py app/bot/render.py app/bot/access_render.py app/bot/week_render.py app/bot/homework_render.py app/bot/tasks_render.py app/bot/reminders_render.py; echo "exit $?"
  for f in test_bot_views test_bot_message_limits test_service_layering test_bot_handlers test_bot_manage test_bot_button_style; do python -m pytest -q -p no:xdist tests/$f.py; done
  ```
  Expected: `exit 0` with nothing printed; then `96 passed`, `29 passed`, `3 passed`, `77 passed`, `181 passed`, `6 passed`.

- [ ] **Step 6: Gates.** ruff; mypy → `Success: no issues found in 165 source files`; then the full suite.

- [ ] **Step 7: Commit**
  ```
  Give each bot screen its own renderer, and keep `render` the one door to the shared wording

  `render.py` held the week view, «что дальше», the homework digest, the task
  list, the access list and the reminders card. Each now has a module beside it
  — `week_render`, `homework_render`, `tasks_render`, `access_render`,
  `reminders_render` — named like the handler that prints it, as
  `diary_render` and `editor_render` already were. `render.py` keeps the
  sentence under the menu and the warning both bells editors print, and goes
  on re-exporting `app.wording` exactly as before: the fifteen names
  `test_service_layering.py` asserts are the very same objects, now each
  spelled `X as X` because nothing left in the file draws with them.

  Every definition moved verbatim (compared by syntax tree), and the comments
  that name a neighbour say the new one. No string changed, so no message
  changed.

  Refs #273

  Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
  ```

---

### Task 7: Split `handlers/content.py` into one module per flow

**Files:**
- Delete: `app/bot/handlers/content.py`
- Create: `app/bot/handlers/content/__init__.py`, `_common.py`, `homework.py`, `overrides.py`, `events.py`
- Modify:
  - Tests:
    - `tests/test_announcements.py`, 47–53;
    - `tests/test_bot_calendar.py`, 24, 26 and 388–403;
    - `tests/test_bot_handlers.py`, 21, 32–45, 221, 237, 250, 405 and 407;
    - `tests/test_bot_message_limits.py`, 34.
  - `app/bot/handlers/manage/__init__.py`, line 4 (docstring).
  - `CLAUDE.md`, lines 517 and 639–642.
- Test: `test_announcements`, `test_bot_calendar`, `test_bot_handlers`, `test_bot_message_limits`, `test_bot_commands`

**Interfaces:**
- Consumes `content_keyboard` (Task 5), and `homework_view` from `app.bot.handlers.tasks`, which is still there until Task 8.
- Produces:
  - `app.bot.handlers.content.router`, the only name the package exports.
  - `content._common`: `_today`, `BAD_DATE`, `BAD_PICK`, `_date_or_none`, `_kind_or_none`, `_index_or_none`.
  - `content.homework`: `router`, `homework_root`, `homework_add`, `homework_pick_day`, `homework_pick_subject`, `homework_typed_subject`, `homework_text`.
  - `content.overrides`: `router`, `NO_BELL`, `overrides_root`, `override_pick_day`, `override_pick_index`, `_save_override`, `override_subject`, `override_cancel`, `override_clear`.
  - `content.events`: `router`, `EVENT_KIND_LABELS`, `_parse_time_range`, `events_root`, `event_pick_day`, `event_pick_kind`, `event_time`, `event_title`.

**Decision:** nothing is re-exported but `router`. The importers are four test files. Re-exporting `_today` would make the precedent's classic trap possible: a patch on the package that silently does not reach the submodule.

**Original handler order (content.py), which must be identical afterwards:**
- message: `homework_typed_subject`, `homework_text`, `override_subject`, `event_time`, `event_title`.
- callback_query: `homework_root`, `homework_add`, `homework_pick_day`, `homework_pick_subject`, `overrides_root`, `override_pick_day`, `override_pick_index`, `override_cancel`, `override_clear`, `events_root`, `event_pick_day`, `event_pick_kind`.

Include order `homework`, `overrides`, `events` reproduces both lists exactly, since each module keeps its handlers in source order.

- [ ] **Step 1: Baseline.** `python /tmp/decompose/handler_order.py > /tmp/decompose/order-before.txt`

- [ ] **Step 2: Create the package**

  `content/__init__.py` (in full):
  ```python
  """Day-to-day editing: homework, substitutions and events. This is what an EDITOR does.

  Every write here does three things, in this order: it saves, it writes one
  line to the audit log in the same transaction, and only then it tells the
  subscribers. The order matters. A notification about a substitution that failed to
  save would send thirty people to the wrong room, so nothing is announced
  before it is committed — and ``notify_subscribers`` swallows a single
  recipient's outage rather than failing the edit that caused it.

  One module per flow — ``homework``, ``overrides``, ``events`` — each with a
  router of its own, and ``_common`` for the clock and the guards on what a
  payload carries. The flows import those by name, so a test that pins the clock
  patches ``_today`` on the flow's own module; nothing here re-exports it, so a
  patch aimed at this package raises instead of quietly changing nothing.
  """

  from aiogram import Router

  from app.bot.handlers.content import events, homework, overrides

  router = Router(name="content")

  # The order the three flows stood in when this was one file. No two of them
  # can match one update: each has its own payload class and its own states.
  router.include_routers(homework.router, overrides.router, events.router)

  __all__ = ["router"]
  ```

  `content/_common.py`:
  ```python
  """What the three flows of :mod:`app.bot.handlers.content` share: the class's
  own today, the two refusals a picker gives, and the guards that turn what a
  callback payload carries into a value or ``None``."""

  from __future__ import annotations

  from datetime import date as Date
  from datetime import datetime

  from app.config import get_settings
  from app.models import EventKind, SchoolClass
  ```
  Moves: 62–69 `_today`, 72–74 (`BAD_DATE` with its comment line, then `BAD_PICK`), 83–98 `_date_or_none`, 101–114 `_kind_or_none`, 117–124 `_index_or_none`.

  `content/homework.py`:
  ```python
  """«📝 Домашнее задание»: setting it, and the digest it lands in.

  Part of :mod:`app.bot.handlers.content`; the order every write here follows —
  save, audit line, then announce — is in that package's docstring.
  """

  from __future__ import annotations

  from datetime import date as Date
  from html import escape

  from aiogram import F, Router
  from aiogram.fsm.context import FSMContext
  from aiogram.types import CallbackQuery, InlineKeyboardButton, Message
  from sqlalchemy.ext.asyncio import AsyncSession

  from app.bot.content_keyboard import HomeworkAction
  from app.bot.handlers.calendar import open_month
  from app.bot.handlers.content._common import BAD_DATE, _date_or_none, _index_or_none, _today
  from app.bot.handlers.tasks import homework_view
  from app.bot.keyboards import Menu, back_to_menu, cancel_keyboard
  from app.bot.render import clamp, human_date, relative_day_name
  from app.bot.states import AddHomework
  from app.models import Role, SchoolClass
  from app.schedule import ScheduleResolver
  from app.services import audit, notify
  from app.services import homework as homework_service

  router = Router(name="content.homework")
  ```
  Moves: 140–142 header, 145–164 `homework_root`, 167–187 `homework_add`, 190–244 `homework_pick_day`, 247–278 `homework_pick_subject`, 281–289 `homework_typed_subject`, 292–353 `homework_text`.

  `content/overrides.py`:
  ```python
  """«🔄 Замены»: a lesson replaced, cancelled or put back for one day.

  Part of :mod:`app.bot.handlers.content`; the order every write here follows —
  save, audit line, then announce — is in that package's docstring.
  """

  from __future__ import annotations

  from datetime import date as Date
  from html import escape

  from aiogram import F, Router
  from aiogram.fsm.context import FSMContext
  from aiogram.types import CallbackQuery, InlineKeyboardButton, Message
  from sqlalchemy import select
  from sqlalchemy.ext.asyncio import AsyncSession

  from app.bot.button_style import DANGER
  from app.bot.content_keyboard import OverrideAction as OverrideCB
  from app.bot.handlers.calendar import open_month
  from app.bot.handlers.content._common import (
      BAD_DATE,
      BAD_PICK,
      _date_or_none,
      _index_or_none,
      _today,
  )
  from app.bot.keyboards import Menu, back_to_menu
  from app.bot.render import human_date, relative_day_name
  from app.bot.states import AddOverride
  from app.models import LessonOverride, OverrideAction, Role, SchoolClass
  from app.schedule import ScheduleResolver
  from app.services import audit, notify, subjects, timetable_edit

  router = Router(name="content.overrides")
  ```
  Moves: 75–80 `NO_BELL` with its comment, 356–358 header, 361–377 `overrides_root`, 380–423 `override_pick_day`, 426–460 `override_pick_index`, 463–519 `_save_override`, 522–592 `override_subject`, 595–638 `override_cancel`, 641–689 `override_clear`.

  `content/events.py`:
  ```python
  """«🎉 События»: something on a day that is not a lesson.

  Part of :mod:`app.bot.handlers.content`; the order every write here follows —
  save, audit line, then announce — is in that package's docstring.
  """

  from __future__ import annotations

  from datetime import date as Date
  from datetime import time
  from html import escape

  from aiogram import F, Router
  from aiogram.fsm.context import FSMContext
  from aiogram.types import CallbackQuery, InlineKeyboardButton, Message
  from sqlalchemy.ext.asyncio import AsyncSession

  from app.bot.content_keyboard import EventAction
  from app.bot.handlers.calendar import open_month
  from app.bot.handlers.content._common import (
      BAD_DATE,
      BAD_PICK,
      _date_or_none,
      _kind_or_none,
      _today,
  )
  from app.bot.keyboards import Menu, back_to_menu, cancel_keyboard
  from app.bot.render import human_date, relative_day_name
  from app.bot.states import AddEvent
  from app.models import DayEvent, EventKind, Role, SchoolClass
  from app.services import audit, notify

  router = Router(name="content.events")
  ```
  Moves: 53–59 `EVENT_KIND_LABELS`, 127–137 `_parse_time_range`, 692–694 header, 697–713 `events_root`, 716–748 `event_pick_day`, 751–767 `event_pick_kind`, 770–781 `event_time`, 784–849 `event_title`.

- [ ] **Step 3: `git rm app/bot/handlers/content.py`.** `app/bot/handlers/__init__.py` needs no change: `from app.bot.handlers import content` and `content.router` still resolve.

- [ ] **Step 4: Retarget the tests**
  - `tests/test_announcements.py`, 47–53:
    ```python
    from app.bot.handlers.content.events import event_title
    from app.bot.handlers.content.homework import homework_text
    from app.bot.handlers.content.overrides import override_cancel, override_clear, override_subject
    ```
  - `tests/test_bot_calendar.py`:
    - line 24 → `from app.bot.handlers.content import _common as content_common`, plus a second line `from app.bot.handlers.content import events as events_flow, homework as homework_flow, overrides as overrides_flow`. Let `ruff --fix` split it.
    - line 26 → `from app.bot.handlers.content.homework import homework_pick_day`.
    - lines 388–394 → the block below. Line 403's `content.BAD_DATE` → `content_common.BAD_DATE`.
      ```python
          flow_module = {"hw": homework_flow, "ovr": overrides_flow, "ev": events_flow}[flow]
          # On the module whose handler reads it: the name is imported into each
          # flow, so patching it on `_common` or on the package changes nothing.
          monkeypatch.setattr(flow_module, "_today", lambda *_: TODAY)
          callback = CardCallback()
          handler = {
              "hw": homework_flow.homework_pick_day,
              "ovr": overrides_flow.override_pick_day,
              "ev": events_flow.event_pick_day,
          }[flow]
      ```
  - `tests/test_bot_handlers.py`:
    - delete line 21 (`from app.bot.handlers import content`);
    - replace 32–45 with:
      ```python
      from app.bot.handlers.content._common import _date_or_none
      from app.bot.handlers.content.events import (
          _parse_time_range,
          event_pick_kind,
          event_time,
          event_title,
      )
      from app.bot.handlers.content.homework import (
          homework_pick_day,
          homework_pick_subject,
          homework_text,
          homework_typed_subject,
      )
      from app.bot.handlers.content.overrides import (
          _save_override,
          override_cancel,
          override_clear,
          override_pick_index,
          override_subject,
      )
      ```
    - lines 221, 237 and 250: `content._save_override(` → `_save_override(`;
    - lines 405 and 407: `content._date_or_none(` → `_date_or_none(`.
  - `tests/test_bot_message_limits.py`, 34:
    ```python
    from app.bot.handlers.content.events import event_title
    from app.bot.handlers.content.overrides import override_subject
    ```

- [ ] **Step 5: Comments and documents**
  - `app/bot/handlers/manage/__init__.py`, line 4: "``content.py``" → "``content/``".
  - `CLAUDE.md`, line 517: "`bot/handlers/content.py`" → "`bot/handlers/content/overrides.py`".
  - `CLAUDE.md`, 639–642. The sentence becomes: "guard for this: `_int_or_none` in `manage/_common.py`, `tasks.py` and `access.py`, `_date_or_none` in `calendar.py` and `content/_common.py`, `_role_or_none` in `access.py`, `_kind_or_none` and `_index_or_none` in `content/_common.py`, and `shift_days`/`shift_weeks` in `keyboards.py`". `manage.py` was already stale: it has been a package since f7928d7.

- [ ] **Step 6: Check the move and the order**
  ```bash
  python -m ruff check --fix app/bot/handlers/content tests
  python /tmp/decompose/moved.py app/bot/handlers/content.py app/bot/handlers/content/__init__.py app/bot/handlers/content/_common.py app/bot/handlers/content/homework.py app/bot/handlers/content/overrides.py app/bot/handlers/content/events.py; echo "exit $?"
  git add -N app/bot/handlers/content
  git diff HEAD --color-moved=dimmed-zebra --color-moved-ws=allow-indentation-change -- app/bot/handlers/content.py app/bot/handlers/content/
  python /tmp/decompose/handler_order.py > /tmp/decompose/order-after.txt
  diff /tmp/decompose/order-before.txt /tmp/decompose/order-after.txt; echo "diff exit $?"
  ```
  Expected:
  - `exit 0` with nothing printed. `router` matches the `__init__`'s identical `Router(name="content")`.
  - In the diff, only docstrings, imports and `router =` lines are bright.
  - `diff exit 0` with no differences.

- [ ] **Step 7: Tests, and prove a stale patch now fails loudly**
  ```bash
  for f in test_announcements test_bot_calendar test_bot_handlers test_bot_message_limits test_bot_commands; do python -m pytest -q -p no:xdist tests/$f.py; done
  ```
  Expected: `6 passed`, `23 passed`, `77 passed`, `29 passed`, `85 passed`.

  Then temporarily change the new `monkeypatch.setattr(flow_module, …)` line to `monkeypatch.setattr("app.bot.handlers.content._today", lambda *_: TODAY)` and run `python -m pytest -q -p no:xdist tests/test_bot_calendar.py`. Expected: `3 failed, 20 passed`, each with `AttributeError: … has no attribute '_today'`. Revert.

- [ ] **Step 8: Gates.** ruff; mypy → `Success: no issues found in 169 source files`; then the full suite.

- [ ] **Step 9: Commit**
  ```
  Split the bot's day-to-day editing into one module per flow

  `handlers/content.py` is now a package the way `handlers/manage/` is:
  `homework`, `overrides` and `events`, each with a router of its own,
  included in the order they stood in, and `_common` for the clock, the two
  refusals a picker gives and the guards on what a payload carries. Checked two
  ways: every top-level definition compared by syntax tree against the old file
  — nothing missing, nothing altered — and the order the dispatcher offers
  updates to the bot's 177 handlers dumped before and after: identical.

  The package exports `router` and nothing else, unlike `handlers/manage`,
  whose `__init__` re-exports ninety names. Four test files imported from here,
  and they now name the module that defines what they use. The one that pinned
  the clock as `content._today` patches the flow whose handler reads it; a
  patch left on the package raises now rather than quietly changing a name
  nothing reads. `test_announcements.py` needed only its import, its list being
  keyed by the functions since the previous commit.

  Refs #273

  Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
  ```

---

### Task 8: Move the homework ticks next to the homework they tick (#276 filed)

**Files:**
- Modify:
  - `app/bot/handlers/tasks.py`: docstring 1–12, imports 16–50, 63–64 (`HOMEWORK_DAYS`), 435–572 (moved out), and line 575 (`__all__`).
  - `app/bot/handlers/content/homework.py`: receives the ticks; drops the `homework_view` import; docstring; the `homework_root` docstring.
  - `app/bot/handlers/content/__init__.py`: docstring.
  - `app/bot/handlers/__init__.py`: `build_router`.
  - `app/bot/homework_render.py`: the comment above `HOMEWORK_ITEMS_MAX`.
  - `tests/test_bot_views.py`, 34–47.
  - `tests/test_bot_commands.py`: one import and one new test.
  - `CLAUDE.md`, line 639.
- Test: `test_bot_views`, `test_bot_commands`, `test_announcements`, `test_bot_handlers`

**Interfaces:**
- Produces:
  - `content.homework`: `HOMEWORK_DAYS`, `_int_or_none`, `_day_short`, `homework_tick_keyboard`, `homework_view`, `cmd_homework`, `homework_toggle`, and `ticks`, the router those two handlers are registered on.
  - `app.bot.handlers.tasks.__all__ == ["router", "task_view"]`.

**Why a router of its own.** Today `cmd_homework` and `homework_toggle` are offered updates at `tasks`' position, ninth of twelve routers. The rest of `content` sits fourth. `CommandBreakoutMiddleware` clears a form only when `_COMMAND` matches, and `_COMMAND` rejects `/homework@`, which aiogram's `Command("homework")` accepts. So `/homework@` typed at a form step asked between those two positions (`overrides`, `events`, `editor`, `timetable`, `tasks`' own `AddTask.text`) is that step's answer today. Registering the ticks with `content` would make it the digest instead. Including `ticks` right after `tasks.router` keeps every update where it went. The callback side has no such overlap, because every callback handler there filters on a payload class with its own prefix.

- [ ] **Step 1: Read #276 before moving anything.** The gap is already filed (3 October 2026, milestone 11, `type:bug`, `area:bot`, `status:next`), with the check that confirmed it. It is not fixed in this plan; this task only keeps the ticks' place in the order because of it.

- [ ] **Step 2: A routing test that must pass before and after.** In `tests/test_bot_commands.py`, add `from app.bot.content_keyboard import HomeworkTick` to the imports. Add this test after `test_a_press_a_handler_owns_never_reaches_the_catch_all`:
  ```python
  async def test_a_tick_still_reaches_the_homework_flow(bot, sent, editor):
      """The «сделал» ticks moved from `tasks` into `content/homework.py`. What a
      tick on an assignment that is no longer there answers is the homework
      flow's own sentence, so the press went where it always went — not to the
      catch-all and not to a screen asked earlier."""
      await dispatcher().feed_update(bot, _press(HomeworkTick(action="toggle", value="999").pack()))

      answers = [m for m in sent.sent if type(m).__name__ == "AnswerCallbackQuery"]
      assert [a.text for a in answers] == ["Это задание уже удалено"]
  ```
  Run `python -m pytest -q -p no:xdist tests/test_bot_commands.py` → `86 passed`. Take the order baseline: `python /tmp/decompose/handler_order.py > /tmp/decompose/order-before.txt`.

- [ ] **Step 3: Move the ticks into `content/homework.py`** (appended after `homework_text`, in source order):
  - 63–64 `HOMEWORK_DAYS` with its comment. Put it after the imports, before `router`.
  - 74–79 `_int_or_none`, copied, not moved. `tasks` still uses its own, and CLAUDE.md records that every handler module carries its own guard.
  - 435–437 header, 440–446 `_day_short`, 449–486 `homework_tick_keyboard`, 489–521 `homework_view`, 524–535 `cmd_homework`, 538–572 `homework_toggle`.

  Make exactly these edits to the moved code:
  - In `homework_view`, `today = _now(school_class).date()` → `today = _today(school_class)`. That is the same expression for the non-`None` class it is always called with.
  - `@router.message(Command("homework"))` → `@ticks.message(Command("homework"))`.
  - `@router.callback_query(HomeworkTick.filter(F.action == "toggle"))` → `@ticks.callback_query(HomeworkTick.filter(F.action == "toggle"))`.

  Directly after `router = Router(name="content.homework")`, add:
  ```python
  #: The «сделал» ticks' own router. `handlers/__init__.py` includes it where
  #: `tasks` used to offer these two handlers updates, not with `router` above:
  #: the comment there says why.
  ticks = Router(name="content.homework.ticks")
  ```

  The import block becomes (`ruff --fix` sorts it):
  ```python
  from datetime import date as Date
  from datetime import timedelta
  from html import escape

  from aiogram import F, Router
  from aiogram.filters import Command
  from aiogram.fsm.context import FSMContext
  from aiogram.types import CallbackQuery, InlineKeyboardButton, InlineKeyboardMarkup, Message
  from sqlalchemy import select
  from sqlalchemy.ext.asyncio import AsyncSession

  from app.bot.button_style import SUCCESS
  from app.bot.content_keyboard import HomeworkAction, HomeworkTick
  from app.bot.handlers.calendar import open_month
  from app.bot.handlers.content._common import BAD_DATE, _date_or_none, _index_or_none, _today
  from app.bot.handlers.tasks import NO_ACCESS
  from app.bot.homework_render import homework_digest_keys, render_homework_digest
  from app.bot.keyboards import Menu, back_to_menu, cancel_keyboard, cut
  from app.bot.render import WEEKDAYS_SHORT, clamp, human_date, relative_day_name
  from app.bot.states import AddHomework
  from app.models import Homework, Role, SchoolClass
  from app.schedule import ResolvedDay, ScheduleResolver
  from app.services import audit, notify
  from app.services import homework as homework_service
  from app.services import tasks as task_service
  ```
  `cut` is `keyboards.cut`, the button-label one. It is not `app.wording.cut`; the tick buttons always used this one.

  The docstring becomes:
  ```python
  """«📝 Домашнее задание»: setting it, the digest it lands in, and the «сделал»
  ticks each reader makes on it.

  Part of :mod:`app.bot.handlers.content`; the order every write here follows —
  save, audit line, then announce — is in that package's docstring. A tick is
  not a write to the class: it is a row keyed by the presser's Telegram id, and
  an id out of a button is re-scoped to the class before it is believed.
  """
  ```

  In `homework_root`'s docstring, "render, from ``handlers/tasks.py`` — one function" → "render — :func:`homework_view`, below — one function".

- [ ] **Step 4: Shrink `tasks.py`**
  - Delete 63–64 and 435–572.
  - Line 575 becomes `__all__ = ["router", "task_view"]`.
  - Imports become (`_now`, `_int_or_none` and `NO_ACCESS` stay):
    ```python
    from datetime import datetime, time, timedelta
    from html import escape

    from aiogram import F, Router
    from aiogram.filters import Command, CommandObject
    from aiogram.fsm.context import FSMContext
    from aiogram.types import CallbackQuery, InlineKeyboardButton, InlineKeyboardMarkup, Message
    from sqlalchemy.ext.asyncio import AsyncSession

    from app.bot.keyboards import Menu, cancel_keyboard
    from app.bot.render import human_date
    from app.bot.states import AddTask
    from app.bot.tasks_keyboard import (
        TaskAction,
        task_delete_confirm,
        task_delete_picker,
        task_list_keyboard,
        task_remind_keyboard,
    )
    from app.bot.tasks_render import render_task_list, render_task_saved
    from app.models import PersonalTask, Role, SchoolClass
    from app.services import tasks as task_service
    ```
  - Docstring:
    ```python
    """Personal tasks.

    Everything here belongs to the person pressing the button, never to the
    class: a task is always looked up by (id, class, owner) through
    ``services.tasks.get_task``. Ids travel in callback data, which the user
    controls, so an id on its own is never trusted. The «сделал» ticks on
    homework are the same kind of thing and live with the homework they tick,
    in ``handlers/content/homework``.

    Adding a task is one message in the class chat's own dialect - «Купить
    тетрадь до 15.09 в 18:00 !» - because a date picker on a phone is slower than
    typing, and the grammar is small enough to remember.
    """
    ```

- [ ] **Step 5: Include `ticks` where `tasks` was.** In `app/bot/handlers/__init__.py`, add `from app.bot.handlers.content.homework import ticks as homework_ticks` below the package import, and after `router.include_router(tasks.router)` insert:
  ```python
      # The «сделал» ticks live with the homework they tick, in
      # `content/homework.py`, and are asked here — where they stood while they
      # were `tasks`'s — rather than with the rest of `content`. aiogram's
      # `Command` reads «/homework@» as /homework and `CommandBreakoutMiddleware`
      # does not, so a form step asked between the two places has always taken
      # it as its answer; asking the ticks earlier would change who answers.
      router.include_router(homework_ticks)
  ```

- [ ] **Step 6: Remaining texts**
  - `content/__init__.py` docstring: after "…``_common`` for the clock and the guards on what a payload carries." add "The «сделал» ticks are in ``homework`` too, on a router of their own that ``handlers/__init__.py`` includes where ``tasks`` used to offer them."
  - `homework_render.py`, comment above `HOMEWORK_ITEMS_MAX`: "``handlers/tasks`` reads" → "``handlers/content/homework`` reads".
  - `CLAUDE.md` line 639: "`_int_or_none` in `manage/_common.py`, `tasks.py` and `access.py`" → "`_int_or_none` in `manage/_common.py`, `tasks.py`, `content/homework.py` and `access.py`".
  - `tests/test_bot_views.py`, 34–47:
    ```python
    from app.bot.handlers.content.homework import (
        cmd_homework,
        homework_tick_keyboard,
        homework_toggle,
        homework_view,
    )
    from app.bot.handlers.tasks import (
        cmd_task,
        cmd_tasks,
        remind_at_for,
        task_add_text,
        task_delete,
        task_set_reminder,
        task_toggle_done,
        task_view,
    )
    ```

- [ ] **Step 7: Check the move and the order**
  ```bash
  python -m ruff check --fix app/bot/handlers tests
  python /tmp/decompose/moved.py app/bot/handlers/tasks.py app/bot/handlers/tasks.py app/bot/handlers/content/homework.py --allow homework_view,cmd_homework,homework_toggle,__all__; echo "exit $?"
  python /tmp/decompose/handler_order.py > /tmp/decompose/order-after.txt
  diff /tmp/decompose/order-before.txt /tmp/decompose/order-after.txt
  ```
  Expected:
  - `CHANGED homework_view`, `CHANGED cmd_homework`, `CHANGED homework_toggle`, `CHANGED __all__`, then `exit 0`.
  - The diff is exactly two changed lines, at the same positions:
    ```
    < message tasks:cmd_homework
    > message content:cmd_homework
    < callback_query tasks:homework_toggle
    > callback_query content:homework_toggle
    ```

- [ ] **Step 8: Tests**
  ```bash
  for f in test_bot_views test_bot_commands test_announcements test_bot_handlers; do python -m pytest -q -p no:xdist tests/$f.py; done
  ```
  Expected: `96 passed`, `86 passed`, `6 passed`, `77 passed`.

- [ ] **Step 9: Gates.** ruff; mypy → `Success: no issues found in 169 source files`; then the full suite.

- [ ] **Step 10: Commit**
  ```
  Keep the homework ticks with the homework they tick

  The «сделал» ticks — `homework_view`, its keyboard, `/homework` and the
  toggle — lived in `handlers/tasks.py` beside personal tasks, and
  `content.py` imported the digest back from there. They now live in
  `content/homework.py`, beside setting the homework they tick, and `tasks.py`
  is personal tasks only.

  They keep their place in the order the dispatcher asks: a router of their
  own, which `handlers/__init__.py` includes right after `tasks`, where they
  stood. With the rest of `content`, five routers earlier, one answer would
  have changed: aiogram's `Command` reads «/homework@» as /homework and
  `CommandBreakoutMiddleware` does not (#276), so a form step asked in
  between has always taken it as its answer. The dump of all 177 handlers in
  dispatch order differs only in which module two of them come from.

  Moved verbatim but for three lines: `homework_view` takes today through
  `content`'s `_today` instead of `tasks`'s `_now(...).date()` — the same
  expression for a class in scope, the only way it is called — and the two
  handlers register on `ticks`. `_int_or_none` is copied, as every handler
  module carries its own guard; `NO_ACCESS` is imported from `tasks` rather
  than written a third time. A test presses a tick through the real dispatcher
  and gets the homework flow's own answer.

  Refs #273, #276

  Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
  ```

---

### Task 9: Split `handlers/start.py` into one module per flow

**Files:**
- Delete: `app/bot/handlers/start.py`
- Create: `app/bot/handlers/start/__init__.py`, `_common.py`, `menu.py`, `onboarding.py`, `days.py`, `codes.py`, `help_page.py`, `timezone.py`
- Modify:
  - Tests:
    - `tests/test_bot_commands.py` (the start import added in Task 3);
    - `tests/test_bot_handlers.py`, 46;
    - `tests/test_bot_message_limits.py`, 36;
    - `tests/test_bot_schools.py`, 15;
    - `tests/test_bot_views.py`, 16 and 27–33;
    - `tests/test_hardening.py`, 556, 569, 585, 600 and 614;
    - `tests/test_roles.py`, 177 (docstring).
  - Comments: `app/api/manage/classes.py` 109; `app/services/manage/classes.py` 4; `app/bot/handlers/manage/class_card.py` 123.
  - `.claude/agents/server-bot.md`, 12–13.
- Test: `test_bot_handlers`, `test_bot_message_limits`, `test_bot_schools`, `test_bot_views`, `test_hardening`, `test_bot_commands`, `test_roles`

**Interfaces:**
- Consumes `start_keyboard` (Task 5).
- Produces:
  - `app.bot.handlers.start.router`, the only name the package exports.
  - `start._common`: `WELCOME_UNKNOWN`.
  - `start.menu`: `LINK_CODE_MAX`, `_send_menu`, `cmd_start_link`, `cmd_start`, `on_contact`, `back_root`.
  - `start.onboarding`: `create_class_*` (8 handlers), `SKIP_ANSWERS`, `SEARCH_PROMPT`, `MANUAL_PROMPT`, `_ask_school`, `_ask_timezone`, `_search_caption`, `_stored_schools`.
  - `start.days`: `_today`, `show_day`, `cmd_today`, `cmd_tomorrow`.
  - `start.codes`: `cmd_code`, `rotate_code`, `phone_code`.
  - `start.help_page`: `HELP_SECTIONS`, `cmd_help`. The module is `help_page`, not `help`, so it does not shadow the builtin.
  - `start.timezone`: `change_timezone_prompt`, `change_timezone_apply`.

**Original handler order (start.py):**
- message: `cmd_start_link`, `cmd_start`, `on_contact`, `create_class_letter`, `create_class_school_search`, `create_class_school_typed`, `cmd_today`, `cmd_tomorrow`, `cmd_code`, `cmd_help`.
- callback_query: `create_class_grade`, `create_class_school_page`, `create_class_school_pick`, `create_class_school_manual`, `create_class_school_skip`, `create_class_timezone`, `back_root`, `show_day`, `rotate_code`, `phone_code`, `change_timezone_prompt`, `change_timezone_apply`.

The include order `menu`, `onboarding`, `days`, `codes`, `help_page`, `timezone` reproduces the message list exactly. On callbacks it moves `back_root` from 7th to 1st. `back_root` filters `Menu` (prefix `m`); the six it passes filter `GradePick` (`grd`), `SchoolPick` (`sch`) and `TimezonePick` (`tz`); a payload has one prefix, so no press can match both. `cmd_start_link` stays with `cmd_start` in `menu`, rather than going to `codes`, so the message order stays exact. The two orders that matter (Task 3's pins) hold.

**Decision:** nothing is re-exported but `router`. There are five test importers. `days._today` reads `datetime` from `days`, so the clock patch must target `start.days`. A patch on the package would raise, which Step 6 shows.

- [ ] **Step 1: Baseline.** `python /tmp/decompose/handler_order.py > /tmp/decompose/order-before.txt`

- [ ] **Step 2: Create the package**

  `start/__init__.py` (in full):
  ```python
  """The way in: ``/start`` and the main menu, creating the first class, a day on
  its own, the codes that put a phone in a class, ``/help``, and the class's time
  zone.

  One module per flow, each with a router of its own, included below in one
  written-down order the way ``handlers/manage`` is. This was one file of eight
  hundred lines where the create-a-class wizard sat between the menu and the day
  view. ``_common`` holds the one sentence two of them say. Nothing is
  re-exported but ``router``: a test that pins the clock patches ``days``, where
  ``_today`` reads it.
  """

  from aiogram import Router

  from app.bot.handlers.start import codes, days, help_page, menu, onboarding, timezone

  router = Router(name="start")

  # The order an update is offered to the flows in. It is the order the handlers
  # stood in when this was one file, with one exception that changes nothing:
  # «‹ Меню» (`menu.back_root`) used to come after the wizard's six buttons, and
  # it takes `Menu(action="root")` while they take `GradePick`, `SchoolPick` and
  # `TimezonePick`, so no press can match both. Two orders here do matter, and
  # `test_bot_commands.py` holds both: `menu` before `onboarding`, so a shared
  # contact is not taken as a wizard step's answer, and `onboarding` before
  # `timezone`, whose handler takes the wizard's `TimezonePick` without its state.
  router.include_routers(
      menu.router,
      onboarding.router,
      days.router,
      codes.router,
      help_page.router,
      timezone.router,
  )

  __all__ = ["router"]
  ```

  `start/_common.py`:
  ```python
  """What more than one flow of :mod:`app.bot.handlers.start` says."""

  from __future__ import annotations
  ```
  Moves: 62–66 `WELCOME_UNKNOWN`.

  `start/menu.py`:
  ```python
  """``/start`` in both its forms, a shared phone number, and «‹ Меню».

  Part of :mod:`app.bot.handlers.start`. ``/start link_…`` is answered here
  rather than with the other codes: it is a ``/start`` too, so it has to be asked
  before the bare one, and keeping both in one router leaves that to their order
  in this file alone.
  """

  from __future__ import annotations

  from html import escape

  from aiogram import F, Router
  from aiogram.filters import CommandObject, CommandStart
  from aiogram.fsm.context import FSMContext
  from aiogram.types import CallbackQuery, Message, ReplyKeyboardRemove
  from sqlalchemy.ext.asyncio import AsyncSession

  from app.bot.handlers.start._common import WELCOME_UNKNOWN
  from app.bot.keyboards import Menu, main_menu, request_contact
  from app.bot.render import render_role_help
  from app.bot.roles import claim_phone_invites, get_role, is_env_owner
  from app.bot.start_keyboard import grade_picker
  from app.bot.states import CreateClass
  from app.models import Role, SchoolClass
  from app.services import linking

  router = Router(name="start.menu")
  ```
  Moves: 75–81 `_send_menu`, 84–86 `LINK_CODE_MAX`, 89–131 `cmd_start_link`, 134–160 `cmd_start`, 163–203 `on_contact`, 490–505 `back_root`.

  `start/onboarding.py`:
  ```python
  """Creating the first class: grade, letter, school, time zone.

  Part of :mod:`app.bot.handlers.start`. Every step but the first is filtered by
  its ``CreateClass`` state, and the last one takes a ``TimezonePick`` — the
  payload ``timezone`` takes without a state — so this router is included before
  that one.
  """

  from __future__ import annotations

  from datetime import datetime
  from html import escape

  from aiogram import F, Router
  from aiogram.fsm.context import FSMContext
  from aiogram.types import CallbackQuery, Message
  from sqlalchemy.ext.asyncio import AsyncSession

  from app.bot.keyboards import cancel_keyboard, main_menu
  from app.bot.render import cut
  from app.bot.roles import is_env_owner
  from app.bot.start_keyboard import (
      GradePick,
      SchoolPick,
      TimezonePick,
      school_fallback,
      school_picker,
      timezone_picker,
  )
  from app.bot.states import CreateClass
  from app.models import DEFAULT_BELLS, BellPeriod, BellSchedule, BotUser, Role, SchoolClass
  from app.providers import dadata
  from app.security import new_join_code
  from app.services import schools as schools_service
  from app.services import terms as terms_service
  from app.services.terms import TermError, compose_name, normalise_letter, validate_grade
  from app.timezones import DEFAULT_TIMEZONE, is_supported, label_for

  router = Router(name="start.onboarding")
  ```
  Moves: 206–487, all of it in source order: `create_class_grade`, `create_class_letter`, `SKIP_ANSWERS`, `SEARCH_PROMPT`, `MANUAL_PROMPT`, `_ask_school`, `_ask_timezone`, `create_class_school_search`, `_search_caption`, `_stored_schools`, `create_class_school_page`, `create_class_school_pick`, `create_class_school_manual`, `create_class_school_skip`, `create_class_school_typed`, `create_class_timezone`.

  `create_class_school_search` must keep calling `schools_service.search(...)` through the module attribute. `test_bot_message_limits.py:800` patches `app.services.schools.search`, and that patch reaches the handler only through the attribute.

  `start/days.py`:
  ```python
  """A day on its own: «📅 Сегодня», «🗓 Завтра», ``/today``, ``/tomorrow`` and the
  ‹ › between days.

  Part of :mod:`app.bot.handlers.start`. ``_today`` is this module's, and a test
  that pins the clock patches ``datetime`` here, where ``_today`` reads it.
  """

  from __future__ import annotations

  from datetime import datetime, timedelta

  from aiogram import Router
  from aiogram.filters import Command
  from aiogram.types import CallbackQuery, Message
  from sqlalchemy.ext.asyncio import AsyncSession

  from app.bot.handlers.start._common import WELCOME_UNKNOWN
  from app.bot.keyboards import DayNav, day_nav, request_contact, shift_days
  from app.bot.render import render_day
  from app.config import get_settings
  from app.models import Role, SchoolClass
  from app.schedule import ScheduleResolver

  router = Router(name="start.days")
  ```
  Moves: 69–72 `_today`, 508–532 `show_day`, 535–546 `cmd_today`, 549–562 `cmd_tomorrow`.

  `start/codes.py`:
  ```python
  """The codes that put a phone in a class: the class code (``/code``, «🔁 Сменить
  код») and a personal one for the presser's own phone («📱 Подключить телефон»).

  Part of :mod:`app.bot.handlers.start`. The third way in — a phone already in
  the class linking itself through ``/start link_…`` — is ``menu``'s, because it
  has to be asked before the bare ``/start``.
  """

  from __future__ import annotations

  from html import escape

  from aiogram import F, Router
  from aiogram.filters import Command
  from aiogram.types import CallbackQuery, InlineKeyboardButton, Message
  from sqlalchemy.ext.asyncio import AsyncSession

  from app.bot.keyboards import ClassAction, Menu, back_to_menu
  from app.bot.render import plural
  from app.models import JoinMode, Role, SchoolClass
  from app.security import new_join_code
  from app.services import audit, device_invites

  router = Router(name="start.codes")
  ```
  Moves: 565–597 `cmd_code`, 600–620 `rotate_code`, 623–686 `phone_code`.

  `start/help_page.py`:
  ```python
  """``/help``, grouped by what the caller may do.

  Part of :mod:`app.bot.handlers.start`.
  """

  from __future__ import annotations

  from aiogram import Router
  from aiogram.filters import Command
  from aiogram.types import Message

  from app.models import Role

  router = Router(name="start.help_page")
  ```
  Moves: 689–737 `HELP_SECTIONS`, 740–754 `cmd_help`.

  `start/timezone.py`:
  ```python
  """«🕒 Часовой пояс» on a class that exists.

  Part of :mod:`app.bot.handlers.start`. The wizard asks the same question in
  ``onboarding`` with the same ``TimezonePick``; that router is included first
  and filtered on its state, which is the only thing telling the two apart.
  """

  from __future__ import annotations

  from aiogram import F, Router
  from aiogram.fsm.context import FSMContext
  from aiogram.types import CallbackQuery
  from sqlalchemy.ext.asyncio import AsyncSession

  from app.bot.keyboards import ClassAction, back_to_menu
  from app.bot.start_keyboard import TimezonePick, timezone_picker
  from app.models import Role, SchoolClass
  from app.services.manage import classes as classes_service
  from app.timezones import label_for

  router = Router(name="start.timezone")
  ```
  Moves: 757–776 `change_timezone_prompt`, 779–810 `change_timezone_apply`.

- [ ] **Step 3: `git rm app/bot/handlers/start.py`.** `build_router` is unchanged.

- [ ] **Step 4: Retarget the tests**
  - `tests/test_bot_commands.py`, the start import from Task 3 →
    ```python
    from app.bot.handlers.start.menu import cmd_start, cmd_start_link, on_contact
    from app.bot.handlers.start.onboarding import (
        create_class_letter,
        create_class_school_search,
        create_class_school_typed,
        create_class_timezone,
    )
    from app.bot.handlers.start.timezone import change_timezone_apply
    ```
  - `tests/test_bot_handlers.py`, line 46 →
    ```python
    from app.bot.handlers.start.codes import cmd_code, phone_code
    from app.bot.handlers.start.days import show_day
    ```
  - `tests/test_bot_message_limits.py`, line 36 → `from app.bot.handlers.start.onboarding import create_class_school_search`.
  - `tests/test_bot_schools.py`, line 15 → `from app.bot.handlers.start import onboarding as handlers`. Every `handlers.` name there is onboarding's: `_ask_school` and `create_class_school_*`.
  - `tests/test_bot_views.py`:
    - line 16 → `from app.bot.handlers.start import days as start_handlers`;
    - lines 27–33 →
      ```python
      from app.bot.handlers.start.days import cmd_today, cmd_tomorrow
      from app.bot.handlers.start.help_page import cmd_help
      from app.bot.handlers.start.menu import cmd_start_link, on_contact
      ```
  - `tests/test_hardening.py`: lines 556, 569, 585 and 614 → `from app.bot.handlers.start.onboarding import create_class_timezone`; line 600 → `from app.bot.handlers.start.onboarding import create_class_grade`.
  - `tests/test_roles.py`, line 177: "`handlers/start.on_contact`" → "`handlers/start/menu.on_contact`".

- [ ] **Step 5: Comments and documents**
  - `app/api/manage/classes.py`, line 109: "(`bot/handlers/start.py`)" → "(`bot/handlers/start/onboarding.py`)".
  - `app/services/manage/classes.py`, line 4: "the zone picker in ``handlers/start.py``" → "the zone picker in ``handlers/start/timezone.py``".
  - `app/bot/handlers/manage/class_card.py`, line 123: "pickers of their own in ``start.py``" → "pickers of their own in ``handlers/start/`` (``timezone``, ``codes``)". Line 104 ("lived in start.py") is history and stays.
  - `.claude/agents/server-bot.md`, lines 12–13: after the list, add "— `content`, `manage` and `start` are packages, one module per flow, each assembling its routers in a written-down order".

- [ ] **Step 6: Check the move and the order, and prove the pins and the patch bite**
  ```bash
  python -m ruff check --fix app/bot/handlers/start tests
  python /tmp/decompose/moved.py app/bot/handlers/start.py app/bot/handlers/start/__init__.py app/bot/handlers/start/_common.py app/bot/handlers/start/menu.py app/bot/handlers/start/onboarding.py app/bot/handlers/start/days.py app/bot/handlers/start/codes.py app/bot/handlers/start/help_page.py app/bot/handlers/start/timezone.py; echo "exit $?"
  python /tmp/decompose/handler_order.py > /tmp/decompose/order-after.txt
  diff /tmp/decompose/order-before.txt /tmp/decompose/order-after.txt
  ```
  Expected:
  - `exit 0` with nothing printed.
  - The diff shows exactly one moved line:
    ```
    > callback_query start:back_root     (before `callback_query start:create_class_grade`)
    < callback_query start:back_root     (was after `callback_query start:create_class_timezone`)
    ```

  Then two deliberate breakages, each reverted:
  - In `start/__init__.py`, swap `onboarding.router` and `timezone.router` and run `python -m pytest -q -p no:xdist tests/test_bot_commands.py -k asked_first`. Expected: `1 failed, 4 passed, 81 deselected`, naming `change_timezone_apply`. Revert.
  - In `tests/test_bot_views.py`, temporarily make line 16 `from app.bot.handlers import start as start_handlers` and run `python -m pytest -q -p no:xdist tests/test_bot_views.py -k whatever_weekday`. Expected: `1 failed`, with `AttributeError: … has no attribute 'datetime'`. Revert.

- [ ] **Step 7: Tests**
  ```bash
  for f in test_bot_handlers test_bot_message_limits test_bot_schools test_bot_views test_hardening test_bot_commands test_roles; do python -m pytest -q -p no:xdist tests/$f.py; done
  ```
  Expected: `77 passed`, `29 passed`, `19 passed`, `96 passed`, `42 passed`, `86 passed`, `33 passed`.

- [ ] **Step 8: Gates.** ruff; mypy → `Success: no issues found in 176 source files`; then the full suite.

- [ ] **Step 9: Commit**
  ```
  Split the bot's way in into one module per flow

  `handlers/start.py` — eight hundred lines in which the create-a-class wizard
  sat between the menu and the day view — is now a package the way
  `handlers/manage/` is: `menu` (/start in both forms, a shared contact, «‹
  Меню»), `onboarding` (the wizard), `days`, `codes`, `help_page` and
  `timezone`, each with a router of its own, and `_common` for the sentence two
  of them say. `/start link_…` is answered in `menu` rather than with the codes,
  because it has to be asked before the bare `/start`.

  Every top-level definition compared by syntax tree against the old file:
  nothing missing, nothing altered. In the dispatch order of all 177 handlers,
  one moved: «‹ Меню» is now offered presses before the wizard's six buttons
  instead of after them; it takes `Menu`, they take `GradePick`, `SchoolPick`
  and `TimezonePick`, so no press can match both. The two orders that do matter
  — a contact before the wizard's text steps, the wizard's time zone before the
  class card's — are held by the pairs `test_bot_commands.py` has carried since
  the earlier commit, and swapping the two routers fails it.

  Only `router` is exported. The test that pins the clock patches
  `start.days.datetime`, where `_today` reads it; aimed at the package, it now
  raises.

  Refs #273

  Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
  ```

---

### Task 10: Split `manage_render.py` into one module per screen

**Files:**
- Delete: `app/bot/manage_render.py`
- Create, under `app/bot/manage_render/`: `__init__.py`, `_common.py`, `subjects.py`, `holidays.py`, `bells.py`, `devices.py`, `audit_log.py`, `class_card.py`, `stats.py`, `import_export.py`, `calendar_feed.py`
- Modify:
  - The `manage_render` import line in each of: `app/bot/handlers/manage/_common.py` 21, `audit_log.py` 15, `bells.py` 17, `calendar_feed.py` 15, `class_card.py` 17, `devices.py` 15, `holidays.py` 18, `import_export.py` 17, `requests.py` 18, `stats.py` 17, `subjects.py` 18.
  - `app/bot/handlers/access.py`, 30.
  - `app/bot/manage_keyboards.py`, 17.
  - `app/bot/handlers/timetable.py`, 40 (comment).
  - Tests: `tests/test_bot_manage.py` 118–129; `tests/test_bot_message_limits.py` 32, 756 and 781; `tests/test_holidays.py` 297.
  - Docs: `CLAUDE.md` 607–608 and 625–628; `docs/bot.md` 10 and 699–701; `.claude/agents/server-bot.md` 48–49; `.claude/skills/bot-message/SKILL.md` 50–51.
- Test: `test_bot_manage`, `test_bot_message_limits`, `test_holidays`, `test_bot_button_style`, `test_bot_handlers`, `test_bot_commands`

**Interfaces:**
- Produces `app.bot.manage_render.<screen>`, with each screen's cap beside its renderer:
  - `subjects.SUBJECTS_MAX`;
  - `holidays.LIST_MAX` and `holidays.KIND_LABELS`;
  - `bells.BELLS_MAX`;
  - `devices.DEVICES_MAX` and `devices.time_ago`;
  - `audit_log.AUDIT_PAGE`;
  - `stats.SEARCH_NEEDLE_MAX`;
  - `import_export.CHUNK_LIMIT`, `import_export.REJECTED_MAX` and `import_export.split_text`;
  - `_common.person`.
- Task 11 reads the caps from these modules.

**Decision:** update the importers. Each handler module uses exactly one render module, so it changes one line: `from app.bot import manage_render as mr` becomes `from app.bot.manage_render import <screen> as mr`. Every `mr.X` stays as it is. mypy flags any `mr.X` that is not on the module it now names.

- [ ] **Step 1: Create the package.** Every module begins with `from __future__ import annotations`. The blocks move verbatim from `manage_render.py`.

  `__init__.py` (in full, no code):
  ```python
  """Text for the management pages, one module per screen of «⚙️ Класс», named
  like the screens in ``handlers/manage/`` — each handler module reads its own as
  ``mr`` — and ``_common`` for what more than one of them prints.

  Kept apart from ``render.py`` for the same reason ``manage_keyboards`` is kept
  apart from ``keyboards``: the day-to-day views and the structural ones are read
  and changed by different people at different times, and a helper renamed in one
  must never be able to quietly reword the other.

  Everything here escapes what it is handed. The rule across this codebase is
  that a row is stored exactly as it was typed and escaped once, at render time -
  so an admin who names a subject «Алгебра & геометрия» sees that name back and
  not an entity, and a name typed with an angle bracket cannot close a tag the
  message opened.

  Every list is capped by row count, but a row carries free text — a note, an
  assignment, an audit summary — and thirty long ones would overrun a limit no
  row count can express; the budget and its three tools live in ``render``. A
  page's cap is declared beside its renderer and read by the same screen's
  keyboard, so what is drawn is what can be pressed.
  """
  ```

  Per screen:

  | Module | Docstring | Imports (after `__future__`) | Moved from `manage_render.py` |
  |---|---|---|---|
  | `_common.py` | `"""What more than one management page prints."""` | `from html import escape` | 131–141 `person` |
  | `subjects.py` | `"""«📚 Предметы»: the list and one subject's card."""` | `from html import escape`; `from app.bot.render import clamp, more_line` | `SUBJECTS_MAX = 30` (line 56; comment below), 124–128 `swatch`, 181–196 `render_subjects`, 199–211 `render_subject_card` |
  | `holidays.py` | `"""«🏖 Особые дни»: the list, and what marking a period says back."""` | `from datetime import date as Date`; `from html import escape`; `from app.bot.render import DAY_KIND_LABELS, clamp, more_line, plural`; `from app.models import DayKind, DayOverride` | `LIST_MAX` (36–38; comment below), 218–220 `KIND_LABELS`, 223–258 `render_holidays`, 261–269 `_PERIOD_WORDS`, 272–277 `render_period_result` |
  | `bells.py` | `"""«🔔 Звонки»: the schedules, and one schedule's rows as its editor takes them back."""` | `from html import escape`; `from app.bot.render import clamp, more_line, plural` | `BELLS_MAX = 10` (line 57; comment below), 285–309 `render_bells`, 312–317 `render_bell_rows` |
  | `devices.py` | `"""«📱 Устройства»: the phones on the class, and when each was last seen."""` | `from datetime import UTC, datetime`; `from html import escape`; `from app.bot.render import clamp, more_line`; `from app.models import Role` | `DEVICES_MAX = 15` (line 58; comment below), 86–88 `_utcnow`, 91–121 `time_ago`, 325–355 `render_devices` |
  | `audit_log.py` | `"""«📜 Журнал»: one page of the class's log."""` | `from datetime import UTC`; `from html import escape`; `from app.bot.render import clamp, cut` | 40–47 `AUDIT_PAGE`, 363–385 `render_audit` |
  | `class_card.py` | `"""The «⚙️ Класс» card."""` | `from html import escape`; `from app.models import JoinMode` | 393–429 `render_class_card` |
  | `stats.py` | `"""``/find``: the assignments a word turns up."""` | `from datetime import date as Date`; `from datetime import timedelta`; `from html import escape`; `from app.bot.render import MONTHS_GENITIVE, WEEKDAYS, clamp, cut` | 68–77 `SEARCH_NEEDLE_MAX`, 437–460 `render_search` |
  | `import_export.py` | `"""``/export`` and ``/import``: a timetable cut into messages, and what «Применить» is about to do."""` | `from html import escape`; `from app.bot.render import WEEKDAYS, clamp, more_line, plural` | 32–34 `CHUNK_LIMIT`, 60–66 `REJECTED_MAX`, 144–173 `split_text`, 463–498 `render_import_preview` |
  | `calendar_feed.py` | `"""«📅 Календарь»: the feed's address and how to subscribe to it."""` | `from html import escape` | 501–521 `render_calendar` |

  The shared comment at lines 49–55 is split among the three caps:
  ```python
  # subjects.py
  #: Subjects drawn on «📚 Предметы», and the ✏️ buttons ``manage_keyboards``
  #: builds under them — one number for both. Three of the four list pages used
  #: to draw more rows than they offered buttons for — forty subjects above
  #: thirty ✏️ buttons, twenty bell schedules above ten — so the tail was
  #: visible, unreachable, and unmentioned by «… и ещё N». The caps differ per
  #: page because the rows do: a schedule carries three buttons and twelve lines
  #: of times, a subject one button and one line.
  SUBJECTS_MAX = 30

  # bells.py
  #: Schedules drawn on «🔔 Звонки», and the rows ``manage_keyboards`` builds for
  #: them; ``subjects.SUBJECTS_MAX`` says why each page has a number of its own.
  BELLS_MAX = 10

  # devices.py
  #: Phones drawn on «📱 Устройства», and the rows ``manage_keyboards`` builds
  #: for them; ``subjects.SUBJECTS_MAX`` says why each page has a number of its own.
  DEVICES_MAX = 15

  # holidays.py — the comment on lines 36–37, plus one line
  #: Rows listed before «… и ещё N». Twenty lines is about a phone screen and a
  #: half; past that the reader scrolls instead of reading. «🏖 Особые дни» draws
  #: this many, and ``manage_keyboards`` builds this many 🗑 buttons under them.
  LIST_MAX = 20
  ```
  The section headers (176–178, 214–216, 280–282, 320–322, 358–360, 388–390, 432–434) and the budget note at 79–83 are dropped. The module names and the package docstring say the same.

- [ ] **Step 2: `git rm app/bot/manage_render.py`, then update the importers**
  - In `app/bot/handlers/manage/`, change the one import line per file:

    | File | New import line |
    |---|---|
    | `_common.py` | `from app.bot.manage_render import _common as mr` |
    | `requests.py` | `from app.bot.manage_render import _common as mr` |
    | `audit_log.py` | `from app.bot.manage_render import audit_log as mr` |
    | `bells.py` | `from app.bot.manage_render import bells as mr` |
    | `calendar_feed.py` | `from app.bot.manage_render import calendar_feed as mr` |
    | `class_card.py` | `from app.bot.manage_render import class_card as mr` |
    | `devices.py` | `from app.bot.manage_render import devices as mr` |
    | `holidays.py` | `from app.bot.manage_render import holidays as mr` |
    | `import_export.py` | `from app.bot.manage_render import import_export as mr` |
    | `stats.py` | `from app.bot.manage_render import stats as mr` |
    | `subjects.py` | `from app.bot.manage_render import subjects as mr` |

  - `app/bot/handlers/access.py`, 30 → `from app.bot.manage_render._common import person`.
  - `app/bot/manage_keyboards.py`, 17 →
    ```python
    from app.bot.manage_render.audit_log import AUDIT_PAGE
    from app.bot.manage_render.bells import BELLS_MAX
    from app.bot.manage_render.devices import DEVICES_MAX
    from app.bot.manage_render.holidays import LIST_MAX
    from app.bot.manage_render.subjects import SUBJECTS_MAX
    ```
  - `tests/test_bot_manage.py`, 118–129 →
    ```python
    from app.bot.manage_render.bells import BELLS_MAX, render_bells
    from app.bot.manage_render.devices import DEVICES_MAX, render_devices, time_ago
    from app.bot.manage_render.holidays import LIST_MAX, render_holidays
    from app.bot.manage_render.import_export import split_text
    from app.bot.manage_render.subjects import SUBJECTS_MAX, render_subjects
    ```
  - `tests/test_bot_message_limits.py`:
    - drop `manage_render` from line 32;
    - add `from app.bot.manage_render.stats import render_search`;
    - lines 756 and 781: `manage_render.render_search(` → `render_search(`.
  - `tests/test_holidays.py`, 297 → `from app.bot.manage_render.holidays import KIND_LABELS`.

- [ ] **Step 3: Comments and documents**
  - `app/bot/handlers/timetable.py`, line 40: "`manage_render.render_import_preview`" → "`manage_render.import_export.render_import_preview`".
  - `CLAUDE.md` line 608: "`manage_render.clamp`" → "`render.clamp`".
  - `CLAUDE.md` 625–628, `.claude/agents/server-bot.md` 48–49 and `.claude/skills/bot-message/SKILL.md` 50–51: "`manage_render` declares `SUBJECTS_MAX`, `BELLS_MAX`, `DEVICES_MAX` and `LIST_MAX`, and `manage_keyboards` builds its rows from those same names" → "`manage_render` declares `SUBJECTS_MAX`, `BELLS_MAX`, `DEVICES_MAX` and `LIST_MAX` beside each page's renderer, and `manage_keyboards` builds that page's rows from the same names". The agent and skill files keep their own list punctuation.
  - `docs/bot.md`:
    - line 10: "`manage_render.py`" → "`manage_render/`, one module per screen like `handlers/manage/`";
    - line 700: "the per-page caps in `manage_render.py`" → "the per-page caps beside each page's renderer in `manage_render/`".

- [ ] **Step 4: Check the move, then run the tests**
  ```bash
  python -m ruff check --fix app/bot/manage_render app/bot/handlers app/bot/manage_keyboards.py tests
  python /tmp/decompose/moved.py app/bot/manage_render.py app/bot/manage_render/_common.py app/bot/manage_render/subjects.py app/bot/manage_render/holidays.py app/bot/manage_render/bells.py app/bot/manage_render/devices.py app/bot/manage_render/audit_log.py app/bot/manage_render/class_card.py app/bot/manage_render/stats.py app/bot/manage_render/import_export.py app/bot/manage_render/calendar_feed.py; echo "exit $?"
  for f in test_bot_manage test_bot_message_limits test_holidays test_bot_button_style test_bot_handlers test_bot_commands; do python -m pytest -q -p no:xdist tests/$f.py; done
  ```
  Expected: `exit 0` with nothing printed; then `181 passed`, `29 passed`, `18 passed`, `6 passed`, `77 passed`, `86 passed`.

- [ ] **Step 5: Gates.** ruff; mypy → `Success: no issues found in 186 source files`. mypy is the check that every `mr.X` is on the module its handler now names. Then the full suite.

- [ ] **Step 6: Commit**
  ```
  Split the management pages' text into one module per screen

  `manage_render.py` is now a package named like `handlers/manage/`:
  `subjects`, `holidays`, `bells`, `devices`, `audit_log`, `class_card`,
  `stats`, `import_export`, `calendar_feed`, and `_common` for `person`, which
  three screens and «👥 Доступ» print. Each page's cap — `SUBJECTS_MAX`,
  `BELLS_MAX`, `DEVICES_MAX`, `LIST_MAX`, `AUDIT_PAGE` — sits beside its
  renderer, which is the number the keyboard under that page reads.

  Moved verbatim (compared by syntax tree). Each handler module used exactly one
  screen's text through `mr`, so each now imports that screen's module as `mr`
  and not one `mr.` line changed; mypy, which asks whether anything reaches for
  an attribute it does not have, is what checks that every one of them is on
  the module it now names. Nothing is re-exported from the package.

  Refs #273

  Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
  ```

---

### Task 11: Split `manage_keyboards.py` into one module per screen

**Files:**
- Delete: `app/bot/manage_keyboards.py`
- Create, under `app/bot/manage_keyboards/`: `__init__.py`, `_common.py`, `subjects.py`, `holidays.py`, `bells.py`, `devices.py`, `requests.py`, `audit_log.py`, `import_export.py`, `class_card.py`, `terms.py`, `diary_binding.py`
- Modify:
  - `app/bot/keyboards.py`: delete `DiarySchoolPick` (116–127) and update the docstring's mention.
  - Importers in `app/bot/handlers/manage/`: `audit_log.py` 17, `bells.py` 22, `calendar_feed.py` 18, `class_card.py` 24, `devices.py` 17, `diary_binding.py` 18–25, `holidays.py` 29–36, `import_export.py` 20, `requests.py` 25, `subjects.py` 21–27, `terms.py` 19.
  - `app/bot/handlers/access.py`, 29.
  - `app/bot/calendar_keyboard.py`, 27.
  - Tests: `tests/test_bot_button_style.py` 22; `tests/test_bot_commands.py` 34; `tests/test_bot_manage.py` 111–117, the `class_menu` import in the census test, 348–353 and 1690; `tests/test_holidays.py` 296 and 410.
  - `docs/bot.md`, 11.
- Test: `test_bot_manage`, `test_bot_button_style`, `test_bot_commands`, `test_holidays`, `test_bot_calendar`, `test_bot_handlers`

**Interfaces:**
- Consumes the `manage_render.<screen>` caps from Task 10.
- Produces:
  - `manage_keyboards._common`: `ManageAction`, `rows_of`, `back_to`.
  - `manage_keyboards.subjects`: `SubjectAction`, `COLOUR_PRESETS`, `subject_list_keyboard`, `subject_card_keyboard`, `colour_keyboard`.
  - `manage_keyboards.holidays`: `DayKindAction`, `PERIOD_KINDS`, `period_kind_keyboard`, `holiday_list_keyboard`, `day_kind_keyboard`, `bells_pick_keyboard`.
  - `manage_keyboards.bells`: `BellsAction`, `bells_list_keyboard`.
  - `manage_keyboards.devices`: `DeviceAction`, `device_keyboard`.
  - `manage_keyboards.requests`: `RequestAction`, `request_keyboard`.
  - `manage_keyboards.audit_log`: `AuditAction`, `audit_keyboard`.
  - `manage_keyboards.import_export`: `ImportAction`, `import_keyboard`.
  - `manage_keyboards.class_card`: `class_menu`, `switch_keyboard`.
  - `manage_keyboards.terms`: `TermAction`, `terms_menu`.
  - `manage_keyboards.diary_binding`: `DiarySchoolPick`, `DIARY_SCHOOLS_MAX`, `diary_provider_menu`, `diary_region_menu`, `diary_school_menu`.

**Decision:** update the importers. All of them use named imports, about twenty lines in all.

- [ ] **Step 1: Create the package.** Every module begins with `from __future__ import annotations`. The blocks move verbatim from `manage_keyboards.py`, except `DiarySchoolPick`, which comes from `keyboards.py`. Below, IKB is `InlineKeyboardButton` and IKM is `InlineKeyboardMarkup`, both from `aiogram.types`; `CallbackData` comes from `aiogram.filters.callback_data`.

  `__init__.py` (in full, no code):
  ```python
  """Callback payloads and keyboards for the management pages, one module per
  screen of «⚙️ Класс», named like the screens in ``handlers/manage/``, and
  ``_common`` for the class card's payload and the way back to it, which every
  screen ends with.

  Kept out of ``keyboards.py`` deliberately: that file is the vocabulary of the
  day-to-day flows, and a prefix collision between the two would not fail a
  build - it would silently route one feature's button into another feature's
  handler. ``tests/test_bot_manage.py`` checks every prefix here against every
  other payload in ``app.bot``, found by walking the package. A page's row cap is
  its renderer's, in ``manage_render``: what is drawn and what can be pressed read
  one number.
  """
  ```

  | Module | Docstring | Imports | Moved |
  |---|---|---|---|
  | `_common.py` | `"""What every management keyboard is built with: the class card's payload, and the way back to it."""` | `CallbackData`; IKB | 26–28 `ManageAction`, 85–86 `rows_of`, 89–90 `back_to` |
  | `subjects.py` | `"""Buttons for «📚 Предметы»."""` | `CallbackData`; IKB, IKM; `from app.bot.button_style import DANGER, SUCCESS`; `from app.bot.keyboards import back_to_menu`; `from app.bot.manage_keyboards._common import back_to, rows_of`; `from app.bot.manage_render.subjects import SUBJECTS_MAX` | comment (below) + 36–38 `SubjectAction`, 71–82 `COLOUR_PRESETS`, 194–234, 237–273, 276–304 |
  | `holidays.py` | `"""Buttons for «🏖 Особые дни»."""` | `CallbackData`; IKB, IKM; `DANGER, PRIMARY, SUCCESS`; `back_to_menu`; `back_to`; `from app.bot.manage_render.bells import BELLS_MAX`; `from app.bot.manage_render.holidays import LIST_MAX`; `from app.models import DayKind` | comment (below) + 41–43 `DayKindAction`, 307–319 `PERIOD_KINDS`, 322–347, 350–414, 417–442, 445–480 |
  | `bells.py` | `"""Buttons for «🔔 Звонки»."""` | `CallbackData`; IKB, IKM; `DANGER, SUCCESS`; `back_to_menu`; `back_to`; `BELLS_MAX` | 46–48 `BellsAction`, 483–523 `bells_list_keyboard` |
  | `devices.py` | `"""Buttons for «📱 Устройства»."""` | `CallbackData`; IKB, IKM; `DANGER`; `back_to_menu`; `back_to`; `from app.bot.manage_render.devices import DEVICES_MAX` | 51–53 `DeviceAction`, 526–547 `device_keyboard` |
  | `requests.py` | `"""Buttons on an access request: approve or decline."""` | `CallbackData`; IKB, IKM; `DANGER, SUCCESS` | 56–58 `RequestAction`, 550–567 `request_keyboard` |
  | `audit_log.py` | `"""Buttons for «📜 Журнал»."""` | `CallbackData`; IKB, IKM; `PRIMARY`; `back_to_menu`; `back_to`; `from app.bot.manage_render.audit_log import AUDIT_PAGE` | 61–63 `AuditAction`, 570–585 `audit_keyboard` |
  | `import_export.py` | `"""Buttons under an import preview."""` | `CallbackData`; IKB, IKM; `DANGER, SUCCESS` | 66–68 `ImportAction`, 588–604 `import_keyboard` |
  | `class_card.py` | `"""The «⚙️ Класс» menu, which opens every other screen, and the class switcher."""` | IKB, IKM; `DANGER`; `from app.bot.keyboards import ClassAction, Menu, back_to_menu`; `from app.bot.manage_keyboards._common import ManageAction, back_to`; `from app.bot.manage_keyboards.audit_log import AuditAction`; `…bells import BellsAction`; `…devices import DeviceAction`; `…holidays import DayKindAction`; `…subjects import SubjectAction`; `…terms import TermAction` | 93–191 `class_menu`, 607–618 `switch_keyboard` |
  | `terms.py` | `"""Buttons for «🗓 Четверти»."""` | `CallbackData`; IKB, IKM; `back_to` | 21–23 `TermAction`, 621–649 `terms_menu` |
  | `diary_binding.py` | the section comment at 652–660, as the docstring (below) | `CallbackData`; IKB, IKM; `from app.bot.manage_keyboards._common import ManageAction, back_to` | `DiarySchoolPick` (`keyboards.py` 116–127), 662–664 `DIARY_SCHOOLS_MAX`, 667–684, 687–701, 704–726 |

  The `sep="|"` comment (lines 31–35) is split between its two classes:
  ```python
  # subjects.py, above SubjectAction
  # ``sep="|"`` because ``value`` is itself a pair - «name:12», «12:5B6ABF».
  # aiogram packs fields with its separator and refuses a value that contains
  # one, so with the default ':' every keyboard here raised ValueError the moment
  # it was built - a page that could not be drawn at all. The pipe never appears
  # in a date, an id or a colour; ``holidays.DayKindAction`` is built the same way.

  # holidays.py, above DayKindAction
  # ``sep="|"`` for ``subjects.SubjectAction``'s reason: ``value`` is a pair -
  # «2026-10-26:holiday» - and aiogram refuses a value holding its separator.
  ```

  `diary_binding.py` docstring:
  ```python
  """Buttons for binding a class to an electronic diary.

  The unbound «📒 Привязать дневник» opens a small chooser rather than binding at
  once, because there is more than one diary now. The rows drawn and the rows a
  press can reach are the same set, so «… и ещё» can never lie: the region list
  is every allow-listed, password-capable region, and the school list is capped
  and says «показаны первые N» when the search found more.
  """
  ```

- [ ] **Step 2: `git rm app/bot/manage_keyboards.py`.** Delete lines 116–127 from `keyboards.py`. Remove "the payloads two features both pack" from its docstring only if no such payload is left: `ClassAction` and `TimetableAction` remain, so the phrase stays.

- [ ] **Step 3: Update the importers**
  - `app/bot/handlers/manage/`:

    | File | New import |
    |---|---|
    | `audit_log.py` | `from app.bot.manage_keyboards.audit_log import AuditAction, audit_keyboard` |
    | `bells.py` | `from app.bot.manage_keyboards.bells import BellsAction, bells_list_keyboard` |
    | `calendar_feed.py` | `from app.bot.manage_keyboards._common import ManageAction, back_to` |
    | `class_card.py` | `from app.bot.manage_keyboards._common import ManageAction` and `from app.bot.manage_keyboards.class_card import class_menu, switch_keyboard` |
    | `devices.py` | `from app.bot.manage_keyboards.devices import DeviceAction, device_keyboard` |
    | `diary_binding.py` 18–25 | `from app.bot.manage_keyboards._common import ManageAction` and `from app.bot.manage_keyboards.diary_binding import DIARY_SCHOOLS_MAX, DiarySchoolPick, diary_provider_menu, diary_region_menu, diary_school_menu` |
    | `holidays.py` 29–36 | `from app.bot.manage_keyboards.holidays import PERIOD_KINDS, DayKindAction, bells_pick_keyboard, day_kind_keyboard, holiday_list_keyboard, period_kind_keyboard` |
    | `import_export.py` | `from app.bot.manage_keyboards.import_export import ImportAction, import_keyboard` |
    | `requests.py` | `from app.bot.manage_keyboards.requests import RequestAction, request_keyboard` |
    | `subjects.py` 21–27 | `from app.bot.manage_keyboards.subjects import COLOUR_PRESETS, SubjectAction, colour_keyboard, subject_card_keyboard, subject_list_keyboard` |
    | `terms.py` | `from app.bot.manage_keyboards.terms import TermAction, terms_menu` |

  - `app/bot/handlers/access.py`, 29 → `from app.bot.manage_keyboards.requests import RequestAction`.
  - `app/bot/calendar_keyboard.py`, 27 → `from app.bot.manage_keyboards.holidays import DayKindAction`.
  - Tests:
    - `tests/test_bot_button_style.py`, 22 → `from app.bot.manage_keyboards.bells import bells_list_keyboard`, `from app.bot.manage_keyboards.devices import device_keyboard` and `from app.bot.manage_keyboards.subjects import colour_keyboard`.
    - `tests/test_bot_commands.py`, 34 → `from app.bot.manage_keyboards.bells import BellsAction`.
    - `tests/test_bot_manage.py`:
      - 111–117 →
        ```python
        from app.bot.manage_keyboards.bells import bells_list_keyboard
        from app.bot.manage_keyboards.devices import device_keyboard
        from app.bot.manage_keyboards.holidays import DayKindAction, holiday_list_keyboard
        from app.bot.manage_keyboards.subjects import subject_list_keyboard
        ```
      - in `test_every_button_on_the_class_menu_is_one_its_payload_class_packed`, `from app.bot.manage_keyboards import class_menu` → `from app.bot.manage_keyboards.class_card import class_menu`;
      - 348–353 →
        ```python
        from app.bot.manage_keyboards.holidays import bells_pick_keyboard, day_kind_keyboard
        from app.bot.manage_keyboards.subjects import colour_keyboard, subject_card_keyboard
        ```
      - 1690 → `from app.bot.manage_keyboards.holidays import bells_pick_keyboard`.
    - `tests/test_holidays.py`: 296 → `from app.bot.manage_keyboards.holidays import PERIOD_KINDS, day_kind_keyboard`; 410 → `from app.bot.manage_keyboards.holidays import holiday_list_keyboard`.
  - `docs/bot.md`, line 11: "`manage_keyboards.py`" → "`manage_keyboards/`, one module per screen the same way".

- [ ] **Step 4: Check the move, the census and the tests**
  ```bash
  python -m ruff check --fix app/bot tests
  python /tmp/decompose/moved.py app/bot/manage_keyboards.py app/bot/manage_keyboards/_common.py app/bot/manage_keyboards/subjects.py app/bot/manage_keyboards/holidays.py app/bot/manage_keyboards/bells.py app/bot/manage_keyboards/devices.py app/bot/manage_keyboards/requests.py app/bot/manage_keyboards/audit_log.py app/bot/manage_keyboards/import_export.py app/bot/manage_keyboards/class_card.py app/bot/manage_keyboards/terms.py app/bot/manage_keyboards/diary_binding.py; echo "exit $?"
  python /tmp/decompose/moved.py app/bot/keyboards.py app/bot/keyboards.py app/bot/manage_keyboards/diary_binding.py; echo "exit $?"
  for f in test_bot_manage test_bot_button_style test_bot_commands test_holidays test_bot_calendar test_bot_handlers; do python -m pytest -q -p no:xdist tests/$f.py; done
  ```
  Expected:
  - `exit 0` twice, with nothing printed.
  - Then `181 passed`, `6 passed`, `86 passed`, `18 passed`, `23 passed`, `77 passed`.
  - The census one-liner from Task 5 Step 6 prints `30 30`.

- [ ] **Step 5: Gates.** ruff; mypy → `Success: no issues found in 197 source files`; then the full suite.

- [ ] **Step 6: Commit**
  ```
  Split the management pages' keyboards into one module per screen

  `manage_keyboards.py` is now a package named like `handlers/manage/` and
  `manage_render/`: a module per screen with its payload and its keyboards, and
  `_common` for `ManageAction`, `rows_of` and `back_to`, which every screen ends
  with. Each list keyboard reads its row cap from the same screen of
  `manage_render`, so what is drawn and what can be pressed still read one
  number. `DiarySchoolPick` leaves `keyboards.py` for the diary-binding screen,
  the only one that packs or reads it.

  Moved verbatim (compared by syntax tree, against both files it came from);
  importers name the screen they use, and nothing is re-exported. No prefix
  changed: the census walks the package, and still counts thirty payloads with
  thirty prefixes.

  Refs #273

  Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
  ```

---

### Task 12: Final gates, the documents' numbers, the stale-path sweep, the handover and the pull request

**Files:**
- Modify:
  - Test counts: `CLAUDE.md` 55, `docs/architecture.md` 865, `README.md` 209, `HANDOVER.md` 1294.
  - Module counts: `CLAUDE.md` 63, `README.md` 208, `CONTRIBUTING.md` 39, `.claude/skills/gates/SKILL.md` 18, `HANDOVER.md` 1295.
  - `CLAUDE.md` 175–183 (the `bot/` bullet).
  - `HANDOVER.md`, per the `handover` skill. That skill moves the batch falling off to the top of `docs/history.md`; nothing else in `docs/history.md` is edited.

**Interfaces:**
- Consumes everything above.

- [ ] **Step 1: Full gates** (only with no Gradle build running)
  ```bash
  python -m ruff check app tests scripts migrations
  python -m mypy
  python -m pytest -q -n auto
  ```
  Expected:
  - `All checks passed!`;
  - `Success: no issues found in 197 source files`;
  - every test passing, with ten more than the count before Task 1: one in Task 1, three in Task 2, five in Task 3, one in Task 8. If the documented 2065 is current, that is `2075 passed`. The 2065 was not re-collected here.

- [ ] **Step 2: The order against the very first baseline**
  ```bash
  python /tmp/decompose/handler_order.py > /tmp/decompose/order-final.txt
  diff /tmp/decompose/order-main.txt /tmp/decompose/order-final.txt
  ```
  Expected: exactly the Task 8 relabelling (two lines, `tasks:` → `content:`, same positions) and the Task 9 move of `callback_query start:back_root`. Nothing else.

- [ ] **Step 3: No document or comment names a file that no longer exists.** From the repository root:
  ```bash
  git grep -nE "handlers/(start|content)\.py|manage_render\.py|manage_keyboards\.py|services/diary\.(py:)?child_scope|\`\`?content\.py\`\`?" -- . ':!docs/history.md' ':!docs/specs/' ':!HANDOVER.md' ':!server/migrations/'
  ```
  Expected: no output, exit 1. "lived in start.py" in `handlers/manage/class_card.py` is history and does not match the pattern.

- [ ] **Step 4: Numbers and one sentence**
  - Replace 2065 with the count from Step 1 in `CLAUDE.md:55`, `docs/architecture.md:865`, `README.md:209` and `HANDOVER.md:1294`.
  - Replace 153 with 197 in `CLAUDE.md:63`, `README.md:208`, `CONTRIBUTING.md:39`, `.claude/skills/gates/SKILL.md:18` and `HANDOVER.md:1295`.
  - In `CLAUDE.md`'s `bot/` bullet (175–183), after "…included in one written-down order by the package's `__init__`.", add:
    ```
    `handlers/start/` and `handlers/content/` are packages the same way, and
    `manage_render/` and `manage_keyboards/` mirror the screens; each feature
    outside «⚙️ Класс» has its own `*_render.py` and `*_keyboard.py` beside
    `render.py` (which re-exports `app/wording.py`) and `keyboards.py`.
    ```

- [ ] **Step 5: Handover.** Update `HANDOVER.md` with the `handover` skill:
  - the opening paragraph;
  - a new «What the last session added» section: this batch, its two filed issues, what was left alone (`api/public.py`, `api/diary.py`, `api/edit.py`, `models.py`, `schedule.py`, providers and Android are not split; `#276` is filed and not fixed; revision `0017`'s text is left as the record), and what nothing verified (no device, no Vercel deploy of the cold-start fix);
  - the batch falling off moved to the top of `docs/history.md`;
  - the milestone table;
  - the counts.

  Write it while the PR is still open.

- [ ] **Step 6: Commit**
  ```
  Say where the work stands after the server decomposition

  The batch that split the bot's long modules and the corrections half of
  `services/diary.py`, filed two defects it found and fixed one of them. The
  test and module counts move to what the suite and mypy now report, and
  CLAUDE.md says where the new packages are.

  Refs #273

  Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
  ```

- [ ] **Step 7: Push and open the PR** as a draft. On this machine `gh` is at `C:\Program Files\GitHub CLI\gh.exe` (call it through PowerShell from a worktree); `gh pr create --milestone "v0.10.0 — One contract: REST v2, Connect and native gRPC, build console"` sets milestone 11. `gh pr edit` fails on this repository (a Projects-classic GraphQL error), so a body is changed with `gh api -X PATCH repos/lumenpearson/lessons/pulls/<N> -F body=@<file>`.
  - Title: `Split the bot's long modules into feature modules inside their layers, with the guards that keep a move honest`
  - Body: mirror `.github/PULL_REQUEST_TEMPLATE.md`. Tick `server/app/bot`, `server/app/services`, `server/app/api` (`telegram.py` and the `diary.py` import) and `docs/`.
  - In «Verification», paste the real outputs of Step 1 and Step 2, and the census `30 30`.
  - The closing lines, each on its own line: `Closes #271`, `Closes #272`, `Closes #275`, `Refs #276`, `Refs #273`.

- [ ] **Step 8: Merge.** Merge after CI is green on the exact head, under the standing instruction and the five checks in the `github-pr` skill.

---

## Self-review

**Spec coverage (section 5, «Server (sub-project 1)»):**

| Spec item | Where it is done |
|---|---|
| `bot/handlers/start.py` → onboarding, day browsing, codes, help | Task 9 (`onboarding`, `days`, `codes`, `help_page`), plus `menu` and `timezone` |
| `bot/handlers/content.py` → homework, substitutions, events, common helpers | Task 7 |
| `bot/handlers/tasks.py` → personal tasks, ticks to the homework module | Task 8 |
| `render.py` and `keyboards.py` per feature; `render.py` stays the `app.wording` re-exporter | Tasks 5 and 6. Task 6 keeps all fifteen names `test_service_layering.py` asserts. |
| `manage_keyboards` / `manage_render` per screen | Tasks 10 and 11 |
| `services/diary.py` → corrections beside `diary_overrides.py` | Task 4 |
| Path-keyed tests made to follow symbols | Task 3 (`test_announcements.py`, `test_directory.py`); Task 1 (the prefix census) |
| The two guards | Task 1 (#271), Task 2 (#272) |
| Monkeypatches listed and moved with the symbol | Tasks 7 and 9 (`content._today`, `start_handlers.datetime`); Task 7 also handles the calls to `content._save_override` and `content._date_or_none`. `service.ADOPT_UPSTREAM_BUDGET_SECONDS`, every `sign_in` patch, `DiaryService`, `public._now` and `week_handlers.datetime` all target names that do not move. Checked by reading each target. |

**Where the code contradicts the spec, or this plan departs from it:**

- **aiogram is on the API's cold start on Vercel today.** Sections 5 and 7, and #272's wording, assume only a guard is missing. `app/api/telegram.py` imports aiogram at the top, and Vercel always mounts the webhook. Task 2 files this as `#275` and fixes it before anything moves.
- **`start.py` holds more than the spec lists.** The menu, the shared contact, «‹ Меню» and the timezone change are in it too, so the package has `menu` and `timezone` besides the four the spec names.
- **The ticks' code moves into the homework module; their place in the dispatch order does not.** They sit on their own router at `tasks`' old position. That is because of `#276`, a gap between aiogram's `Command` and `CommandBreakoutMiddleware` that this plan files but does not fix. Once it is fixed, `ticks` can join `content`'s router.
- **No re-exports.** The precedent (f7928d7) re-exported every old name. This plan deliberately re-exports nothing except `render`'s wording names. The importer counts are small, and re-exporting `_today` or `datetime` would recreate the silent-patch trap.
- **`test_directory.py`** is named in the spec, but it only lists `api/` files, none of which this sub-project moves. It is made symbol-keyed anyway because it is cheap and the v2 layer will move `api/public.py`'s limiter.
- **Two cosmetic leftovers from moving verbatim.** `_date_or_none`'s docstring still says "the three handlers below", which is now in another module. `adopt`'s docstring still says `:func:`child_scope``, which still resolves through the import. Both are left alone so the syntax-tree check stays exact.

**Not verified here (read-only session):**

- The full suite, mypy and ruff were not run. The two checking scripts were written but not executed as files; their logic was run inline:
  - the walker resolution: 12 functions;
  - the census: 30 against 27;
  - the order dump: 177 handlers;
  - the cold-start probe: 736 modules held by `app.api.telegram`.
- The final test total assumes the documented 2065 is current.
- Exact `ruff --fix` import ordering is inferred; the skeletons will be re-sorted by it.
- That module-level annotations under PEP 563 need no runtime import is inferred from the language rules. Task 2 Step 5 checks it.
- That `pkgutil.walk_packages` swallows `ImportError` in subpackages by default is inferred from stdlib documentation, not read in this session. The helper raises regardless.
- The handler pairs that share an update were derived by reading every handler filter in `app/bot/handlers` and the middleware. No handler has router-level filters.
- The order dumps compare registration order; that each handler's filters are unchanged is covered by the syntax-tree check, not by the dump.

**Found while planning, to file:**

- `#275`: filed on 3 October 2026, after the planner's finding was re-measured (736 aiogram modules, 3.07 s); fixed in Task 2.
- `#276`: filed on 3 October 2026 after `looks_like_command('/homework@')` and aiogram's `extract_command` were checked; not fixed here.
- `app/bot/handlers/access.py:91` and CLAUDE.md still say `manage.py`, which has been a package since f7928d7. CLAUDE.md's instance is corrected in Task 7. `access.py`'s docstring is left alone: it is out of this plan's files.

### Critical Files for Implementation

- `C:\Users\lumen\StudioProjects\lessons\.claude\worktrees\spec-one-contract\server\app\bot\handlers\__init__.py`
- `C:\Users\lumen\StudioProjects\lessons\.claude\worktrees\spec-one-contract\server\app\bot\handlers\start.py`
- `C:\Users\lumen\StudioProjects\lessons\.claude\worktrees\spec-one-contract\server\app\bot\handlers\content.py`
- `C:\Users\lumen\StudioProjects\lessons\.claude\worktrees\spec-one-contract\server\app\api\telegram.py`
- `C:\Users\lumen\StudioProjects\lessons\.claude\worktrees\spec-one-contract\server\tests\test_bot_commands.py`