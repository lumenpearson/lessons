# Six ways to deploy: where the core runs, and how the diary is reached

Agreed with the owner on 10 October 2026. It corrects the programme of
`docs/specs/2026-10-03-one-contract-design.md` (§2 «The two targets», §6 the console, and §8 the
order) and the pending «phone reads the diary» design.

## Why

- **Vercel's addresses are blocked or blacklisted by many Russian mobile operators.** On those
  networks, a phone in Russia may not reach a Vercel deployment at all.
- **The electronic diaries are state services, and they refuse traffic from abroad.** So the
  server's calls to a diary need an egress inside Russia. Today that is the Squid proxy on RUVDS,
  behind `DIARY_PROXY_URL`, used by the Petersburg diary alone.

Neither problem has one answer that suits every family, so the project offers several, chosen per
deployment.

## Two axes, six scenarios

**Where the core runs:**
- **a VPS:** Selectel or RUVDS, the host target `python -m app.host` and the root `Dockerfile`;
- **Vercel:** today's deployment;
- **the app itself.**

**How the server reaches a diary:**
- **a proxy** on RUVDS, or on any host from which state services are not blocked;
- **the relay:** the phone fetches the diary's answers from its own Russian address and hands them
  to the server;
- **nothing in between**, when the core is in the app and the phone is already the one asking.

| # | Core | Diary through | Who it suits |
| --- | --- | --- | --- |
| 1 | own VPS | proxy on RUVDS | the default for families in Russia: both hops stay inside the country |
| 2 | Vercel | proxy on RUVDS | whoever can reach Vercel |
| 3 | own VPS | the relay (the phone) | a VPS that does not run, or cannot reach, a proxy |
| 4 | Vercel | the relay (the phone) | whoever can reach Vercel and wants no proxy |
| 5 | the app | — (the phone asks directly) | one phone, no server at all |
| 6 | the app | proxy on RUVDS | the same, where the phone's own network cannot reach the diary |

## What each piece needs

**A core on a VPS (1, 3).** The host target exists since stage 3c: the image, HTTP/2, native gRPC
and the stream. A recipe is still missing:
- the image, with PostgreSQL on the same VPS (Neon is in AWS Frankfurt, and its reachability from
  Russia is not assumed);
- TLS in front of the host, for example Caddy with Let's Encrypt;
- the Telegram webhook pointed at it;
- the external cron's tick pointed at it.

That is `docs/deploy.md`'s «Option 3», made concrete for Selectel and RUVDS.

**A core on Vercel (2, 4).** Unchanged. `docs/deploy.md` says plainly who it does not suit.

**A proxy for every diary (1, 2, 6).** `DIARY_PROXY_URL` routes Petersburg alone; «Сетевой
город» is called directly. It should carry every provider in `providers/diary/registry.py`'s
table. The tick's self-check already asks the proxy whether it answers.

**The relay (3, 4).** The owner chose relaying over «the phone reads the diary itself», so every
diary feature keeps working the same in every scenario. Parsing, corrections, statistics and the
bot's diary all stay on the server. The phone only fetches and forwards.

The owner asked for the connection's protection to be designed in, not added later:
1. **The phone fetches only what the server's provider table names.** That means an allow-listed
   origin and a path per read. The server never hands the phone a URL, so the relay can never
   become an open proxy, from either end. `UpstreamHttp`'s origin allow-list is that list's
   starting point.
2. **The upstream credential stays on the phone in relay mode.** The server never stores the
   diary's session, the one secret today sealed with `DIARY_SECRET`. What the server stores
   instead is the relayed answers, under the family's diary token.
3. **TLS only, both legs.**
   - The phone reaches the diary over HTTPS.
   - The phone reaches our server over HTTPS, with cleartext refused by the network security
     config.
   - Pinning our server's certificate is considered, and decided in the relay's own design.
