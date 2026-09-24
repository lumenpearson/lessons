# The electronic diaries of Russia's regions

Which electronic diary the schools of each federal subject run, how a client signs in to it,
and which routes the open-source clients of each platform call. This is the map a second
provider would be written from, in the way `server/app/providers/petersburg/` was written from
the open clients of `dnevnik2.petersburgedu.ru` — and it is a map, not a contract: **nothing in
it has been tried against a live diary.** Every diary host refuses a connection from the
sessions this project is built in, so every route, header and parameter here was read out of
somebody else's code, a package archive or a help page, and each one names where it was read.

Written in September 2026 for #130. The date matters more than usual. The school year that
began on 1 September 2026 moved a first wave of regions onto the federal ТОР «Моя школа», and
several more changed platform in the two years before it; a region's answer here is its answer
in September 2026, and the previous system is kept beside it.

## How to read it

**Three questions, three places.** «Which diary does region X use» is the table in
[The regions](#the-regions), with the evidence for every row in [diaries/regions.md](diaries/regions.md).
«What is that platform and how does a client get in» is the table in
[The platforms](#the-platforms). «Which routes does it expose» is that platform's page under
[diaries/](diaries/), one page per platform, each with its hosts, its sign-in flows step by
step, the headers the clients send, the full route table, the data formats, the regional
hosts, the sources read and the caveats.

**The mandatory regions are in bold** in the regions table: Санкт-Петербург, Москва, and the
eleven regions the МЭШ-based app «Моя Школа» offers when it asks «Выберите регион» —
Вологодская, Калужская, Карелия, Московская, Мурманская, Башкортостан, Татарстан, Дагестан,
Тверская, Тюменская области and республики, and ЯНАО. Each of them had a deep check of its own
on top of the ordinary two passes.

**Confidence means one thing: how good the source is.** *High* is the regional portal itself,
the ministry, the operator or the vendor. *Medium* is a news item, a school's own site, or a
maintained client whose code plainly does the thing. *Low* is indirect. A route's **status** is
a different axis: *current* is what a maintained client calls today, *legacy* is a generation
the code, its author or the operator has marked as old, and *uncertain* was seen in one place
only — a single unmaintained client, a string in a decompiled app, a link nobody requests.
**A route seen in one place is a lead, not a fact**, and the tables say which is which. Rows an
extractor proposed and no source carries are listed under «refuted» on each page, so that
nobody proposes them again.

**Russian names stay Russian.** Systems, hosts and quoted text appear as they do on the screen
— «Сетевой город. Образование», «Госуслуги Моя школа», «Выберите регион» — and the prose around
them is English, like everything else written about this project.

## How it was made

The method is the one the Petersburg provider used, applied to the whole country and written
down, because the next person to extend it should be able to repeat it.

1. **Region by region.** For each of the 89 federal subjects, searches in Russian for the
   system its schools use, followed to the regional portal, the ministry or a dated news item,
   with the sentence that names the system quoted as evidence. A second, independent pass was
   told to refute the first, and overruled or completed it for more than a quarter of the
   regions — mostly migrations the first pass had missed. The thirteen mandatory regions had a third pass of their own.
2. **Finding the clients.** Four independent sweeps: GitHub topic and repository-search pages;
   package registries (PyPI, npm, GitLab, Codeberg, crates.io, NuGet); searches for the
   hostnames and path fragments themselves; and articles, vendor pages and reverse-engineering
   write-ups. Merged, and completed by a second round on the gaps, they gave the platforms that
   have a page under [diaries/](diaries/).
3. **Platform by platform.** Every client was cloned or downloaded and read in full, and its
   HTTP calls were tabulated. A second reader grepped every proposed row back to the source,
   struck what was not there and added what was missed; a third read the newest client, the
   repositories' issues and the vendor's pages to date each row and to move routes that belong
   to a neighbouring platform back where they belong.
4. **A completeness critique** read the whole and named what was missing, and a second round
   went after its list. That round covered the official list of ТОР's first wave, the 2026/27
   status of every region with no dated source, and the edges of the mandatory regions. Two
   platforms have no open client worth reading, so their official apps were read instead:
   «Госуслуги Моя школа» for ТОР, and «ЭПОС» for ЭПОС.Школа. What could be closed was closed;
   the rest is in [What is not covered](#what-is-not-covered).

What the method cannot do is the thing that matters most to whoever builds on it: **it cannot
tell a route that works from a route that worked in 2023.** The clients are dated on each
platform's page for exactly that reason, and the first thing a provider author should do is
open the diary once with a real account — #121 says the same about the one provider that
exists.

## The shape of the country

**Four large groups and a long tail.** Counting each region once for the system its families
use as the diary in September 2026:

| Platform | Regions |
| --- | --- |
| [«Сетевой город»](diaries/netschool.md) | 20 |
| [ТОР «Моя школа»](diaries/myschool-federal.md), switched on 1 September 2026 | 20 |
| [Дневник.ру](diaries/dnevnik-ru.md) | 13 |
| [«Моя школа»](diaries/mesh-myschool.md) (МЭШ), plus [МЭШ](diaries/mesh-moscow.md) itself and [ЭПОС.Школа](diaries/epos.md) | 11 + 1 + 1 |
| [the «one.» platform](diaries/one-x1.md) | 8 |
| [ЭлЖур](diaries/eljur.md) as a region's system, some under the region's own name | 6, and single schools elsewhere |
| [БАРС](diaries/bars.md) | 3 |
| a system of one region — [Санкт-Петербург](diaries/petersburg.md), [Кузбасс](diaries/ruobr.md), [Красноярский край](diaries/kiasuo.md), [Белгородская](diaries/virtual-school.md), Пензенская | 1 each |
| no electronic diary this year — Севастополь, on paper since 1 September 2026 with its ЭлЖур-based system suspended | 1 |

**The map moved under this survey.** On 1 September 2026 the twenty pilot regions on the
official list — the Госуслуги parents' channel published it, and the ЛНР's ministry pointed to it
— made ТОР «Моя школа» their diary, reached only through «Госуслуги Моя школа»; the ДНР runs it
in test operation, with paper kept beside it for two months. Almost every one of them left a
platform that has open clients: «Сетевой город» and ЭлЖур lost six regions each, БАРС five,
Дневник.ру four, and Свердловская's ЕЦП and Ярославская's «Дневник76» chain went with them; only
Брянская left a system with none, «Виртуальная школа». Two regions that news had counted in did
not switch: Липецкая keeps ЭлЖур with the app in front of it, and Оренбургская calls September a
phased transition while its «one.» portal still runs. Everywhere else the app is an option in
front of the regional system, except in Запорожская and Херсонская, where it is not offered at
all. Before all this, the МЭШ-based «Моя школа»
had taken over from Дневник.ру in Московская (2023) and Дагестан (2024), from БАРС in Карелия,
Вологодская, Мурманская and Тюменская, from edu.tatar.ru in Татарстан (2024) and from ELSCHOOL
in Башкортостан (2026). БАРС is the platform that has shrunk the most: 14 regions list
it as a previous system.

**What that means for a client author** is in [What it means for this project](#what-it-means-for-this-project):
«Сетевой город», Дневник.ру and the МЭШ family between them cover
46 regions with three code shapes; ТОР
covers 20 with one more, known only from its own app and closed behind
Госуслуги's sign-in.

## The regions

Bold is a mandatory region. «Checked» says how far the row was verified: *deep check* for the
mandatory regions, *confirmed* or *corrected* where the independent second pass agreed with the
first or overruled it, *unresolved* where neither could settle it. *In the second round* (or
*second round* after *deep check*) marks a row the round on the critique's gaps went back to,
and its verdict is that round's. The evidence for every row —
the quoted sentence and its URL — is in [diaries/regions.md](diaries/regions.md).

| Region | In use now (September 2026) | Moving to | Previous | Confidence | Checked |
| --- | --- | --- | --- | --- | --- |
| Республика Адыгея | [Дневник.ру](diaries/dnevnik-ru.md) `dnevnik.ru`, `login.dnevnik.ru` | — | — | high | confirmed |
| Республика Алтай | [ТОР «Моя школа»](diaries/myschool-federal.md) `gosuslugi.ru/school` | — | [«Сетевой город»](diaries/netschool.md) `sgo.altaiobr04.ru`, `poo.altaiobr04.ru` (until 31 August 2026); [БАРС](diaries/bars.md) `sosh.mon-ra.ru`, `spo.mon-ra.ru` (until about 2022) | high | corrected in the second round |
| **Республика Башкортостан** | [«Моя школа» (МЭШ)](diaries/mesh-myschool.md) `dnevnik.edurb.ru` | — | [ELSCHOOL](diaries/elschool.md) `elschool.ru`, `api.elschool.ru` (primary until 2025/26) | high | deep check |
| Республика Бурятия | [«Сетевой город»](diaries/netschool.md) `deti.obr03.ru`; [ЭлЖур](diaries/eljur.md) `maou-37.eljur.ru` | — | — | high | confirmed |
| **Республика Дагестан** | [«Моя школа» (МЭШ)](diaries/mesh-myschool.md) `myschool.05edu.ru`, `education.05edu.ru` | — | [Дневник.ру](diaries/dnevnik-ru.md) `dnevnik.ru`, `login.dnevnik.ru` (until 2024) | high | deep check |
| Донецкая Народная Республика | [ТОР «Моя школа»](diaries/myschool-federal.md) `gosuslugi.ru/school`; [ЭлЖур](diaries/eljur.md) `donschool47.eljur.ru`, `*.eljur.ru` | — | — | medium | corrected in the second round |
| Республика Ингушетия | [«Сетевой город»](diaries/netschool.md) `sgo.edu-ri.ru`, `poo.edu-ri.ru`; [ЭлЖур](diaries/eljur.md) `ri-sch2.eljur.ru`, `ri-sch3.eljur.ru` | — | — | medium | corrected in the second round |
| Кабардино-Балкарская Республика | [ТОР «Моя школа»](diaries/myschool-federal.md) `gosuslugi.ru/school` | — | [БАРС](diaries/bars.md) `school.07.edu.o7.com` (until 31 August 2026 as the families' diary) | medium | corrected in the second round |
| Республика Калмыкия | [Дневник.ру](diaries/dnevnik-ru.md) `dnevnik.ru`, `08.dnevnik.ru` | — | [«Сетевой город»](diaries/netschool.md) `sgo.monrk.ru` | high | corrected in the second round |
| Карачаево-Черкесская Республика | [«Сетевой город»](diaries/netschool.md) `sgo.kchgov.ru`, `poo.kchgov.ru` | — | — | medium | confirmed |
| **Республика Карелия** | [«Моя школа» (МЭШ)](diaries/mesh-myschool.md) `karelia.minedu.ru` | — | [БАРС](diaries/bars.md) `school.karelia.ru`, `college.karelia.ru` («Электронная школа» from 01.01.2018) | high | deep check |
| Республика Коми | [«Сетевой город»](diaries/netschool.md) `giseo.rkomi.ru`; [«Госуслуги Моя школа»](diaries/myschool-federal.md) as a front end `gosuslugi.ru/school` | — | — | high | corrected |
| Республика Крым | [ЭлЖур](diaries/eljur.md) `edu.rk.gov.ru`, `*.eljur.ru` | — | — | high | confirmed |
| Луганская Народная Республика | [ТОР «Моя школа»](diaries/myschool-federal.md) `gosuslugi.ru/school` | — | [ЭлЖур](diaries/eljur.md) `lnr0487.eljur.ru`, `lnr0523.eljur.ru` (2024/25 pilots) | high | corrected in the second round |
| Республика Марий Эл | [«Сетевой город»](diaries/netschool.md) `sgo.mari-el.gov.ru` | — | — | medium | confirmed |
| Республика Мордовия | [«Сетевой город»](diaries/netschool.md) `sgo.e-mordovia.ru` | — | — | high | confirmed |
| Республика Саха (Якутия) | [«Сетевой город»](diaries/netschool.md) `sgo.e-yakutia.ru` | — | — | high | corrected |
| Республика Северная Осетия — Алания | [Дневник.ру](diaries/dnevnik-ru.md) `dnevnik.ru`, `15.dnevnik.ru` | — | — | high | corrected in the second round |
| **Республика Татарстан** | [«Моя школа» (МЭШ)](diaries/mesh-myschool.md) `ms-edu.tatar.ru`, `school-edu.tatar.ru`; [«Госуслуги Моя школа»](diaries/myschool-federal.md) as a front end `gosuslugi.ru/school` | — | [edu.tatar.ru](diaries/edu-tatar.md) `edu.tatar.ru` (until January 2024) | high | deep check |
| Республика Тыва | [Дневник.ру](diaries/dnevnik-ru.md) `dnevnik.ru`, `17.dnevnik.ru` | — | [БАРС](diaries/bars.md) `school.rtyva.ru` (c. 2015 – c. 2022) | high | corrected in the second round |
| Удмуртская Республика | [ТОР «Моя школа»](diaries/myschool-federal.md) `gosuslugi.ru/school` | — | [БАРС](diaries/bars.md) `es.ciur.ru` (2014 – 31 August 2026) | high | corrected in the second round |
| Республика Хакасия | [БАРС](diaries/bars.md) `school.r-19.ru` | — | — | high | confirmed in the second round |
| Чеченская Республика | [Дневник.ру](diaries/dnevnik-ru.md) `dnevnik.ru`, `login.dnevnik.ru` | — | [ЭлЖур](diaries/eljur.md) `mvd1.eljur.ru`, `gimn12.eljur.ru` | high | corrected in the second round |
| Чувашская Республика | [ТОР «Моя школа»](diaries/myschool-federal.md) `gosuslugi.ru/school` | — | [«Сетевой город»](diaries/netschool.md) `net-school.cap.ru`; Е-услуги. Образование | high | confirmed in the second round |
| Алтайский край | [«Сетевой город»](diaries/netschool.md) `netschool.edu22.info`, `neteducation.edu22.info` | — | — | high | confirmed |
| Забайкальский край | [«Сетевой город»](diaries/netschool.md) `region.zabedu.ru`; Е-услуги. Образование `es.zabedu.ru`; [«Госуслуги Моя школа»](diaries/myschool-federal.md) as a front end `gosuslugi.ru/school` | — | — | high | corrected in the second round |
| Камчатский край | [«Сетевой город»](diaries/netschool.md) `school.sgo41.ru`, `sgo41.ru`; Е-услуги. Образование; [«Госуслуги Моя школа»](diaries/myschool-federal.md) as a front end `gosuslugi.ru/school` | — | — | high | corrected in the second round |
| Краснодарский край | [ТОР «Моя школа»](diaries/myschool-federal.md) `gosuslugi.ru/school` | — | [«Сетевой город»](diaries/netschool.md) `sgo.rso23.ru` | medium | corrected in the second round |
| Красноярский край | [КИАСУО](diaries/kiasuo.md) `dnevnik.kiasuo.ru`, `pwa.kiasuo.ru` | — | [ЭлЖур](diaries/eljur.md) `*.eljur.ru` (2017) | high | confirmed |
| Пермский край | [ЭПОС.Школа](diaries/epos.md) `school.permkrai.ru`, `epos.permkrai.ru` | — | Web2edu `web2edu.ru` (about 2010) | high | confirmed |
| Приморский край | [«Сетевой город»](diaries/netschool.md) `sgo.prim-edu.ru` | — | — | medium | confirmed |
| Ставропольский край | [«one.» platform](diaries/one-x1.md) `one.stavminobr.ru`, `portal.stavminobr.ru` | — | — | high | confirmed |
| Хабаровский край | [Дневник.ру](diaries/dnevnik-ru.md) `dnevnik.ru`, `27.dnevnik.ru` | — | — | high | corrected in the second round |
| Амурская область | [«Сетевой город»](diaries/netschool.md) `region.obramur.ru`, `portal.obramur.ru`; [«Госуслуги Моя школа»](diaries/myschool-federal.md) as a front end `gosuslugi.ru/school` | — | [Дневник.ру](diaries/dnevnik-ru.md) `dnevnik.ru`, `login.dnevnik.ru` (until 2 August 2021) | high | confirmed |
| Архангельская область | [Дневник.ру](diaries/dnevnik-ru.md) `dnevnik.ru`, `29.dnevnik.ru`; [ЭлЖур](diaries/eljur.md) `3329.eljur.ru`, `sulfat51.eljur.ru` | — | — | high | corrected in the second round |
| Астраханская область | [ТОР «Моя школа»](diaries/myschool-federal.md) `gosuslugi.ru/school` | — | [Дневник.ру](diaries/dnevnik-ru.md) `dnevnik.ru`, `login.dnevnik.ru/login/esia/astrakhan` (until the start of 2024/25); [ЭлЖур](diaries/eljur.md) `one.astrobl.ru`, `dnevnik.astrobl.ru` (2024/25 – 2025/26) | medium | corrected in the second round |
| Белгородская область | [«Виртуальная школа»](diaries/virtual-school.md) `vs.belregion.ru`, `belgorod.vsopen.ru`; [«Госуслуги Моя школа»](diaries/myschool-federal.md) as a front end `gosuslugi.ru/school` | — | — | high | corrected in the second round |
| Брянская область | [ТОР «Моя школа»](diaries/myschool-federal.md) `gosuslugi.ru/school` | — | [«Виртуальная школа»](diaries/virtual-school.md) `bryansk.vsopen.ru`, `obr.b-edu.ru` (until the end of 2025/26) | medium | corrected in the second round |
| Владимирская область | [БАРС](diaries/bars.md) `школа.образование33.рф`, `xn--80atdl2c.xn--33-6kcadhwnl3cfdx.xn--p1ai`; [«Госуслуги Моя школа»](diaries/myschool-federal.md) as a front end `gosuslugi.ru/school` | — | — | medium | corrected in the second round |
| Волгоградская область | [«Сетевой город»](diaries/netschool.md) `sgo.volganet.ru`; [«Госуслуги Моя школа»](diaries/myschool-federal.md) as a front end `gosuslugi.ru/school` | — | — | medium | confirmed in the second round |
| **Вологодская область** | [«Моя школа» (МЭШ)](diaries/mesh-myschool.md) `dnevnik.edu35.ru`; [БАРС](diaries/bars.md) `ssuz.vip.edu35.ru` | — | [БАРС](diaries/bars.md) `school.vip.edu35.ru` (2014) | high | deep check |
| Воронежская область | [ТОР «Моя школа»](diaries/myschool-federal.md) `gosuslugi.ru/school` | — | [Дневник.ру](diaries/dnevnik-ru.md) `dnevnik.ru`, `36.dnevnik.ru` (at least 2017 – 2025/26) | high | confirmed in the second round |
| Запорожская область | [«one.» platform](diaries/one-x1.md) `one.umnik-zo.ru`, `passport.umnik-zo.ru` | — | — | high | corrected in the second round |
| Ивановская область | [Дневник.ру](diaries/dnevnik-ru.md) `dnevnik.ru`, `37.dnevnik.ru`; [ЭлЖур](diaries/eljur.md) `iklp.eljur.ru`; [«Госуслуги Моя школа»](diaries/myschool-federal.md) as a front end `gosuslugi.ru/school` | — | — | high | corrected in the second round |
| Иркутская область | [Дневник.ру](diaries/dnevnik-ru.md) `login.dnevnik.ru/login/esia/irkutsk`, `38.dnevnik.ru`; [ЭлЖур](diaries/eljur.md) `fec.eljur.ru`, `sh28irk.eljur.ru`; [«Госуслуги Моя школа»](diaries/myschool-federal.md) as a front end `gosuslugi.ru/school` | — | — | high | corrected in the second round |
| Калининградская область | [ТОР «Моя школа»](diaries/myschool-federal.md) `gosuslugi.ru/school` | — | [ЭлЖур](diaries/eljur.md) `keo.gov39.ru`, `klgd.eljur.ru` (by December 2023 on keo.gov39.ru) | high | confirmed in the second round |
| **Калужская область** | [«Моя школа» (МЭШ)](diaries/mesh-myschool.md) `education.admoblkaluga.ru`, `dnevnik.admoblkaluga.ru`; [«Госуслуги Моя школа»](diaries/myschool-federal.md) as a front end `gosuslugi.ru/school` | — | [«Сетевой город»](diaries/netschool.md) `edu.admoblkaluga.ru:444`, `es.admoblkaluga.ru` (until the move to «Моя школа») | high | deep check |
| Кемеровская область — Кузбасс | [«Электронная школа 2.0»](diaries/ruobr.md) `ruobr.ru`, `cabinet.ruobr.ru`; [«Госуслуги Моя школа»](diaries/myschool-federal.md) as a front end `gosuslugi.ru/school` | — | — | high | corrected in the second round |
| Кировская область | [«one.» platform](diaries/one-x1.md) `one.43edu.ru`, `passport.43edu.ru`; [«Госуслуги Моя школа»](diaries/myschool-federal.md) as a front end `gosuslugi.ru/school` | — | «Аверс» (until 2022) | medium | corrected in the second round |
| Костромская область | [ТОР «Моя школа»](diaries/myschool-federal.md) `gosuslugi.ru/school` | — | [«Сетевой город»](diaries/netschool.md) `netschool.eduportal44.ru` (until 31 August 2026) | medium | corrected in the second round |
| Курганская область | [ЭлЖур](diaries/eljur.md) `eschool.gov45.ru`; [«Госуслуги Моя школа»](diaries/myschool-federal.md) as a front end `gosuslugi.ru/school` | — | — | high | corrected in the second round |
| Курская область | [ТОР «Моя школа»](diaries/myschool-federal.md) `gosuslugi.ru/school` | — | [ЭлЖур](diaries/eljur.md) `edu.kurskobr.ru` (2023) | high | confirmed in the second round |
| Ленинградская область | [«Сетевой город»](diaries/netschool.md) `e-school.obr.lenreg.ru`, `obr.lenreg.ru`; [«Госуслуги Моя школа»](diaries/myschool-federal.md) as a front end `gosuslugi.ru/school` | — | [Дневник.ру](diaries/dnevnik-ru.md) `47.dnevnik.ru` (until the 2019/20 school year at the latest) | high | corrected in the second round |
| Липецкая область | [ЭлЖур](diaries/eljur.md) `edu.schools48.ru`, `schools48.ru`; [«Госуслуги Моя школа»](diaries/myschool-federal.md) as a front end `gosuslugi.ru/school` | — | [БАРС](diaries/bars.md) `schools48.ru` (before January 2025) | medium | corrected in the second round |
| Магаданская область | [БАРС](diaries/bars.md) `eschool.49gov.ru`, `openschool.49gov.ru`; [«Госуслуги Моя школа»](diaries/myschool-federal.md) as a front end `gosuslugi.ru/school` | — | — | high | corrected in the second round |
| **Московская область** | [«Моя школа» (МЭШ)](diaries/mesh-myschool.md) `authedu.mosreg.ru`, `myschool.mosreg.ru` | — | [Дневник.ру](diaries/dnevnik-ru.md) `school.mosreg.ru`, `login.school.mosreg.ru` (2015 – August 2023) | high | deep check |
| **Мурманская область** | [«Моя школа» (МЭШ)](diaries/mesh-myschool.md) `edu.mso51.ru` | — | [БАРС](diaries/bars.md) `s51.edu.o7.com` (2012/13 – 2025/26) | high | deep check |
| Нижегородская область | [ЭлЖур](diaries/eljur.md) `edu.gounn.ru`; [«Госуслуги Моя школа»](diaries/myschool-federal.md) as a front end `gosuslugi.ru/school` | — | [Дневник.ру](diaries/dnevnik-ru.md) `dnevnik.ru`, `login.dnevnik.ru` (2011/12 – 2020) | high | corrected in the second round |
| Новгородская область | [Дневник.ру](diaries/dnevnik-ru.md) `53.dnevnik.ru`, `dnevnik.ru`; [«Госуслуги Моя школа»](diaries/myschool-federal.md) as a front end `gosuslugi.ru/school` | — | — | high | corrected in the second round |
| Новосибирская область | [ЭлЖур](diaries/eljur.md) `school.nso.ru`; [«Госуслуги Моя школа»](diaries/myschool-federal.md) as a front end `gosuslugi.ru/school` | — | [БАРС](diaries/bars.md) `shkola.nso.ru` (before 2021) | high | corrected in the second round |
| Омская область | [Дневник.ру](diaries/dnevnik-ru.md) `dnevnik.ru`, `55.dnevnik.ru`; [«Госуслуги Моя школа»](diaries/myschool-federal.md) as a front end `gosuslugi.ru/school` | — | — | high | corrected in the second round |
| Оренбургская область | [«one.» platform](diaries/one-x1.md) `de.edu.orb.ru`, `edu.orb.ru` | [ТОР «Моя школа»](diaries/myschool-federal.md) `gosuslugi.ru/school` (phased transition announced from 1 September…) | — | medium | corrected in the second round |
| Орловская область | [«one.» platform](diaries/one-x1.md) `one.obr57.ru`, `passport.obr57.ru` | — | [«Виртуальная школа»](diaries/virtual-school.md) `vsopen.obr57.ru` | medium | corrected |
| Пензенская область | «АйТи Школа» («АйТи Софт - Цифровая школа») — образовательный сервис Г… `school.edu-penza.ru`, `school.edu-penza.ru/login`; [«Госуслуги Моя школа»](diaries/myschool-federal.md) as a front end `gosuslugi.ru/school` | — | [БАРС](diaries/bars.md) `uko.edu-penza.ru` (2011) | high | corrected in the second round |
| Псковская область | [«one.» platform](diaries/one-x1.md) `one.pskovedu.ru`, `passport.pskovedu.ru`; [«Госуслуги Моя школа»](diaries/myschool-federal.md) as a front end `gosuslugi.ru/school` | — | — | high | corrected in the second round |
| Ростовская область | [ТОР «Моя школа»](diaries/myschool-federal.md) `gosuslugi.ru/school` | — | [БАРС](diaries/bars.md) `sh-open.ris61edu.ru`, `col-open.ris61edu.ru` (until 31 August 2026) | medium | corrected in the second round |
| Рязанская область | [«Сетевой город»](diaries/netschool.md) `e-school.ryazan.gov.ru` | — | [БАРС](diaries/bars.md) `e-school.ryazangov.ru` (until August 2025) | high | confirmed |
| Самарская область | [«Сетевой город»](diaries/netschool.md) `asurso.ru`, `spo.asurso.ru` | — | — | medium | confirmed |
| Саратовская область | [Дневник.ру](diaries/dnevnik-ru.md) `dnevnik.ru`, `login.dnevnik.ru` | — | — | high | confirmed |
| Сахалинская область | [«Сетевой город»](diaries/netschool.md) `netcity.admsakhalin.ru:11111`, `netcity.admsakhalin.ru` | — | — | medium | confirmed |
| Свердловская область | [ТОР «Моя школа»](diaries/myschool-federal.md) `gosuslugi.ru/school` | — | [ГИС СО «ЕЦП»](diaries/egov66.md) `dnevnik.egov66.ru`, `jurnal.egov66.ru` (1 September 2023 – 31 August 2026); [«Сетевой город»](diaries/netschool.md) `netcity.eimc.ru`, `sgo.egov66.ru` (until August 2023); [Дневник.ру](diaries/dnevnik-ru.md) `dnevnik.ru` (until August 2023) | high | corrected in the second round |
| Смоленская область | [ТОР «Моя школа»](diaries/myschool-federal.md) `gosuslugi.ru/school` | — | [ЭлЖур](diaries/eljur.md) `dnevnik.admin-smolensk.ru` (2018 – 31 August 2026) | high | confirmed in the second round |
| Тамбовская область | [Дневник.ру](diaries/dnevnik-ru.md) `dnevnik.ru`, `login.dnevnik.ru` | — | — | high | confirmed |
| **Тверская область** | [«Моя школа» (МЭШ)](diaries/mesh-myschool.md) `dnevnik.tvobr.ru` | — | [«Сетевой город»](diaries/netschool.md) `sgo.tvobr.ru` (by 2020 until 2025/26) | high | deep check |
| Томская область | [«Сетевой город»](diaries/netschool.md) `sgo.tomedu.ru`, `poo.tomedu.ru` | — | [Дневник.ру](diaries/dnevnik-ru.md) `dnevnik.ru`, `login.dnevnik.ru` (seen 2021–2022) | medium | confirmed |
| Тульская область | [«Сетевой город»](diaries/netschool.md) `sgo1.edu71.ru`, `sgo.edu71.ru` | — | — | medium | confirmed |
| **Тюменская область** | [«Моя школа» (МЭШ)](diaries/mesh-myschool.md) `myschool.72to.ru`, `school.72to.ru` | — | [БАРС](diaries/bars.md) `old-school.72to.ru` (≈2013 until 29 August 2024) | high | deep check |
| Ульяновская область | [«Сетевой город»](diaries/netschool.md) `sgo.cit73.ru`, `spo.cit73.ru`; [«Госуслуги Моя школа»](diaries/myschool-federal.md) as a front end `gosuslugi.ru/school` | — | — | high | confirmed in the second round |
| Херсонская область | [«one.» platform](diaries/one-x1.md) `one.edu.khogov.ru`, `passport.edu.khogov.ru` | — | — | high | corrected in the second round |
| Челябинская область | [ТОР «Моя школа»](diaries/myschool-federal.md) `gosuslugi.ru/school` | — | [«Сетевой город»](diaries/netschool.md) `sgo.edu-74.ru`, `es.edu-74.ru` (2013) | high | corrected in the second round |
| Ярославская область | [ТОР «Моя школа»](diaries/myschool-federal.md) `gosuslugi.ru/school` | — | [ЭлЖур](diaries/eljur.md) `school.yarcloud.ru` (1 September 2024); [dnevnik76](diaries/dnevnik76.md) `my.dnevnik76.ru`, `dnevnik76.ru` (until September 2024) | high | corrected in the second round |
| **Москва** | [МЭШ](diaries/mesh-moscow.md) `school.mos.ru`, `dnevnik.mos.ru` | — | МРКО — «Электронный дневник школьника» (Московский регистр качества об… `mrko.mos.ru`, `pgu.mos.ru` (until 2017) | high | deep check |
| **Санкт-Петербург** | [«Петербургское образование»](diaries/petersburg.md) `dnevnik2.petersburgedu.ru`, `petersburgedu.ru`; [eSchool](diaries/eschool-center.md) `app.eschool.center`; [«Сетевой город»](diaries/netschool.md) `netschool.school.ioffe.ru` | — | [«Петербургское образование»](diaries/petersburg.md) `petersburgedu.ru` (until about 2019/20) | high | deep check |
| Севастополь | paper journals and diaries; no electronic diary | — | [ЭлЖур](diaries/eljur.md) `riso.sev.gov.ru` (by September 2022 – August 2026) | medium | corrected in the second round |
| Еврейская автономная область | [ТОР «Моя школа»](diaries/myschool-federal.md) `gosuslugi.ru/school`, `edu.gosuslugi.ru` | — | [Дневник.ру](diaries/dnevnik-ru.md) `dnevnik.ru`, `login.dnevnik.ru` (until 31 August 2026) | high | confirmed in the second round |
| Ненецкий автономный округ | [ТОР «Моя школа»](diaries/myschool-federal.md) `gosuslugi.ru/school` | — | [БАРС](diaries/bars.md) `edu.adm-nao.ru` (until 31 August 2026) | high | confirmed in the second round |
| Ханты-Мансийский автономный округ — Югра | [ЭлЖур](diaries/eljur.md) `cop.admhmao.ru`, `cop-sh.admhmao.ru`; [«Госуслуги Моя школа»](diaries/myschool-federal.md) as a front end `gosuslugi.ru/school` | — | — | high | corrected in the second round |
| Чукотский автономный округ | [«one.» platform](diaries/one-x1.md) `one.edu87.ru`, `passport.edu87.ru`; [«Госуслуги Моя школа»](diaries/myschool-federal.md) as a front end `gosuslugi.ru/school` | — | — | high | corrected in the second round |
| **Ямало-Ненецкий автономный округ** | [«Моя школа» (МЭШ)](diaries/mesh-myschool.md) `school.yanao.ru`; [«Госуслуги Моя школа»](diaries/myschool-federal.md) as a front end | — | [«Сетевой город»](diaries/netschool.md) `sgo.yanao.ru` (until November 2025); [ЭлЖур](diaries/eljur.md) `school.yanao.ru` (2021–2022) | high | deep check |

## The platforms

Counted from the tables above and the pages below. «Regions on it now» counts a region once
for each platform it runs as its main system in September 2026, so a region with two main
systems counts twice.

| Platform | Regions on it now | Moving to it | Routes (current / legacy / uncertain) | Signing in today | Start reading |
| --- | --- | --- | --- | --- | --- |
| [«Петербургское образование»](diaries/petersburg.md) | 1 | — | 35 (14 / 10 / 11) | email and password, cookie `X-JWT-Token`; Госуслуги accounts copy the cookie from a browser | this project's `providers/petersburg/`, then kewldan/TelegramDnevnik |
| [МЭШ](diaries/mesh-moscow.md) | 1 | — | 431 (366 / 51 / 14) | `login.mos.ru` OAuth2 with PKCE in a browser; headless password now meets a proof-of-work | Mag329/OctoDiary-py, OctoDiary/OctoDiary-kt |
| [«Моя школа» (МЭШ)](diaries/mesh-myschool.md) | 11 | — | 89 (55 / 7 / 27) | Госуслуги in a browser, then the node's own code exchange for an `aupd_token` | OctoDiary/OctoDiary-kt, OctoDiary/OctoDiary |
| [ФГИС / ТОР «Моя школа»](diaries/myschool-federal.md) | 20 | 1 | 84 (14 / 0 / 70) | Госуслуги only, in a browser or WebView; the portal session is the cookies `acc_t` and `u` | the page itself, read from the official app; «Lyric» for the WebView capture |
| [Дневник.ру](diaries/dnevnik-ru.md) | 13 | — | 259 (167 / 70 / 22) | Госуслуги in most regions; OAuth2 grants give an `Access-Token`; the form shows a captcha | kesha1225/DnevnikRuAPI and the 2021 Swagger in the Wayback Machine |
| [«Сетевой город»](diaries/netschool.md) | 20 | — | 236 (162 / 54 / 20) | salted MD5 of a windows-1251 password, then header `at`; Госуслуги in ЕСИА-only regions | netschoolpy (PyPI), nm17/netschoolapi |
| [ЭлЖур](diaries/eljur.md) | 6 | — | 96 (64 / 13 / 19) | API login with a `devkey`, query `auth_token`; Госуслуги through a `v_token` exchange | the vendor's own documentation, BetterJournal/EljurAuthUtil |
| [БАРС](diaries/bars.md) | 3 | — | 31 (20 / 1 / 10) | cookie `sessionid`, in practice copied after a Госуслуги sign-in | iamlostshe/bars-api, mironovmeow/barsdiary |
| [«one.» platform](diaries/one-x1.md) | 8 | — | 61 (20 / 0 / 41) | `passport.` login or СНИЛС, cookie `X1_SSO`; Госуслуги and QR as alternatives | zlexdev/pskovedu-sdk, Mihail-Galkin/Two.Diary |
| [КИАСУО](diaries/kiasuo.md) | 1 | — | 12 (6 / 0 / 6) | Госуслуги in a browser once, then a refresh token | oddyamill/kiasuo |
| [ЭПОС.Школа](diaries/epos.md) | 1 | — | 232 (214 / 13 / 5) | Госуслуги in a WebView, cookie `auth_token`, then `Auth-Token` and `Profile-Id` | the page itself, read from the official app «ЭПОС» 1.53; nkrapivin/epos.py for 2022 |
| [«Электронная школа 2.0»](diaries/ruobr.md) | 1 | — | 41 (19 / 16 / 6) | base64 login and password in headers on every call of the mobile API | raitonoberu/ruobr_api |
| [«Виртуальная школа»](diaries/virtual-school.md) | 1 | — | 29 (6 / 9 / 14) | OAuth2 with PKCE at `/auth/authorize`, Госуслуги or a local login, then a bearer token | the archived official bundle; Asmoorr/ScheduleVsopen for the old `/app` |
| [ELSCHOOL](diaries/elschool.md) | — | — | 62 (11 / 2 / 49) | form login, cookie `JWToken` (outgoing system) | Ilya-Repin/elschool-api, Ramin2009Mc/Elschool-Parser |
| [eSchool](diaries/eschool-center.md) | — | — | 47 (40 / 4 / 3) | login and password, cookie `JSESSIONID` | reSchool-org/reSchool-flutter |
| [ГИС СО «ЕЦП»](diaries/egov66.md) | — | — | 20 (0 / 15 / 5) | switched off on 31 August 2026 | history only |
| [edu.tatar.ru](diaries/edu-tatar.md) | — | — | 82 (15 / 49 / 18) | retired in January 2024 | history only |
| [«Дневник76»](diaries/dnevnik76.md) | — | — | 47 (0 / 34 / 13) | closed on 2 September 2024 | history only |
| [`vip.edu35.ru`](diaries/vip-edu35.md) | — | — | 22 (17 / 4 / 1) | cookie pasted from a browser; the school register retired on 1 September 2026 | SkifssA/LauncherSPO |

**Three families share code, and a client for one reads most of the others.** МЭШ in Москва,
the regional «Моя школа» nodes and Пермский край's ЭПОС.Школа are one code base at different
ages: the same `/api/family/*`, `acl/api`, `core/api` paths, the same `Auth-Token` and
`Profile-Id` headers. «Сетевой город» is one product on some fifty regional servers. БАРС is
one product under a regional name in each of its regions. The «one.» platform of Кировская,
Псковская, Запорожская, Оренбургская and Орловская — and, by every sign short of a client,
Ставропольский край, Чукотка and Херсонская — is one code base behind a `passport.` sign-in on every one of
them. ЭлЖур sits under a region's own name in Новосибирская and Крым, and did in Севастополь
until the city went back to paper in September 2026.

## Signing in, across the country

**The password is disappearing, and Госуслуги is what replaces it.** Region after region has
made ЕСИА the only way a family signs in: Дневник.ру per region, «Сетевой город» in its
ЕСИА-only regions, БАРС almost everywhere, the Кузбасс cabinet since July 2024, and the
«Моя школа» nodes and ТОР from the start. Three consequences run through every platform page:

- **Where a login and password still exist, they are the easy case**, and every client
  implements them: Санкт-Петербург, the ЭлЖур API, «Сетевой город» outside its ЕСИА-only
  regions, eSchool, the «one.» `passport.` sign-in, the Кузбасс mobile API.
- **Where ЕСИА is the only door, almost every client asks a person to sign in once in a browser
  and keeps what the browser was given** — a cookie, a JWT, a refresh token. The few that drive
  ЕСИА themselves (netschoolpy, OctoDiary-py, pskovedu-sdk) are replaying Госуслуги's private
  login API, with its SMS and TOTP second factors, its captcha and its «security question», and
  that API changes without notice.
- **A token is the unit that survives.** The clients that have lasted longest are the ones
  that keep a long-lived token or a refresh token and never see the password again —
  КИАСУО's refresh endpoint and ЕСИА-issued tokens on the МЭШ nodes are the clean versions.

## What it means for this project

- **Санкт-Петербург is covered, and it is the unusual case.** Its diary still takes an email
  and a password, which is what `/diary/signin` was built around. Almost no other region's
  diary does, so a second provider cannot reuse that page as it stands: it would have to accept
  a token a person brought from a browser, or drive ЕСИА, and the second is the fragile one.
- **The largest single group is «Сетевой город»**, one route set on some fifty regional
  servers; after it come Дневник.ру and the МЭШ family. Any one of those is a provider that
  covers many regions at once. The platforms of one region each — КИАСУО, ЭПОС.Школа, the
  Кузбасс system — are small providers with small audiences.
- **The twenty ТОР regions have one API between them, and it is the hardest to reach.** The
  routes of «Госуслуги Моя школа» are known from the official app, and a single batched call
  carries the whole diary; but the sign-in is Госуслуги's own, its `client_secret` is signed on
  Госуслуги's side, and the only way a third party has found in is a person signing in inside a
  WebView. Until that is judged acceptable — technically and under the service's terms — the
  manual half of this product, the class kept by hand in the bot, is the only half for those
  regions, which is what #127 already says about every region that is not Петербург.
- **Three decisions come before any of it, and they are the owner's: #135.** Which platform
  comes second, how a family signs in to it, and whether ТОР may be used at all. Whichever
  platform it is, the first step is one real session against it. Not one route in these
  pages has been seen answering.

## What is not covered

- **Nothing here was tried against a live diary.** Every host refuses a connection from the
  sessions this was built in. A route is what a client's code sends, not what a server was seen
  to answer, and the clients are dated on every page for that reason. #121 is the same caveat
  for the one provider that exists.
- **ТОР «Моя школа» rests on one source.** Its routes come from the official app
  «Госуслуги Моя школа» 5.0.0.454; an independent client confirms the diary call and the
  sign-in, and nothing else. It is the diary of 20 regions from September
  2026, and the terms under which a third party may use it were not reviewed.
- **One regional system has no open client and no route.** «АйТи Школа» in Пензенская
  (`school.edu-penza.ru`, ООО «АйТи Софт») is known only from its archived login pages, with
  Госуслуги and a local sign-in. Web2edu and «Аверс» are history. Three systems that looked
  clientless turned out not to be:
  - the Херсонская РГИС is the «one.» platform at `one.edu.khogov.ru`, handed over by the Pskov
    regional IT centre in 2025, though no client has been seen on that instance;
  - ГИС НСО «Электронная школа» (`school.nso.ru`) is the ЭлЖур web application under the
    region's name;
  - «Виртуальная школа» (`vsopen.ru`) is known from its archived official front end and one
    scraper.

  «Е-услуги. Образование» beside «Сетевой город» is an admissions system, not a diary.
- **The teacher's side is almost absent.** Every platform page describes what a pupil or a
  parent sees. The exceptions are one browser extension's calls to the МЭШ teacher journal and
  the Вологодская college register; nothing shows how a mark is set anywhere else.
- **A parent with several children is thinly covered.** Most clients sign in as a pupil or
  assume one child; where a platform page says nothing about choosing a child, nothing was seen.
- **What the second round left open is marked on each row.** The round went after the
  completeness critique's list, and settled it:
  - the official list of ТОР's first wave;
  - the 2026/27 status of every region the list named;
  - the regional systems that had been filed under no platform;
  - the edges of the mandatory regions.

  What remains is 22 regions whose current system is at medium confidence in the
  table. For most of them the only source is a single dated one, or an announcement made before
  1 September. Four secondary rows have a role nobody could fix:
  - the МЭШ node `school.mon95.ru` in Чечня;
  - a Дневник.ру trace in Ставропольский край;
  - «Сетевой город» in Иркутская;
  - «Госуслуги Моя школа» in Хакасия.

  In August 2026 several regional ministries moved their channels from Telegram to MAX, which
  made September's notices scarce.
