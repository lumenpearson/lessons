from pathlib import Path

import pytest


@pytest.fixture
def root():
    return Path(__file__).resolve().parents[3]
