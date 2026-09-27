FROM python:3.12-slim

ENV PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1 \
    PIP_NO_CACHE_DIR=1

WORKDIR /srv

# The code first and the install after it, not the other way round. With only
# pyproject.toml in place, `pip install .` found no `app` to package and
# installed the dependencies around an empty distribution; the image worked
# only because `python -m uvicorn` puts the working directory on the import
# path and the working directory happened to be where `app/` was copied later.
# Installed after the copy, the package is whole in site-packages — catalog
# data included — and imports from any directory. The price is that a code
# change re-resolves the dependencies on rebuild, which for a server rebuilt a
# few times a term is minutes nobody waits on.
COPY pyproject.toml ./
COPY app ./app
COPY scripts ./scripts
RUN pip install --no-cache-dir .

# The revisions and their config, although nothing imports them and the package
# metadata above deliberately excludes them.
#
# `app.main` only calls `create_all` for SQLite, so a deployment on Postgres has
# to be migrated by something — and without these two the image has no way to do
# it. `docker compose up` brought up an empty database, answered `/api/v1/health`
# green, and failed on the first ORM read: for the bot that is the middleware,
# i.e. every update at once. `alembic` is already a runtime dependency; what was
# missing was the revisions themselves.
COPY alembic.ini ./
COPY migrations ./migrations

# Not root. Nothing here needs a privilege — 8000 is above 1024, and the
# process only reads its code and talks to the network — and a process that is
# root inside a container is root to whatever a container escape reaches. The
# user owns /srv itself and nothing in it: the SQLite default
# (`sqlite+aiosqlite:///./lessons.db`, for a class run without the `db`
# service) has to be able to create its file there, and the code must not be
# writable by the process that runs it.
RUN useradd --system --no-create-home --shell /usr/sbin/nologin lessons \
    && chown lessons /srv
USER lessons

EXPOSE 8000

# One process runs both the read API and the Telegram long-poll loop.
CMD ["python", "-m", "uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000"]
