"""Vercel entry point.

Vercel routes every request to this module and looks for a module-level ASGI
callable named ``app``. Keeping it a thin re-export means the serverless
deployment and a plain ``uvicorn app.main:app`` run byte-identical code, so a
bug can never be specific to one of them.
"""

from __future__ import annotations

import sys
from pathlib import Path

# Vercel puts the repository root on the path, not server/, so make the package
# importable without duplicating it into this directory.
sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "server"))

from app.main import app  # noqa: E402

__all__ = ["app"]
