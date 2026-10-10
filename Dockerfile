# The host target (server/app/host.py): the whole app over HTTP/2 — v1, v2 over
# REST, Connect and native gRPC, WatchClass's stream, the webhook and the
# tick — as one container, chosen instead of Vercel rather than beside it
# (docs/specs/2026-10-05-server-v2-design.md, decision 13; docs/deploy.md,
# «Option 3: the host target»). Built from the repository root, because it
# installs Vercel's lock, which is there, beside the host's:
#
#     docker build -t lessons-host .
#
# server/Dockerfile is the other image, docker-compose.yml's: uvicorn, long
# polling, no marker — a deployment configured by hand, not this one.
FROM python:3.12-slim

# LESSONS_TARGET is the deployment's statement about itself, as VERCEL is
# Vercel's: with it the server refuses to start without what a deployment
# needs (app/config.py, get_settings). The image says it; app.host never does.
ENV PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1 \
    PIP_NO_CACHE_DIR=1 \
    LESSONS_TARGET=host \
    HOST=0.0.0.0 \
    PORT=8000

WORKDIR /srv

# The two locks first, so that a change to the code does not install them
# again: Vercel's, and the host's, compiled against it so that the two cannot
# disagree. Nothing is resolved here that one of them does not pin.
COPY requirements.txt ./requirements.txt
COPY server/requirements-host.txt ./requirements-host.txt
RUN pip install --no-cache-dir -r requirements.txt -r requirements-host.txt

# The package itself, and not its dependencies: pyproject.toml's floors would
# bring what the locks leave out on purpose — uvicorn, aiosqlite and alembic,
# none of which the host runs. Migrations are applied from a workstation, as
# for Vercel (docs/deploy.md).
COPY server/pyproject.toml ./
COPY server/app ./app
COPY server/scripts ./scripts
RUN pip install --no-cache-dir --no-deps .

# Not root: nothing here needs a privilege — 8000 is above 1024, and the
# process reads its code and talks to the network — and a process that is root
# in a container is root to whatever a container escape reaches.
RUN useradd --system --no-create-home --shell /usr/sbin/nologin lessons
USER lessons

EXPOSE 8000

# One process, one instance: a stream hears what this process writes
# (server/app/watch.py), so a second replica would serve streams that miss
# every change made through the first.
CMD ["python", "-m", "app.host"]
