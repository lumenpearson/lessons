# Building the APK

## Through GitHub Actions

### Build one right now

**Actions → APK → Run workflow.** Nothing has to be pushed.

| Field | What it does |
| --- | --- |
| `build_type` | `release` (the default), `debug` or `both` |
| `version_name` | what to write as the versionName; empty means the value from `build.gradle.kts` |
| `version_code` | what to write as the versionCode; empty means this run's number |

The APK lands in the **lessons-apk** artifact on the run's page and is kept for 90 days.
`versionCode` defaults to the run number, which advances on its own, so two builds cannot be
confused with each other. Give `version_code` when the number has to be chosen rather than
inherited — it is the only version Android compares, and what is set in `build.gradle.kts`
never reaches a CI build, because the workflow passes its own.

Ordinary CI (`Actions → CI`) builds both debug and release on every push and pull request —
the artifacts `app-debug` and `app-release-unsigned-key`. Release is built always, not only
for a release: R8 and resource shrinking are the classic source of "it worked in debug and
not in the installed APK", and catching that on a pull request is cheaper than catching it
on users.

The only difference from `lessons-apk` is how long it lives: the CI artifacts last a week,
this one ninety days, because it exists to be handed to somebody. Why a week is in "Actions
minutes" below.

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
  this was measured; it is 1559 today. That is safe by
  construction rather than by luck: `tests/conftest.py` takes the SQLite path from an
  `mkdtemp` computed at import time, and every xdist worker is a separate process with its
  own import, so they never share a database. It started as a saving and stayed for the
  speed of the answer.
- **Artifacts live a week, not ninety days.** Storage on the free plan is 500 MB, and one
  run put a 27 MB debug APK and a 5 MB release APK there for ninety days (the default).
  After about fifteen runs, any upload fails with `Artifact storage quota has been hit` — a
  red job on a build that passed. That is exactly what happened, and it could not be cured
  from inside the workflow: only by deleting old artifacts or waiting for them to expire.
  Going public removed the problem — a public repository's storage is not billed — but the
  week instead of ninety days stayed, because privacy can come back and nobody needs the
  APK from a two-month-old pull request. For the same reason, uploading the test reports is
  marked `continue-on-error`: the gate is `./gradlew test`, not where the report landed.
- **Path filters.** The "What changed" job decides in eight seconds which halves could
  possibly have broken; a change confined to `docs/` runs neither. If the commit range
  cannot be worked out (a force push, a branch's first push), both run — a skipped build
  costs more than ten wasted minutes.

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

## The typeface is compressed at build time

The app is set in Google Sans Flex, and the file as it was downloaded is 3.81 MB — more than
all the code in the release APK. Almost none of that is letters. It is a variable font with
six variation axes, and an axis costs one set of outline deltas per glyph in the `gvar`
table: at six axes that table was **3411 KB of the 3811 KB**, while the outlines themselves
were 31 KB. The app moves two of the six — it varies `wght` and sets `ROND` to 100 — so the
other four are four fifths of the download for shapes nothing ever asks for.

So the font is not a resource in this repository, it is a **source**. It lives at
`android/core/designsystem/fonts/google_sans_flex.ttf`, exactly as downloaded, and a Gradle
task freezes the axes you do not ask to keep and writes the result into the variant's
generated resources, where `R.font.google_sans_flex` finds it. Freezing an axis is not
dropping a feature: the glyphs are redrawn at the value it is frozen at and all 657 are
kept. What is lost is the ability to ask for a different value later, which is why the axes
are a setting rather than a decision the build makes for you.

The setting is `lessons.font.axes`, and the sizes below were measured on one tree, three
builds:

| `-Plessons.font.axes=` | the font | release APK | needs |
| --- | --- | --- | --- |
| `wght,ROND` — the default | 0.29 MB | 3.60 MB | Python with `fonttools` |
| `wght` | 0.27 MB | 3.58 MB | the same |
| `all` | 3.81 MB | 5.71 MB | nothing |

`wght` is not the default, although it is smaller. It bakes `ROND` in at 100, which is what
every screen asks for today, so it looks free — and the day somebody wants a less rounded
cut, the code will ask for an axis that no longer exists. Android does not complain about
that: it draws the default and logs nothing. Fifteen kilobytes is not worth a setting that
can stop working silently.

`all` is the escape hatch, and it is a correct build — the file that ships is the file that
was downloaded, to the byte. It costs 2.1 MB of APK.

**Installing the tool.** One line, on Python 3.10 or newer (which is what
`fonttools` 4.65 asks for):

```bash
python3 -m pip install fonttools
```

If neither `python3` nor `python` has it, the build stops with a message naming the flag
rather than a stack trace out of a missing module. To stop passing the flag, put it in
`~/.gradle/gradle.properties`, beside the signing values above:

```properties
lessons.font.axes=all
```

Both Android workflows install `fonttools==4.65.0` and build at the default, because a CI
run exists to build what ships. It is pinned for the reason every version here is pinned:
that is the one the sizes above were measured with.

**When you update the typeface**, replace the file under `fonts/` with the new download and
change nothing else. That is the whole reason this is a build step: the instanced file used
to be committed, and the obvious way to update a font is to download it again — which would
have put 2.1 MB back into the APK without anything failing. `FontAxisTest` now fails instead,
in both directions: an axis the code asks for that the shipped font does not declare, and an
axis the shipped font carries that the build was not told to keep.

## What the environment needs

| | |
| --- | --- |
| JDK | 21 |
| Android SDK | compileSdk 37, minSdk 26 |
| Gradle | through the wrapper, 9.7.1 |
| Python | 3.10+ with `fonttools`, for the typeface — or `-Plessons.font.axes=all` |

The wrapper and its jar are in the repository, so `./gradlew` works on a fresh clone with
no Gradle installed. Python is the one thing that is not: see the section above for what the
build does with it and how to do without.

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

4. In the app, press **Сервер** at the bottom of the connection screen and enter
   `http://<address>:8000/`. Then enter the class code.

The bot hands out a class code with `/code`, and `python -m scripts.seed_demo` creates a
demo class with the code `DEMO24`.

### Why HTTP and not HTTPS

Since Android 9 the system blocks `http://` by default. The app allows it through
`network_security_config.xml`, because the real scenario is a server in the same school,
reachable at a local address, and public CAs do not issue certificates for an IP on a
private network. What travels over that wire is only a class code and a timetable: no
passwords, no personal data. If the school has configured TLS, enter `https://` — the
configuration does not apply to such an address.
