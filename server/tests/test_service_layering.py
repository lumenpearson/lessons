"""Nothing under ``app/services/`` reaches ``app.bot``, by any path.

``services/`` is the layer both shells stand on: the bot's handlers and the
``/api/v1`` endpoints call the same functions so that a rule is written once.
Four services imported from the bot's own package — the role ladder from
``bot/roles``, the day card, the message budget and the calendar names from
``bot/render`` — so the API reached the bot through the layer it shares with
it, and the next rule written beside those imports would have had nowhere
neutral to live (#205). The pieces moved to ``app.services.roles`` and
``app.wording``, and this holds the direction.

It reads the source rather than ``sys.modules``, for the reason
``test_test_imports`` gives: what matters is every spelling of the import,
including the ones inside a function body that no test happens to call. And it
follows the imports **through** the rest of ``app/``, because a service that
imports a neutral module which imports the bot has the same dependency with
one more step in it; the failure names the whole chain.
"""

from __future__ import annotations

import ast
from pathlib import Path

APP = Path(__file__).resolve().parents[1] / "app"
FORBIDDEN = "app.bot"


def _is_forbidden(dotted: str) -> bool:
    return dotted == FORBIDDEN or dotted.startswith(FORBIDDEN + ".")


def _imported_names(tree: ast.AST, module: str, is_package: bool) -> set[str]:
    """Every dotted name ``tree`` imports, relative spellings made absolute.

    ``ast.walk`` rather than the module body, so an import inside a function,
    under ``if TYPE_CHECKING:`` or in a ``try`` is seen like any other. For
    ``from X import Y`` both ``X`` and ``X.Y`` are returned, because ``Y`` may
    be a module (``from app.bot import roles``) and only the caller knows
    which names are modules. A literal handed to ``import_module`` counts too.
    """
    found: set[str] = set()
    for node in ast.walk(tree):
        if isinstance(node, ast.Import):
            found.update(alias.name for alias in node.names)
        elif isinstance(node, ast.ImportFrom):
            if node.level:
                base = module.split(".") if is_package else module.split(".")[:-1]
                base = base[: len(base) - (node.level - 1)]
                source = ".".join(base + ([node.module] if node.module else []))
            else:
                source = node.module or ""
            found.add(source)
            found.update(f"{source}.{alias.name}" for alias in node.names)
        elif isinstance(node, ast.Call):
            name = getattr(node.func, "attr", None) or getattr(node.func, "id", None)
            if name in ("import_module", "__import__") and node.args:
                first = node.args[0]
                if isinstance(first, ast.Constant) and isinstance(first.value, str):
                    found.add(first.value)
    return found


def _modules() -> dict[str, Path]:
    modules: dict[str, Path] = {}
    for path in APP.rglob("*.py"):
        parts = list(path.relative_to(APP.parent).with_suffix("").parts)
        if parts[-1] == "__init__":
            parts = parts[:-1]
        modules[".".join(parts)] = path
    return modules


def _module_of(dotted: str, modules: dict[str, Path]) -> str | None:
    """The module of ``app/`` a dotted name lives in, or ``None`` outside it."""
    while dotted and dotted not in modules:
        dotted = dotted.rpartition(".")[0]
    return dotted or None


def _chains_into_the_bot() -> list[str]:
    modules = _modules()
    edges: dict[str, set[str]] = {}
    for name, path in modules.items():
        tree = ast.parse(path.read_text(encoding="utf-8"), str(path))
        imported = _imported_names(tree, name, path.name == "__init__.py")
        edges[name] = {
            target
            for target in (_module_of(dotted, modules) for dotted in imported)
            if target is not None and target != name
        }

    chains: list[str] = []
    for start in sorted(name for name in modules if name.startswith("app.services")):
        came_from: dict[str, str | None] = {start: None}
        queue = [start]
        while queue:
            current = queue.pop(0)
            for target in sorted(edges[current]):
                if target in came_from:
                    continue
                came_from[target] = current
                if _is_forbidden(target):
                    chain = [target]
                    while (step := came_from[chain[-1]]) is not None:
                        chain.append(step)
                    chains.append(" -> ".join(reversed(chain)))
                    continue
                queue.append(target)
    return chains


def test_no_service_reaches_the_bot():
    chains = _chains_into_the_bot()
    assert chains == [], (
        "A service may not import from `app.bot`, directly or through another "
        "module: the API stands on `services/`, and the bot is one of the two "
        "shells above it. Move what the service needs down — a rule into "
        "`app/services/`, wording into `app/wording.py` — and import it back "
        "into the bot module it came from.\n" + "\n".join(chains)
    )


def test_the_check_recognises_every_spelling_of_the_slip():
    """Held here rather than trusted, as ``test_test_imports`` holds its own.

    One dependency, many spellings; a matcher that saw only the first would
    report the tree clean with the other seven in it.
    """
    for source in (
        "from app.bot.roles import can_grant",
        "from app.bot import roles",
        "from app import bot",
        "import app.bot.render",
        "import app.bot.render as render",
        "from ..bot.roles import get_role",
        "from ..bot import render",
        "def later():\n    from app.bot.render import WEEKDAYS\n",
        "if TYPE_CHECKING:\n    from app.bot.roles import get_role\n",
        "importlib.import_module('app.bot.render')",
    ):
        names = _imported_names(ast.parse(source), "app.services.example", False)
        assert any(_is_forbidden(name) for name in names), source

    # And it is quiet about what a service legitimately imports, including a
    # package whose name merely starts with the same three letters.
    for source in (
        "from app.services import audit",
        "from app.services.roles import get_role",
        "from app.wording import WEEKDAYS",
        "from app.models import Role",
        "from . import audit",
        "from app.botany import leaves",
        "import aiogram",
    ):
        names = _imported_names(ast.parse(source), "app.services.example", False)
        assert not any(_is_forbidden(name) for name in names), source


def test_the_moved_names_are_still_the_bot_modules_own():
    """``app.bot.roles`` and ``app.bot.render`` hand out the very same objects.

    The handlers, the middleware and two endpoints still import from there.
    Imported back rather than copied, because a copy is a second definition
    free to drift from the first — the thing ``services/`` exists to prevent.
    """
    from app import wording
    from app.bot import render, roles
    from app.services import roles as service_roles

    for name in (
        "can_grant",
        "claim_phone_invites",
        "default_class_for",
        "get_membership",
        "get_role",
        "is_env_owner",
        "list_memberships",
        "require_role",
    ):
        assert getattr(roles, name) is getattr(service_roles, name), name

    for name in (
        "DAY_HOMEWORK_TEXT_MAX",
        "DAY_KIND_LABELS",
        "EVENT_ICONS",
        "MESSAGE_LIMIT",
        "MONTHS_GENITIVE",
        "MONTHS_NOMINATIVE",
        "WEEKDAYS",
        "WEEKDAYS_SHORT",
        "clamp",
        "cut",
        "human_date",
        "more_line",
        "plural",
        "relative_day_name",
        "render_day",
    ):
        assert getattr(render, name) is getattr(wording, name), name
