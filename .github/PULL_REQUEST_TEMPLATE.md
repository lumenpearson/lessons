# Description

<!-- What changes and why. Link an issue: Closes #123 -->

## What is affected

- [ ] `server/app/api` — the client-facing API
- [ ] `server/app/bot` — the bot
- [ ] `server/app/services` — the rules the bot and the API share
- [ ] `server/app/providers/petersburg` — the electronic diary
- [ ] `server/migrations` — the database schema
- [ ] `android/core/model` — the models and the state engine
- [ ] `android/core/data` — Room, network, sync, notifications
- [ ] `android/core/designsystem` — theme and components
- [ ] `android/widget` — the widget
- [ ] `android/app` — the screens
- [ ] `docs/`, `README.md`
- [ ] `.github/` — build and automation

## Boundaries

Tick what you checked. If something is broken deliberately, say why.

- [ ] `app/schedule.py` still imports neither FastAPI nor aiogram
- [ ] The rule did not appear in two places: the bot and `/api/v1/manage` call one
      function from `app/services/`, not two copies of one decision
- [ ] A model change comes with an Alembic revision (the head of the chain is
      `app/db.py:EXPECTED_REVISION`, currently `0013`; no `create_all` after `0001`)
- [ ] Nothing is scheduled "in process" on the server: nothing runs between requests on
      Vercel, and everything on a clock goes through `/api/v1/cron/tick`
- [ ] "Now" is taken in the class's time zone — `school_class.tz` on the server,
      `Timetable.nowAtSchool()` on the client — and not the server's or the phone's
- [ ] `:core:data` does not depend on `:widget`
- [ ] Every new Russian string has its English twin in `values-en/`
- [ ] There is no token, class code, password or keystore path in the diff

## Verification

<!--
Paste the REAL output of the commands, not the phrase "everything passes".
The summary lines with the numbers are enough.
-->

```text
cd server && ruff check app tests scripts migrations →
cd server && python -m pytest -q                      →
cd android && ./gradlew test                          →
```

If `android/` changed:

- [ ] `./gradlew assembleDebug`
- [ ] `./gradlew assembleRelease` — R8 and resource shrinking break what worked in debug,
      and on a pull request that is cheaper than in an installed APK
- [ ] Checked on a device or an emulator <!-- model and Android version -->
- [ ] The widget checked at two sizes at least <!-- which -->

## What is NOT covered

<!--
A mandatory section. "Written, never run" is an honest status and is worth writing down.
A claim that something was verified when it was never run is not.
If the change fixes something the README names in "Honest status", update that too.
-->

## Documentation

- [ ] `docs/` updated, if the change made a document wrong
- [ ] `CLAUDE.md` updated, if commands, modules or boundaries changed
- [ ] `README.md` updated, if what the user sees changed
- [ ] No update needed
