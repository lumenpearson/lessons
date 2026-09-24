# Documentation

Nine documents, the reference pages one of them keeps in a folder of its own, and one folder
the app itself reads. Each answers its own question, and none retells a neighbour — if the
answer is not here it is in the code, and there is usually a link to it.

## Where to start

| You | Read |
| --- | --- |
| use the app | [guide.md](guide.md) |
| keep a class's timetable | [guide.md](guide.md), then [bot.md](bot.md) |
| want to run the whole thing locally | [build.md](build.md), "From a clone to a working pair" |
| want to build the APK | [build.md](build.md) |
| are deploying the server | [deploy.md](deploy.md) |
| are writing code | [architecture.md](architecture.md), then [CLAUDE.md](../CLAUDE.md) and [CONTRIBUTING.md](../CONTRIBUTING.md) |
| are writing a client for the API | [api.md](api.md) |
| want to connect a diary other than Петербург's | [diaries.md](diaries.md) |

## Every document

| Document | About |
| --- | --- |
| [guide.md](guide.md) | first run, the class code, the widget, notifications, the bot, the diary, the translation-correction mode — for whoever uses the app |
| [bot.md](bot.md) | roles, invitation by phone number, editing the timetable, every command, what a card may say before Telegram refuses it |
| [widget.md](widget.md) | twelve sizes, seven states, the update schedule, why not a tick once a minute |
| [build.md](build.md) | standing the project up from a clone and how much of `server/.env` each step needs, the eight secrets Actions holds and what reads them, building the APK in Actions and locally, signing with your own key, a release from a tag, Actions minutes and where they go, the bundled typeface, pointing the app at a server |
| [deploy.md](deploy.md) | Vercel plus Neon or your own server, the webhook, migrations, why the server has no clock of its own |
| [api.md](api.md) | the whole `/api/v1` contract: reads, writes, class management, the Petersburg diary |
| [architecture.md](architecture.md) | why the bot is the backend, the timetable resolution model, the five Android modules, the service layer, the tests |
| [design.md](design.md) | the design system: what was taken from Essentials, what was fixed, and the reasoning behind every visible decision in the interface |
| [diaries.md](diaries.md) | which electronic diary every region of Russia runs in September 2026, how a client signs in to each platform, and what that means for a second provider; **not about this project's own code** — the survey a future provider is written from |
| [diaries/](diaries/) | **the reference pages of [diaries.md](diaries.md)**: one page per platform with its hosts, sign-in flows, headers and the full route table read out of the open-source clients, and one page with the evidence for every region. Nothing in them has been tried against a live diary |
| [app/](app/) | **not a document — the guide the app draws.** `guide.ru.md` is the source, `guide.en.md` the translation, `manifest.json` says which version they are and which app version they describe. The app fetches these files from this repository and falls back to the copy built into the APK |

Everything written about this project is English — these documents, the code comments, the
commit messages and the pull request descriptions — so that anybody can read it. The
product itself is a different matter: the app and the bot speak Russian, and the reader
chooses the language in the app. Where a document quotes a button, a menu path or an error
the user will actually see, it quotes it in Russian, because that is what is on the screen.

## Where to look for what

- **How time works** — [architecture.md](architecture.md), the "Time" section: time is
  stored as the school's wall clock, and the time zone belongs to the class rather than to
  the server.
- **Why the widget does not refresh once a minute** — [widget.md](widget.md), "Updates".
- **Why the server has no scheduler** — [deploy.md](deploy.md), the "The clock" section.
  The clock is an external cron, and `.github/workflows/reminders.yml` is only a fallback:
  GitHub's schedules delivered 6.7 ticks a day out of the 288 asked for.
- **What a CI run costs, and what a public repository changes** — [build.md](build.md),
  "Actions minutes" and the sections after it.
- **Which variable goes where** — three places, and they are not the same list.
  `server/.env.example` carries every setting the server reads, each with what empty means;
  [deploy.md](deploy.md), "Secrets", is the eleven a hosted deployment needs;
  [build.md](build.md), "The other place variables live", is the eight GitHub Actions holds
  for signing and for the fallback tick. `CRON_SECRET` is the one name in two of the three,
  and nothing checks that the two values match.
- **The two different tokens** — [api.md](api.md): the device token comes from
  `POST /api/v1/join`, the diary token from `POST /api/v1/diary/login`, and neither implies
  the other.
- **Who lets a phone into a class** — [api.md](api.md), "Who lets a phone in: the class
  code or the bot", and [bot.md](bot.md), "Who lets a phone in": a class has two join
  modes, and in one of them the class code opens nothing.
- **Why one corner is rounder than another** — [design.md](design.md), "A corner inside a
  corner": the inner radius plus the padding equals the outer one, in the two forms the
  answer is known in, and the four places that deliberately do not follow it.
- **What is not verified** — the "Honest status" section of the [README](../README.md). It
  is kept on purpose and updated together with the code.
- **What the app's own documentation is written in** — [app/guide.ru.md](app/guide.ru.md),
  whose opening comment states the whole format. It is a deliberately small subset of
  Markdown: `##` is a page, `-` a list, `1.` with a bold lead a step, `>` an aside. The
  parser is `DocsMarkdown` in `:core:data`, and `DocsGuideParityTest` holds the two
  languages to the same pages in the same order — the job `ResourceTranslationTest` does
  for the text that is still in `values/`.

## The rules these documents follow

A document that lies is worse than a missing one. So:

- a statement is checked against the code before it lands here;
- a change that made a document wrong fixes it in the same batch of work —
  [bot.md](bot.md) and [widget.md](widget.md) have each been rewritten over drifting away
  from the code, and that is more expensive than keeping up;
- what is not done is called not done.
