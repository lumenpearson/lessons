"""The electronic diary, as a shape any provider can meet.

One diary is Petersburg's (`app.providers.petersburg`) and one is «Сетевой
город» (`app.providers.netschool`); more may follow. What they share lives
here so that the service, the routes and the bot depend on the shape rather
than on either upstream:

- :mod:`app.providers.diary.models` — the models every provider returns;
- :mod:`app.providers.diary.errors` — the failures every provider raises, each
  one a different answer at the API edge;
- :mod:`app.providers.diary.base` — the :class:`DiaryProvider` and
  :class:`DiaryConnection` protocols, and the :class:`SignInRequest` they take;
- :mod:`app.providers.diary.http` — the shared HTTP client, whose cookie jar
  keeps nothing so two families' sessions can never meet;
- :mod:`app.providers.diary.registry` — the map from a stored provider key to
  an implementation, and :func:`binding`, the one place that reads a class's
  diary binding.

Petersburg pre-dates this package and keeps every public name it had, as a
re-export of the very objects defined here — so no import anywhere breaks and
`isinstance` still holds across the two spellings.
"""
