FROM python:3.12-slim

ENV PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1 \
    PIP_NO_CACHE_DIR=1

WORKDIR /srv

COPY pyproject.toml ./
RUN pip install --no-cache-dir .

COPY app ./app
COPY scripts ./scripts

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

EXPOSE 8000

# One process runs both the read API and the Telegram long-poll loop.
CMD ["python", "-m", "uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000"]
