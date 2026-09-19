# Security policy

## Supported versions

There has been no public release yet. Until `v1.0.0`, only the current state of the `main`
branch is supported; an APK from an old Actions run is neither updated nor fixed.

| Version | Supported |
| --- | --- |
| `main` | yes |
| APKs from earlier runs and tags | no |

## How to report a vulnerability

**Do not open a public issue.** The private channel is
[Report a vulnerability](https://github.com/lumenpearson/lessons/security/advisories/new).

In the report, state:

- what is affected — `server/app/api`, `server/app/bot`,
  `server/app/providers/petersburg`, `android/…` or the build;
- the commit or the version (`versionName` is visible in the app, under "About");
- the steps to reproduce and the impact you expect;
- whether you want attribution in the advisory.

We aim to acknowledge receipt within 72 hours and to name a plan for the fix once it is
reproduced.

**Do not send real tokens, class codes or electronic-diary passwords.** Replace them with
`<redacted>`; that does not get in the way of reproducing the bug, and a token that reaches
a conversation has to be revoked either way.

## What counts as a vulnerability

These are invariants, not wishes. Breaking one is a vulnerability even if the app goes on
working.

### Who may change what

- **The owner comes only from the environment.** `OWNER_IDS` is read from `.env` and cannot
  be changed from inside the bot, so nobody can be promoted up to the owner role.
- **Nobody grants a role equal to their own or above it** (`app/bot/roles.py`,
  `can_grant`). The same function answers an access request through `/request`, so that an
  approval cannot route around the rule.
- **Every step checks again.** The state of a multi-step form lives in the database and is
  driven by the user's client, so a step does not trust the one before it.
- **A phone has no rights of its own.** A device is bound to a Telegram account by a code,
  and any write from the phone is judged by that account's role **at the moment of the
  request** (`services/linking.py`). Revoke somebody in the bot and the next press in the
  app gets a 403.

### Secrets

- Device tokens are random 256 bits, shown once and stored only as SHA-256
  (`app/security.py`). The database holds no token in the clear.
- The Petersburg electronic-diary password **is not stored anywhere, in any form**: it is
  needed for one request, after which the service's own session is what lives on. There is
  no column for a password even in the revision that added diary sessions (`0004`).
- Client addresses are not stored: the rate-limit counter counts by hash
  (`client_bucket`).
- `BOT_TOKEN`, `WEBHOOK_SECRET`, `CRON_SECRET` and `DATABASE_URL` live in the host's
  environment or in `server/.env`, which is not in the repository. So does the keystore
  that signs the APK: it comes from `LESSONS_KEYSTORE_*` or from
  `~/.gradle/gradle.properties`.

### The webhook and the reminder tick

- Without `WEBHOOK_SECRET` the webhook endpoint **is not mounted at all**. Telegram signs
  nothing: it sends JSON, and the bot trusts the `user_id` inside that JSON when deciding
  who may edit a timetable. With no secret, anybody who guesses the URL sends a forged
  update with somebody else's id — that is not spam, that is taking the bot over.
- The comparison goes through `hmac.compare_digest`, so the secret cannot be recovered from
  how long the answer takes.
- An empty `CRON_SECRET` means `/api/v1/cron/tick` refuses everyone. A tick anybody can
  fire is a way to make the bot write to every subscriber on demand.

### Guessing a class code

An attempt to join a class is checked against **every** class at once, so the rate limit
here is part of the model rather than an optimisation. The counter lives in the database
(on serverless a process remembers nothing), a correct code is not counted against it, and
`X-Forwarded-For` is honoured for only as many proxies as `TRUSTED_PROXY_HOPS` declares:
the header is written by the client, and taking it at its word means everyone picks their
own bucket. New codes are eight characters with no `O`, `0`, `I` or `1`.

### The app

- Plain HTTP is allowed on purpose (`res/xml/network_security_config.xml`): the real
  scenario is a school's server on a local address, and public CAs do not issue
  certificates for an IP on a private network. What travels over that wire is a class code
  and a timetable. This configuration does not touch an address with `https://`, and user
  certificates are not trusted, so nobody can install their own CA and intercept a
  configured TLS connection.
- Signing in through GitHub uses the device flow, with no client secret: an APK cannot keep
  a secret. The client id is set at build time, and without it the sign-in line is not
  shown.
- The `SCHEDULE_EXACT_ALARM` permission is not requested, and the app degrades to a
  one-minute window when exact alarms are unavailable.

## Out of scope

- A release APK built without a key is signed with the **debug** key. It installs and it
  survives R8, but it must not be published: the debug key is the same for everybody. This
  is documented in [docs/build.md](../docs/build.md) and the run summary says so — it is a
  known limitation, not a vulnerability.
- The Petersburg electronic diary is somebody else's undocumented service. Its own problems
  are not ours; our boundary is `server/app/providers/petersburg/`, and a vulnerability in
  that (leaking somebody else's session, reaching for somebody else's `student_id`) is
  worth reporting to us.
- Somebody with administrator access to the bot can ruin a timetable. That is a role, not a
  vulnerability; the vulnerability would be their getting that role around `can_grant`.
