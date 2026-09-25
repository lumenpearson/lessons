# Building the APK

This page is about the APK, and one section before it is about everything else: how to get
from a clone to a server answering, a bot replying and that APK talking to both. If you
only want the file, skip to "Through GitHub Actions".

## From a clone to a working pair

The project is three things — a Python server that is also the Telegram bot, an Android app,
and a widget inside it — and the only one of them that needs configuring is the server.
Everything it reads is one file, `server/.env`, and `server/.env.example` is that file with
every variable in it, each one commented with what it does and what leaving it empty means.
Copy it; how much of it you then fill in is the next section, and for the first step the
answer is one line.

```bash
git clone https://github.com/lumenpearson/lessons && cd lessons/server
python3 -m venv .venv && .venv/bin/pip install -e ".[dev]"
cp .env.example .env
```

### Four steps, and you can stop after any of them

Each is worth doing before the next: everything in a later one depends on the earlier ones
working.

**1. The API alone, with no bot and no Telegram account.** Set `RUN_BOT=false` and change
nothing else. SQLite is the default and is a real database here: `python -m scripts.seed_demo`
makes a class with the join code `DEMO24`, and `uvicorn app.main:app --reload` serves it.
This is what the tests run against, and it is enough to develop every screen in the app.

**2. The bot, which is the admin panel.** `BOT_TOKEN` from
[@BotFather](https://t.me/BotFather), and `OWNER_IDS` — your own numeric id, from
[@userinfobot](https://t.me/userinfobot). Leave `RUN_BOT=true`: locally the bot **polls**,
so there is no webhook, no public address and no `WEBHOOK_SECRET` to think about. `/start`
then offers to create the first class. Two things go wrong here and both are quiet:
`OWNER_IDS` separates on a comma and on nothing else — a newline between two ids reads as
one unparsable value and leaves you with no rights at all — and a second copy of the server
running against the same token takes the updates away from the first, because Telegram
gives its updates to one consumer.

**3. The electronic diary, if you want that half.** `DIARY_SECRET`, any long random string —
`python -c "import secrets; print(secrets.token_urlsafe(48))"`. Empty is not a degraded
diary, it is no diary: the feature refuses at the door rather than storing the upstream's
session token in plaintext. With it set you also want `PUBLIC_BASE_URL`, because the
sign-in page is a link the bot has to be able to build, and from inside a Telegram update
there is no request to read a host from.

**4. The school search.** `DADATA_TOKEN`, the API key from
[dadata.ru](https://dadata.ru/profile/#info) — the API one, not the secret one. Empty
leaves typing the school's name by hand, which works. There is no bundled list to fall back
to on purpose: a snapshot would answer confidently with last year's schools and nothing on
the screen would say which of the two you were looking at.

The three that are **not** on this list — `WEBHOOK_SECRET`, `CRON_SECRET` and
`BOT_USERNAME` — are the ones a hosted deployment needs and a local run does not: the
webhook replaces polling, the tick replaces a scheduler nothing serverless has, and the
deep link needs to know the bot's @name. They are in the example file with the rest, and
what a deployment does about them is [deploy.md](deploy.md), "Secrets".

### Then check it, before the phone is anywhere near it

```bash
.venv/bin/python -m uvicorn app.main:app --reload
curl -s localhost:8000/api/v1/health     # {"status":"ok","api_version":1}
curl -s localhost:8000/api/v1/warmup     # the same, plus the schema revision
```

`health` opens no database connection, which is exactly why it is not the interesting one:
`warmup` does, and it is what tells you the schema and the code agree. A `degraded` answer
naming two revisions means the migrations have not been run — `alembic upgrade head`, from
a working copy and never from inside a request.

### And the app

```bash
cd ../android
./gradlew test               # every JVM test across the five modules
./gradlew assembleDebug
```

The Android SDK has to be real — its path in `android/local.properties` or in
`ANDROID_HOME` — and Gradle does not, because the wrapper is in the repository. Nothing
about the app has to be configured in a file: the address of the server is typed into the
app itself, and how to make it reachable from a phone is "Pointing the app at a server" at
the end of this page. The class code is the one the bot hands out with `/code`, or `DEMO24`
from the seeding script. The one build setting a **fork** has to think about is where the
first screen's terms and privacy policy link to — "The terms and the privacy policy the app
links", below.

### The other place variables live, and it is not `.env`

**GitHub Actions holds eight repository secrets and one repository variable of its own**
(Settings → Secrets and variables → Actions), and they overlap with `server/.env` by
exactly one name. They are nothing to do with running the project — none of them is read by
the server, and a fresh clone needs none of them to develop against. They exist because two
workflows do things a local build does not: sign an APK and say whose terms it links, and
call a deployed server.

| Name | Read by | Without it |
| --- | --- | --- |
| `KEYSTORE_BASE64` | `apk.yml` | the release APK is signed with the **debug** key; the workflow says so as a warning and carries on. It installs and must not be published — see "Signing" below |
| `KEYSTORE_PASSWORD` | `apk.yml` | as above: all four are needed together, and Gradle treats three of four as no key at all |
| `KEY_ALIAS` | `apk.yml` | as above |
| `KEY_PASSWORD` | `apk.yml` | as above |
| `LESSONS_GITHUB_CLIENT_ID` | `apk.yml` | «Войти через GitHub» is hidden, and with it the only way to file a bug report from inside the app |
| `LESSONS_CONTACT_EMAIL` | `apk.yml` | «Отправить письмом» is hidden |
| `SERVER_URL` | `reminders.yml` | the fallback tick skips with a notice rather than failing — see [deploy.md](deploy.md), "The clock" |
| `CRON_SECRET` | `reminders.yml` | the same skip |
| `LESSONS_LEGAL_BASE_URL` — a **variable**, not a secret | `apk.yml` | the APK links `docs/legal/` of the repository the workflow runs in — see "The terms and the privacy policy the app links" |

Four things about that table are worth more than the table.

**`ci.yml` reads no secret and no variable at all.** The gate — ruff, pytest, `./gradlew
test` and both assembles — needs nothing configured, which is why a pull request from a fork
runs the whole of it. The one consequence worth knowing: its APKs are built with the legal
link's default, so they link *this* repository's terms, whoever's CI built them.

**`CRON_SECRET` is one value living in two places, and nothing checks that they match.**
The server takes it from its own environment and compares it with the `X-Cron-Secret`
header; Actions sends it. Set them separately, mistype one, and the tick returns `403` on
a schedule while both halves look perfectly configured. The same is true of any external
cron service, which is what actually keeps the promise the bot makes — the workflow is
only a fallback.

**`SERVER_URL` is `PUBLIC_BASE_URL` under a different name**, or rather the address of the
same deployment: one is what the server tells the world about itself, the other is where
Actions goes looking. They can differ legitimately — a custom domain, say — so they are
two variables rather than one.

**`DIARY_SECRET` is deliberately not in that list**, though it looks like it belongs.
Nothing in Actions imports the server's code: the tests set their own key in
`tests/conftest.py`, and `reminders.yml` only calls an already-deployed endpoint over
HTTP. It belongs to whatever *runs* the app, which is Vercel's environment variables.

The signing four are set up in "Configuring your own key" further down, the two build
properties in "What the build is told about itself", and the variable in "The terms and the
privacy policy the app links".

## Through GitHub Actions

### Build one right now

**Actions → APK → Run workflow.** Nothing has to be pushed.

| Field | What it does |
| --- | --- |
| `build_type` | `release` (the default), `debug` or `both` |
| `version_name` | what to write as the versionName; empty means the value from `build.gradle.kts` |
| `version_code` | what to write as the versionCode; empty means this run's number |

The APK lands in the **lessons-apk** artifact on the run's page. **How long it stays is a
repository setting, not a workflow one** — Settings → Actions → General → "Artifact and log
retention". No workflow here asks for a number any more, because a number larger than that
setting is not honoured: it is silently reduced, and the only trace is a warning in the log
that reads like a build problem. This page promised ninety days for months while every run
was reducing it, which is the kind of claim a document is worst at catching.
`versionCode` defaults to the run number, which advances on its own, so two builds cannot be
confused with each other. Give `version_code` when the number has to be chosen rather than
inherited — it is the only version Android compares, and what is set in `build.gradle.kts`
never reaches a CI build, because the workflow passes its own.

Ordinary CI (`Actions → CI`) builds both debug and release on every push and pull request —
the artifacts `app-debug` and `app-release-unsigned-key`. Release is built always, not only
for a release: R8 and resource shrinking are the classic source of "it worked in debug and
not in the installed APK", and catching that on a pull request is cheaper than catching it
on users.

These used to ask for a week where `lessons-apk` asked for ninety days — short on purpose
for the CI ones, for the reason in "Actions minutes" below. Neither number survived the
repository's own retention setting, so both now leave it out and all of them live exactly
as long as that setting says. If the APK has to outlive its run, that setting is the thing
to raise, and raising it lengthens the CI artifacts too — which is the storage problem
"Actions minutes" describes, so raise it deliberately rather than by default.

### Cut a release

```bash
git tag v0.2.0
git push origin v0.2.0
```

A `v*` tag builds release, creates a GitHub Release with automatic release notes and
attaches the APK. The `versionName` is taken from the tag without the `v`.

### Actions minutes

**This repository is public, so the standard runners are not billed at all** — neither
minutes nor artifact storage. The section is kept not for accounting but because it holds
the measurements that made CI the shape it is — and because all of it comes back the day
the repository becomes private again.

On a private repository on the free plan there are 2000 minutes a month, and **each job is
billed separately, rounded up to a whole minute** (Linux, multiplier 1): a twenty-second
step costs a minute. Measured here:

| Job | Was | Billed | Became |
| --- | --- | --- | --- |
| What changed | 8 s | 1 | 7 s |
| Android (`test` + both assembles) | 6 min 47 s | 7 | — |
| Server (`ruff` + `pytest`) | 12 min 43 s | 13 | 3 min 56 s |
| **A full CI run** | | **21** | |

2000 minutes was about 95 full runs, and nearly two thirds of each went into one `pytest`
step. What stayed in CI for good:

- **`pytest -n auto`.** The suite spread across the runner's cores: 289 s → 101 s on four
  cores locally, 12:18 → 3:23 on the runner, the same 1340 green — 1340 was the count when
  this was measured, and the README's "Honest status" has today's. That is safe by
  construction rather than by luck: `tests/conftest.py` takes the SQLite path from an
  `mkdtemp` computed at import time, and every xdist worker is a separate process with its
  own import, so they never share a database. It started as a saving and stayed for the
  speed of the answer.
- **A short artifact life — but asked for in the repository's settings, not in a
  workflow.** Storage on the free plan is 500 MB, and one run put a 27 MB debug APK and a
  5 MB release APK there for ninety days (the default). After about fifteen runs, any
  upload fails with `Artifact storage quota has been hit` — a red job on a build that
  passed. That is exactly what happened, and it could not be cured from inside the
  workflow: only by deleting old artifacts or waiting for them to expire. Going public
  removed the problem — a public repository's storage is not billed — but the short life
  stayed, because privacy can come back and nobody needs the APK from a two-month-old pull
  request. What did **not** stay is the `retention-days:` that used to ask for it: this
  repository's own retention is lower than the week those lines requested, so every upload
  was silently reduced under a warning and the seven was a wish rather than a setting.
  `grep -rn retention .github/workflows/` now finds only the comments explaining that.
  Raising the setting is the only thing that lengthens an artifact, and it lengthens all of
  them at once — which is the storage problem this section is about. For the same reason as
  the short life, uploading the test reports is marked `continue-on-error`: the gate is
  `./gradlew test`, not where the report landed.
- **Path filters.** The "What changed" job decides in eight seconds which halves could
  possibly have broken, and a half runs when a file **its tests read** changed, not only a
  file in its own folder. So a change confined to `docs/` usually runs neither — but
  `docs/app/` and `docs/legal/` are packaged into the APK and run Android; `docs/build.md`,
  `docs/deploy.md`, `docs/diaries.md` and `docs/diaries/` are read by server tests and run
  the server; and the region catalog and the protocol vectors, which live under `server/`
  and are the phone's inputs too, run both. A commit touching only one of those documents
  used to run nothing and come back green (#159); `ci.yml` names each file beside the test
  that reads it. If the commit range cannot be worked out (a force push, a branch's first
  push), both run — a skipped build costs more than ten wasted minutes.

And one thing that did **not** stay: for a while, release was not built on a pull request.
That saved about two minutes per push at the cost of finding a broken R8 at the merge
rather than before it. The minutes stopped costing anything, and the check came back.

Separate from money are the clocks. `reminders.yml` asked for a tick every five minutes —
288 a day — and got 6.7: GitHub's schedules run on a best-effort basis. That is not about
the plan, it is about not being able to rely on an Actions schedule as a clock; the time is
kept by an external cron, see [deploy.md](deploy.md), "The clock". The workflow has not
stopped asking for five minutes: on a public repository that costs nothing, and how the
schedules behave here has yet to be measured again.

### If the repository becomes private again

Then the 2000 minutes come back, and the 500 MB, and the rule that once they run out no
workflow runs until the next billing period (the default spending limit is zero). The ways
out, besides everything above: do not build release on a pull request (≈2 minutes per
push); **your own runner** — `runs-on: self-hosted` and a machine switched on, with no
minutes billed at all; or pay, on the order of $0.008 per Linux minute.

**Your own runner and a public repository are incompatible.** A pull request from a fork
will run somebody else's code on your machine, and that is not a theoretical danger — it is
what GitHub writes a separate warning about. Either a public repository with GitHub's
runners, or a private one with your own.

### What else being public changes

- **Workflows run on pull requests from forks.** `ci.yml` is ready for that: it is
  `pull_request`, not `pull_request_target`, its permissions are `contents: read`, and it
  uses no secret. But `./gradlew` executes code from the pull request at build
  configuration time, so under **Settings → Actions → General → Fork pull request workflows
  from outside collaborators** it is worth choosing *Require approval for all outside
  collaborators*.
- **Anybody can download a run's artifacts** — here that is the debug APK, the release APK
  signed with the debug key, and the test reports. Only `apk.yml` on a tag signs with the
  real key, and only when the secrets are configured.
- **The whole history is visible, including what was removed from the working tree.** There
  are no credentials in it: `BOT_TOKEN`, `OWNER_IDS`, `WEBHOOK_SECRET` and `CRON_SECRET`
  always lived in the environment, the signing key in `LESSONS_KEYSTORE_*`, `.gitignore`
  covers `.env`, `*.keystore`, `*.jks`, `*.p12` and `keystore.properties`, and
  `server/.env.example` contains nothing but placeholders. But the production Neon
  endpoint's host and the project id did spend time in the tests and the documentation, and
  they cannot be taken out of the history. That is not a password — nobody connects without
  a role and a password — but the address is known, and the only thing now standing between
  the database and a stranger is that role's password. Changing it in the Neon console and
  updating `DATABASE_URL` in Vercel's environment variables is cheap and closes the
  question.

## Signing

With nothing configured, the release APK is signed with the **debug key**. It installs on a
phone and it survives R8 — so it is fine for testing — but it must not be published, and it
cannot be updated. The run summary states plainly which key was used.

An unsigned APK does not install at all, which is why the fallback is the debug key rather
than no key.

### A debug-signed build is never an update of another one

**Every build mints its own debug key.** `~/.android/debug.keystore` is generated by the
tooling on the machine that builds, the first time it is needed. The alias and both
passwords are the same everywhere — `androiddebugkey`, `android` — but the key pair behind
them is not: it is created locally and never shared. A GitHub runner is a fresh machine on
every run, and the Gradle cache covers `~/.gradle` rather than `~/.android`, so **each APK
run signs with a certificate that has never existed before**. Measured rather than assumed:
the APK from run 15 carries `CN=Android Debug` with `validFrom` two minutes into that run's
own build step.

Android compares the signing certificate before it looks at any version number. So
installing one debug-signed build over another is refused — «Приложение не установлено», with
no reason given, after offering to update, because the package name matches and only the
certificate does not. The version numbers have nothing to do with it: `versionName` is never
compared at all, and a higher `versionCode` does not help.

There are exactly two ways out, and the first is not a fix:

* **uninstall the old app and install the new one.** This erases everything the app keeps on
  the phone — the classes it is connected to, its device token, the diary session and any
  translation corrections — and the class has to be joined again with a class code or a
  personal code;
* **configure the four secrets below.** Then every build is signed with one key that is
  yours, updates install over each other for ever, and none of this arises again.

**The switch to your own key costs one uninstall, once.** Your key is not the debug key
either, so the first build signed with it cannot install over a debug-signed one any more
than two debug builds can, and it takes the same things with it — the classes, the device
token, the diary session, the corrections. It is the last time: every build after that
updates the one before it.

What publishing a debug-signed APK actually costs is worth being precise about, because it
is not what it looks like. Nobody can push a fake update over it: the private key it was
signed with was thrown away with the runner, so no one holds it — **including you**, which
is the first problem. The second is that a debug certificate carries no authorship: the
distinguished name is `CN=Android Debug, O=Android, C=US` on every machine on Earth, so
there is nothing in it that says the build is yours rather than somebody else's.

### Configuring your own key

> **A key is created on your own machine only.** No website that generates a keystore
> "online" will do: it would end up with your private key, and that key signs **every**
> update of the app — for ever. Android refuses to install an update signed with a different
> key, so a lost or leaked key can be neither replaced nor revoked. You would have to
> publish a new app under a different `applicationId`, and everybody who installed the old
> one would stay on it. There is no place for an online generator here, not even "just to
> try".
>
> Passwords are a different matter: those can come from a password manager. A leaked
> password means changing a password; a leaked key means changing the app.

1. Create the keystore. Once, outside the repository, and make a copy straight away.

   ```bash
   keytool -genkeypair -v -keystore release.jks \
     -alias lessons -keyalg RSA -keysize 4096 -validity 10000
   ```

   **On a phone, through Termux** — the same keytool, from any JDK:

   ```bash
   pkg update && pkg install -y openjdk-17
   keytool -genkeypair -v -keystore release.jks \
     -alias lessons -keyalg RSA -keysize 4096 -validity 10000
   ```

   A password can be generated there too, with nothing extra installed:

   ```bash
   head -c 24 /dev/urandom | base64
   ```

   `-validity 10000` is 27 years. There is no point in less: once the certificate expires
   there is nothing left to update the app with.

2. **The store password and the key password have to be the same.** A modern `keytool`
   creates the keystore in PKCS12 format, which does not support different passwords:
   keytool quietly keeps one and prints a warning that is easy to scroll past. If they
   diverge, the build fails at signing and the reason will not be obvious. Set one password
   and put it in both secrets.

3. Encode the keystore as base64 **on a single line**, and check it before pasting:

   ```bash
   base64 release.jks | tr -d '\n\r' > keystore.b64

   base64 -d keystore.b64 > check.jks
   cmp release.jks check.jks && echo "OK"      # must say OK
   wc -c keystore.b64                          # remember this number
   ```

   `tr` rather than `-w0`, because `-w0` is a GNU flag and Termux's `base64` is often
   toybox, which does not have it — and a flag that is not understood puts an error message
   where the key should be. What actually matters is that neither a carriage return nor a
   line break survives.

   **The check is not ceremony.** The commonest failure is a value truncated while copying
   several thousand characters out of a terminal, and it is silent: what you paste looks
   like base64, and the build stops at «Decode keystore» with no keystore. Compare `wc -c`
   with the length of what ended up in the field. Delete `check.jks` and `keystore.b64`
   afterwards - both are copies of your key.

   **On a phone, do not copy it at all.** Selecting several thousand characters in a
   terminal by hand is the thing that goes wrong; `termux-clipboard-set` is not the way
   round it either, because the `termux-api` package is only the command and it blocks for
   ever waiting on the Termux:API app, which is a separate install. Write the secret
   straight from the shell instead:

   ```bash
   pkg install gh && gh auth login      # «Login with a web browser» — the same device flow
   gh secret set KEYSTORE_BASE64 --repo <owner>/<repo> \
     --body "$(base64 release.jks | tr -d '\n\r')"
   gh secret list --repo <owner>/<repo>   # names and dates; GitHub returns no values
   ```

   Nothing is selected, so nothing can be truncated, and no copy of the key is left in a
   file. If `gh` is not wanted, copy from a text editor rather than from the terminal —
   `termux-setup-storage` then `cp keystore.b64 ~/storage/downloads/`, open it, select all —
   and delete that copy afterwards: shared storage is readable by anything on the phone.

4. Add four secrets under **Settings → Secrets and variables → Actions** (on the
   repository, not the organisation, if you are unsure):

   | Secret | Value |
   | --- | --- |
   | `KEYSTORE_BASE64` | the output of the previous command, whole, with no line breaks |
   | `KEYSTORE_PASSWORD` | the keystore's password |
   | `KEY_ALIAS` | `lessons` — whatever came after `-alias` |
   | `KEY_PASSWORD` | the same password as `KEYSTORE_PASSWORD` (see point 2) |

5. Save `release.jks` somewhere it will not disappear along with the phone. A GitHub secret
   is not a backup: its value cannot be read back, only overwritten.

**If «Decode keystore» fails**, the step says which of three things went wrong instead of
leaving you with `base64: invalid input` — which is what it said before, and which names
neither the secret nor this project.

| What it says | What it means |
| --- | --- |
| `KEYSTORE_BASE64 is set but … KEY_ALIAS is empty` | One of the other three secrets is missing. All four or none: Gradle falls back to the debug key with only a warning when any one of them is absent, which is how a public release once went out debug-signed under a summary calling it signed. |
| `… is not valid base64 … which is N more than a multiple of four` | Characters are missing from the end — two is the `=` padding, which is what a selection stopping one gesture early loses. This is the one that happens. |
| `… is not valid base64 … a multiple of four, so something inside it is not a base64 character` | The length is right and the content is not: a stray space, a shell prompt, an error message from a `base64` that did not understand a flag. |
| `… keytool cannot open it` | It decoded, and what came out is either not a keystore or does not open with `KEYSTORE_PASSWORD`. |

The last one is `keytool` opening the rebuilt file before the build starts, so a wrong
password or a base64 of the wrong file is caught in seconds rather than eight minutes later
inside Gradle, where the message blames the password whatever the cause.

You can check the result without cutting a release: **Actions → APK → Run workflow**. If
`KEYSTORE_BASE64` is not set, the run does not fail — it prints `::warning::` and signs with
the debug key. Pushing a tag with the secrets unconfigured fails deliberately, before the
build, so that a debug-signed APK cannot go out in a public release.

After that it is automatic. The workflow decodes the keystore into a temporary directory on
the runner rather than into the working copy, and deletes it before anything is uploaded —
so the key cannot leak through an artifact.

## Two buttons that are not in the build unless you say so

### What the build is told about itself

Five more optional properties, all of them facts about the build rather than secrets:
`LESSONS_BUILD_REPOSITORY`, `LESSONS_BUILD_REF`, `LESSONS_BUILD_COMMIT`,
`LESSONS_BUILD_NUMBER` and `LESSONS_BUILD_TIME`. They become the badges on «О
приложении», and they exist because **a build keeps no memory of the checkout that
produced it** — an APK on a phone cannot work out which repository, branch or commit it
came from, so the only thing that can say is whatever ran it.

`apk.yml` fills them from the runner's own `github.repository`, `github.ref_name`,
`github.sha` and `github.run_number`, plus a UTC timestamp it generates in the build step.
From the runner rather than from repository settings on purpose: a build of a fork or of a
branch then describes itself honestly instead of repeating this repository's name. A local
build sets them in `~/.gradle/gradle.properties` as `lessons.build.repository` and so on,
or sets none of them — every badge hides itself when its value is empty, and the card says
«Собрано вручную» instead of drawing a row of blanks.

None of them is secret. On a public repository the name, the ref and the commit are public
by definition, and they are exactly the three facts somebody holding a phone needs in order
to match it against a pull request.

`LESSONS_GITHUB_CLIENT_ID` and `LESSONS_CONTACT_EMAIL` are optional, and each one is a
button. Without the first, «Войти через GitHub» is not on «О приложении» at all — and with
it goes the only way to file a bug report from inside the app, because that is what the
account is for. Without the second, «Отправить письмом» is not on the bug-report sheet.
Nothing in the app explains either absence: the row about a build the reader did not make
would be a row about the wrong subject, which is a deliberate decision and is written down
in `docs/design.md`.

**The workflow did not pass either of them for its whole life**, so every APK it has built
has had both switched off, and the first anybody knew of it was looking for a button that
was never there. It passes them now, from repository secrets of the same names, and the run
summary says «on» or «off» for each — which is where to look next time a button is missing.

### Registering the OAuth App

**Settings → Developer settings → OAuth Apps → New OAuth App**, on your own account. The
form asks for more than the device flow uses:

| Field | What to put |
| --- | --- |
| Application name | What the reader sees on GitHub's confirmation page. A parent is going to read it, so «Дневник» tells them more than «Lessons API» does. |
| Homepage URL | Required by the form, unused by the device flow. The repository's own address will do. |
| Authorization callback URL | Also required, also unused: the device flow never redirects. The same address again. |
| **Enable Device Flow** | **Tick it.** Without it `POST /login/device/code` refuses, and nothing else on the page shows that anything is wrong. |
| **Expire user access tokens** | **Leave it unticked.** See below — this one costs a day. |
| Client secret | **Do not generate one.** |

Then copy the **Client ID** from the top of the app's page — `Ov23li…`, twenty characters —
into the repository secret `LESSONS_GITHUB_CLIENT_ID`.

**«Expire user access tokens» must be off, and the reason is in this codebase rather than in
GitHub's.** Ticking it makes GitHub hand out an access token that dies after eight hours,
together with a `refresh_token` to replace it. `AccessTokenDto` in
`core/data/.../github/GithubDtos.kt` reads `access_token`, `token_type`, `scope` and
`error`; there is no `refresh_token` field anywhere in `:core:data`, and the only
`expires_in` in the project is the *device code's* fifteen minutes. So the refresh token is
parsed into nothing and dropped. Eight hours later `/user` answers 401, the repository does
what it is written to do with a revoked token — forgets it — and the reader is back at
«Войти через GitHub». Every day. Nothing logs why, and the symptom looks like a bug in
sign-in rather than a checkbox on a web page.

**There is no client secret, on purpose.** The device flow is the only OAuth grant that
works without one, and it was chosen for exactly that: the app is handed out as an APK, and
anything inside an APK can be read by unzipping it. A leaked client secret would let
somebody act as this OAuth App, including revoking other people's tokens. The price of
having none is written down in `docs/design.md` — the token cannot be revoked from inside
the app; «Выйти» only forgets it, and the real revocation is on
https://github.com/settings/applications.

The client id is *not* a secret by the same argument — it ships in every APK. It is in the
repository's secrets because it is the builder's to give, not because it has to be hidden.
`public_repo` is the whole scope the app asks for: enough to open an issue or a pull request
on a public repository, and nothing else.

### The address for «Отправить письмом»

`LESSONS_CONTACT_EMAIL` is an address you are willing to receive bug reports at. Nothing
derives it; you choose it. It is not in the source because an address in a public repository
is an address on every spam list — so if you would rather not publish your own, a separate
mailbox is the answer, not leaving it empty.

### Setting them for a local build

Both are ordinary build properties: `lessons.github.clientId` and `lessons.contactEmail` in
`~/.gradle/gradle.properties`, or the environment variables above.

## The terms and the privacy policy the app links

The first screen of the first run says «Продолжая, вы принимаете Условия использования и
Политику конфиденциальности» under its «Продолжить» button, always, and «О приложении» has a
row for each document. Both names are links, and where they lead is a build setting:
`LESSONS_LEGAL_BASE_URL` in the environment or `lessons.legal.baseUrl` in
`~/.gradle/gradle.properties`, compiled into `BuildConfig.LEGAL_BASE_URL`.

**What it is.** The `https://` address of a folder holding `terms.ru.md`, `terms.en.md`,
`privacy.ru.md` and `privacy.en.md` — the four files of [docs/legal/](legal/). The app
appends `/terms.ru.md` and the like, Russian for a Russian reader and English for everybody
else, and opens the result in the browser when the phone has a validated network and a
browser starts. Otherwise — offline, behind a captive portal, no browser — it shows the copy
the APK carries: `docs/legal/` is an asset folder of `:app`, so the bundled text is the
repository's own bytes at build time, in a sheet that names its edition and date from
`docs/legal/legal.json`.

**What the build refuses.** A value that is not `https://`, has no host, or carries a query
or a fragment fails the build with a message pointing here, and so does a quote, a backslash
or a space, which would break the generated Java string. It is refused rather than kept
because it is the first link of every install, and a policy fetched over plain HTTP is one
anybody on the café's network can rewrite before it is read. A trailing slash is trimmed; a
blank value counts as unset.

**The default is not empty:** `https://github.com/lumenpearson/lessons/blob/HEAD/docs/legal`,
this repository's folder on its default branch (GitHub resolves `blob/HEAD` to it). That is
so a build from a fresh clone links a real, current document rather than nothing — and it
means **a build that sets nothing links this repository's operator's texts.** A local
`./gradlew`, and every APK `ci.yml` builds, is such a build.

**`apk.yml` works it out per repository.** Its «Resolve legal documents» step derives
`https://github.com/<owner>/<repo>/blob/<default branch>/docs/legal` for whichever repository
the workflow runs in (`HEAD` when the event carries no default branch), so a public fork's
APK links the fork's own folder with nothing configured. The repository **variable**
`LESSONS_LEGAL_BASE_URL` (Settings → Secrets and variables → Actions → **Variables**)
overrides the derivation — a variable rather than a secret because the value is public and
the run summary prints it, which a masked secret would not allow. The step checks the value
in seconds, before the compile: whitespace, a quote, a backslash or anything that is not
`https://` fails it. On a **private** repository it warns, because the derived address opens
for nobody outside the repository; set the variable to somewhere that does. The summary's
«Terms and privacy policy» row says which address was used and where it came from. The
default branch rather than the branch or tag being built, because an installed APK keeps its
link for life: a feature branch is deleted within a week, and a tag would freeze the policy
at the day it was cut.

**What every fork has to do.** Set the address to its **own** `docs/legal/` — nothing for a
public fork's `apk.yml`, the variable for a private one, the property for any build made by
hand. Then rewrite the texts themselves: the address changes where the link goes and not a
word of what it says, and until the passages marked `FORK` are rewritten the fork's copy still
names this repository's maintainer as the operator, and this deployment's hosting, database
and country. The fields each passage needs are listed at the top of
`docs/legal/privacy.ru.md` and `docs/legal/terms.ru.md`. The Russian file is the source and the
English one its translation, held level section for section by `LegalDocumentsTest`; a change
to what the code stores, sends or keeps changes the texts and raises the `edition` in
`legal.json`.

**Not verified.** That the default address answers was checked on 25 September 2026 by
fetching it, not by a tap on a phone; that `github.event.repository.default_branch` is filled
on a manual run rests on GitHub's documentation; no run of `apk.yml` with this step has been
seen; neither the browser hand-off nor the bundled sheet has been opened on a device. The texts
describe what this code does and were never reviewed by a lawyer.

## Locally

```bash
cd android
./gradlew assembleDebug     # app/build/outputs/apk/debug/
./gradlew assembleRelease   # app/build/outputs/apk/release/
```

To sign locally with your own key, put this in `~/.gradle/gradle.properties` (a file
outside the repository, where secrets usually go):

```properties
lessons.keystore.file=/absolute/path/release.jks
lessons.keystore.password=...
lessons.key.alias=lessons
lessons.key.password=...
```

The same four values are read from the environment variables `LESSONS_KEYSTORE_FILE`,
`LESSONS_KEYSTORE_PASSWORD`, `LESSONS_KEY_ALIAS` and `LESSONS_KEY_PASSWORD` — which is what
CI uses.

## The bundled typeface is two files

The app is set in **Google Sans Flex for Latin and digits and Onest for Cyrillic**, chained
by coverage. Both are committed under `android/core/designsystem/src/main/res/font/`, both
are SIL Open Font License 1.1, and both carry one variable axis, `wght`, which is the one
the app varies.

| | bytes | code points | what it draws |
| --- | --- | --- | --- |
| `google_sans_flex.ttf` | 268 000 | 516 | Latin, digits, punctuation |
| `onest.ttf` | 193 056 | 780 | Cyrillic, and Latin behind it |

**Why two.** Google Sans Flex declares no Cyrillic at all — not a dropped subset; its
coverage on Google Fonts is latin, latin-ext, vietnamese, math, symbols and five scripts
nobody here writes. This app's product language is Russian. So while it was the only
bundled file, every Russian word was drawn by whatever face the device fell back to, beside
digits drawn from the bundle: two typefaces in one row, at different x-heights, and nothing
failed or was logged. It had been that way since the design system was taken from
Essentials, which is an English app.

**How the chain is built.** `FallbackTypeface.kt` puts the two files in one
`Typeface.CustomFallbackBuilder` — Latin first, Cyrillic behind it, the system behind both —
and hands it to Compose through `AndroidFont`, one per weight. Nothing else can express
this: a Compose `FontFamily` of several fonts picks between them by weight and style, and a
font-family XML does the same; only the platform's fallback chain chooses by which file can
draw the character in hand. `CustomFallbackBuilder` is API 29 and this app's floor is 26, so
**Android 8.0 and 9.0 are set in Onest alone** — correct, and merely less like itself. That
is the right way round: the older phone loses the Latin face, not its own alphabet.

**Google Sans Flex is frozen before it is committed.** Upstream it carries six axes and
3.81 MB, of which 3.41 MB is `gvar` — one set of outline deltas per axis per glyph. The app
moves `wght` and nothing else, so the other five are frozen at their defaults and the file
is 0.26 MB. That is a one-off done by hand, not a build step: a Gradle task did it for one
batch, and with both files now down to a single axis there is nothing left for it to do.
`./gradlew` on a fresh clone needs nothing but the JDK and the SDK.

```bash
# What produced the committed file, if the typeface is ever updated:
fonttools varLib.instancer GoogleSansFlex.ttf \
  opsz=18 wdth=100 GRAD=0 ROND=0 slnt=0 -o google_sans_flex.ttf
```

**What holds it.** `FontAxisTest` fails if the bundled files between them cannot draw the
Russian alphabet, if either carries an axis `Type.kt` never varies, or if a file is bundled
that nothing names — each proved red by breaking it. `FontLicenceTest` reads each file's own
`name` table and requires a notice under `assets/licenses/` carrying that copyright and that
licence, so a typeface cannot be swapped without its licence following it.

## What the environment needs

| | |
| --- | --- |
| JDK | 21 |
| Android SDK | compileSdk 37, minSdk 26 |
| Gradle | through the wrapper, 9.7.1 |

The wrapper and its jar are in the repository, so `./gradlew` works on a fresh clone with
no Gradle installed, and nothing else has to be on the machine.

## Pointing the app at a server

The app needs an address the **phone** can reach, not the computer. `localhost` and
`127.0.0.1` do not work in the app: to a phone, those are the phone.

1. Start the server so that it listens on more than the loopback:

   ```bash
   cd server
   .venv/bin/python -m uvicorn app.main:app --host 0.0.0.0 --port 8000
   ```

   `--host 0.0.0.0` is mandatory. By default uvicorn listens on `127.0.0.1` and is visible
   only to the computer itself.

2. Find the computer's address on the local network:

   ```bash
   ip -4 addr | grep -oP '(?<=inet )192\.168\.[0-9.]+'   # Linux
   ipconfig getifaddr en0                                 # macOS
   ipconfig                                               # Windows, the IPv4 line
   ```

3. Check from the phone: open `http://<address>:8000/api/v1/health` in a browser. It should
   answer `{"status":"ok","api_version":1}`. If it does not open, the problem is the
   network rather than the app: the phone and the computer have to be on the same Wi-Fi,
   and the firewall has to let port 8000 through.

4. In the app, press «Адрес сервера» under «Сервер» — on the first run's «Как
   подключиться?» screen, at the bottom of the class-code screen, or later in «Настройки →
   Синхронизация» — and enter `http://<address>:8000/`. Then enter the class code. The app
   has no server of its own to fall back to: until an address is typed, there is none.

The bot hands out a class code with `/code`, and `python -m scripts.seed_demo` creates a
demo class with the code `DEMO24`.

### Why HTTP and not HTTPS

Since Android 9 the system blocks `http://` by default. The app allows it through
`network_security_config.xml`, because the real scenario is a server in the same school,
reachable at a local address, and public CAs do not issue certificates for an IP on a
private network. If the school has configured TLS, enter `https://` — the configuration does
not apply to such an address.

What travels over a plain `http://` wire is **not** harmless, and this page used to say it
was — «only a class code and a timetable: no passwords, no personal data». The diary
**password** is not on it, from this app: the app signs in to the diary itself, straight at
the diary's own origin, which comes from the region catalog bundled in the APK and is
`https://` or dropped; the client that makes that request refuses any other host
(`OriginGuard`) and follows no redirect, so nothing typed into the server field can send the
password anywhere near it. What does cross an `http://` server, readable by anybody on the
same Wi-Fi, is:

- the diary **session** the diary handed back, sent once at registration
  (`POST /api/v1/diary/session`) — a working key to the child's diary until it expires;
- the **diary bearer** on every diary read, and the marks, homework and timetable in the
  answers;
- the **class bearer**, which is a write token on a phone connected with a personal code or
  linked to Telegram, along with the phone's name and the class's timetable.

Two routes still carry a **password** through the server, and over `http://` they carry it
in the clear: the older `POST /api/v1/diary/login`, kept for the APKs built before
registration, and the bot's sign-in page, which posts the password to the server at
`PUBLIC_BASE_URL` — in the clear if that address is `http://`. Neither is on this app's path.
Before anything is typed, the app's diary sign-in warns when the server address is `http://`
(«⚠️ Адрес сервера начинается с http://…»), and the warning is about the session, not the
password; the configuration file's own comment carries the same list.