4. **A relayed answer is untrusted input**, exactly as a direct one is today. It is size-capped
   (`UpstreamHttp`'s 2 MiB), parsed by the same mappers, and refused whole when it does not parse.
5. **A relayed answer is bound to its sender.** It is accepted only under the diary token of the
   session whose pupils it lists. A phone can relay its own family's diary and nobody else's. It
   is rate-limited like any other diary call.
6. **No background reads.** A relayed read happens while the app is open, so a stale diary is said
   to be stale; it is never filled in the background. The server's keep-alive does not exist in
   relay mode, and the capabilities say so (decision «What a server or a diary cannot do, it
   says»).
7. **The privacy policy changes before the relay ships.** It must say what the server holds in
   relay mode: answers, not credentials. `docs/legal/` is product text, Russian first.

**A core in the app (5, 6).** The class's rules are rewritten in Kotlin over Room. That covers the
timetable, bells, substitutions, special days, homework, tasks and the diary's reads, parsing and
corrections. This is the owner's choice over embedding the Python server: no interpreter in the
APK. The cost is two implementations of the same rules, held level by shared fixtures (one JSON
corpus that both the Python and the Kotlin suite read). There is no bot in this scenario, because
a bot needs a server. There is no sharing between phones either: each phone is its own class.
Scenario 6 adds the RUVDS proxy for a phone whose network cannot reach the diary.

## How a scenario is chosen

- **On the server, the deployment is the choice.** Where the core runs is where it was deployed.
  How a diary is reached is one setting, `DIARY_EGRESS = direct | proxy | relay`, read beside
  `DIARY_PROXY_URL`. It is reported in the diary's capabilities, so the app learns it rather than
  guessing it.
- **The app has two builds.**
  - The standard build covers scenarios 1–4. It is told its server's address at the first run, by
    the invite link or the class code's QR, or typed under «Для разработчиков». It learns the
    egress from the capabilities, and relays when told to.
  - The standalone build covers scenarios 5–6, with the core inside. It is a separate flavor, so
    the other build does not carry its weight.

## What changes in the programme

| Sub-project | Before | After |
| --- | --- | --- |
| 3, the targets | «where the host lives is not decided» | decided: Selectel or RUVDS for scenarios 1 and 3. The host stays deployable anywhere. Done otherwise (#396) |
| (any time) | — | **the proxy for every diary** and **the VPS recipe**: small, independent, and wanted first, because scenario 1 is the default for families in Russia |
| 4, Android decomposition | unchanged | unchanged |
| 5, Android transports | three bindings, `RemoteError`, the new APK, v1 deleted | the same, plus **the server's address at run time** and **the relay**: the phone's side, the server's endpoint in the contract, and the protections above. The privacy policy is updated with it |
| 6, the build console | Vercel and the local host target | **the Deploy tab knows the scenarios**: build and check each, the VPS over SSH, the proxy's health, the egress setting. It still never deploys production by itself |
| **7, the core in the app (new)** | — | the class's rules in Kotlin over Room, the diary read on the phone, and the standalone build (scenarios 5 and 6). After the console, as the owner decided |

## Decisions this supersedes

- **«The phone never talks to dnevnik2»** (`docs/architecture.md`, and the commit that wrote it).
  It holds for scenarios 1 and 2 and no longer for 3–6. Both architecture.md and api.md must say
  so when the relay lands.
- **The pending «phone reads the diary» design.** It becomes the relay of scenarios 3 and 4, and
  the reads of sub-project 7.
- **«Where the host lives is not decided»** is decided.

## Not decided yet

- Pinning our server's certificate in the relay: weighed against how a family's own VPS
  certificate gets rotated.
- How a VPS deployment updates itself on a merge to `main`: by an Action over SSH, or a pull on the
  VPS. Production on Vercel is a merge, and a VPS should not be a second, unreviewed way in.
- Whether Neon is reachable enough from Russia to serve scenario 1, or PostgreSQL on the VPS is
  the only recipe.
- Whether a standalone phone (5, 6) can share its class later, by export and import or by joining
  a server afterwards.
